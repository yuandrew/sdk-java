package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

public class ActivityMetadataTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  static final String activitySummary = "activity-summary";

  @Test
  public void testActivityWithMetaData() {
    TestWorkflow1 stub = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    stub.execute(testWorkflowRule.getTaskQueue());

    WorkflowExecution exec = WorkflowStub.fromTyped(stub).getExecution();
    WorkflowExecutionHistory workflowExecutionHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(exec.getWorkflowId());
    List<HistoryEvent> activityScheduledEvents =
        workflowExecutionHistory.getEvents().stream()
            .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
            .collect(Collectors.toList());
    assertEventMetadata(activityScheduledEvents.get(0), activitySummary, null);
  }

  private void assertEventMetadata(HistoryEvent event, String summary, String details) {
    if (summary != null) {
      String describedSummary =
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
              event.getUserMetadata().getSummary(), String.class, String.class);
      assertEquals(summary, describedSummary);
    }
    if (details != null) {
      String describedDetails =
          DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
              event.getUserMetadata().getDetails(), String.class, String.class);
      assertEquals(details, describedDetails);
    }
  }

  public static class TestWorkflowImpl implements TestWorkflow1 {

    private final TestActivities.VariousTestActivities activities =
        Workflow.newActivityStub(
            TestActivities.VariousTestActivities.class,
            ActivityOptions.newBuilder()
                .setSummary(activitySummary)
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .build());

    @Override
    public String execute(String taskQueue) {
      return activities.activity();
    }
  }
}
