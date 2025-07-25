package io.temporal.common.interceptors;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.update.v1.WaitPolicy;
import io.temporal.client.*;
import io.temporal.common.Experimental;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Intercepts calls to the {@link io.temporal.client.WorkflowClient} related to the lifecycle of a
 * Workflow.
 *
 * <p>Prefer extending {@link WorkflowClientCallsInterceptorBase} and overriding only the methods
 * you need instead of implementing this interface directly. {@link
 * WorkflowClientCallsInterceptorBase} provides correct default implementations to all the methods
 * of this interface.
 */
@Experimental
public interface WorkflowClientCallsInterceptor {
  /**
   * If you implement this method, {@link #signalWithStart} and @{link #updateWithStart} most likely
   * need to be implemented too.
   *
   * @see #signalWithStart
   */
  WorkflowStartOutput start(WorkflowStartInput input);

  /**
   * If you implement this method, {@link #signalWithStart} most likely needs to be implemented too.
   *
   * @see #signalWithStart
   */
  WorkflowSignalOutput signal(WorkflowSignalInput input);

  WorkflowSignalWithStartOutput signalWithStart(WorkflowSignalWithStartInput input);

  /**
   * Intercepts calls from {@link WorkflowStub#startUpdateWithStart} and {@link
   * WorkflowStub#executeUpdateWithStart} as well as {@link WorkflowClient#startUpdateWithStart} and
   * {@link WorkflowClient#executeUpdateWithStart}.
   */
  @Experimental
  <R> WorkflowUpdateWithStartOutput<R> updateWithStart(WorkflowUpdateWithStartInput<R> input);

  /**
   * If you implement this method, {@link #getResultAsync} most likely needs to be implemented too.
   *
   * @see #getResultAsync
   */
  <R> GetResultOutput<R> getResult(GetResultInput<R> input) throws TimeoutException;

  /**
   * If you implement this method, {@link #getResult} most likely needs to be implemented too.
   *
   * @see #getResult
   */
  <R> GetResultAsyncOutput<R> getResultAsync(GetResultInput<R> input);

  <R> QueryOutput<R> query(QueryInput<R> input);

  <R> WorkflowUpdateHandle<R> startUpdate(StartUpdateInput<R> input);

  <R> PollWorkflowUpdateOutput<R> pollWorkflowUpdate(PollWorkflowUpdateInput<R> input);

  CancelOutput cancel(CancelInput input);

  TerminateOutput terminate(TerminateInput input);

  DescribeWorkflowOutput describe(DescribeWorkflowInput input);

  ListWorkflowExecutionsOutput listWorkflowExecutions(ListWorkflowExecutionsInput input);

  final class ListWorkflowExecutionsInput {
    private final String query;
    private final Integer pageSize;

    public ListWorkflowExecutionsInput(@Nullable String query, @Nullable Integer pageSize) {
      this.query = query;
      this.pageSize = pageSize;
    }

    @Nullable
    public String getQuery() {
      return query;
    }

    @Nullable
    public Integer getPageSize() {
      return pageSize;
    }
  }

  final class ListWorkflowExecutionsOutput {
    private final Stream<WorkflowExecutionMetadata> stream;

    public ListWorkflowExecutionsOutput(Stream<WorkflowExecutionMetadata> stream) {
      this.stream = stream;
    }

    public Stream<WorkflowExecutionMetadata> getStream() {
      return stream;
    }
  }

  CountWorkflowOutput countWorkflows(CountWorkflowsInput input);

  final class WorkflowStartInput {
    private final String workflowId;
    private final String workflowType;
    private final Header header;
    private final Object[] arguments;
    private final WorkflowOptions options;

    /**
     * @param workflowId id of the workflow to be started
     * @param workflowType workflow type name
     * @param header internal Temporal header that is used to pass context between different
     *     abstractions and actors
     * @param arguments input arguments for the workflow
     * @param options workflow options
     */
    public WorkflowStartInput(
        @Nonnull String workflowId,
        @Nonnull String workflowType,
        @Nonnull Header header,
        @Nonnull Object[] arguments,
        @Nonnull WorkflowOptions options) {
      this.workflowId = workflowId;
      this.workflowType = workflowType;
      this.header = header;
      this.arguments = arguments;
      this.options = options;
    }

    public String getWorkflowId() {
      return workflowId;
    }

    public String getWorkflowType() {
      return workflowType;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public WorkflowOptions getOptions() {
      return options;
    }
  }

  final class WorkflowStartOutput {
    private final @Nonnull WorkflowExecution workflowExecution;

    public WorkflowStartOutput(@Nonnull WorkflowExecution workflowExecution) {
      this.workflowExecution = workflowExecution;
    }

    @Nonnull
    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }
  }

  final class WorkflowSignalInput {
    private final WorkflowExecution workflowExecution;
    private final String signalName;
    private final Header header;
    private final Object[] arguments;

    public WorkflowSignalInput(
        WorkflowExecution workflowExecution,
        String signalName,
        Header header,
        Object[] signalArguments) {
      this.workflowExecution = workflowExecution;
      this.signalName = signalName;
      this.header = header;
      this.arguments = signalArguments;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    public String getSignalName() {
      return signalName;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }
  }

