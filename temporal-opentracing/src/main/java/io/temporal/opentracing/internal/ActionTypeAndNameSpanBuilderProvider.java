package io.temporal.opentracing.internal;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.opentracing.Tracer;
import io.temporal.opentracing.SpanBuilderProvider;
import io.temporal.opentracing.SpanCreationContext;
import io.temporal.opentracing.SpanOperationType;
import io.temporal.opentracing.StandardTagNames;
import java.util.Map;

/**
 * Default implementation of the {@link SpanBuilderProvider}. Uses both the {@link
 * SpanOperationType} and the {@link SpanCreationContext#getActionName()} as the name of the
 * OpenTracing span, e.g "StartActivity:LoadUsersFromDatabaseActivity". <br>
 * This class also provides any available IDs, such as workflow ID, run ID, or parent workflow/run
 * ID, as tags depending on the context of the operation.
 */
public class ActionTypeAndNameSpanBuilderProvider implements SpanBuilderProvider {
  public static final ActionTypeAndNameSpanBuilderProvider INSTANCE =
      new ActionTypeAndNameSpanBuilderProvider();

  private static final String PREFIX_DELIMITER = ":";

  public ActionTypeAndNameSpanBuilderProvider() {}

  public Tracer.SpanBuilder createSpanBuilder(Tracer tracer, SpanCreationContext context) {
    Tracer.SpanBuilder spanBuilder = tracer.buildSpan(this.getSpanName(context));

    getSpanTags(context).forEach(spanBuilder::withTag);

    return spanBuilder;
  }

  /**
   * Generates the name of the span given the span context.
   *
   * @param context Span creation context
   * @return The span name
   */
  protected String getSpanName(SpanCreationContext context) {
    return context.getSpanOperationType().getDefaultPrefix()
        + PREFIX_DELIMITER
        + context.getActionName();
  }

  /**
   * Generates tags for the span given the span creation context
   *
   * @param context The span creation context
   * @return The map of tags for the span
   */
  protected Map<String, String> getSpanTags(SpanCreationContext context) {
    SpanOperationType operationType = context.getSpanOperationType();
    switch (operationType) {
      case START_WORKFLOW:
      case SIGNAL_WITH_START_WORKFLOW:
        return ImmutableMap.of(StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
      case START_CHILD_WORKFLOW:
        return ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, context.getWorkflowId(),
            StandardTagNames.PARENT_WORKFLOW_ID, context.getParentWorkflowId(),
            StandardTagNames.PARENT_RUN_ID, context.getParentRunId());
      case START_CONTINUE_AS_NEW_WORKFLOW:
        return ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, context.getWorkflowId(),
            StandardTagNames.PARENT_RUN_ID, context.getParentRunId());
      case RUN_WORKFLOW:
      case START_ACTIVITY:
      case RUN_ACTIVITY:
      case SIGNAL_EXTERNAL_WORKFLOW:
      case SIGNAL_WORKFLOW:
      case UPDATE_WORKFLOW:
      case QUERY_WORKFLOW:
      case HANDLE_SIGNAL:
      case HANDLE_UPDATE:
        String runId = context.getRunId();
        Preconditions.checkNotNull(
            runId, "runId is expected to be not null for span operation type %s", operationType);
        return ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, context.getWorkflowId(),
            StandardTagNames.RUN_ID, context.getRunId());
      case START_NEXUS_OPERATION:
        return ImmutableMap.of(
            StandardTagNames.WORKFLOW_ID, context.getWorkflowId(),
            StandardTagNames.RUN_ID, context.getRunId());
      case RUN_START_NEXUS_OPERATION:
      case RUN_CANCEL_NEXUS_OPERATION:
      case HANDLE_QUERY:
        return ImmutableMap.of();
    }
    throw new IllegalArgumentException("Unknown span operation type provided");
  }
}
