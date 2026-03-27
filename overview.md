# Worker Heartbeat Changes Overview

~1,900 lines added across 32 files.

## 1. Configuration (`WorkflowClientOptions`)
New `workerHeartbeatInterval` option. Defaults to 60s, negative disables, positive must be 1-60s. This is where users opt in/out.

## 2. HeartbeatManager (core orchestrator)
New class that owns the heartbeat lifecycle:
- Holds a map of registered worker callbacks
- Runs a `ScheduledExecutorService` that fires `heartbeatTick()` on the configured interval
- Sends `RecordWorkerHeartbeatRequest` RPCs aggregating all workers' heartbeats
- Lazy start (first worker registers) / auto-stop (last worker unregisters)
- Self-disables on `UNIMPLEMENTED` gRPC status

## 3. Capability gating (`WorkerFactory`)
At `start()` time, calls `DescribeNamespace` to check `capabilities.workerHeartbeats`. If unsupported, shuts down and nulls the HeartbeatManager before any workers register.

## 4. Heartbeat payload assembly (`Worker.buildHeartbeatCallback`)
Each `Worker` builds a `Supplier<WorkerHeartbeat>` that collects:
- Worker identity, task queue, SDK version, status, timestamps
- Deployment version info
- **Slot info** per task type (workflow, activity, local-activity, nexus) — available/used slots + task counters
- **Poller info** — autoscaling flag, active pollers, last successful poll time
- Sticky cache stats (hits, misses, current size)
- Registered plugins

## 5. New supporting infrastructure
- **`HeartbeatTaskCounters`** — per-task-type counters (processed/failed) with snapshotting for interval deltas
- **`PollerTracker`** — tracks in-flight polls and last successful poll time
- **`TrackingSlotSupplier`** additions — exposes supplier kind (fixed/resource-based) and max slot count
- **`WorkflowExecutorCache`** additions — exposes cache hit/miss/size counters

## 6. Poller instrumentation
Modified all poll tasks (`ActivityPollTask`, `WorkflowPollTask`, `NexusPollTask` + async variants) to report poll start/complete/success to `PollerTracker`.

## 7. Task counter instrumentation
Modified `ActivityWorker`, `NexusWorker`, `WorkflowWorker`, `LocalActivityWorker` to increment `HeartbeatTaskCounters` on task completion/failure.

## 8. Shutdown integration
`WorkerFactory.doShutdown()` unregisters workers from the HeartbeatManager. `WorkflowWorker` includes a heartbeat with `SHUTTING_DOWN` status in the `ShutdownWorker` RPC. After that, the worker simply stops heartbeating — the server infers full shutdown from the absence of heartbeats. This matches the Go and Rust SDK contract.

## 9. Tests
- **Unit tests**: `HeartbeatManagerTest`, `HeartbeatTaskCountersTest`, `PollerTrackerTest`, `TrackingSlotSupplierKindTest`
- **Integration tests**: `WorkerHeartbeatIntegrationTest` (verifies end-to-end against a real server), `WorkerHeartbeatDeploymentVersionTest`
- **Test utility**: `HeartbeatCapturingInterceptor` for capturing heartbeat payloads

## Gating behavior

### Two-level gating with runtime fallback

| Condition | HeartbeatManager created? | Heartbeats sent? |
|---|---|---|
| Negative interval | No | No |
| Capabilities unsupported | Yes, then shut down + nulled | No |
| `UNIMPLEMENTED` at runtime | Yes, running, then self-disables | Stops after first attempt |
| Happy path (default 60s + capabilities OK) | Yes | Yes |

### Cross-SDK log level comparison (capability not supported)

| SDK | Log level | Message |
|---|---|---|
| **Go** | `Debug` | "Worker heartbeating configured, but server version does not support it." |
| **Rust** | `debug!` | "Worker heartbeating configured for runtime, but server version does not support it." |
| **Java** | `debug` | "Server does not support worker heartbeats for namespace {}, heartbeating disabled" |
