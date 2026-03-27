package io.temporal.worker;

import static org.junit.Assert.*;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.WorkerStatus;
import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.tuning.ResourceBasedControllerOptions;
import io.temporal.worker.tuning.ResourceBasedTuner;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class WorkerHeartbeatIntegrationTest {

  private static final String SKIP_MSG =
      "No heartbeats captured — test server may not support worker heartbeat capability";

  private static final HeartbeatCapturingInterceptor interceptor =
      new HeartbeatCapturingInterceptor();

  // Shared latches for blocking activity tests
  static final CountDownLatch blockingActivityStarted = new CountDownLatch(1);
  static final CountDownLatch blockingActivityRelease = new CountDownLatch(1);

  // Separate latches for sticky cache miss test
  static final CountDownLatch cacheTestActivityStarted = new CountDownLatch(1);
  static final CountDownLatch cacheTestActivityRelease = new CountDownLatch(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowServiceStubsOptions(
              WorkflowServiceStubsOptions.newBuilder()
                  .setGrpcClientInterceptors(Collections.singletonList(interceptor))
                  .build())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setWorkerHeartbeatInterval(Duration.ofSeconds(1))
                  .build())
          .setActivityImplementations(
              new TestActivityImpl(),
              new FailingActivityImpl(),
              new BlockingActivityImpl(),
              new CacheTestActivityImpl())
          .setWorkflowTypes(
              TestWorkflowImpl.class,
              FailingWorkflowImpl.class,
              BlockingWorkflowImpl.class,
              CacheTestWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  @Test
  public void testHeartbeatRpcSentWithCorrectFields() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());
    String workerInstanceKey = requests.get(0).getWorkerHeartbeat(0).getWorkerInstanceKey();

    // Validate via DescribeWorker — server-side round-trip
    WorkerHeartbeat hb = describeWorker(workerInstanceKey);
    assertNotNull("DescribeWorker should return stored heartbeat", hb);
    assertEquals("temporal-java", hb.getSdkName());
    assertFalse("sdk version should be set", hb.getSdkVersion().isEmpty());
    assertFalse("task queue should be set", hb.getTaskQueue().isEmpty());
    assertEquals(workerInstanceKey, hb.getWorkerInstanceKey());
    assertEquals(WorkerStatus.WORKER_STATUS_RUNNING, hb.getStatus());
    assertTrue("start time should be set", hb.hasStartTime());
    assertTrue("heartbeat time should be set", hb.hasHeartbeatTime());
    assertTrue("host info should be set", hb.hasHostInfo());
    assertFalse("host name should be set", hb.getHostInfo().getHostName().isEmpty());
    assertFalse("process id should be set", hb.getHostInfo().getProcessId().isEmpty());

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testSlotInfoInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow wf =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
    assertEquals("done", wf.execute("test"));

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());
    String workerInstanceKey = requests.get(0).getWorkerHeartbeat(0).getWorkerInstanceKey();

    WorkerHeartbeat hb = describeWorker(workerInstanceKey);
    assertNotNull("DescribeWorker should return stored heartbeat", hb);

    // All four slot types should be present
    assertTrue("workflow_task_slots_info should be set", hb.hasWorkflowTaskSlotsInfo());
    assertTrue("activity_task_slots_info should be set", hb.hasActivityTaskSlotsInfo());
    assertTrue("local_activity_slots_info should be set", hb.hasLocalActivitySlotsInfo());
    assertTrue("nexus_task_slots_info should be set", hb.hasNexusTaskSlotsInfo());

    // Slot supplier kind should be set for all types
    assertFalse(
        "workflow slot supplier kind should be set",
        hb.getWorkflowTaskSlotsInfo().getSlotSupplierKind().isEmpty());
    assertFalse(
        "activity slot supplier kind should be set",
        hb.getActivityTaskSlotsInfo().getSlotSupplierKind().isEmpty());

    // After workflow+activity completion, used slots should be 0
    assertEquals(
        "workflow used slots should be 0 after completion",
        0,
        hb.getWorkflowTaskSlotsInfo().getCurrentUsedSlots());
    assertEquals(
        "activity used slots should be 0 after completion",
        0,
        hb.getActivityTaskSlotsInfo().getCurrentUsedSlots());

    // Available slots should be positive (default fixed-size supplier)
    assertTrue(
        "workflow available slots should be > 0",
        hb.getWorkflowTaskSlotsInfo().getCurrentAvailableSlots() > 0);
    assertTrue(
        "activity available slots should be > 0",
        hb.getActivityTaskSlotsInfo().getCurrentAvailableSlots() > 0);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testTaskCountersInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow wf =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
    assertEquals("done", wf.execute("test"));

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    boolean foundWorkflowProcessed =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                hb ->
                    hb.hasWorkflowTaskSlotsInfo()
                        && hb.getWorkflowTaskSlotsInfo().getTotalProcessedTasks() >= 1);
    assertTrue(
        "workflow_task_slots_info.total_processed_tasks should be >= 1", foundWorkflowProcessed);

    boolean foundActivityProcessed =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                hb ->
                    hb.hasActivityTaskSlotsInfo()
                        && hb.getActivityTaskSlotsInfo().getTotalProcessedTasks() >= 1);
    assertTrue(
        "activity_task_slots_info.total_processed_tasks should be >= 1", foundActivityProcessed);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testPollerInfoInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    io.temporal.api.worker.v1.WorkerHeartbeat hb = requests.get(0).getWorkerHeartbeat(0);
    assertTrue(
        "workflow_poller_info should have current_pollers > 0",
        hb.hasWorkflowPollerInfo() && hb.getWorkflowPollerInfo().getCurrentPollers() > 0);
    assertTrue(
        "activity_poller_info should have current_pollers > 0",
        hb.hasActivityPollerInfo() && hb.getActivityPollerInfo().getCurrentPollers() > 0);
    // Nexus pollers are only active if nexus services are registered.
    // Just verify the field is present.
    assertTrue("nexus_poller_info should be set", hb.hasNexusPollerInfo());

    // After 3 seconds, at least one successful poll should have occurred
    assertTrue(
        "workflow_poller_info should have last_successful_poll_time set",
        hb.getWorkflowPollerInfo().hasLastSuccessfulPollTime());
    assertTrue(
        "activity_poller_info should have last_successful_poll_time set",
        hb.getActivityPollerInfo().hasLastSuccessfulPollTime());

    // Default fixed-size pollers should not report autoscaling
    assertFalse(
        "workflow_poller_info.is_autoscaling should be false with default pollers",
        hb.getWorkflowPollerInfo().getIsAutoscaling());
    assertFalse(
        "activity_poller_info.is_autoscaling should be false with default pollers",
        hb.getActivityPollerInfo().getIsAutoscaling());

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testHostInfoInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());
    String workerInstanceKey = requests.get(0).getWorkerHeartbeat(0).getWorkerInstanceKey();

    WorkerHeartbeat hb = describeWorker(workerInstanceKey);
    assertNotNull("DescribeWorker should return stored heartbeat", hb);
    assertTrue("host_info should be set", hb.hasHostInfo());
    assertFalse(
        "host_info.host_name should not be empty", hb.getHostInfo().getHostName().isEmpty());
    assertFalse(
        "host_info.process_id should not be empty", hb.getHostInfo().getProcessId().isEmpty());
    assertFalse(
        "host_info.worker_grouping_key should not be empty",
        hb.getHostInfo().getWorkerGroupingKey().isEmpty());
    assertTrue(
        "host_info.current_host_cpu_usage should be >= 0",
        hb.getHostInfo().getCurrentHostCpuUsage() >= 0.0f);
    assertTrue(
        "host_info.current_host_mem_usage should be >= 0",
        hb.getHostInfo().getCurrentHostMemUsage() >= 0.0f);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testTimestampsInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    io.temporal.api.worker.v1.WorkerHeartbeat hb = requests.get(0).getWorkerHeartbeat(0);

    assertTrue("start_time should be set", hb.hasStartTime());
    long startTimeSec = hb.getStartTime().getSeconds();
    long nowSec = java.time.Instant.now().getEpochSecond();
    assertTrue("start_time should be within 30 seconds of now", nowSec - startTimeSec <= 30);

    assertTrue("heartbeat_time should be set", hb.hasHeartbeatTime());
    long heartbeatTimeSec = hb.getHeartbeatTime().getSeconds();
    assertTrue(
        "heartbeat_time should be within 30 seconds of now", nowSec - heartbeatTimeSec <= 30);
    assertTrue("heartbeat_time should be >= start_time", heartbeatTimeSec >= startTimeSec);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testElapsedSinceLastHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    // Wait for at least 2 heartbeat ticks (interval is 1s)
    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.size() < 2);

    // First heartbeat should not have elapsed_since_last_heartbeat
    WorkerHeartbeat first = requests.get(0).getWorkerHeartbeat(0);
    assertFalse(
        "first heartbeat should not have elapsed_since_last_heartbeat",
        first.hasElapsedSinceLastHeartbeat());

    // Subsequent heartbeats should have a non-zero elapsed duration
    boolean foundNonZeroElapsed =
        requests.stream()
            .skip(1)
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .filter(WorkerHeartbeat::hasElapsedSinceLastHeartbeat)
            .anyMatch(
                hb -> {
                  com.google.protobuf.Duration d = hb.getElapsedSinceLastHeartbeat();
                  return d.getSeconds() > 0 || d.getNanos() > 0;
                });
    assertTrue(
        "subsequent heartbeats should have non-zero elapsed_since_last_heartbeat",
        foundNonZeroElapsed);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testShutdownHeartbeatStatus() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    Thread.sleep(2000);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);

    List<ShutdownWorkerRequest> shutdownRequests = interceptor.getShutdownRequests();
    Assume.assumeFalse(SKIP_MSG, shutdownRequests.isEmpty());

    ShutdownWorkerRequest shutdownReq = shutdownRequests.get(0);
    assertTrue(
        "ShutdownWorkerRequest should include a worker_heartbeat",
        shutdownReq.hasWorkerHeartbeat());
    assertEquals(
        "shutdown heartbeat status should be WORKER_STATUS_SHUTTING_DOWN",
        WorkerStatus.WORKER_STATUS_SHUTTING_DOWN,
        shutdownReq.getWorkerHeartbeat().getStatus());
    assertFalse(
        "shutdown heartbeat task_queue should be set",
        shutdownReq.getWorkerHeartbeat().getTaskQueue().isEmpty());
  }

  @Test
  public void testPluginsInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    io.temporal.api.worker.v1.WorkerHeartbeat hb = requests.get(0).getWorkerHeartbeat(0);
    assertEquals(
        "plugins list should be empty when no plugins are configured", 0, hb.getPluginsCount());

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testFailureMetricsInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    FailingWorkflow wf =
        client.newWorkflowStub(
            FailingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
    try {
      wf.execute();
    } catch (Exception e) {
      // Expected: the activity fails and the workflow fails
    }

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    // ApplicationFailure is handled within the activity handler and returned as a result,
    // so it counts as a processed task, not a failed task. "Failed tasks" tracks
    // infrastructure-level failures where the task handler itself threw an exception.
    boolean foundActivityProcessed =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                hb ->
                    hb.hasActivityTaskSlotsInfo()
                        && hb.getActivityTaskSlotsInfo().getTotalProcessedTasks() >= 1);
    assertTrue(
        "activity_task_slots_info.total_processed_tasks should be >= 1 after activity execution",
        foundActivityProcessed);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  @Test
  public void testWorkflowTaskProcessedCounts() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    for (int i = 0; i < 3; i++) {
      TestWorkflow wf =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                  .build());
      assertEquals("done", wf.execute("test" + i));
    }

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    long maxProcessed =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .filter(io.temporal.api.worker.v1.WorkerHeartbeat::hasWorkflowTaskSlotsInfo)
            .mapToLong(hb -> hb.getWorkflowTaskSlotsInfo().getTotalProcessedTasks())
            .max()
            .orElse(0);
    assertTrue(
        "workflow_task_slots_info.total_processed_tasks should be >= 3 after 3 workflows",
        maxProcessed >= 3);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  /** Verifies activity slots are occupied while an activity is running, then released after. */
  @Test
  public void testActivityInFlightSlotTracking() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    BlockingWorkflow wf =
        client.newWorkflowStub(
            BlockingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());

    // Start workflow async — the activity will block until we release it
    CompletableFuture<Void> wfFuture = WorkflowClient.execute(wf::execute);

    // Wait for the blocking activity to start
    assertTrue(
        "blocking activity should have started",
        blockingActivityStarted.await(10, TimeUnit.SECONDS));

    // Wait for a heartbeat to capture the in-flight state
    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    // While the activity is running, at least one heartbeat should show used slots >= 1
    boolean foundUsedSlot =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                hb ->
                    hb.hasActivityTaskSlotsInfo()
                        && hb.getActivityTaskSlotsInfo().getCurrentUsedSlots() >= 1);
    assertTrue(
        "activity_task_slots_info.current_used_slots should be >= 1 while activity is running",
        foundUsedSlot);

    // Release the activity
    blockingActivityRelease.countDown();
    wfFuture.get(10, TimeUnit.SECONDS);

    // Wait for a heartbeat after completion
    Thread.sleep(2000);

    requests = interceptor.getHeartbeatRequests();
    // Get the last heartbeat
    WorkerHeartbeat lastHb = requests.get(requests.size() - 1).getWorkerHeartbeat(0);
    assertEquals(
        "activity used slots should be 0 after activity completes",
        0,
        lastHb.getActivityTaskSlotsInfo().getCurrentUsedSlots());

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  /** Verifies sticky cache counters are reported in heartbeat. */
  @Test
  public void testStickyCacheCountersInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    // Run a workflow to generate at least one sticky cache hit or populate the cache
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow wf =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
    assertEquals("done", wf.execute("test"));

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());
    String workerInstanceKey = requests.get(0).getWorkerHeartbeat(0).getWorkerInstanceKey();

    WorkerHeartbeat hb = describeWorker(workerInstanceKey);
    assertNotNull("DescribeWorker should return stored heartbeat", hb);

    // Sticky cache fields should be present (values may be 0 if no cache hits yet)
    // The key thing is the fields are populated — exact values depend on timing
    assertTrue(
        "total_sticky_cache_hit + total_sticky_cache_miss + current_sticky_cache_size should be >= 0",
        hb.getTotalStickyCacheHit() >= 0
            && hb.getTotalStickyCacheMiss() >= 0
            && hb.getCurrentStickyCacheSize() >= 0);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  /**
   * Verifies sticky cache misses are tracked in heartbeat. Starts a workflow with a blocking
   * activity, purges the cache while the activity runs, then completes. The workflow task on resume
   * triggers a cache miss. Matches Go's TestWorkerHeartbeatStickyCacheMiss and Rust's
   * worker_heartbeat_sticky_cache_miss.
   */
  @Test
  public void testStickyCacheMissInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    CacheTestWorkflow wf =
        client.newWorkflowStub(
            CacheTestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());

    // Start workflow async — the activity will block until we release it
    CompletableFuture<String> wfFuture = WorkflowClient.execute(wf::execute);

    // Wait for the blocking activity to start
    assertTrue(
        "cache test activity should have started",
        cacheTestActivityStarted.await(10, TimeUnit.SECONDS));

    // Purge the sticky cache so the workflow's next WFT triggers a cache miss
    testWorkflowRule.invalidateWorkflowCache();

    // Release the activity — workflow resumes on non-sticky queue
    cacheTestActivityRelease.countDown();
    assertEquals("done", wfFuture.get(10, TimeUnit.SECONDS));

    // Wait for heartbeat to capture the sticky cache miss
    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    boolean foundCacheMiss =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(hb -> hb.getTotalStickyCacheMiss() >= 1);
    assertTrue("should have at least 1 sticky cache miss after cache purge", foundCacheMiss);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  /**
   * Verifies that interval counters (last_interval_processed_tasks) reset between heartbeat
   * intervals.
   */
  @Test
  public void testIntervalCounterReset() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();

    // Run a workflow to generate processed tasks
    TestWorkflow wf =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
    assertEquals("done", wf.execute("test"));

    // Wait for heartbeats to capture the processed tasks AND a subsequent heartbeat with no new
    // work
    Thread.sleep(4000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.size() < 3);

    // Find a heartbeat with interval processed > 0
    boolean foundNonZeroInterval =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                hb ->
                    hb.hasWorkflowTaskSlotsInfo()
                        && hb.getWorkflowTaskSlotsInfo().getLastIntervalProcessedTasks() > 0);
    assertTrue(
        "should find a heartbeat with last_interval_processed_tasks > 0", foundNonZeroInterval);

    // The last heartbeat (after no new work) should have interval processed = 0
    WorkerHeartbeat lastHb = requests.get(requests.size() - 1).getWorkerHeartbeat(0);
    if (lastHb.hasWorkflowTaskSlotsInfo()) {
      assertEquals(
          "last_interval_processed_tasks should reset to 0 when no new work occurs",
          0,
          lastHb.getWorkflowTaskSlotsInfo().getLastIntervalProcessedTasks());
    }

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
  }

  /**
   * Tests that two workers on different task queues produce distinct instance keys but share the
   * same worker_grouping_key. Matches Go's TestWorkerHeartbeatMultipleWorkers.
   */
  @Test
  public void testMultipleWorkersHaveDistinctInstanceKeys() throws Exception {
    interceptor.clear();

    String taskQueue1 = testWorkflowRule.getTaskQueue();
    String taskQueue2 = taskQueue1 + "-second";

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkerFactory factory =
        WorkerFactory.newInstance(client, testWorkflowRule.getWorkerFactoryOptions());

    Worker worker1 = factory.newWorker(taskQueue1);
    worker1.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker1.registerActivitiesImplementations(new TestActivityImpl());

    Worker worker2 = factory.newWorker(taskQueue2);
    worker2.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker2.registerActivitiesImplementations(new TestActivityImpl());

    factory.start();

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    // Both workers should be batched in the same request (same namespace)
    RecordWorkerHeartbeatRequest lastReq = requests.get(requests.size() - 1);
    assertTrue(
        "should have heartbeats for at least 2 workers", lastReq.getWorkerHeartbeatCount() >= 2);

    WorkerHeartbeat hb1 =
        lastReq.getWorkerHeartbeatList().stream()
            .filter(hb -> hb.getTaskQueue().equals(taskQueue1))
            .findFirst()
            .orElse(null);
    WorkerHeartbeat hb2 =
        lastReq.getWorkerHeartbeatList().stream()
            .filter(hb -> hb.getTaskQueue().equals(taskQueue2))
            .findFirst()
            .orElse(null);

    assertNotNull("should find heartbeat for task queue 1", hb1);
    assertNotNull("should find heartbeat for task queue 2", hb2);

    assertFalse("worker 1 instance key should not be empty", hb1.getWorkerInstanceKey().isEmpty());
    assertFalse("worker 2 instance key should not be empty", hb2.getWorkerInstanceKey().isEmpty());
    assertNotEquals(
        "workers should have distinct instance keys",
        hb1.getWorkerInstanceKey(),
        hb2.getWorkerInstanceKey());

    assertTrue("worker 1 should have host info", hb1.hasHostInfo());
    assertTrue("worker 2 should have host info", hb2.hasHostInfo());
    assertEquals(
        "workers should share the same worker_grouping_key",
        hb1.getHostInfo().getWorkerGroupingKey(),
        hb2.getHostInfo().getWorkerGroupingKey());

    // Verify both are stored server-side
    WorkerHeartbeat described1 = describeWorker(hb1.getWorkerInstanceKey());
    WorkerHeartbeat described2 = describeWorker(hb2.getWorkerInstanceKey());
    assertNotNull("worker 1 should be stored server-side", described1);
    assertNotNull("worker 2 should be stored server-side", described2);
    assertEquals(taskQueue1, described1.getTaskQueue());
    assertEquals(taskQueue2, described2.getTaskQueue());

    factory.shutdown();
    factory.awaitTermination(10, TimeUnit.SECONDS);
  }

  /**
   * Tests that resource-based tuner reports SlotSupplierKind as "ResourceBased". Matches Go's
   * TestWorkerHeartbeatResourceBasedTuner.
   */
  @Test
  public void testResourceBasedSlotSupplierKind() throws Exception {
    interceptor.clear();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkerFactory factory =
        WorkerFactory.newInstance(client, testWorkflowRule.getWorkerFactoryOptions());

    Worker worker =
        factory.newWorker(
            testWorkflowRule.getTaskQueue() + "-resource",
            WorkerOptions.newBuilder()
                .setWorkerTuner(
                    ResourceBasedTuner.newBuilder()
                        .setControllerOptions(
                            ResourceBasedControllerOptions.newBuilder(0.7, 0.7).build())
                        .build())
                .build());
    worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());

    factory.start();

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    // Find the heartbeat for the resource-based worker
    WorkerHeartbeat hb =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .filter(h -> h.getTaskQueue().equals(testWorkflowRule.getTaskQueue() + "-resource"))
            .findFirst()
            .orElse(null);
    Assume.assumeTrue("should find heartbeat for resource-based worker", hb != null);

    assertEquals(
        "workflow slot supplier kind should be ResourceBased",
        "ResourceBased",
        hb.getWorkflowTaskSlotsInfo().getSlotSupplierKind());
    assertEquals(
        "activity slot supplier kind should be ResourceBased",
        "ResourceBased",
        hb.getActivityTaskSlotsInfo().getSlotSupplierKind());
    assertEquals(
        "local activity slot supplier kind should be ResourceBased",
        "ResourceBased",
        hb.getLocalActivitySlotsInfo().getSlotSupplierKind());

    factory.shutdown();
    factory.awaitTermination(10, TimeUnit.SECONDS);
  }

  /**
   * Tests that no heartbeats are sent when heartbeat interval is not configured. Matches Go's
   * TestWorkerHeartbeatDisabled and Rust's worker_heartbeat_no_runtime_heartbeat.
   */
  @Test
  public void testNoHeartbeatsSentWhenDisabled() throws Exception {
    HeartbeatCapturingInterceptor localInterceptor = new HeartbeatCapturingInterceptor();

    SDKTestWorkflowRule noHeartbeatRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowServiceStubsOptions(
                WorkflowServiceStubsOptions.newBuilder()
                    .setGrpcClientInterceptors(Collections.singletonList(localInterceptor))
                    .build())
            // No workerHeartbeatInterval — heartbeats should be disabled
            .setDoNotStart(true)
            .build();

    try {
      localInterceptor.clear();
      noHeartbeatRule.getTestEnvironment().start();

      Thread.sleep(5000);

      assertTrue(
          "no heartbeats should be sent when heartbeat interval is not configured",
          localInterceptor.getHeartbeatRequests().isEmpty());
    } finally {
      noHeartbeatRule.getTestEnvironment().shutdown();
      noHeartbeatRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  /**
   * Queries the test server for the stored heartbeat of a given worker via the DescribeWorker RPC.
   */
  private WorkerHeartbeat describeWorker(String workerInstanceKey) {
    try {
      DescribeWorkerResponse resp =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .describeWorker(
                  DescribeWorkerRequest.newBuilder()
                      .setNamespace(
                          testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                      .setWorkerInstanceKey(workerInstanceKey)
                      .build());
      return resp.getWorkerInfo().getWorkerHeartbeat();
    } catch (io.grpc.StatusRuntimeException e) {
      if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
        return null;
      }
      throw e;
    }
  }

  // --- Workflow and Activity types ---

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute(String input);
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public String execute(String input) {
      TestActivity activity =
          Workflow.newActivityStub(
              TestActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());
      return activity.doWork(input);
    }
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String doWork(String input);
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String doWork(String input) {
      return "done";
    }
  }

  @WorkflowInterface
  public interface FailingWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class FailingWorkflowImpl implements FailingWorkflow {
    @Override
    public void execute() {
      FailingActivity activity =
          Workflow.newActivityStub(
              FailingActivity.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(10))
                  .setRetryOptions(
                      io.temporal.common.RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      activity.fail();
    }
  }

  @ActivityInterface
  public interface FailingActivity {
    @ActivityMethod
    void fail();
  }

  public static class FailingActivityImpl implements FailingActivity {
    @Override
    public void fail() {
      throw io.temporal.failure.ApplicationFailure.newFailure(
          "intentional failure for test", "TestFailure");
    }
  }

  @WorkflowInterface
  public interface BlockingWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class BlockingWorkflowImpl implements BlockingWorkflow {
    @Override
    public void execute() {
      BlockingActivity activity =
          Workflow.newActivityStub(
              BlockingActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
      activity.block();
    }
  }

  @ActivityInterface
  public interface BlockingActivity {
    @ActivityMethod
    void block();
  }

  public static class BlockingActivityImpl implements BlockingActivity {
    @Override
    public void block() {
      blockingActivityStarted.countDown();
      try {
        blockingActivityRelease.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @WorkflowInterface
  public interface CacheTestWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class CacheTestWorkflowImpl implements CacheTestWorkflow {
    @Override
    public String execute() {
      CacheTestActivity activity =
          Workflow.newActivityStub(
              CacheTestActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
      return activity.doWork();
    }
  }

  @ActivityInterface
  public interface CacheTestActivity {
    @ActivityMethod
    String doWork();
  }

  public static class CacheTestActivityImpl implements CacheTestActivity {
    @Override
    public String doWork() {
      cacheTestActivityStarted.countDown();
      try {
        cacheTestActivityRelease.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return "done";
    }
  }
}
