package io.temporal.internal.client;

import com.google.protobuf.ByteString;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.*;
import io.temporal.api.history.v1.*;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryResponse;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.WorkflowExecutionUtils;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;

/** This class encapsulates sync long poll logic of {@link RootWorkflowClientInvoker} */
final class WorkflowClientLongPollHelper {
  private WorkflowClientLongPollHelper() {}

  /**
   * Returns result of a workflow instance execution or throws an exception if workflow did not
   * complete successfully. Will wait for continue-as-new executions of the original workflow
   * execution if present.
   *
   * @param workflowType is optional.
   * @throws TimeoutException if workflow didn't complete within specified timeout
   * @throws CanceledFailure if workflow was canceled
   * @throws WorkflowFailedException if workflow execution failed
   */
  static Optional<Payloads> getWorkflowExecutionResult(
      GenericWorkflowClient genericClient,
      WorkflowClientRequestFactory workflowClientHelper,
      @Nonnull WorkflowExecution workflowExecution,
      Optional<String> workflowType,
      DataConverter converter,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    // getInstanceCloseEvent waits for workflow completion including new runs.
    HistoryEvent closeEvent =
        getInstanceCloseEvent(
            genericClient, workflowClientHelper, workflowExecution, timeout, unit);
    return WorkflowExecutionUtils.getResultFromCloseEvent(
        workflowExecution, workflowType, closeEvent, converter);
  }

  /**
   * @param timeout timeout to retrieve InstanceCloseEvent in {@code unit} units. If 0 - MAX_INTEGER
   *     will be used
   * @param unit time unit of {@code timeout}
   * @return an instance closing event, potentially waiting for workflow or continue-as-new
   *     executions to complete.
   */
  private static HistoryEvent getInstanceCloseEvent(
      GenericWorkflowClient genericClient,
      WorkflowClientRequestFactory workflowClientHelper,
      @Nonnull WorkflowExecution workflowExecution,
      long timeout,
      TimeUnit unit)
      throws TimeoutException {
    ByteString pageToken = ByteString.EMPTY;
    GetWorkflowExecutionHistoryResponse response;
    Deadline longPollTimeoutDeadline = Deadline.after(timeout, unit);

    while (true) {
      GetWorkflowExecutionHistoryRequest request =
          workflowClientHelper.newHistoryLongPollRequest(workflowExecution, pageToken);

      try {
        response = genericClient.longPollHistory(request, longPollTimeoutDeadline);
      } catch (StatusRuntimeException e) {
        if (longPollTimeoutDeadline.isExpired()
            && Status.Code.DEADLINE_EXCEEDED.equals(e.getStatus().getCode())) {
          // we want to form timeout exception only if the original deadline is indeed expired.
          // Otherwise, we should rethrow a raw DEADLINE_EXCEEDED. throwing TimeoutException
          // in this case will be highly misleading.
          throw newTimeoutException(workflowExecution, timeout, unit);
        }
        throw e;
      }

      History history = response.getHistory();
      if (history.getEventsCount() > 0) {
        HistoryEvent event = history.getEvents(0); // should be only one event
        if (!WorkflowExecutionUtils.isWorkflowExecutionClosedEvent(event)) {
          throw new RuntimeException("Unexpected workflow execution closing event: " + event);
        }
        // Workflow called continueAsNew. Start polling the new execution with new runId.
        if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW) {
          pageToken = ByteString.EMPTY;
          workflowExecution =
              WorkflowExecution.newBuilder()
                  .setWorkflowId(workflowExecution.getWorkflowId())
                  .setRunId(
                      event
                          .getWorkflowExecutionContinuedAsNewEventAttributes()
                          .getNewExecutionRunId())
                  .build();
          continue;
        }
        return event;
      }
      if (!response.getNextPageToken().isEmpty()) {
        pageToken = response.getNextPageToken();
      }
    }
  }

  static TimeoutException newTimeoutException(
      @Nonnull WorkflowExecution workflowExecution, long timeout, TimeUnit unit) {
    return new TimeoutException(
        "WorkflowId="
            + workflowExecution.getWorkflowId()
            + ", runId="
            + workflowExecution.getRunId()
            + ", timeout="
            + timeout
            + ", unit="
            + unit);
  }
}
