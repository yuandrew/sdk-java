# Worker Heartbeat Architecture

## Before Heartbeating

```
WorkflowClient
  └── WorkflowClientInternalImpl
        ├── WorkflowServiceStubs (gRPC connection)
        └── WorkflowClientOptions

WorkerFactory
  ├── WorkflowClient (reference)
  ├── Worker["task-queue-1"]
  │     ├── SyncWorkflowWorker
  │     │     └── WorkflowWorker
  │     │           ├── Poller (polls WorkflowTaskQueue)
  │     │           └── PollTaskExecutor
  │     ├── ActivityWorker (optional)
  │     └── NexusWorker
  └── Worker["task-queue-2"]
        └── ...

Startup flow:
  WorkerFactory.start()
    → DescribeNamespace RPC
    → Worker.start() for each worker
    → WorkerFactory registers with client

Shutdown flow:
  WorkerFactory.shutdown()
    → Worker.shutdown() for each worker
      → WorkflowWorker.shutdown()
        → Drain pollers
        → ShutdownWorker RPC (only if graceful + sticky queue)
    → Deregister from client
```

## After Heartbeating

```
WorkflowClient
  └── WorkflowClientInternalImpl
        ├── WorkflowServiceStubs (gRPC connection)
        ├── WorkflowClientOptions
        │     └── workerHeartbeatInterval (default 60s, negative = disabled)
        └── HeartbeatManager (nullable, null if disabled or server unsupported)
              ├── ScheduledExecutorService (fires heartbeatTick every interval)
              ├── callbacks: Map<workerInstanceKey, Supplier<WorkerHeartbeat>>
              └── workerGroupingKey (shared UUID for all workers on this client)

WorkerFactory
  ├── WorkflowClient (reference)
  ├── Worker["task-queue-1"]
  │     ├── SyncWorkflowWorker
  │     │     └── WorkflowWorker
  │     │           ├── heartbeatSupplier ───────────────┐
  │     │           ├── Poller                          │
  │     │           └── PollTaskExecutor                │ (same instance)
  │     ├── ActivityWorker                              │
  │     └── NexusWorker                                 │
  │                                                     │
  │  HeartbeatManager.callbacks["worker-key-1"] ────────┘
  │
  └── Worker["task-queue-2"]
        └── ... (same pattern)


                    ┌─────────────────────────────┐
                    │      HeartbeatManager        │
                    │                              │
                    │  Every N seconds:            │
                    │    for each callback:        │
                    │      hb = callback.get()     │
                    │      add elapsedSince...     │
                    │                              │
                    │  ──► RecordWorkerHeartbeat    │
                    │      RPC (all workers in     │
                    │      one batched request)    │
                    └──────────────┬──────────────┘
                                   │
                    Same supplier instance (set at start)
                                   │
                    ┌──────────────┴──────────────┐
                    │      WorkflowWorker          │
                    │                              │
                    │  At shutdown:                │
                    │    hb = supplier.get()       │
                    │    hb.status = SHUTTING_DOWN │
                    │                              │
                    │  ──► ShutdownWorker RPC      │
                    │      (per worker, includes   │
                    │      heartbeat + sticky TQ)  │
                    └─────────────────────────────┘
```

## Lifecycle

### Startup

```
WorkerFactory.start()
  │
  ├─ DescribeNamespace RPC
  │    └─ If server doesn't support worker heartbeats
  │         → clientInternal.disableHeartbeatManager()  (null it out, done)
  │
  ├─ Plugin chain → doStart()
  │    │
  │    ├─ Start each Worker
  │    │
  │    └─ If HeartbeatManager exists:
  │         For each Worker:
  │           supplier = worker.buildHeartbeatCallback(groupingKey)
  │           │
  │           ├─ hbManager.registerWorker(key, supplier)
  │           │    └─ Stores in callbacks map
  │           │    └─ Starts scheduler if first worker (immediate tick, then every interval)
  │           │
  │           └─ worker.workflowWorker.setHeartbeatSupplier(supplier)
  │                └─ Same supplier, stored for ShutdownWorker RPC
  │
  └─ Register WorkerFactory with client
```

### Periodic Heartbeating

```
HeartbeatManager scheduler (every interval):
  │
  └─ heartbeatTick()
       │
       ├─ For each registered callback:
       │    hb = callback.get()        ← calls buildHeartbeatCallback lambda
       │    │                              which reads LIVE worker state:
       │    │                              - slot counts (used/available)
       │    │                              - cache hits/misses
       │    │                              - current timestamp
       │    │                              - status = RUNNING (hardcoded)
       │    │
       │    └─ Adds elapsedSinceLastHeartbeat
       │
       └─ RecordWorkerHeartbeat RPC
            (batches all workers into one request)
```

### Shutdown

```
WorkerFactory.shutdown()
  │
  ├─ Plugin chain → doShutdown()
  │    │
  │    ├─ If HeartbeatManager exists:
  │    │    For each Worker:
  │    │      hbManager.unregisterWorker(key)
  │    │        └─ Removes from callbacks map
  │    │        └─ Stops scheduler if no workers remain
  │    │
  │    ├─ Deregister WorkerFactory from client
  │    │
  │    └─ For each Worker → shutdown()
  │         │
  │         └─ WorkflowWorker.shutdown()
  │              │
  │              ├─ Drain pollers
  │              │
  │              └─ ShutdownWorker RPC (always sent)
  │                   ├─ identity, namespace
  │                   ├─ stickyTaskQueue (if present)
  │                   ├─ reason = "graceful shutdown"
  │                   └─ workerHeartbeat:
  │                        hb = heartbeatSupplier.get()  ← same supplier from start, LIVE state
  │                        hb.status = SHUTTING_DOWN     ← overridden via toBuilder()
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Single supplier, two consumers | Same supplier instance set at start time on both HeartbeatManager (periodic) and WorkflowWorker (shutdown). Makes the relationship explicit from creation. |
| Status overridden at send time | Supplier always builds with RUNNING. Shutdown path overrides to SHUTTING_DOWN via `toBuilder()`. No need for a separate status-aware supplier. |
| HeartbeatManager on client, not factory | Multiple WorkerFactory instances can share one client. HeartbeatManager batches all workers across factories into one RPC per tick. |
| Disabled via null HeartbeatManager | Negative interval or unsupported server → manager is null. All call sites guard with `if (hbManager != null)`. ShutdownWorker RPC still fires (without heartbeat payload). |
| Immediate first tick | `scheduleAtFixedRate(0, interval)` sends first heartbeat immediately on worker registration, matching Rust SDK. |
