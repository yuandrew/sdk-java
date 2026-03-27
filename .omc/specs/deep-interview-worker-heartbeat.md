# Deep Interview Spec: Worker Heartbeating in Java SDK

## Metadata
- Interview Rounds: 5
- Final Ambiguity Score: 19.0%
- Type: brownfield
- Generated: 2026-03-07
- Threshold: 20%
- Status: PASSED

## Clarity Breakdown
| Dimension | Score | Weight | Weighted |
|-----------|-------|--------|----------|
| Goal Clarity | 0.9 | 35% | 0.315 |
| Constraint Clarity | 0.7 | 25% | 0.175 |
| Success Criteria | 0.8 | 25% | 0.200 |
| Context Clarity | 0.8 | 15% | 0.120 |
| **Total Clarity** | | | **0.810** |
| **Ambiguity** | | | **19.0%** |

## Goal
Implement worker heartbeating in the Java SDK so that workers periodically send `RecordWorkerHeartbeat` RPCs to the server with metadata about their state (slot usage, poller info, sticky cache stats, task processing counts, timestamps, deployment version). On shutdown, send a final heartbeat via `ShutdownWorker` RPC with status=SHUTTING_DOWN. Gate the feature behind server capability check via `DescribeNamespace`.

## Reference Implementations
- **Go SDK**: https://github.com/temporalio/sdk-go/pull/2186
  - Key files: `internal_worker_heartbeat.go`, `internal_worker_heartbeat_metrics.go`, `internal_worker.go`, `internal_workflow_client.go`
  - Tests: `test/worker_heartbeat_test.go` (915 lines, 10 test scenarios)
- **Rust SDK Core**: `crates/sdk-core/src/worker/heartbeat.rs`
  - PRs: #1082 (shutdown), #1046 (capability gating), #1068 (interval bounds)

## Architecture

### Heartbeat Manager (on WorkflowClient)
- `HeartbeatManager` lives on `WorkflowClientInternalImpl`, created when `workerHeartbeatInterval` is configured
- Groups workers by namespace (Java client is already namespace-scoped, so effectively one group)
- `SharedNamespaceWorker` per namespace: runs a `ScheduledExecutorService` that fires on the configured interval
- On each tick: collects heartbeat data from all registered workers, sends single `RecordWorkerHeartbeat` RPC

### Worker Registration
- Each `Worker` (within a `WorkerFactory`) registers a heartbeat callback with the `HeartbeatManager` on start
- Each `Worker` unregisters on stop
- If no workers remain for a namespace, the `SharedNamespaceWorker` is stopped

### Heartbeat Callback
- Each `Worker` provides a callback (lambda/supplier) that builds a `WorkerHeartbeat` proto
- Callback captures: slot info per worker type, poller info, sticky cache stats, task counts, timestamps, identity, task queue, SDK info, status

### Metrics Capture
- Implementation approach is flexible (decided during implementation)
- Must capture: slot available/used counts, task processed/failed counts, poller counts, sticky cache hit/miss/size, last successful poll time
- Go uses a wrapping `HeartbeatMetricsHandler`; Java may use direct instrumentation or a similar wrapping approach

### Configuration
- `WorkflowClientOptions.workerHeartbeatInterval(Duration)` — experimental
- Default: 0 → defaults to 60s interval
- Negative value → disabled
- Must be between 1s and 60s inclusive (else throw)

