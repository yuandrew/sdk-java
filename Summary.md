# Worker Heartbeating Implementation Summary

## Overview

Implements worker heartbeating for the Temporal Java SDK. Workers periodically send `RecordWorkerHeartbeat` RPCs to the server with metadata about their state (slot usage, sticky cache stats, host info, SDK version). On shutdown, a final heartbeat with `SHUTTING_DOWN` status is delivered via the appropriate mechanism. The feature is gated behind a server capability check via `DescribeNamespace`.

**Branch:** `worker-heartbeat-deep-research`
**Reference:** Go SDK PR #2186, Rust SDK core implementation
**Plan:** `.omc/plans/worker-heartbeat-plan.md`
**Spec:** `.omc/specs/deep-interview-worker-heartbeat.md`

---

## Architecture

```
WorkflowClientOptions                    WorkflowClientInternalImpl
  workerHeartbeatInterval ──────────────► creates HeartbeatManager (if interval > 0)
                                                │
WorkerFactory.start()                           │
  DescribeNamespace ──► hbManager.checkCapability()
  for each Worker:                              │
    Worker.start() ──► hbManager.registerWorker(key, callback)
                                                │
                                    ScheduledExecutorService (lazy)
                                    heartbeatTick() @ interval
                                                │
                                    RecordWorkerHeartbeatRequest
                                    (namespace, identity, [WorkerHeartbeat...])
                                                │
WorkerFactory.shutdown()                        │
  for each Worker:                              │
    graceful + sticky ──► ShutdownWorkerRequest.worker_heartbeat
    graceful + no-sticky ──► hbManager.sendFinalHeartbeat()
    shutdownNow ──► hbManager.sendFinalHeartbeat() (best-effort)
    hbManager.unregisterWorker()
```

### Key Design Decisions

1. **HeartbeatManager lives on WorkflowClient** — supports multiple WorkerFactories sharing a single heartbeat loop
2. **Direct instrumentation** (not metrics-wrapping like Go) — Java's tally `Scope` API is hard to wrap cleanly
3. **Lazy executor lifecycle** — ScheduledExecutorService starts on first worker register, stops on last unregister
4. **Capability gating** — `DescribeNamespace.namespace_info.capabilities.worker_heartbeats` checked once at factory start
5. **Java 8 compatible** — uses `ManagementFactory.getRuntimeMXBean()` instead of `ProcessHandle`

---

## New Files

| File | Purpose |
|------|---------|
| `temporal-sdk/.../internal/worker/HeartbeatManager.java` | Manages heartbeat lifecycle, scheduling, RPC. Lazy executor start/stop. |
| `temporal-sdk/.../internal/worker/HeartbeatTaskCounters.java` | Thread-safe task processed/failed counters with interval reset |
| `temporal-sdk/...test.../internal/worker/HeartbeatManagerTest.java` | 10 unit tests with mock WorkflowServiceStubs |
| `temporal-sdk/...test.../internal/worker/HeartbeatTaskCountersTest.java` | 3 unit tests for counters |
| `temporal-sdk/...test.../internal/worker/TrackingSlotSupplierKindTest.java` | 3 unit tests for supplier kind detection |
| `temporal-sdk/...test.../worker/WorkerHeartbeatIntegrationTest.java` | 4 integration tests with gRPC interceptor |

## Modified Files

