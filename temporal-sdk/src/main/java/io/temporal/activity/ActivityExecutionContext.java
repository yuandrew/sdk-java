package io.temporal.activity;

import com.uber.m3.tally.Scope;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerOptions;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Context object passed to an Activity implementation. Use {@link Activity#getExecutionContext()}
 * from an activity implementation to access.
 *
 * @author fateev
 */
public interface ActivityExecutionContext {

  /** Information about the Activity Execution and the Workflow Execution that invoked it. */
  ActivityInfo getInfo();

  /**
   * Used to notify the Workflow Execution that the Activity Execution is alive.
   *
   * @param details In case the Activity Execution times out details are returned as a field of the
   *     exception that is thrown. The details are also accessible through {@link
   *     #getHeartbeatDetails(Class)}() on the next Activity Execution retry.
   * @throws ActivityCompletionException Which indicates that cancellation of the Activity Execution
   *     was requested by the Workflow Execution. Or it could indicate any other reason for an
   *     Activity Execution to stop. Should be rethrown from the Activity implementation to indicate
   *     a successful cancellation.
   */
  <V> void heartbeat(V details) throws ActivityCompletionException;

  /**
   * Extracts Heartbeat details from the last failed attempt. This is used in combination with retry
   * options. An Activity Execution could be scheduled with optional {@link
   * io.temporal.common.RetryOptions} via {@link io.temporal.activity.ActivityOptions}. If an
   * Activity Execution failed then the server would attempt to dispatch another Activity Task to
   * retry the execution according to the retry options. If there were Heartbeat details reported by
   * the last Activity Execution that failed, they would be delivered along with the Activity Task
   * for the next retry attempt and can be extracted by the Activity implementation.
   *
   * @param detailsClass Class of the Heartbeat details
   */
  <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass);

  /**
   * Extracts Heartbeat details from the last failed attempt. This is used in combination with retry
   * options. An Activity Execution could be scheduled with optional {@link
   * io.temporal.common.RetryOptions} via {@link io.temporal.activity.ActivityOptions}. If an
   * Activity Execution failed then the server would attempt to dispatch another Activity Task to
   * retry the execution according to the retry options. If there were Heartbeat details reported by
   * the last Activity Execution that failed, the details would be delivered along with the Activity
   * Task for the next retry attempt. The Activity implementation can extract the details via {@link
   * #getHeartbeatDetails(Class)}() and resume progress.
   *
   * @param detailsClass Class of the Heartbeat details
   * @param detailsGenericType Type of the Heartbeat details
   */
  <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType);

  /**
   * Gets a correlation token that can be used to complete the Activity Execution asynchronously
   * through {@link io.temporal.client.ActivityCompletionClient#complete(byte[], Object)}.
   */
  byte[] getTaskToken();

  /**
   * If this method is called during an Activity Execution then the Activity Execution is not going
   * to complete when it's method returns. It is expected to be completed asynchronously using
   * {@link io.temporal.client.ActivityCompletionClient}.
   *
   * <p>Async Activity Executions that have {@link #isUseLocalManualCompletion()} set to false will
   * not respect the limit defined by {@link WorkerOptions#getMaxConcurrentActivityExecutionSize()}.
   *
   * <p>If you want to maintain the workflow's limit on the total number of concurrent Activity
   * Executions and if you always complete those Activity Executions within the same Java process,
   * you may use {@link #useLocalManualCompletion()} instead.
   *
   * @see #useLocalManualCompletion() as a stricter version of this method respecting the worker's
   *     concurrent activities limit
   */
  void doNotCompleteOnReturn();

  /**
   * @return true if {@link #doNotCompleteOnReturn()} was called and supported by the activity type,
   *     false otherwise
   */
  boolean isDoNotCompleteOnReturn();

  /**
   * @return true if {@link #useLocalManualCompletion()} method has been called on this context. If
   *     this flag is set to true, {@link io.temporal.internal.worker.ActivityWorker} would not
   *     release concurrency semaphore and delegate release function to the manual Activity client
   *     returned by {@link #useLocalManualCompletion()}
   */
  boolean isUseLocalManualCompletion();

  /**
   * For local manual completion, sets the {@link #doNotCompleteOnReturn()} flag, making Activity
   * Execution completion asynchronous, and returns the completion client. Returned completion
   * client must be used to complete the Activity Execution inside the same Java process.
   *
   * <p>The completion client returned from this method updates the Worker's Active Activity
   * Executions Counter. This way the limit of concurrent Activity Executions defined by {@link
   * WorkerOptions.Builder#setMaxConcurrentActivityExecutionSize(int)} is respected. It's the main
   * difference of this method from calling {@link #doNotCompleteOnReturn()}.
   *
   * <p>Always call one of the completion methods on the obtained completion client. Otherwise, an
   * Activity Worker could stop polling as it will consider Activity Executions that wasn't
   * explicitly completed as still running.
   */
  ManualActivityCompletionClient useLocalManualCompletion();

  /**
   * Get scope for reporting business metrics in activity logic. This scope is tagged with a
   * workflow and an activity type.
   *
   * <p>The original metrics scope is set through {@link
   * WorkflowServiceStubsOptions.Builder#setMetricsScope(Scope)} when a worker starts up.
   */
  Scope getMetricsScope();

  /**
   * Get a {@link WorkflowClient} that can be used to start interact with the Temporal service from
   * an activity.
   */
  WorkflowClient getWorkflowClient();

  /** Get the currently running activity instance. */
  Object getInstance();
}
