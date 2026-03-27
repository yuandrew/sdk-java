# Worker Heartbeating Implementation Plan

## Metadata
- **Date:** 2026-03-07
- **Spec:** `.omc/specs/deep-interview-worker-heartbeat.md`
- **Reference:** Go SDK PR #2186
- **Branch:** `worker-heartbeat-deep-research`
- **Revision:** 2 (addresses Architect/Critic review feedback)

---

## RALPLAN-DR Summary

### Principles
1. **Match Go SDK architecture** -- HeartbeatManager on WorkflowClient, SharedNamespaceHeartbeatWorker per namespace, worker callbacks
2. **Thread safety first** -- Heartbeat callbacks are invoked from a scheduled executor thread; all worker state reads must be safe
3. **Zero behavioral change for existing code** -- Feature is opt-in via `WorkflowClientOptions.workerHeartbeatInterval`; disabled by default
4. **Graceful degradation** -- Capability-gate via `DescribeNamespace`; errors logged but never fail shutdown
5. **Minimal surface area** -- New public API is one setter on `WorkflowClientOptions.Builder`; everything else is internal

### Decision Drivers
1. **Proto availability** -- All required protos (`RecordWorkerHeartbeatRequest`, `WorkerHeartbeat`, `WorkerSlotsInfo`, `WorkerPollerInfo`, `WorkerHostInfo`, `WorkerStatus`, `ShutdownWorkerRequest.worker_heartbeat`) already exist in the checked-in API submodule
2. **Existing shutdown pattern** -- `WorkflowWorker` already sends `ShutdownWorkerRequest` with identity/namespace/stickyTaskQueue; needs extension to include `worker_heartbeat` field
3. **Existing slot tracking** -- `TrackingSlotSupplier` already exists at `temporal-sdk/src/main/java/io/temporal/internal/worker/TrackingSlotSupplier.java` and tracks `issuedSlots` (AtomicInteger), `usedSlots` (ConcurrentHashMap), with `getIssuedSlots()`, `getUsedSlots()`, and `maximumSlots()` methods. Only needs a `getSupplierKind()` method added.

### Options

**Option A: Direct instrumentation (CHOSEN)**
- Add `HeartbeatTaskCounters` fields directly to internal workers to track task counts
- Extend existing `TrackingSlotSupplier` with `getSupplierKind()` for heartbeat reporting
- Each Worker exposes a `Supplier<WorkerHeartbeat>` callback registered with HeartbeatManager
- Pros: Simple, no wrapping layers, data accessible where needed, reuses existing infrastructure
- Cons: Requires adding counters to existing internal classes (SyncActivityWorker, SyncWorkflowWorker, etc.)

**Option B: Wrapping HeartbeatMetricsHandler (like Go)**
- Wrap the tally `Scope` to intercept metric emissions and capture them for heartbeat
- Pros: Decoupled from internal worker changes, mirrors Go pattern
- Cons: Java uses `com.uber.m3.tally.Scope` not micrometer; wrapping tally Scope is complex due to its API surface; metrics may not map 1:1 to heartbeat fields (e.g., interval deltas require separate tracking anyway)

**Why Option A:** The Go SDK uses a metrics-wrapping approach because its metrics handler is a clean interface. Java's tally `Scope` API is much harder to wrap cleanly. Direct instrumentation is simpler, more explicit, and avoids a leaky abstraction. The spec explicitly says the approach is "flexible -- decided during implementation."

---

## Context

### Proto Types (all available in `temporal-serviceclient/src/main/proto/`)
- `temporal.api.worker.v1.WorkerHeartbeat` -- main heartbeat payload (22 fields)
- `temporal.api.worker.v1.WorkerSlotsInfo` -- per-task-type slot info (available, used, supplier kind, processed, failed, interval processed, interval failed)
- `temporal.api.worker.v1.WorkerPollerInfo` -- pollers (count, last_successful_poll_time, is_autoscaling)
- `temporal.api.worker.v1.WorkerHostInfo` -- host/process info (hostname, grouping_key, process_id, cpu, mem)
- `temporal.api.enums.v1.WorkerStatus` -- RUNNING, SHUTTING_DOWN, SHUTDOWN
- `temporal.api.workflowservice.v1.RecordWorkerHeartbeatRequest` -- namespace, identity, repeated WorkerHeartbeat
- `temporal.api.workflowservice.v1.ShutdownWorkerRequest` -- already has `worker_heartbeat` field (field 5)
- `temporal.api.namespace.v1.NamespaceInfo.Capabilities.worker_heartbeats` -- capability flag

### Key Existing Code
- `Version.SDK_NAME` = `"temporal-java"`, `Version.LIBRARY_VERSION` = loaded from properties
- `WorkflowExecutorCache` -- has `cache.size()` for sticky cache size
- `WorkflowWorker` -- already sends `ShutdownWorkerRequest` on graceful shutdown (line 219)
- `WorkerFactory.start()` -- already calls `describeNamespace` (line 254-260)
- `WorkerFactoryRegistry` -- tracks factories per client, `CopyOnWriteArrayList`
- `FixedSizeSlotSupplier.getMaximumSlots()` -- returns `Optional<Integer>` with fixed count
- `SingleWorkerOptions` -- carries identity, buildId, deploymentOptions per worker type
- **`TrackingSlotSupplier`** -- already exists at `temporal-sdk/src/main/java/io/temporal/internal/worker/TrackingSlotSupplier.java`. Already wraps `SlotSupplier<SI>`, tracks `issuedSlots` (AtomicInteger), `usedSlots` (ConcurrentHashMap<SlotPermit, SI>), exposes `getIssuedSlots()`, `getUsedSlots()`, `maximumSlots()`. WorkflowWorker already wraps slot suppliers with this class.

---

## Work Objectives

