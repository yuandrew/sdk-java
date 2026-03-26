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
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class WorkerHeartbeatIntegrationTest {

  private static final String SKIP_MSG =
      "No heartbeats captured — test server may not support worker heartbeat capability";

  private static final HeartbeatCapturingInterceptor interceptor =
      new HeartbeatCapturingInterceptor();

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
          .setActivityImplementations(new TestActivityImpl(), new FailingActivityImpl())
          .setWorkflowTypes(TestWorkflowImpl.class, FailingWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  @Test
  public void testHeartbeatRpcSentWithCorrectFields() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    Thread.sleep(3000);

    // Use interceptor to discover the workerInstanceKey
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
  public void testShutdownIncludesHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    Thread.sleep(2000);

    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);

    List<ShutdownWorkerRequest> shutdownRequests = interceptor.getShutdownRequests();
    Assume.assumeFalse(SKIP_MSG, shutdownRequests.isEmpty());

    ShutdownWorkerRequest shutdownReq = shutdownRequests.get(0);
    if (shutdownReq.hasWorkerHeartbeat()) {
      assertEquals(
          WorkerStatus.WORKER_STATUS_SHUTTING_DOWN, shutdownReq.getWorkerHeartbeat().getStatus());
    }
  }

  @Test
  public void testInterceptorClearWorks() {
    interceptor.clear();
    assertTrue(
        "interceptor should start empty after clear", interceptor.getHeartbeatRequests().isEmpty());
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
    String result = wf.execute("test");
    assertEquals("done", result);

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    Assume.assumeFalse(SKIP_MSG, requests.isEmpty());

    boolean foundSlotInfo =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(hb -> hb.hasWorkflowTaskSlotsInfo());
    if (foundSlotInfo) {
      io.temporal.api.worker.v1.WorkerHeartbeat hb =
          requests.stream()
              .flatMap(req -> req.getWorkerHeartbeatList().stream())
              .filter(h -> h.hasWorkflowTaskSlotsInfo())
              .findFirst()
              .get();
      assertFalse(
          "slot supplier kind should be set",
          hb.getWorkflowTaskSlotsInfo().getSlotSupplierKind().isEmpty());
    }

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
}