| File | Change |
|------|--------|
| `WorkflowClientOptions.java` | Added `workerHeartbeatInterval` field, builder setter (`@Experimental`), validation (0→60s default, 1-60s range, negative disables), getter. Updated constructor, `Builder(options)` copy, `equals`, `hashCode`, `toString`. |
| `WorkflowClientInternalImpl.java` | Creates `HeartbeatManager` in constructor when interval > 0. Added `getHeartbeatManager()`. |
| `WorkflowClientInternal.java` (interface) | Added `@Nullable HeartbeatManager getHeartbeatManager()` |
| `TrackingSlotSupplier.java` | Added `supplierKind` field, `determineSupplierKind()` static method (Fixed/ResourceBased/className), `getSupplierKind()` getter, `getUsedSlotCount()` public method |
| `WorkflowExecutorCache.java` | Added `AtomicInteger cacheHits/cacheMisses`. Incremented in `getOrCreate()`. Added `getCacheHits()`, `getCacheMisses()`, `getCurrentCacheSize()` getters. |
| `Worker.java` | Added `workerInstanceKey` (UUID), `startTime` (Instant), `clientOptions`, `cache` fields. Added `buildHeartbeatCallback()` producing full `WorkerHeartbeat` proto, `buildSlotsInfo()`, `buildHostInfo()`, `getProcessId()` (Java 8 compatible), `toProtoTimestamp()`. |
| `WorkerFactory.java` | In `start()`: captures `DescribeNamespaceResponse`, calls `hbManager.checkCapability()`. In `doStart()`: registers heartbeat callbacks for all workers. In `doShutdown()`: sends final heartbeat per Shutdown Heartbeat Matrix, unregisters workers. |
| `WorkflowWorker.java` | Added `volatile WorkerHeartbeat shutdownHeartbeat` field. Modified shutdown to include heartbeat in `ShutdownWorkerRequest`. Added `setShutdownHeartbeat()`, `getSlotSupplier()`, `hasStickyQueue()`. |
| `SyncWorkflowWorker.java` | Added `getWorkflowSlotSupplier()`, `getLocalActivitySlotSupplier()`, `setShutdownHeartbeat()`, `hasStickyQueue()` — delegates to package-private workers |
| `SyncActivityWorker.java` | Added `getSlotSupplier()` delegating to `ActivityWorker` |
| `SyncNexusWorker.java` | Added `getSlotSupplier()` delegating to `NexusWorker` |
| `ActivityWorker.java` | Added `getSlotSupplier()` returning `TrackingSlotSupplier<ActivitySlotInfo>` |
| `LocalActivityWorker.java` | Added `getSlotSupplier()` returning `TrackingSlotSupplier<LocalActivitySlotInfo>` |
| `NexusWorker.java` | Added `getSlotSupplier()` returning `TrackingSlotSupplier<NexusSlotInfo>` |

---

## Test Results

**All 21 tests pass:**

### Tier 1: Unit Tests (16 tests)

**HeartbeatManagerTest (10 tests):**
- `testHeartbeatRpcSentAtInterval` — verifies RPC sent with correct namespace/identity/heartbeat fields
- `testMultipleWorkersInSingleRpc` — 2 workers produce single RPC with 2 heartbeat entries
- `testCapabilityGatingDisablesRpc` — no RPC when capability=false
- `testCapabilityGatingEnablesRpc` — RPC sent when capability=true
- `testUnregisterStopsRpcWhenEmpty` — scheduler stops after last unregister
- `testElapsedSinceLastHeartbeat` — second+ heartbeat includes elapsed duration
- `testExceptionsCaughtAndLogged` — RPC exception doesn't kill scheduler
- `testSendFinalHeartbeat` — standalone heartbeat with SHUTTING_DOWN status
- `testSendFinalHeartbeatSkippedWhenCapabilityDisabled` — no final heartbeat when capability=false
- `testLifecycleNoExecutorWhenEmpty` — no executor running with zero workers

**HeartbeatTaskCountersTest (3 tests):**
- `testRecordAndSnapshot` — basic record and snapshot
- `testIntervalReset` — interval counters reset, totals accumulate
- `testConcurrentAccess` — 8 threads × 1000 ops, no lost updates

**TrackingSlotSupplierKindTest (3 tests):**
- `testFixedSupplierKind` — returns "Fixed"
- `testResourceBasedSupplierKind` — returns "ResourceBased"
- `testCustomSupplierKind` — returns class simple name

### Tier 2: Integration Tests (4 tests)

**WorkerHeartbeatIntegrationTest** — uses gRPC `ClientInterceptor` to capture `RecordWorkerHeartbeatRequest` and `ShutdownWorkerRequest` before the test server responds (UNIMPLEMENTED):
- `testHeartbeatRpcSentWithCorrectFields` — validates sdk_name, version, task queue, instance key, status, timestamps, host info
- `testShutdownIncludesHeartbeat` — `ShutdownWorkerRequest.worker_heartbeat` has SHUTTING_DOWN status
- `testHeartbeatDisabledNoRpc` — smoke test for interceptor clearing
- `testSlotInfoInHeartbeat` — runs a workflow, verifies slot supplier kind is set

### Regression: Existing tests pass
- `WorkflowWorkerTest.concurrentPollRequestLockTest` — PASSED
- `WorkerIsNotGettingStartedTest.verifyThatWorkerIsNotGettingStarted` — PASSED

---

## Shutdown Heartbeat Matrix