Implement worker heartbeating so that:
1. Workers periodically send `RecordWorkerHeartbeat` RPCs with full metadata
2. On shutdown, the final heartbeat with `SHUTTING_DOWN` status is delivered (via the appropriate mechanism -- see Shutdown Heartbeat Matrix)
3. Feature is gated behind `DescribeNamespace` capability check
4. Multiple workers on the same client share a single heartbeat loop

---

## Guardrails

### Must Have
- `@Experimental` annotation on new public API
- Thread-safe heartbeat callback invocation
- Interval validation: 1s-60s inclusive, negative disables, 0 defaults to 60s
- `ShutdownWorker` always sent (even if heartbeating disabled) for sticky queue cleanup
- Comprehensive test coverage (unit + integration tiers)

### Must NOT Have
- CPU/memory reporting (report 0)
- Plugin name reporting (empty list)
- Breaking changes to existing `WorkflowClientOptions`, `Worker`, or `WorkerFactory` public API
- Changes to activity heartbeat behavior

---

## Task Flow

```
Phase 1: Configuration & HeartbeatManager skeleton
    |
Phase 2: Worker heartbeat data collection (counters, extend TrackingSlotSupplier, poller info)
    |
Phase 3: HeartbeatManager timer loop + RecordWorkerHeartbeat RPC + lifecycle management
    |
Phase 4: Shutdown heartbeat delivery (with Shutdown Heartbeat Matrix)
    |
Phase 5: Capability gating via DescribeNamespace
    |
Phase 6: Tests (two-tier: unit tests with mocks + integration tests)
```

---

## Phase 1: Configuration + HeartbeatManager Skeleton

### 1a. Add `workerHeartbeatInterval` to `WorkflowClientOptions`

**File:** `temporal-sdk/src/main/java/io/temporal/client/WorkflowClientOptions.java`

Add field, builder setter, getter, validation:

```java
// In Builder:
private Duration workerHeartbeatInterval;

@Experimental
public Builder setWorkerHeartbeatInterval(Duration workerHeartbeatInterval) {
  this.workerHeartbeatInterval = workerHeartbeatInterval;
  return this;
}

// In validateAndBuildWithDefaults():
Duration resolvedHeartbeatInterval = workerHeartbeatInterval;
if (resolvedHeartbeatInterval != null) {
  long seconds = resolvedHeartbeatInterval.getSeconds();
  if (seconds == 0) {
    resolvedHeartbeatInterval = Duration.ofSeconds(60);
  } else if (seconds > 0) {
    Preconditions.checkArgument(
        seconds >= 1 && seconds <= 60,
        "workerHeartbeatInterval must be between 1s and 60s, got %ss", seconds);
  }
  // negative means disabled -- pass through as-is
}

// In class body:
private final Duration workerHeartbeatInterval;

@Experimental
public Duration getWorkerHeartbeatInterval() { return workerHeartbeatInterval; }
```

Update constructor, `Builder(options)` copy, `equals`, `hashCode`, `toString`.

**Acceptance criteria:**
- [ ] `setWorkerHeartbeatInterval(Duration.ofSeconds(30))` compiles and round-trips
- [ ] `Duration.ofSeconds(0)` resolves to 60s
- [ ] `Duration.ofSeconds(90)` throws `IllegalArgumentException`
- [ ] `Duration.ofSeconds(-1)` accepted (disables heartbeating)
- [ ] Null means "not configured" (heartbeating disabled)

### 1b. Create `HeartbeatManager`

**New file:** `temporal-sdk/src/main/java/io/temporal/internal/worker/HeartbeatManager.java`

```java
package io.temporal.internal.worker;

/**
 * Manages worker heartbeat lifecycle. Created by WorkflowClientInternalImpl when
 * workerHeartbeatInterval is configured and positive.
 *
 * Responsibilities:
 * - Maintains a ScheduledExecutorService that fires at the configured interval
 * - Collects WorkerHeartbeat protos from all registered worker callbacks
 * - Sends a single RecordWorkerHeartbeatRequest per tick
 * - Sends final heartbeat via RecordWorkerHeartbeat on worker unregistration
 *   (when ShutdownWorkerRequest is not applicable)
 *
 * Lifecycle management:
 * - The ScheduledExecutorService is started lazily when the first worker registers
 * - When all workers unregister, the executor is shut down
 * - Re-registrations restart the executor as needed
 */
public class HeartbeatManager {
  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String identity;
  private final Duration interval;
  private final String workerGroupingKey; // UUID per client
  private final ConcurrentHashMap<String, Supplier<WorkerHeartbeat>> callbacks; // keyed by workerInstanceKey
  private volatile boolean serverSupportsHeartbeats; // set after capability check
  private volatile boolean capabilityChecked;
  private volatile Instant lastHeartbeatTime;

  // Lifecycle: executor created/shutdown based on registered worker count
  private @Nullable ScheduledExecutorService scheduler;
  private final Object lifecycleLock = new Object();

  public HeartbeatManager(WorkflowServiceStubs service, String namespace,
      String identity, Duration interval) { ... }

  /**
   * Register a worker's heartbeat callback. Called on Worker.start().
   * Starts the heartbeat scheduler if this is the first registered worker.
   */
  public void registerWorker(String workerInstanceKey,
      Supplier<WorkerHeartbeat> callback) {
    callbacks.put(workerInstanceKey, callback);
    ensureSchedulerRunning();
  }

  /**
   * Unregister a worker. Called on Worker.shutdown().
   * Does NOT send final heartbeat -- that is handled by the shutdown path
   * (see Shutdown Heartbeat Matrix in Phase 4).
   * Stops the scheduler if no workers remain.
   */
  public void unregisterWorker(String workerInstanceKey) {
    callbacks.remove(workerInstanceKey);
    maybeStopScheduler();
  }

  /**
   * Send a one-shot final heartbeat via RecordWorkerHeartbeat RPC.
   * Used when ShutdownWorkerRequest is not the delivery mechanism
   * (e.g., no sticky queue, or shutdownNow() path).
   */
  public void sendFinalHeartbeat(WorkerHeartbeat heartbeat) { ... }

  /** Start the heartbeat timer (called internally by registerWorker). */
  private void ensureSchedulerRunning() {
    synchronized (lifecycleLock) {
      if (scheduler == null || scheduler.isShutdown()) {
        scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "worker-heartbeat"); t.setDaemon(true); return t; });
        scheduler.scheduleAtFixedRate(this::heartbeatTick,
            interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
  }

  /** Stop the scheduler if no workers are registered. */
  private void maybeStopScheduler() {
    synchronized (lifecycleLock) {
      if (callbacks.isEmpty() && scheduler != null) {
        scheduler.shutdown();
        scheduler = null;
      }
    }
  }

  /** Force stop the heartbeat timer. */
  public void shutdown() {
    synchronized (lifecycleLock) {
      if (scheduler != null) {
        scheduler.shutdown();
        scheduler = null;
      }
    }
  }

  /** Check namespace capabilities. Cache result. */
  public void checkCapability(DescribeNamespaceResponse response) { ... }
}
```

