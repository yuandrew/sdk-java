package io.temporal.workflow.upsertMemoTests;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class UpsertMemoTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new WorkerInterceptor())
                  .build())
          .setWorkflowTypes(TestWorkflow1Impl.class)
          .build();

  @Test
  public void upsertMemo() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertEquals("memoValue2", result);
    // Verify that describeWorkflowExecution returns the correct final memo
    DescribeWorkflowExecutionResponse resp =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                    .setExecution(WorkflowStub.fromTyped(workflowStub).getExecution())
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .build());
    Map<String, Payload> memo =
        Collections.singletonMap(
            "memoKey2", DefaultDataConverter.newDefaultInstance().toPayload("memoValue2").get());
    Assert.assertEquals(memo, resp.getWorkflowExecutionInfo().getMemo().getFieldsMap());
  }

  public static class TestWorkflow1Impl implements TestWorkflow1 {

    @Override
    public String execute(String testName) {
      String memoVal = Workflow.getMemo("memoKey", String.class, String.class);
      Assert.assertNull(memoVal);

      Workflow.upsertMemo(Collections.singletonMap("memoKey", "memoValue"));
      memoVal = Workflow.getMemo("memoKey", String.class, String.class);
      Assert.assertEquals("memoValue", memoVal);

      Workflow.sleep(Duration.ofMillis(100));

      Workflow.upsertMemo(Collections.singletonMap("memoKey2", "memoValue2"));
      memoVal = Workflow.getMemo("memoKey", String.class, String.class);
      Assert.assertEquals("memoValue", memoVal);

      Workflow.sleep(Duration.ofMillis(100));

      Workflow.upsertMemo(Collections.singletonMap("memoKey", null));
      memoVal = Workflow.getMemo("memoKey", String.class, String.class);
      Assert.assertNull(memoVal);
      return Workflow.getMemo("memoKey2", String.class, String.class);
    }
  }

  private static class WorkerInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          next.init(new OutboundCallsInterceptor(outboundCalls));
        }
      };
    }
  }

  private static class OutboundCallsInterceptor extends WorkflowOutboundCallsInterceptorBase {
    public OutboundCallsInterceptor(WorkflowOutboundCallsInterceptor next) {
      super(next);
    }
  }
}