| Condition | Sticky Queue? | Mechanism |
|-----------|:------------:|-----------|
| Graceful `shutdown()` | Yes | `ShutdownWorkerRequest.worker_heartbeat` (single RPC for both purposes) |
| Graceful `shutdown()` | No | `HeartbeatManager.sendFinalHeartbeat()` via `RecordWorkerHeartbeat` |
| Forced `shutdownNow()` | N/A | `HeartbeatManager.sendFinalHeartbeat()` (best-effort) |

---

## Heartbeat Proto Fields Populated

| Field | Source |
|-------|--------|
| `worker_instance_key` | `UUID.randomUUID()` per Worker |
| `worker_identity` | `WorkflowClientOptions.identity` |
| `task_queue` | Worker's task queue |
| `sdk_name` | `Version.SDK_NAME` ("temporal-java") |
| `sdk_version` | `Version.LIBRARY_VERSION` |
| `status` | RUNNING or SHUTTING_DOWN |
| `start_time` | `Instant.now()` at Worker construction |
| `heartbeat_time` | `Instant.now()` at callback invocation |
| `elapsed_since_last_heartbeat` | Computed by HeartbeatManager between ticks |
| `host_info.host_name` | `InetAddress.getLocalHost().getHostName()` |
| `host_info.process_id` | `ManagementFactory.getRuntimeMXBean().getName()` (Java 8 compatible) |
| `host_info.worker_grouping_key` | UUID per WorkflowClient |
| `workflow_task_slots_info` | From `TrackingSlotSupplier` (available, used, supplier kind) |
| `activity_task_slots_info` | From `TrackingSlotSupplier` (if activity worker exists) |
| `local_activity_slots_info` | From `TrackingSlotSupplier` |
| `nexus_task_slots_info` | From `TrackingSlotSupplier` |
| `total_sticky_cache_hit` | `WorkflowExecutorCache.getCacheHits()` |
| `total_sticky_cache_miss` | `WorkflowExecutorCache.getCacheMisses()` |
| `current_sticky_cache_size` | `WorkflowExecutorCache.getCurrentCacheSize()` |

---

## Remaining Tasks

### Must-do before merge

1. **Wire HeartbeatTaskCounters into task execution paths** — `HeartbeatTaskCounters` class exists but is not yet instrumented in the actual task completion/failure paths of `WorkflowWorker`, `ActivityWorker`, `LocalActivityWorker`, and `NexusWorker`. The `buildSlotsInfo()` method in `Worker.java` currently only reports slot data, not processed/failed counts. Need to:
   - Add `HeartbeatTaskCounters` field to each `*Worker` class
   - Call `counters.recordProcessed()` / `counters.recordFailed()` in `PollTaskExecutor` completion paths
   - Pass counters to `buildSlotsInfo()` and populate `total_processed_tasks`, `total_failed_tasks`, `last_interval_processed_tasks`, `last_interval_failed_tasks` fields
   - Expose counters through Sync wrappers to `Worker.buildHeartbeatCallback()`

2. **Wire poller info** — `WorkerPollerInfo` proto fields (`poller_count`, `last_successful_poll_time`, `is_autoscaling`) are not populated. Need to:
   - Add `volatile Instant lastSuccessfulPollTime` to each `*Worker` or their pollers
   - Expose poller count from `PollerBehavior` configuration
   - Build `WorkerPollerInfo` in `Worker.buildHeartbeatCallback()` for workflow, workflow-sticky, activity, and nexus pollers

3. **Wire deployment version** — If `DeploymentOptions` is configured on the worker, the `deployment_version` field should be populated in the heartbeat. Need to plumb `SingleWorkerOptions.getDeploymentOptions()` through to `Worker.buildHeartbeatCallback()`.

### Nice-to-have (follow-up PRs)

4. **CPU/memory reporting** — Report 0 for now (as specified in plan). Future work when `ResourceBasedController` or system monitoring is added.
5. **Plugin name reporting** — Empty list for now. Future work.
6. **Spring Boot autoconfiguration** — `workerHeartbeatInterval` via Spring Boot config properties.
7. **Real-server integration tests** — When the test server adds `ListWorkers`/`RecordWorkerHeartbeat` support, add tests that verify server-side state.
8. **Additional integration tests** — The plan specifies 7 integration tests; currently 4 are implemented. Missing: `testActivityInFlightSlotUsage`, `testMultipleWorkersSameClient`, `testTaskCountersInHeartbeat` (blocked on counter wiring).