**Acceptance criteria:**
- [ ] Class compiles with all method stubs
- [ ] Thread-safe registration/unregistration via `ConcurrentHashMap`
- [ ] `ScheduledExecutorService` created lazily on first `registerWorker`, shut down when last worker unregisters
- [ ] No executor running when zero workers are registered (addresses idle lifecycle concern)
- [ ] `sendFinalHeartbeat()` available for non-ShutdownWorkerRequest shutdown paths

### 1c. Wire HeartbeatManager into `WorkflowClientInternalImpl`

**File:** `temporal-sdk/src/main/java/io/temporal/client/WorkflowClientInternalImpl.java`

```java
// New field:
private final @Nullable HeartbeatManager heartbeatManager;

// In constructor, after options are validated:
Duration heartbeatInterval = options.getWorkerHeartbeatInterval();
if (heartbeatInterval != null && !heartbeatInterval.isNegative()) {
  this.heartbeatManager = new HeartbeatManager(
      workflowServiceStubs, options.getNamespace(),
      options.getIdentity(), heartbeatInterval);
} else {
  this.heartbeatManager = null;
}

// Expose to Worker/WorkerFactory via WorkflowClientInternal interface:
@Nullable HeartbeatManager getHeartbeatManager();
```

**File:** `temporal-sdk/src/main/java/io/temporal/internal/client/WorkflowClientInternal.java`

Add `@Nullable HeartbeatManager getHeartbeatManager();` to the interface.

**Acceptance criteria:**
- [ ] `HeartbeatManager` created when interval > 0
- [ ] `HeartbeatManager` is null when interval not set or negative
- [ ] Accessible from `WorkerFactory` via `WorkflowClientInternal`

---

## Phase 2: Worker Heartbeat Data Collection

### 2a. Add heartbeat counters to internal workers

Each task-type worker needs to track: processed count, failed count, interval deltas.

**New file:** `temporal-sdk/src/main/java/io/temporal/internal/worker/HeartbeatTaskCounters.java`

```java
package io.temporal.internal.worker;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe counters for task processing stats reported in worker heartbeats.
 * Tracks both cumulative totals and per-interval deltas.
 */
public class HeartbeatTaskCounters {
  private final AtomicInteger totalProcessed = new AtomicInteger();
  private final AtomicInteger totalFailed = new AtomicInteger();
  private final AtomicInteger intervalProcessed = new AtomicInteger();
  private final AtomicInteger intervalFailed = new AtomicInteger();

  public void recordProcessed() {
    totalProcessed.incrementAndGet();
    intervalProcessed.incrementAndGet();
  }

  public void recordFailed() {
    totalFailed.incrementAndGet();
    intervalFailed.incrementAndGet();
  }

  /** Called by heartbeat tick to snapshot and reset interval counters. */
  public Snapshot snapshotAndResetInterval() {
    return new Snapshot(
        totalProcessed.get(), totalFailed.get(),
        intervalProcessed.getAndSet(0), intervalFailed.getAndSet(0));
  }

  public record Snapshot(int totalProcessed, int totalFailed,
      int intervalProcessed, int intervalFailed) {}
}
```

**Instrument in:**
- `WorkflowWorker` -- after workflow task completion, call `counters.recordProcessed()` or `counters.recordFailed()`
- `ActivityWorker` -- after activity task completion
- `LocalActivityWorker` -- after local activity task completion
- `NexusWorker` (if exists) -- after nexus task completion

The exact insertion points are in the `PollTaskExecutor` / task handler completion paths. Each `*Worker` class gets a `HeartbeatTaskCounters` field exposed via a getter.

### 2b. Extend existing `TrackingSlotSupplier` with `getSupplierKind()`

**IMPORTANT:** `TrackingSlotSupplier` already exists at `temporal-sdk/src/main/java/io/temporal/internal/worker/TrackingSlotSupplier.java`. It already tracks:
- `issuedSlots` (AtomicInteger) via `getIssuedSlots()`
- `usedSlots` (ConcurrentHashMap<SlotPermit, SI>) via `getUsedSlots()` (returns the map, `.size()` for count)
- `maximumSlots()` via delegation to inner supplier

**What to add:** A `getSupplierKind()` method that returns a string identifying the inner supplier type.

**File:** `temporal-sdk/src/main/java/io/temporal/internal/worker/TrackingSlotSupplier.java`

