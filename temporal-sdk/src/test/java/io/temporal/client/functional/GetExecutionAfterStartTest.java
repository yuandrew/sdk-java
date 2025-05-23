package io.temporal.client.functional;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.temporal.client.WorkflowStub;
import io.temporal.internal.Issue;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

@Issue("https://github.com/temporalio/sdk-java/issues/1667")
public class GetExecutionAfterStartTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl1.class, TestWorkflowImpl2.class)
          .build();

  @Test
  public void testWorkflowExecutionIsAvailableOnTheStubAfterStart() {
    TestWorkflows.NoArgsWorkflow workflow1 =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowStub workflowStub1 = WorkflowStub.fromTyped(workflow1);
    workflowStub1.start();
    assertNotNull(workflowStub1.getExecution());
    assertFalse(workflowStub1.getExecution().getRunId().isEmpty());

    TestWorkflows.TestSignaledWorkflow workflow2 =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestSignaledWorkflow.class);
    WorkflowStub workflowStub2 = WorkflowStub.fromTyped(workflow2);
    workflowStub2.signalWithStart("signal", new String[] {"arg"}, new String[] {});
    assertNotNull(workflowStub2.getExecution());
    assertFalse(workflowStub2.getExecution().getRunId().isEmpty());
  }

  public static class TestWorkflowImpl1 implements TestWorkflows.NoArgsWorkflow {
    @Override
    public void execute() {}
  }

  public static class TestWorkflowImpl2 implements TestWorkflows.TestSignaledWorkflow {
    @Override
    public String execute() {
      return "done";
    }

    @Override
    public void signal(String arg) {}
  }
}