  final class WorkflowSignalOutput {}

  final class WorkflowSignalWithStartInput {
    private final WorkflowStartInput workflowStartInput;
    private final String signalName;
    private final Object[] signalArguments;

    public WorkflowSignalWithStartInput(
        WorkflowStartInput workflowStartInput, String signalName, Object[] signalArguments) {
      this.workflowStartInput = workflowStartInput;
      this.signalName = signalName;
      this.signalArguments = signalArguments;
    }

    public WorkflowStartInput getWorkflowStartInput() {
      return workflowStartInput;
    }

    public String getSignalName() {
      return signalName;
    }

    public Object[] getSignalArguments() {
      return signalArguments;
    }
  }

  final class WorkflowSignalWithStartOutput {
    private final WorkflowStartOutput workflowStartOutput;

    public WorkflowSignalWithStartOutput(WorkflowStartOutput workflowStartOutput) {
      this.workflowStartOutput = workflowStartOutput;
    }

    public WorkflowStartOutput getWorkflowStartOutput() {
      return workflowStartOutput;
    }
  }

  final class WorkflowUpdateWithStartInput<R> {
    private final WorkflowStartInput workflowStartInput;
    private final StartUpdateInput<R> workflowUpdateInput;

    public WorkflowUpdateWithStartInput(
        WorkflowStartInput workflowStartInput, StartUpdateInput<R> workflowUpdateInput) {
      this.workflowStartInput = workflowStartInput;
      this.workflowUpdateInput = workflowUpdateInput;
    }

    public WorkflowStartInput getWorkflowStartInput() {
      return workflowStartInput;
    }

    public StartUpdateInput<R> getStartUpdateInput() {
      return workflowUpdateInput;
    }
  }

  final class WorkflowUpdateWithStartOutput<R> {
    private final WorkflowStartOutput workflowStartOutput;
    private final WorkflowUpdateHandle<R> updateHandle;

    public WorkflowUpdateWithStartOutput(
        WorkflowStartOutput workflowStartOutput, WorkflowUpdateHandle<R> updateHandle) {
      this.workflowStartOutput = workflowStartOutput;
      this.updateHandle = updateHandle;
    }

    public WorkflowStartOutput getWorkflowStartOutput() {
      return workflowStartOutput;
    }

    public WorkflowUpdateHandle<R> getUpdateHandle() {
      return updateHandle;
    }
  }

  final class GetResultInput<R> {
    private final WorkflowExecution workflowExecution;
    private final Optional<String> workflowType;
    private final long timeout;
    private final TimeUnit timeoutUnit;
    private final Class<R> resultClass;
    private final Type resultType;

    public GetResultInput(
        WorkflowExecution workflowExecution,
        Optional<String> workflowType,
        long timeout,
        TimeUnit timeoutUnit,
        Class<R> resultClass,
        Type resultType) {
      this.workflowExecution = workflowExecution;
      this.workflowType = workflowType;
      this.timeout = timeout;
      this.timeoutUnit = timeoutUnit;
      this.resultClass = resultClass;
      this.resultType = resultType;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    public Optional<String> getWorkflowType() {
      return workflowType;
    }

    public long getTimeout() {
      return timeout;
    }

    public TimeUnit getTimeoutUnit() {
      return timeoutUnit;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }
  }

  final class GetResultOutput<R> {
    private final R result;

    public GetResultOutput(R result) {
      this.result = result;
    }

    public R getResult() {
      return result;
    }
  }

  final class GetResultAsyncOutput<R> {
    private final CompletableFuture<R> result;

    public GetResultAsyncOutput(CompletableFuture<R> result) {
      this.result = result;
    }

    public CompletableFuture<R> getResult() {
      return result;
    }
  }

  final class QueryInput<R> {
    private final WorkflowExecution workflowExecution;
    private final String queryType;
    private final Header header;
    private final Object[] arguments;
    private final Class<R> resultClass;
    private final Type resultType;

    public QueryInput(
        WorkflowExecution workflowExecution,
        String queryType,
        Header header,
        Object[] arguments,
        Class<R> resultClass,
        Type resultType) {
      this.workflowExecution = workflowExecution;
      this.queryType = queryType;
      this.header = header;
      this.arguments = arguments;
      this.resultClass = resultClass;
      this.resultType = resultType;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    public String getQueryType() {
      return queryType;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }
  }

  final class QueryOutput<R> {
    private final WorkflowExecutionStatus queryRejectedStatus;
    private final R result;

    /**
     * @param queryRejectedStatus should be null if query is not rejected
     * @param result converted result value
     */
    public QueryOutput(WorkflowExecutionStatus queryRejectedStatus, R result) {
      this.queryRejectedStatus = queryRejectedStatus;
      this.result = result;
    }

    public boolean isQueryRejected() {
      return queryRejectedStatus != null;
    }

    public WorkflowExecutionStatus getQueryRejectedStatus() {
      return queryRejectedStatus;
    }

    public R getResult() {
      return result;
    }
  }