### Capability Gating
- Before first heartbeat, call `DescribeNamespace` to check `namespace_info.capabilities.worker_heartbeats`
- If server doesn't support it, log a debug message and skip heartbeating
- Cache the result on the client (like Go's `loadNamespaceCapabilities`)

### Shutdown
- On `Worker.stop()` / `WorkerFactory.shutdown()`, send `ShutdownWorker` RPC with:
  - Final heartbeat (status=SHUTTING_DOWN)
  - Sticky task queue (if applicable)
  - Worker identity and instance key
- Always send ShutdownWorker, even if heartbeating is disabled (for sticky queue cleanup)
- Errors logged but don't fail shutdown
- Unregister from heartbeat manager after shutdown RPC

### Worker Instance Key
- Each `Worker` gets a unique UUID (`workerInstanceKey`) — identifies this specific worker instance
- `workerGroupingKey` on the client groups all workers from the same process/client

## Data Fields (Core — No SysInfo/Plugins)
Per heartbeat:
- `worker_instance_key`: UUID per worker
- `worker_identity`: from WorkerOptions/ClientOptions
- `host_info.host_name`: `InetAddress.getLocalHost().getHostName()`
- `host_info.worker_grouping_key`: UUID per client
- `host_info.process_id`: from `ProcessHandle.current().pid()`
- `host_info.current_host_cpu_usage`: 0 (not implemented)
- `host_info.current_host_mem_usage`: 0 (not implemented)
- `task_queue`: worker's task queue
- `deployment_version`: if versioning enabled
- `sdk_name`: "temporal-java"
- `sdk_version`: from Version class
- `status`: RUNNING or SHUTTING_DOWN
- `start_time`: timestamp when worker was created
- `heartbeat_time`: current timestamp
- `elapsed_since_last_heartbeat`: duration since previous heartbeat
- `workflow_task_slots_info`: available, used, supplier kind, total processed, total failed, interval processed, interval failed
- `activity_task_slots_info`: same
- `local_activity_slots_info`: same
- `nexus_task_slots_info`: same (if nexus worker exists)
- `workflow_poller_info`: current pollers, last successful poll time, is_autoscaling
- `workflow_sticky_poller_info`: same
- `activity_poller_info`: same
- `nexus_poller_info`: same
- `total_sticky_cache_hit`: counter
- `total_sticky_cache_miss`: counter
- `current_sticky_cache_size`: gauge

## Constraints
- Configuration on `WorkflowClientOptions` (matches Go)
- HeartbeatManager on WorkflowClient (matches Go/Rust — groups by namespace)
- Interval: 1s-60s, default 60s, negative to disable
- Mark as experimental
- No SysInfo (CPU/memory) — report 0
- No plugin name reporting
- Must not break existing activity heartbeat behavior
- Thread-safe: heartbeat callback invoked from heartbeat executor thread, must safely read worker state

## Non-Goals
- CPU/memory reporting (SysInfoProvider) — future work
- Plugin name reporting — future work
- Autoscaling poller behavior reporting — report false for `is_autoscaling`
- Resource-based tuner integration — future work
- Spring Boot autoconfiguration for heartbeat interval — future work

## Acceptance Criteria
- [ ] `WorkflowClientOptions.Builder.setWorkerHeartbeatInterval(Duration)` exists and validates bounds
- [ ] `HeartbeatManager` created on client when interval > 0
- [ ] Workers register/unregister heartbeat callbacks on start/stop
- [ ] `RecordWorkerHeartbeat` RPC sent at configured interval with correct proto fields
- [ ] Server capability check via `DescribeNamespace` gates heartbeating
- [ ] `ShutdownWorker` RPC sent on worker stop with final heartbeat (status=SHUTTING_DOWN)
- [ ] Multiple workers on same client share heartbeat loop
- [ ] Disabled heartbeating (negative interval) means no heartbeats sent
- [ ] Slot info tracks available/used/processed/failed per worker type
- [ ] Poller info tracks count and last successful poll time
- [ ] Sticky cache stats (hit/miss/size) tracked
- [ ] Task failure metrics (workflow task + activity) tracked with interval deltas
- [ ] Deployment version included when versioning enabled
- [ ] All tests pass with integration test server

## Test Scenarios (matching Go)
1. **TestWorkerHeartbeatBasic** — Start worker, run workflow+activity, verify all heartbeat fields, verify shutdown status
2. **TestWorkerHeartbeatDeploymentVersion** — Worker with versioning, verify deployment version in heartbeat
3. **TestWorkerHeartbeatDisabled** — Negative interval, verify no worker in ListWorkers
4. **TestWorkerHeartbeatWithActivityInFlight** — Verify activity slot tracked as used during execution
5. **TestWorkerHeartbeatStickyCacheMiss** — Purge cache, verify sticky cache miss counted
6. **TestWorkerHeartbeatMultipleWorkers** — Two workers on same client, different task queues, verify separate tracking
7. **TestWorkerHeartbeatFailureMetrics** — Failing activity, verify failure count and interval reset
8. **TestWorkerHeartbeatWorkflowTaskFailureMetrics** — Panicking workflow, verify WFT failure count
9. **TestWorkerHeartbeatWorkflowTaskProcessed** — Multiple workflows, verify processed count and interval reset
10. **TestWorkerHeartbeatResourceBasedTuner** — Skip or adapt (Java doesn't have resource-based tuner yet) — verify slot supplier kind reporting for fixed supplier

## Assumptions Exposed & Resolved
| Assumption | Challenge | Resolution |
|------------|-----------|------------|
| Config on WorkerFactory | Go puts it on Client | User chose WorkflowClientOptions (match Go) |
| HeartbeatManager on WorkerFactory | Simpler lifecycle | User chose WorkflowClient (match Go, supports multiple factories) |
| Need CPU/memory reporting | Go has SysInfoProvider | Skip for now, report 0 — future work |
| Need plugin reporting | Go has plugin Name() | Skip for now — future work |
| Metrics wrapping vs direct | Go wraps metrics handler | Flexible — decided during implementation |

## Technical Context
### Key Files to Modify
- `temporal-sdk/src/main/java/io/temporal/client/WorkflowClientOptions.java` — add heartbeat interval
- `temporal-sdk/src/main/java/io/temporal/client/WorkflowClientInternalImpl.java` — create HeartbeatManager
- `temporal-sdk/src/main/java/io/temporal/worker/Worker.java` — register/unregister heartbeat callback
- `temporal-sdk/src/main/java/io/temporal/worker/WorkerFactory.java` — shutdown integration

### New Files
- `temporal-sdk/src/main/java/io/temporal/internal/worker/HeartbeatManager.java`
- `temporal-sdk/src/main/java/io/temporal/internal/worker/SharedNamespaceHeartbeatWorker.java`
- `temporal-sdk/src/main/java/io/temporal/internal/worker/HeartbeatMetricsHandler.java` (if wrapping approach chosen)
- `temporal-sdk/src/test/java/io/temporal/worker/WorkerHeartbeatTest.java`

### Existing Patterns
- `WorkerFactoryRegistry` — existing pattern for client tracking multiple factories
- `HeartbeatContextImpl` — existing activity heartbeat (separate concern, but similar naming)
- `ScheduledExecutorService` — used throughout for periodic tasks
- JUnit 4 test suites with `TestWorkflowRule` / `SDKTestWorkflowRule`

## Interview Transcript
<details>
<summary>Full Q&A (5 rounds)</summary>

### Round 1
**Q:** Where should heartbeat interval configuration live?
**A:** WorkflowClientOptions (matches Go)
**Ambiguity:** 50.5%

### Round 2
**Q:** Should Java tests match all Go test scenarios?
**A:** Match all Go tests (port all, skip only for features that don't exist in Java)
**Ambiguity:** 40.5%

### Round 3
**Q:** Should we wrap the metrics handler (like Go) or use direct instrumentation?
**A:** Decide during implementation — flexible
**Ambiguity:** 34.0%

### Round 4
**Q:** Should HeartbeatManager live on WorkflowClient or WorkerFactory?
**A:** WorkflowClient (match Go — supports multiple factories sharing a client)
**Ambiguity:** 26.5%

### Round 5
**Q:** Should we include SysInfo (CPU/memory) and plugin reporting?
**A:** Core data only — skip SysInfo and plugins for now
**Ambiguity:** 19.0%
</details>