```java
// Add new field:
private final String supplierKind;

// In constructor, determine kind from inner supplier type:
public TrackingSlotSupplier(SlotSupplier<SI> inner, Scope metricsScope) {
  this.inner = inner;
  this.metricsScope = metricsScope;
  this.supplierKind = determineSupplierKind(inner);
  publishSlotsMetric();
}

private static String determineSupplierKind(SlotSupplier<?> supplier) {
  if (supplier instanceof FixedSizeSlotSupplier) return "Fixed";
  if (supplier instanceof ResourceBasedSlotSupplier) return "ResourceBased";
  return supplier.getClass().getSimpleName();
}

public String getSupplierKind() {
  return supplierKind;
}
```

**For heartbeat reporting, use existing methods:**
- Available slots: `maximumSlots().orElse(-1) - getUsedSlots().size()`
- Used slots: `getUsedSlots().size()`
- Supplier kind: new `getSupplierKind()`

**No new wrapper class needed.** Workers already use `TrackingSlotSupplier` internally.

**Acceptance criteria:**
- [ ] `getSupplierKind()` returns "Fixed" for `FixedSizeSlotSupplier`, "ResourceBased" for `ResourceBasedSlotSupplier`
- [ ] No changes to existing `TrackingSlotSupplier` behavior (all existing methods unchanged)
- [ ] Heartbeat callback can read slot data directly from existing `TrackingSlotSupplier` instances

### 2c. Track poller info

**Approach:** The `Poller` / `BasePoller` classes manage polling threads. We need:
- Current poller count: available from `PollerBehavior` configuration (static for `PollerBehaviorSimpleMaximum`)
- Last successful poll time: add `volatile Instant lastSuccessfulPollTime` to each `*Worker`, set after successful poll

Add `volatile Instant lastSuccessfulPollTime` fields to `WorkflowWorker`, `ActivityWorker`, and `SyncNexusWorker` (or their pollers), updated on each successful poll task receipt.

### 2d. Track sticky cache stats

**File:** `temporal-sdk/src/main/java/io/temporal/internal/worker/WorkflowExecutorCache.java`

Add atomic counters:
```java
private final AtomicInteger cacheHits = new AtomicInteger();
private final AtomicInteger cacheMisses = new AtomicInteger();

public void recordCacheHit() { cacheHits.incrementAndGet(); }
public void recordCacheMiss() { cacheMisses.incrementAndGet(); }
public int getCacheHits() { return cacheHits.get(); }
public int getCacheMisses() { return cacheMisses.get(); }
public int getCurrentCacheSize() { return (int) cache.size(); }
```

Increment `cacheHits` when a workflow execution is found in cache, `cacheMisses` when not found. The check happens in `WorkflowWorker.handleWorkflowTask()` or similar.

### 2e. Build heartbeat callback in `Worker`

Each `Worker` gets a `workerInstanceKey` (UUID) and assembles the `WorkerHeartbeat` proto:

```java
// In Worker class:
private final String workerInstanceKey = UUID.randomUUID().toString();
private final Instant startTime = Instant.now();

Supplier<WorkerHeartbeat> buildHeartbeatCallback(WorkerStatus status) {
  return () -> {
    WorkerHeartbeat.Builder hb = WorkerHeartbeat.newBuilder()
        .setWorkerInstanceKey(workerInstanceKey)
        .setWorkerIdentity(options.getIdentity() != null
            ? options.getIdentity() : clientOptions.getIdentity())
        .setTaskQueue(taskQueue)
        .setSdkName(Version.SDK_NAME)
        .setSdkVersion(Version.LIBRARY_VERSION)
        .setStatus(status)
        .setStartTime(toProtoTimestamp(startTime))
        .setHeartbeatTime(toProtoTimestamp(Instant.now()));

    // Host info
    hb.setHostInfo(buildHostInfo(heartbeatManager.getWorkerGroupingKey()));

    // Slot info per type -- uses EXISTING TrackingSlotSupplier instances
    hb.setWorkflowTaskSlotsInfo(buildSlotsInfo(workflowTrackingSlotSupplier, workflowCounters));
    hb.setActivityTaskSlotsInfo(buildSlotsInfo(activityTrackingSlotSupplier, activityCounters));
    hb.setLocalActivitySlotsInfo(buildSlotsInfo(laTrackingSlotSupplier, laCounters));
    if (nexusWorker != null) {
      hb.setNexusTaskSlotsInfo(buildSlotsInfo(nexusTrackingSlotSupplier, nexusCounters));
    }

    // Poller info
    hb.setWorkflowPollerInfo(buildPollerInfo(workflowWorker));
    hb.setWorkflowStickyPollerInfo(buildPollerInfo(workflowWorker.getStickyPoller()));
    hb.setActivityPollerInfo(buildPollerInfo(activityWorker));
    hb.setNexusPollerInfo(buildPollerInfo(nexusWorker));

    // Sticky cache
    hb.setTotalStickyCacheHit(cache.getCacheHits());
    hb.setTotalStickyCacheMiss(cache.getCacheMisses());
    hb.setCurrentStickyCacheSize(cache.getCurrentCacheSize());

    // Deployment version (if configured)
    if (deploymentVersion != null) {
      hb.setDeploymentVersion(deploymentVersion);
    }

    return hb.build();
  };
}

// Helper to build WorkerSlotsInfo from existing TrackingSlotSupplier:
private WorkerSlotsInfo buildSlotsInfo(
    TrackingSlotSupplier<?> tracker, HeartbeatTaskCounters counters) {
  HeartbeatTaskCounters.Snapshot snap = counters.snapshotAndResetInterval();
  return WorkerSlotsInfo.newBuilder()
      .setCurrentAvailableSlots(
          tracker.maximumSlots().orElse(-1) - tracker.getUsedSlots().size())
      .setCurrentUsedSlots(tracker.getUsedSlots().size())
      .setSlotSupplierKind(tracker.getSupplierKind())
      .setTotalProcessedTasks(snap.totalProcessed())
      .setTotalFailedTasks(snap.totalFailed())
      .setLastIntervalProcessedTasks(snap.intervalProcessed())
      .setLastIntervalFailedTasks(snap.intervalFailed())
      .build();
}
```

