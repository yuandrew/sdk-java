package io.temporal.internal.client;

import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import java.lang.reflect.Type;
import java.util.concurrent.*;

public final class LazyWorkflowUpdateHandleImpl<T> implements WorkflowUpdateHandle<T> {

  private final WorkflowClientCallsInterceptor workflowClientInvoker;
  private final String workflowType;
  private final String updateName;
  private final String id;
  private final WorkflowExecution execution;
  private final Class<T> resultClass;
  private final Type resultType;
  private WorkflowClientCallsInterceptor.PollWorkflowUpdateOutput<T> waitCompletedPollCall;

  public LazyWorkflowUpdateHandleImpl(
      WorkflowClientCallsInterceptor workflowClientInvoker,
      String workflowType,
      String updateName,
      String id,
      WorkflowExecution execution,
      Class<T> resultClass,
      Type resultType) {
    this.workflowClientInvoker = workflowClientInvoker;
    this.workflowType = workflowType;
    this.updateName = updateName;
    this.id = id;
    this.execution = execution;
    this.resultClass = resultClass;
    this.resultType = resultType;
  }

  @Override
  public WorkflowExecution getExecution() {
    return execution;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public CompletableFuture<T> getResultAsync(long timeout, TimeUnit unit) {

    WorkflowClientCallsInterceptor.PollWorkflowUpdateOutput<T> pollCall = null;

    // If waitCompleted was called, use the result from that call.
    synchronized (this) {
      if (waitCompletedPollCall != null) {
        pollCall = waitCompletedPollCall;
        waitCompletedPollCall = null;
      }
    }

    if (pollCall == null) {
      pollCall = pollUntilComplete(timeout, unit);
    }

    return pollCall
        .getResult()
        .exceptionally(
            failure -> {
              if (failure instanceof CompletionException) {
                // unwrap the CompletionException
                failure = failure.getCause();
              }
              failure = CheckedExceptionWrapper.unwrap(failure);
              if (failure instanceof Error) {
                throw (Error) failure;
              }
              if (failure instanceof StatusRuntimeException) {
                StatusRuntimeException sre = (StatusRuntimeException) failure;
                // Currently no way to tell if the NOT_FOUND was because the workflow ID
                // does not exist or because the update ID does not exist.
                throw sre;
              } else if (failure instanceof WorkflowException) {
                throw (WorkflowException) failure;
              } else if (failure instanceof TimeoutException) {
                throw new CompletionException(failure);
              }
              throw new WorkflowServiceException(execution, workflowType, failure);
            });
  }

  @Override
  public T getResult() {
    try {
      return getResultAsync().get();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw (cause instanceof RuntimeException
          ? (RuntimeException) cause
          : new RuntimeException(cause));
    }
  }

  @Override
  public T getResult(long timeout, TimeUnit unit) {
    try {
      return getResultAsync(timeout, unit).get();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw (cause instanceof RuntimeException
          ? (RuntimeException) cause
          : new RuntimeException(cause));
    }
  }

  @Override
  public CompletableFuture<T> getResultAsync() {
    return this.getResultAsync(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
  }

  // Can be called immediately after initialization to wait for the update to be completed, but
  // still have the result be returned by getResultAsync.
  void waitCompleted() {
    waitCompletedPollCall = pollUntilComplete(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
  }

  private WorkflowClientCallsInterceptor.PollWorkflowUpdateOutput<T> pollUntilComplete(
      long timeout, TimeUnit unit) {
    return workflowClientInvoker.pollWorkflowUpdate(
        new WorkflowClientCallsInterceptor.PollWorkflowUpdateInput<>(
            execution, updateName, id, resultClass, resultType, timeout, unit));
  }
}
