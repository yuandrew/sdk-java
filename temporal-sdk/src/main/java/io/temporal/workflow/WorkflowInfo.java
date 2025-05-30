package io.temporal.workflow;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.common.Experimental;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Provides information about the current Workflow Execution and Run. Also provides access to
 * immutable information about connected entities like Parent Workflow Execution or a previous Run.
 */
public interface WorkflowInfo {

  /**
   * @return Workflow Namespace
   */
  String getNamespace();

  /**
   * @return Workflow ID
   */
  String getWorkflowId();

  /**
   * @return Workflow Type
   */
  String getWorkflowType();

  /**
   * Note: RunId is unique identifier of one workflow code execution. Reset changes RunId.
   *
   * @return Workflow Run ID that is handled by the current workflow code execution.
   * @see #getOriginalExecutionRunId() for RunId variation that is resistant to Resets
   * @see #getFirstExecutionRunId() for the very first RunId that is preserved along the whole
   *     Workflow Execution chain, including ContinueAsNew, Retry, Cron and Reset.
   */
  @Nonnull
  String getRunId();

  /**
   * @return The very first original RunId of the current Workflow Execution preserved along the
   *     chain of ContinueAsNew, Retry, Cron and Reset. Identifies the whole Runs chain of Workflow
   *     Execution.
   */
  @Nonnull
  String getFirstExecutionRunId();

  /**
   * @return Run ID of the previous Workflow Run which continued-as-new or retried or cron-scheduled
   *     into the current Workflow Run.
   */
  Optional<String> getContinuedExecutionRunId();

  /**
   * Note: This value is NOT preserved by continue-as-new, retries or cron Runs. They are separate
   * Runs of one Workflow Execution Chain.
   *
   * @return original RunId of the current Workflow Run. This value is preserved during Reset which
   *     changes RunID.
   * @see #getFirstExecutionRunId() for the very first RunId that is preserved along the whole
   *     Workflow Execution chain, including ContinueAsNew, Retry, Cron and Reset.
   */
  @Nonnull
  String getOriginalExecutionRunId();

  /**
   * @return Workflow Task Queue name
   */
  String getTaskQueue();

  @Nullable
  RetryOptions getRetryOptions();

  /**
   * @return Timeout for a Workflow Run specified during Workflow start in {@link
   *     io.temporal.client.WorkflowOptions.Builder#setWorkflowRunTimeout(Duration)}
   */
  Duration getWorkflowRunTimeout();

  /**
   * @return Timeout for the Workflow Execution specified during Workflow start in {@link
   *     io.temporal.client.WorkflowOptions.Builder#setWorkflowExecutionTimeout(Duration)}
   */
  Duration getWorkflowExecutionTimeout();

  /**
   * The time workflow run has started. Note that this time can be different from the time workflow
   * function started actual execution.
   */
  long getRunStartedTimestampMillis();

  /**
   * This method is used to get raw proto serialized Search Attributes.
   *
   * <p>Consider using more user-friendly methods on {@link Workflow} class, including {@link
   * Workflow#getSearchAttributes()}, {@link Workflow#getSearchAttribute(String)} or {@link
   * Workflow#getSearchAttributeValues(String)} instead of this method to access deserialized search
   * attributes.
   *
   * @return raw Search Attributes Protobuf entity, null if empty
   * @deprecated use {@link Workflow#getTypedSearchAttributes()} instead.
   */
  @Deprecated
  @Nullable
  SearchAttributes getSearchAttributes();

  /**
   * @return Workflow ID of the parent Workflow
   */
  Optional<String> getParentWorkflowId();

  /**
   * @return Run ID of the parent Workflow
   */
  Optional<String> getParentRunId();

  /**
   * @return Workflow ID of the root Workflow
   * @apiNote On server versions prior to v1.27.0, this method will be empty. Otherwise, it will be
   *     empty if the workflow is its own root.
   */
  Optional<String> getRootWorkflowId();

  /**
   * @return Run ID of the root Workflow
   * @apiNote On server versions prior to v1.27.0, this method will be empty. Otherwise, it will be
   *     empty if the workflow is its own root.
   */
  Optional<String> getRootRunId();

  /**
   * @return Workflow retry attempt handled by this Workflow code execution. Starts on "1".
   */
  int getAttempt();

  /**
   * @return Workflow cron schedule
   */
  String getCronSchedule();

  /**
   * @return length of Workflow history up until the current moment of execution. This value changes
   *     during the lifetime of a Workflow Execution. You may use this information to decide when to
   *     call {@link Workflow#continueAsNew(Object...)}.
   */
  long getHistoryLength();

  /**
   * @return size of Workflow history in bytes up until the current moment of execution. This value
   *     changes during the lifetime of a Workflow Execution.
   */
  long getHistorySize();

  /**
   * @return true if the server is configured to suggest continue as new and it is suggested. This
   *     value changes during the lifetime of a Workflow Execution.
   */
  boolean isContinueAsNewSuggested();

  /**
   * @return The Build ID of the worker which executed the current Workflow Task. May be empty the
   *     task was completed by a worker without a Build ID. If this worker is the one executing this
   *     task for the first time and has a Build ID set, then its ID will be used. This value may
   *     change over the lifetime of the workflow run, but is deterministic and safe to use for
   *     branching.
   */
  Optional<String> getCurrentBuildId();

  /**
   * Return the priority of the workflow task.
   *
   * @apiNote If unset or on an older server version, this method will return {@link
   *     Priority#getDefaultInstance()}.
   */
  @Experimental
  @Nonnull
  Priority getPriority();
}