  final class CancelInput {
    private final WorkflowExecution workflowExecution;
    private final @Nullable String reason;

    /**
     * @deprecated Use {@link #CancelInput(WorkflowExecution, String)} to provide a cancellation
     *     reason instead.
     */
    @Deprecated
    public CancelInput(WorkflowExecution workflowExecution) {
      this(workflowExecution, null);
    }

    public CancelInput(WorkflowExecution workflowExecution, @Nullable String reason) {
      this.workflowExecution = workflowExecution;
      this.reason = reason;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    @Nullable
    public String getReason() {
      return reason;
    }
  }

  final class StartUpdateInput<R> {
    private final WorkflowExecution workflowExecution;
    private final Optional<String> workflowType;
    private final String updateName;
    private final Header header;
    private final Object[] arguments;
    private final Class<R> resultClass;
    private final Type resultType;
    private final String updateId;
    private final String firstExecutionRunId;
    private final WaitPolicy waitPolicy;

    public StartUpdateInput(
        WorkflowExecution workflowExecution,
        Optional<String> workflowType,
        String updateName,
        Header header,
        String updateId,
        Object[] arguments,
        Class<R> resultClass,
        Type resultType,
        String firstExecutionRunId,
        WaitPolicy waitPolicy) {
      this.workflowExecution = workflowExecution;
      this.workflowType = workflowType;
      this.header = header;
      this.updateId = updateId;
      this.updateName = updateName;
      this.arguments = arguments;
      this.resultClass = resultClass;
      this.resultType = resultType;
      this.firstExecutionRunId = firstExecutionRunId;
      this.waitPolicy = waitPolicy;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    public Optional<String> getWorkflowType() {
      return workflowType;
    }

    public String getUpdateName() {
      return updateName;
    }

    public Header getHeader() {
      return header;
    }

    public String getUpdateId() {
      return updateId;
    }

    public Object[] getArguments() {
      return arguments;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }

    public String getFirstExecutionRunId() {
      return firstExecutionRunId;
    }

    public WaitPolicy getWaitPolicy() {
      return waitPolicy;
    }
  }

  final class PollWorkflowUpdateInput<R> {
    private final WorkflowExecution workflowExecution;
    private long timeout;
    private TimeUnit timeoutUnit;
    private final Class<R> resultClass;
    private final Type resultType;
    private final String updateName;
    private final String updateId;

    public PollWorkflowUpdateInput(
        WorkflowExecution workflowExecution,
        String updateName,
        String updateId,
        Class<R> resultClass,
        Type resultType,
        long timeout,
        TimeUnit timeoutUnit) {
      this.workflowExecution = workflowExecution;
      this.updateName = updateName;
      this.updateId = updateId;
      this.resultClass = resultClass;
      this.resultType = resultType;
      this.timeout = timeout;
      this.timeoutUnit = timeoutUnit;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    public long getTimeout() {
      return timeout;
    }

    public TimeUnit getTimeoutUnit() {
      return timeoutUnit;
    }

    public Class<R> getResultClass() {
      return resultClass;
    }

    public Type getResultType() {
      return resultType;
    }

    public String getUpdateName() {
      return updateName;
    }

    public String getUpdateId() {
      return updateId;
    }
  }

  final class PollWorkflowUpdateOutput<R> {
    private final CompletableFuture<R> result;

    public PollWorkflowUpdateOutput(CompletableFuture<R> result) {
      this.result = result;
    }

    public CompletableFuture<R> getResult() {
      return result;
    }
  }

  final class CancelOutput {}

  final class TerminateInput {
    private final WorkflowExecution workflowExecution;
    private final @Nullable String reason;
    private final Object[] details;

    public TerminateInput(
        WorkflowExecution workflowExecution, @Nullable String reason, Object[] details) {
      this.workflowExecution = workflowExecution;
      this.reason = reason;
      this.details = details;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }

    @Nullable
    public String getReason() {
      return reason;
    }

    public Object[] getDetails() {
      return details;
    }
  }

  final class TerminateOutput {}

  final class DescribeWorkflowInput {
    private final WorkflowExecution workflowExecution;

    public DescribeWorkflowInput(WorkflowExecution workflowExecution) {
      this.workflowExecution = workflowExecution;
    }

    public WorkflowExecution getWorkflowExecution() {
      return workflowExecution;
    }
  }

  final class DescribeWorkflowOutput {
    private final WorkflowExecutionDescription description;

    public DescribeWorkflowOutput(WorkflowExecutionDescription description) {
      this.description = description;
    }

    public WorkflowExecutionDescription getDescription() {
      return description;
    }
  }

  final class CountWorkflowsInput {
    private final String query;

    public CountWorkflowsInput(@Nullable String query) {
      this.query = query;
    }

    @Nullable
    public String getQuery() {
      return query;
    }
  }

  final class CountWorkflowOutput {
    private final WorkflowExecutionCount count;

    public CountWorkflowOutput(WorkflowExecutionCount count) {
      this.count = count;
    }

    public WorkflowExecutionCount getCount() {
      return count;
    }
  }
}
