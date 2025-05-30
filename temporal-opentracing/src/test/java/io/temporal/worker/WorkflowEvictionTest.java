package io.temporal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.client.*;
import io.temporal.opentracing.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowEvictionTest {
  private static final MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  private final OpenTracingOptions OT_OPTIONS =
      OpenTracingOptions.newBuilder().setTracer(mockTracer).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTracingClientInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTracingWorkerInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(SleepingWorkflowImpl.class)
          .build();

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  public static class SleepingWorkflowImpl implements TestWorkflow {

    @Override
    public String workflow(String input) {
      Workflow.sleep(1000);
      return "ok";
    }
  }

  @Test
  public void workflowEvicted() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    SignalWithStartTest.TestWorkflow workflow =
        client.newWorkflowStub(
            SignalWithStartTest.TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());

    Span span = mockTracer.buildSpan("ClientFunction").start();

    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      WorkflowClient.start(workflow::workflow, "input");
    } finally {
      span.finish();
    }

    SDKTestWorkflowRule.waitForOKQuery(WorkflowStub.fromTyped(workflow));

    testWorkflowRule.getTestEnvironment().getWorkerFactory().getCache().invalidateAll();

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());

    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");

    MockSpan workflowStartSpan = spansHelper.getByParentSpan(clientSpan).get(0);
    assertEquals(clientSpan.context().spanId(), workflowStartSpan.parentId());
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.operationName());

    List<MockSpan> workflowRunSpans = spansHelper.getByParentSpan(workflowStartSpan);
    assertEquals(1, workflowRunSpans.size());

    MockSpan workflowRunSpan = workflowRunSpans.get(0);
    assertEquals(workflowStartSpan.context().spanId(), workflowRunSpan.parentId());
    assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.operationName());
    assertEquals(true, workflowRunSpan.tags().get(StandardTagNames.EVICTED));
    assertNull(workflowRunSpan.tags().get(Tags.ERROR.getKey()));
  }
}
