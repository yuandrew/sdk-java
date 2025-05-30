package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;

import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.io.IOException;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;

public class ActivityRetryWithMaxAttemptsTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestActivityRetryWithMaxAttempts.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testActivityRetryWithMaxAttempts() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    try {
      workflowStub.execute(testWorkflowRule.getTaskQueue());
      fail("unreachable");
    } catch (WorkflowException e) {
      assertTrue(e.getCause() instanceof ActivityFailure);
      assertTrue(e.getCause().getCause() instanceof ApplicationFailure);
      assertEquals(
          IOException.class.getName(), ((ApplicationFailure) e.getCause().getCause()).getType());
    }
    assertEquals(activitiesImpl.toString(), 3, activitiesImpl.invocations.size());
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "currentTimeMillis",
            "executeActivity HeartbeatAndThrowIO",
            "activity HeartbeatAndThrowIO",
            "heartbeat 1",
            "activity HeartbeatAndThrowIO",
            "heartbeat 2",
            "activity HeartbeatAndThrowIO",
            "heartbeat 3",
            "currentTimeMillis");
  }

  public static class TestActivityRetryWithMaxAttempts implements TestWorkflow1 {
    @Override
    @SuppressWarnings("Finally")
    public String execute(String taskQueue) {
      ActivityOptions options =
          ActivityOptions.newBuilder()
              .setTaskQueue(taskQueue)
              .setHeartbeatTimeout(Duration.ofSeconds(5))
              .setScheduleToCloseTimeout(Duration.ofSeconds(3))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setMaximumInterval(Duration.ofSeconds(1))
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setMaximumAttempts(3)
                      .setDoNotRetry(AssertionError.class.getName())
                      .build())
              .build();
      VariousTestActivities activities =
          Workflow.newActivityStub(VariousTestActivities.class, options);
      long start = Workflow.currentTimeMillis();
      try {
        activities.heartbeatAndThrowIO();
      } finally {
        if (Workflow.currentTimeMillis() - start < 2000) {
          fail("Activity retried without delay");
        }
      }
      return "ignored";
    }
  }
}