**Acceptance criteria for Phase 2:**
- [ ] `HeartbeatTaskCounters` tracks processed/failed with atomic counters and interval reset
- [ ] Existing `TrackingSlotSupplier` extended with `getSupplierKind()` -- no new wrapper class
- [ ] Poller last-successful-poll-time tracked
- [ ] Sticky cache hit/miss/size accessible from `WorkflowExecutorCache`
- [ ] `Worker.buildHeartbeatCallback()` produces a complete `WorkerHeartbeat` proto using existing `TrackingSlotSupplier` instances

---

## Phase 3: HeartbeatManager Timer Loop + RPC

### 3a. Implement the scheduled heartbeat tick

**File:** `HeartbeatManager.java`

```java
private void heartbeatTick() {
  if (!serverSupportsHeartbeats) return;
  if (callbacks.isEmpty()) return;

  try {
    List<WorkerHeartbeat> heartbeats = new ArrayList<>();
    Instant now = Instant.now();

    for (Supplier<WorkerHeartbeat> callback : callbacks.values()) {
      WorkerHeartbeat hb = callback.get();
      WorkerHeartbeat.Builder builder = hb.toBuilder();

      // Set elapsed since last heartbeat
      if (lastHeartbeatTime != null) {
        builder.setElapsedSinceLastHeartbeat(
            toDuration(Duration.between(lastHeartbeatTime, now)));
      }
      heartbeats.add(builder.build());
    }

    if (!heartbeats.isEmpty()) {
      service.blockingStub().recordWorkerHeartbeat(
          RecordWorkerHeartbeatRequest.newBuilder()
              .setNamespace(namespace)
              .setIdentity(identity)
              .addAllWorkerHeartbeat(heartbeats)
              .build());
    }
    lastHeartbeatTime = now;
  } catch (Exception e) {
    log.debug("Failed to send worker heartbeat", e);
  }
}
```

**Acceptance criteria:**
- [ ] Heartbeat RPC sent at configured interval
- [ ] All registered workers' heartbeats collected into single RPC
- [ ] `elapsed_since_last_heartbeat` computed correctly
- [ ] Exceptions caught and logged at debug level (never propagated)

### 3b. Worker registration/unregistration flow

**In `WorkerFactory.doStart()`** (after `describeNamespace` call):
```java
HeartbeatManager hbManager = getHeartbeatManager();
if (hbManager != null) {
  hbManager.checkCapability(describeResponse);
  for (Worker worker : workers.values()) {
    hbManager.registerWorker(worker.getWorkerInstanceKey(),
        worker.buildHeartbeatCallback(WorkerStatus.WORKER_STATUS_RUNNING));
  }
  // scheduler starts automatically on first registerWorker call
}
```

**In `WorkerFactory.doShutdown()`** -- see Phase 4 for shutdown details.

### 3c. HeartbeatManager Lifecycle Management

**Design:** The `ScheduledExecutorService` is managed with reference-counting semantics:

- **Start:** Created lazily on first `registerWorker()` call (inside `ensureSchedulerRunning()`)
- **Stop:** Shut down when the last worker calls `unregisterWorker()` (inside `maybeStopScheduler()`)
- **Restart:** If a worker re-registers after all previous workers unregistered, a new executor is created
- **Force stop:** `shutdown()` unconditionally stops the executor (called during WorkflowClient shutdown)

This prevents the executor from running indefinitely with zero registered workers.

**Acceptance criteria:**
- [ ] Workers registered on factory start, unregistered on factory shutdown
- [ ] HeartbeatManager scheduler starts lazily on first worker registration
- [ ] HeartbeatManager scheduler stops when last worker unregisters
- [ ] No executor running when zero workers are registered
- [ ] HeartbeatManager forcibly shut down during factory/client shutdown

---

## Phase 4: Shutdown Heartbeat Delivery

### Shutdown Heartbeat Matrix

The final heartbeat (`SHUTTING_DOWN` status) is delivered through different mechanisms depending on shutdown conditions:

| Condition | Sticky Queue? | Mechanism | Details |
|-----------|:------------:|-----------|---------|
| **Graceful shutdown** (`shutdown()`) | Yes | `ShutdownWorkerRequest` | `WorkflowWorker` already sends `ShutdownWorkerRequest` for sticky queue cleanup. The `worker_heartbeat` field is populated with a `SHUTTING_DOWN` heartbeat. Single RPC serves both purposes. |
| **Graceful shutdown** (`shutdown()`) | No | `HeartbeatManager.sendFinalHeartbeat()` | No `ShutdownWorkerRequest` is sent (no sticky queue to clean up). HeartbeatManager sends a standalone `RecordWorkerHeartbeatRequest` with the final heartbeat. |
| **Forced shutdown** (`shutdownNow()`) | N/A | `HeartbeatManager.sendFinalHeartbeat()` | `ShutdownWorkerRequest` is NOT sent during `shutdownNow()` (interruptTasks=true means we skip the graceful shutdown RPC). HeartbeatManager makes a best-effort `RecordWorkerHeartbeatRequest` call. This may fail if the channel is already shutting down. |

### 4a. Add heartbeat to existing ShutdownWorkerRequest (graceful + sticky queue path)

**File:** `temporal-sdk/src/main/java/io/temporal/internal/worker/WorkflowWorker.java` (~line 219)

The existing `ShutdownWorkerRequest` already has a `worker_heartbeat` field (field 5). Modify the shutdown path to include it:

