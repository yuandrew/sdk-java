package io.temporal.opentracing;

import static org.junit.Assert.*;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class AsyncLambdaTest {

  private static final String BAGGAGE_ITEM_KEY = "baggage-item-key";

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
          .setWorkflowTypes(WorkflowImpl.class)
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

  public static class WorkflowImpl implements TestWorkflow {
    @Override
    public String workflow(String input) {
      Promise<String> lambda =
          Async.function(
              () -> {
                Span lambdaActiveSpan = mockTracer.scopeManager().activeSpan();

                MockSpan lambdaMockSpan = (MockSpan) lambdaActiveSpan;
                assertNotNull(lambdaMockSpan);
                assertNotEquals(0, lambdaMockSpan.parentId());

                return lambdaActiveSpan.getBaggageItem(BAGGAGE_ITEM_KEY);
              });

      // returning lambda result which should be a value of the baggage item
      return lambda.get();
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * StartWorkflow:TestWorkflow  -follow>  RunWorkflow:TestWorkflow
   * And async invocation of lambda doesn't create its own child or following spans
   */
  @Test
  public void asyncLambdaCorrectSpanStructureAndBaggagePropagation() {
    Span span = mockTracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      Span activeSpan = mockTracer.scopeManager().activeSpan();
      final String BAGGAGE_ITEM_VALUE = "baggage-item-value";
      activeSpan.setBaggageItem(BAGGAGE_ITEM_KEY, BAGGAGE_ITEM_VALUE);

      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals(
          "Baggage item should be propagated all the way to the lambda body",
          BAGGAGE_ITEM_VALUE,
          workflow.workflow("input"));
    } finally {
      span.finish();
    }

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());

    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");

    MockSpan workflowStartSpan = spansHelper.getByParentSpan(clientSpan).get(0);
    assertEquals(clientSpan.context().spanId(), workflowStartSpan.parentId());
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.operationName());

    MockSpan workflowRunSpan = spansHelper.getByParentSpan(workflowStartSpan).get(0);
    assertEquals(workflowStartSpan.context().spanId(), workflowRunSpan.parentId());
    assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.operationName());

    assertEquals(
        "Lambda shouldn't create any new spans, it should carry an existing span",
        0,
        spansHelper.getByParentSpan(workflowRunSpan).size());
  }
}
