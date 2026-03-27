## Worker Heartbeat Plan - 2026-03-07 (Revision 2)

### Resolved in Revision 2
- [x] Does the Java test server support `RecordWorkerHeartbeat` and `ListWorkers` RPCs? -- **No, it does not.** Resolved by two-tier test strategy: unit tests with mock stubs, integration tests with gRPC interceptor to capture outgoing RPCs.
- [x] Should `TrackingSlotSupplier` wrapping be done inside `Worker` constructor or at the `SyncActivityWorker`/`SyncWorkflowWorker` level? -- **Resolved: `TrackingSlotSupplier` already exists and is already used internally by workers.** Only need to add `getSupplierKind()` method to existing class.

### Still Open
- [ ] Where exactly in the poll task executor chain should `lastSuccessfulPollTime` be recorded? -- Need to identify the precise callback/handler where a successful poll response is received (vs. empty poll).
- [ ] Should the `workerGroupingKey` UUID be generated per `WorkflowClient` instance or per `HeartbeatManager`? -- Go uses one per client. Since HeartbeatManager is 1:1 with client, either works, but naming should be clear.
- [ ] How should `elapsed_since_last_heartbeat` behave on the very first heartbeat? -- Go likely sends Duration(0) or omits it. Need to match Go behavior.
- [ ] Does `SDKTestWorkflowRule` support passing `WorkflowClientOptions` with custom settings like `workerHeartbeatInterval`? -- Need to verify the test builder API supports this, or if we need to construct the client manually for integration tests.
- [ ] For the gRPC interceptor approach in integration tests, will the test server's UNIMPLEMENTED response cause any issues with HeartbeatManager's error handling? -- The plan assumes exceptions are caught and logged, but need to verify the gRPC channel stays healthy after UNIMPLEMENTED responses.