```java
ShutdownWorkerRequest.Builder shutdownReq = ShutdownWorkerRequest.newBuilder()
    .setIdentity(options.getIdentity())
    .setNamespace(namespace)
    .setStickyTaskQueue(stickyTaskQueueName)
    .setReason(GRACEFUL_SHUTDOWN_MESSAGE);

// If heartbeat callback available, include final heartbeat
if (shutdownHeartbeatSupplier != null) {
  shutdownReq.setWorkerHeartbeat(shutdownHeartbeatSupplier.get());
}

return shutdownManager.waitOnWorkerShutdownRequest(
    service.futureStub().shutdownWorker(shutdownReq.build()));
```

The `shutdownHeartbeatSupplier` is passed into `WorkflowWorker` (and through `SyncWorkflowWorker`) from `Worker` during construction or set before shutdown.

### 4b. HeartbeatManager final heartbeat path (graceful + no sticky queue, or shutdownNow)

**File:** `HeartbeatManager.java`

```java
/**
 * Send a one-shot final heartbeat via RecordWorkerHeartbeat RPC.
 * Used when ShutdownWorkerRequest is not the delivery mechanism.
 */
public void sendFinalHeartbeat(WorkerHeartbeat heartbeat) {
  if (!serverSupportsHeartbeats) return;
  try {
    service.blockingStub().recordWorkerHeartbeat(
        RecordWorkerHeartbeatRequest.newBuilder()
            .setNamespace(namespace)
            .setIdentity(identity)
            .addWorkerHeartbeat(heartbeat)
            .build());
  } catch (Exception e) {
    log.debug("Failed to send final worker heartbeat", e);
  }
}
```

### 4c. Shutdown orchestration in `WorkerFactory` / `Worker`

**In `Worker.shutdown()`:**
```java
// Determine which path to use for final heartbeat
boolean hasStickyQueue = workflowWorker != null && workflowWorker.hasStickyQueue();
boolean isGraceful = !interruptTasks;

if (heartbeatManager != null && serverSupportsHeartbeats) {
  WorkerHeartbeat finalHb = buildHeartbeatCallback(
      WorkerStatus.WORKER_STATUS_SHUTTING_DOWN).get();

  if (isGraceful && hasStickyQueue) {
    // Path 1: ShutdownWorkerRequest carries the heartbeat
    workflowWorker.setShutdownHeartbeat(finalHb);
  } else {
    // Path 2 & 3: HeartbeatManager sends standalone RecordWorkerHeartbeat
    heartbeatManager.sendFinalHeartbeat(finalHb);
  }

  heartbeatManager.unregisterWorker(workerInstanceKey);
}
```

**Acceptance criteria:**
- [ ] `ShutdownWorkerRequest` includes `worker_heartbeat` with `SHUTTING_DOWN` status (graceful + sticky queue)
- [ ] Standalone `RecordWorkerHeartbeat` sent for final heartbeat (graceful + no sticky queue)
- [ ] Best-effort `RecordWorkerHeartbeat` attempted during `shutdownNow()` path
- [ ] Shutdown still works correctly when heartbeating is disabled (null heartbeatManager)
- [ ] Existing shutdown tests still pass
- [ ] `shutdownNow()` documents that `ShutdownWorkerRequest` is NOT sent in this path

---

## Phase 5: Capability Gating

### 5a. Cache `DescribeNamespace` capability result

**File:** `HeartbeatManager.java`

```java
public void checkCapability(DescribeNamespaceResponse describeResponse) {
  this.capabilityChecked = true;
  this.serverSupportsHeartbeats = describeResponse
      .getNamespaceInfo()
      .getCapabilities()
      .getWorkerHeartbeats();

  if (!serverSupportsHeartbeats) {
    log.debug("Server does not support worker heartbeats for namespace {},"
        + " heartbeating disabled", namespace);
  }
}
```

**File:** `WorkerFactory.start()` -- pass the already-fetched `DescribeNamespaceResponse` to `HeartbeatManager`:

```java
DescribeNamespaceResponse describeResponse = workflowClient
    .getWorkflowServiceStubs()
    .blockingStub()
    .describeNamespace(
        DescribeNamespaceRequest.newBuilder()
            .setNamespace(workflowClient.getOptions().getNamespace())
            .build());

// ... existing start logic ...

if (heartbeatManager != null) {
  heartbeatManager.checkCapability(describeResponse);
}
```

This reuses the existing `describeNamespace` call that `WorkerFactory.start()` already makes -- just capture the response instead of discarding it.

**Acceptance criteria:**
- [ ] Capability checked once during factory start
- [ ] Result cached -- no additional `describeNamespace` calls
- [ ] Heartbeating silently disabled if server lacks capability
- [ ] Debug log emitted when capability missing

---

## Phase 6: Tests (Two-Tier Strategy)

### Tier 1: Unit Tests with Mock WorkflowServiceStubs

The temporal test server does NOT support `ListWorkers` or `RecordWorkerHeartbeat` RPCs. All unit tests use mock `WorkflowServiceStubs` to verify correct RPC construction.

**New file:** `temporal-sdk/src/test/java/io/temporal/internal/worker/HeartbeatManagerTest.java`

Uses JUnit 4 + Mockito, mocking `WorkflowServiceStubs` and its blocking stub.

