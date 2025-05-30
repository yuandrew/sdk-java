package io.temporal.worker.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class CleanNexusWorkerShutdownTest {

  private static final String COMPLETED = "Completed";
  private static final String INTERRUPTED = "Interrupted";
  private static final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private static final CountDownLatch shutdownNowLatch = new CountDownLatch(1);
  private static final TestNexusServiceImpl nexusServiceImpl =
      new TestNexusServiceImpl(shutdownLatch, shutdownNowLatch);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setNexusServiceImplementation(nexusServiceImpl)
          .setWorkerOptions(WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build())
          .build();

  @Test(timeout = 20000)
  public void testShutdown() throws InterruptedException {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, null);
    shutdownLatch.await();
    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.MINUTES);
    List<HistoryEvent> events =
        testWorkflowRule
            .getExecutionHistory(execution.getWorkflowId())
            .getHistory()
            .getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED) {
        found = true;
        Payload ar = e.getNexusOperationCompletedEventAttributes().getResult();
        String r =
            DefaultDataConverter.STANDARD_INSTANCE.fromPayload(ar, String.class, String.class);
        assertEquals(COMPLETED, r);
      }
    }
    assertTrue("Contains NexusOperationCompleted", found);
  }

  @Test(timeout = 20000)
  public void testShutdownNow() throws InterruptedException {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, "now");
    shutdownNowLatch.await();
    testWorkflowRule.getTestEnvironment().shutdownNow();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.MINUTES);
    List<HistoryEvent> events =
        testWorkflowRule
            .getExecutionHistory(execution.getWorkflowId())
            .getHistory()
            .getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED) {
        found = true;
        Payload ar = e.getNexusOperationCompletedEventAttributes().getResult();
        String r =
            DefaultDataConverter.STANDARD_INSTANCE.fromPayload(ar, String.class, String.class);
        assertEquals(INTERRUPTED, r);
      }
    }
    assertTrue("Contains NexusOperationCompleted", found);
  }

  public static class TestWorkflowImpl implements TestWorkflow1 {

    private final TestNexusServices.TestNexusService1 service =
        Workflow.newNexusServiceStub(
            TestNexusServices.TestNexusService1.class,
            NexusServiceOptions.newBuilder()
                .setOperationOptions(
                    NexusOperationOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                        .build())
                .build());

    @Override
    public String execute(String now) {
      return service.operation(now);
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    private final CountDownLatch shutdownLatch;
    private final CountDownLatch shutdownNowLatch;

    public TestNexusServiceImpl(CountDownLatch shutdownLatch, CountDownLatch shutdownNowLatch) {
      this.shutdownLatch = shutdownLatch;
      this.shutdownNowLatch = shutdownNowLatch;
    }

    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (ctx, details, now) -> {
            if (now == null) {
              shutdownLatch.countDown();
            } else {
              shutdownNowLatch.countDown();
            }
            try {
              Thread.sleep(3000);
            } catch (InterruptedException e) {
              // We ignore the interrupted exception here to let the operation complete and return
              // the
              // result. Otherwise, the result is not reported:
              return INTERRUPTED;
            }
            return COMPLETED;
          });
    }
  }
}