| # | Test Name | Description | Key Assertions |
|---|-----------|-------------|----------------|
| 1 | `testHeartbeatRpcSentAtInterval` | Register a worker callback, advance time, verify `recordWorkerHeartbeat` called with correct request | Mock stub receives `RecordWorkerHeartbeatRequest` with namespace, identity, and worker heartbeat list |
| 2 | `testMultipleWorkersInSingleRpc` | Register 2 workers, verify single RPC with 2 heartbeats | `request.getWorkerHeartbeatCount() == 2` |
| 3 | `testCapabilityGatingDisablesRpc` | Set capability to false, verify no RPC sent | Mock stub `recordWorkerHeartbeat` never called |
| 4 | `testCapabilityGatingEnablesRpc` | Set capability to true, verify RPC sent | Mock stub called at least once |
| 5 | `testUnregisterStopsRpcWhenEmpty` | Register then unregister, verify scheduler stops and no more RPCs | `scheduler.isShutdown() == true` after last unregister |
| 6 | `testElapsedSinceLastHeartbeat` | Send two heartbeat ticks, verify second includes `elapsed_since_last_heartbeat` | Duration field present and approximately equals interval |
| 7 | `testExceptionsCaughtAndLogged` | Mock stub throws, verify no exception propagates | No exception from `heartbeatTick()`; subsequent ticks still fire |
| 8 | `testSendFinalHeartbeat` | Call `sendFinalHeartbeat`, verify standalone RPC sent | Mock stub receives request with single heartbeat with `SHUTTING_DOWN` status |
| 9 | `testLifecycleNoExecutorWhenEmpty` | Create HeartbeatManager, verify no executor running before any worker registers | Internal scheduler is null |
| 10 | `testIntervalValidation` | Verify 0 defaults to 60s, 90s rejects, -1 disables | `WorkflowClientOptions` validation behavior |

**New file:** `temporal-sdk/src/test/java/io/temporal/internal/worker/HeartbeatTaskCountersTest.java`

| # | Test Name | Description |
|---|-----------|-------------|
| 1 | `testRecordAndSnapshot` | Record processed/failed, snapshot, verify totals and interval |
| 2 | `testIntervalReset` | Snapshot resets interval counters but not totals |
| 3 | `testConcurrentAccess` | Multiple threads recording, verify no lost updates |

**New file:** `temporal-sdk/src/test/java/io/temporal/internal/worker/TrackingSlotSupplierKindTest.java`

| # | Test Name | Description |
|---|-----------|-------------|
| 1 | `testFixedSupplierKind` | `FixedSizeSlotSupplier` reports "Fixed" |
| 2 | `testResourceBasedSupplierKind` | `ResourceBasedSlotSupplier` reports "ResourceBased" |
| 3 | `testCustomSupplierKind` | Custom supplier reports class simple name |

### Tier 2: Integration Tests

**New file:** `temporal-sdk/src/test/java/io/temporal/worker/WorkerHeartbeatIntegrationTest.java`

These tests verify the end-to-end wiring works with a real gRPC flow. They use a **gRPC interceptor** on the client stub to capture outgoing `RecordWorkerHeartbeat` and `ShutdownWorker` RPCs rather than relying on `ListWorkers` on the server side.

**Approach:** Use a `ClientInterceptor` that records `RecordWorkerHeartbeatRequest` and `ShutdownWorkerRequest` protos into a thread-safe list. Attach this interceptor to the `WorkflowServiceStubs` via `WorkflowServiceStubsOptions.Builder.setGrpcClientInterceptors()`.

```java
/**
 * Interceptor that captures heartbeat-related RPCs for test assertions.
 */
class HeartbeatCapturingInterceptor implements ClientInterceptor {
  final List<RecordWorkerHeartbeatRequest> heartbeatRequests =
      Collections.synchronizedList(new ArrayList<>());
  final List<ShutdownWorkerRequest> shutdownRequests =
      Collections.synchronizedList(new ArrayList<>());

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<>(
        next.newCall(method, callOptions)) {
      @Override
      public void sendMessage(ReqT message) {
        if (message instanceof RecordWorkerHeartbeatRequest) {
          heartbeatRequests.add((RecordWorkerHeartbeatRequest) message);
        } else if (message instanceof ShutdownWorkerRequest) {
          shutdownRequests.add((ShutdownWorkerRequest) message);
        }
        super.sendMessage(message);
      }
    };
  }
}
```

**Note:** The test server will return UNIMPLEMENTED for these RPCs. The interceptor captures the request before the server responds, and the HeartbeatManager already catches and logs exceptions. Tests assert on captured requests, not server responses.

| # | Test Name | Description | Key Assertions |
|---|-----------|-------------|----------------|
| 1 | `testHeartbeatRpcSentWithCorrectFields` | Start worker, run workflow + activity, verify captured heartbeat fields | All heartbeat fields populated correctly; `sdk_name == "temporal-java"`; `status == RUNNING` |
| 2 | `testShutdownIncludesHeartbeat` | Start worker (with sticky queue), graceful shutdown, verify `ShutdownWorkerRequest` has heartbeat | Captured `ShutdownWorkerRequest.worker_heartbeat` has `SHUTTING_DOWN` status |
| 3 | `testHeartbeatDisabledNoRpc` | Negative interval, verify no `RecordWorkerHeartbeat` RPCs captured | `heartbeatRequests.isEmpty()` |
| 4 | `testActivityInFlightSlotUsage` | Long-running activity, check slot usage in captured heartbeat | `activity_task_slots_info.current_used_slots >= 1` in at least one captured heartbeat |
| 5 | `testMultipleWorkersSameClient` | Two workers on same client, different task queues | Captured heartbeat request contains 2 worker heartbeat entries with different task queues |
| 6 | `testFixedSlotSupplierKind` | Verify slot supplier kind in captured heartbeat | `slot_supplier_kind == "Fixed"` |
| 7 | `testTaskCountersInHeartbeat` | Run several workflows, verify processed count in captured heartbeat | `total_processed_tasks > 0` in workflow slot info |

**Acceptance criteria for Phase 6:**
- [ ] All Tier 1 unit tests pass (no server dependency)
- [ ] All Tier 2 integration tests pass with gRPC interceptor approach (test server returns UNIMPLEMENTED but interceptor captures requests)
- [ ] No existing tests broken

---

## Files Summary

### New Files
| File | Purpose |
|------|---------|
| `temporal-sdk/.../internal/worker/HeartbeatManager.java` | Manages heartbeat lifecycle, scheduling, RPC, with lazy executor lifecycle |
| `temporal-sdk/.../internal/worker/HeartbeatTaskCounters.java` | Thread-safe task processed/failed counters |
| `temporal-sdk/...test.../internal/worker/HeartbeatManagerTest.java` | Unit tests for HeartbeatManager with mock stubs (10 tests) |
| `temporal-sdk/...test.../internal/worker/HeartbeatTaskCountersTest.java` | Unit tests for counters (3 tests) |
| `temporal-sdk/...test.../internal/worker/TrackingSlotSupplierKindTest.java` | Unit tests for supplier kind (3 tests) |
| `temporal-sdk/...test.../worker/WorkerHeartbeatIntegrationTest.java` | Integration tests with gRPC interceptor (7 tests) |

### Modified Files
| File | Change |
|------|--------|
| `WorkflowClientOptions.java` | Add `workerHeartbeatInterval` field + builder + validation |
| `WorkflowClientInternalImpl.java` | Create `HeartbeatManager`, expose via interface |
| `WorkflowClientInternal.java` | Add `getHeartbeatManager()` to interface |
| `TrackingSlotSupplier.java` | Add `supplierKind` field + `getSupplierKind()` method (existing file, minimal change) |
| `Worker.java` | Add `workerInstanceKey`, `startTime`, heartbeat callback builder |
| `WorkerFactory.java` | Wire heartbeat registration/unregistration in start/shutdown, capture `DescribeNamespaceResponse` |
| `WorkflowWorker.java` | Accept shutdown heartbeat supplier, include in `ShutdownWorkerRequest` |
| `SyncWorkflowWorker.java` | Pass through heartbeat supplier to `WorkflowWorker` |
| `WorkflowExecutorCache.java` | Add cache hit/miss counters, expose `getCurrentCacheSize()` |
| `ActivityWorker.java` / `LocalActivityWorker.java` | Add `HeartbeatTaskCounters`, increment on task completion |
| `SyncNexusWorker.java` | Add `HeartbeatTaskCounters` if nexus worker tracks tasks |

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Proto API not generated if submodule not updated | Medium | High (compile failure) | Ensure `git submodule update` is run; verify generated classes exist before coding |
| Thread safety in heartbeat callback | Medium | High (data corruption) | Use `AtomicInteger` for all counters; `volatile` for timestamps; heartbeat callback reads are snapshot-style |
| `getSupplierKind()` addition to `TrackingSlotSupplier` breaks existing behavior | Very Low | Low | Only adds a new field and getter; no change to existing methods |
| Interval counter reset race condition | Medium | Low (slightly wrong interval numbers) | `getAndSet(0)` is atomic; acceptable for monitoring data |
| `DescribeNamespaceResponse` capture changes existing start() flow | Low | Medium | Minimal change: just capture return value that was previously discarded |
| Test server returns UNIMPLEMENTED for heartbeat RPCs | Certain | Medium (test design) | Mitigated by two-tier strategy: unit tests use mocks; integration tests use gRPC interceptor to capture requests before server responds |
| HeartbeatManager executor runs with zero workers | Low | Low (resource waste) | Mitigated by lazy start/stop lifecycle: executor created on first register, stopped on last unregister |

---

## Success Criteria

- [ ] Workers send `RecordWorkerHeartbeat` RPCs at configured interval with all required fields
- [ ] Heartbeats stop after worker shutdown
- [ ] Final heartbeat delivered via correct mechanism per Shutdown Heartbeat Matrix
- [ ] Feature correctly disabled when server lacks `worker_heartbeats` capability
- [ ] Multiple workers on same client produce separate heartbeat entries in single RPC
- [ ] HeartbeatManager executor lifecycle managed (no idle executor with zero workers)
- [ ] All Tier 1 unit tests pass (16 tests)
- [ ] All Tier 2 integration tests pass (7 tests)
- [ ] No existing tests broken
- [ ] Zero compile warnings from new code

---

## ADR

**Decision:** Implement worker heartbeating using direct instrumentation with a HeartbeatManager on WorkflowClient, extending the existing `TrackingSlotSupplier` for slot data.

**Drivers:** Match Go SDK architecture for cross-SDK consistency; all required protos already exist; `TrackingSlotSupplier` already provides slot tracking infrastructure; spec mandates client-level heartbeat management.

**Alternatives considered:**
1. HeartbeatManager on WorkerFactory (simpler lifecycle but doesn't support multiple factories sharing heartbeat loop)
2. Metrics-wrapping approach like Go (tally Scope API is too complex to wrap cleanly in Java)
3. New `TrackingSlotSupplier` wrapper class (rejected: class already exists with needed tracking; only needs `getSupplierKind()` added)

**Why chosen:** Direct instrumentation is simpler in Java's metrics ecosystem, HeartbeatManager on client matches Go for multi-factory support, reusing existing `TrackingSlotSupplier` avoids unnecessary wrapping layers.

**Consequences:** Adding counters to internal worker classes increases their surface area slightly. `TrackingSlotSupplier` gains one field and one getter method. HeartbeatManager introduces a new lifecycle-managed executor.

**Follow-ups:**
- SysInfo (CPU/memory) reporting -- future work when `ResourceBasedController` or system monitoring is added
- Plugin name reporting -- future work
- Spring Boot autoconfiguration for `workerHeartbeatInterval` -- future work
- Resource-based tuner slot supplier kind reporting -- verify when tuner is more mature
- Real-server integration tests -- when test server adds `ListWorkers`/`RecordWorkerHeartbeat` support
