package io.temporal.internal.client;

import static io.temporal.internal.common.HeaderUtils.toHeaderGrpc;
import static io.temporal.internal.common.RetryOptionsUtils.toRetryPolicy;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.*;
import io.temporal.api.enums.v1.HistoryEventFilterType;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.OnConflictOptions;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequestOrBuilder;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.internal.common.PriorityUtils;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class WorkflowClientRequestFactory {
  private final WorkflowClientOptions clientOptions;

  public WorkflowClientRequestFactory(WorkflowClientOptions clientOptions) {
    this.clientOptions = clientOptions;
  }

  // If you add anything new here, keep newSignalWithStartWorkflowExecutionRequest in sync
  @SuppressWarnings("deprecation")
  @Nonnull
  StartWorkflowExecutionRequest.Builder newStartWorkflowExecutionRequest(
      @Nonnull String workflowId,
      @Nonnull String workflowTypeName,
      @Nonnull io.temporal.common.interceptors.Header header,
      @Nonnull WorkflowOptions options,
      @Nullable Payloads inputArgs,
      @Nullable Memo memo,
      @Nullable UserMetadata userMetadata) {
    StartWorkflowExecutionRequest.Builder request =
        StartWorkflowExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setRequestId(generateUniqueId())
            .setIdentity(clientOptions.getIdentity())
            .setWorkflowId(workflowId)
            .setWorkflowType(WorkflowType.newBuilder().setName(workflowTypeName))
            .setWorkflowRunTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getWorkflowRunTimeout()))
            .setWorkflowExecutionTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getWorkflowExecutionTimeout()))
            .setWorkflowTaskTimeout(
                ProtobufTimeUtils.toProtoDuration(options.getWorkflowTaskTimeout()));

    if (inputArgs != null) {
      request.setInput(inputArgs);
    }

    if (options.getWorkflowIdReusePolicy() != null) {
      request.setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy());
    }

    if (options.getWorkflowIdConflictPolicy() != null) {
      request.setWorkflowIdConflictPolicy(options.getWorkflowIdConflictPolicy());
    }

    if (options.getRequestId() != null) {
      request.setRequestId(options.getRequestId());
    }

    if (options.getCompletionCallbacks() != null) {
      options.getCompletionCallbacks().forEach(request::addCompletionCallbacks);
    }

    if (options.getLinks() != null) {
      options.getLinks().forEach(request::addLinks);
    }

    if (options.getOnConflictOptions() != null) {
      OnConflictOptions.Builder onConflictOptions =
          OnConflictOptions.newBuilder()
              .setAttachRequestId(options.getOnConflictOptions().isAttachRequestId())
              .setAttachLinks(options.getOnConflictOptions().isAttachLinks())
              .setAttachCompletionCallbacks(
                  options.getOnConflictOptions().isAttachCompletionCallbacks());
      request.setOnConflictOptions(onConflictOptions);
    }

    String taskQueue = options.getTaskQueue();
    if (taskQueue != null && !taskQueue.isEmpty()) {
      request.setTaskQueue(TaskQueue.newBuilder().setName(taskQueue).build());
    }

    RetryOptions retryOptions = options.getRetryOptions();
    if (retryOptions != null) {
      request.setRetryPolicy(toRetryPolicy(retryOptions));
    }

    if (!Strings.isNullOrEmpty(options.getCronSchedule())) {
      request.setCronSchedule(options.getCronSchedule());
    }

    if (memo != null) {
      request.setMemo(memo);
    }

    if (options.getStartDelay() != null) {
      request.setWorkflowStartDelay(ProtobufTimeUtils.toProtoDuration(options.getStartDelay()));
    }

    if (userMetadata != null) {
      request.setUserMetadata(userMetadata);
    }

    if (options.getPriority() != null) {
      request.setPriority(PriorityUtils.toProto(options.getPriority()));
    }

    if (options.getSearchAttributes() != null && !options.getSearchAttributes().isEmpty()) {
      if (options.getTypedSearchAttributes() != null) {
        throw new IllegalArgumentException(
            "Cannot have search attributes and typed search attributes");
      }
      request.setSearchAttributes(SearchAttributesUtil.encode(options.getSearchAttributes()));
    } else if (options.getTypedSearchAttributes() != null
        && options.getTypedSearchAttributes().size() > 0) {
      request.setSearchAttributes(
          SearchAttributesUtil.encodeTyped(options.getTypedSearchAttributes()));
    }

    Header grpcHeader =
        toHeaderGrpc(header, extractContextsAndConvertToBytes(options.getContextPropagators()));
    request.setHeader(grpcHeader);

    return request;
  }

  @Nonnull
  SignalWithStartWorkflowExecutionRequest.Builder newSignalWithStartWorkflowExecutionRequest(
      @Nonnull StartWorkflowExecutionRequestOrBuilder startParameters,
      @Nonnull String signalName,
      @Nullable Payloads signalInput) {
    SignalWithStartWorkflowExecutionRequest.Builder request =
        SignalWithStartWorkflowExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setRequestId(generateUniqueId())
            .setIdentity(clientOptions.getIdentity())
            .setSignalName(signalName)
            .setWorkflowRunTimeout(startParameters.getWorkflowRunTimeout())
            .setWorkflowExecutionTimeout(startParameters.getWorkflowExecutionTimeout())
            .setWorkflowTaskTimeout(startParameters.getWorkflowTaskTimeout())
            .setWorkflowType(startParameters.getWorkflowType())
            .setWorkflowIdReusePolicy(startParameters.getWorkflowIdReusePolicy())
            .setWorkflowIdConflictPolicy(startParameters.getWorkflowIdConflictPolicy())
            .setCronSchedule(startParameters.getCronSchedule());

    String workflowId = startParameters.getWorkflowId();
    if (workflowId.isEmpty()) {
      workflowId = generateUniqueId();
    }
    request.setWorkflowId(workflowId);

    if (signalInput != null) {
      request.setSignalInput(signalInput);
    }

    if (startParameters.hasInput()) {
      request.setInput(startParameters.getInput());
    }

    if (startParameters.hasTaskQueue()) {
      request.setTaskQueue(startParameters.getTaskQueue());
    }

    if (startParameters.hasRetryPolicy()) {
      request.setRetryPolicy(startParameters.getRetryPolicy());
    }

    if (startParameters.hasMemo()) {
      request.setMemo(startParameters.getMemo());
    }

    if (startParameters.hasSearchAttributes()) {
      request.setSearchAttributes(startParameters.getSearchAttributes());
    }

    if (startParameters.hasHeader()) {
      request.setHeader(startParameters.getHeader());
    }

    if (startParameters.hasWorkflowStartDelay()) {
      request.setWorkflowStartDelay(startParameters.getWorkflowStartDelay());
    }

    if (startParameters.hasUserMetadata()) {
      request.setUserMetadata(startParameters.getUserMetadata());
    }

    return request;
  }

  @Nonnull
  GetWorkflowExecutionHistoryRequest newHistoryLongPollRequest(
      WorkflowExecution workflowExecution, ByteString pageToken) {
    return GetWorkflowExecutionHistoryRequest.newBuilder()
        .setNamespace(clientOptions.getNamespace())
        .setExecution(workflowExecution)
        .setHistoryEventFilterType(HistoryEventFilterType.HISTORY_EVENT_FILTER_TYPE_CLOSE_EVENT)
        .setWaitNewEvent(true)
        .setNextPageToken(pageToken)
        .build();
  }

  private io.temporal.common.interceptors.Header extractContextsAndConvertToBytes(
      List<ContextPropagator> workflowOptionsContextPropagators) {
    List<ContextPropagator> workflowClientContextPropagators =
        clientOptions.getContextPropagators();
    if ((workflowClientContextPropagators.isEmpty() && workflowOptionsContextPropagators == null)
        || (workflowOptionsContextPropagators != null
            && workflowOptionsContextPropagators.isEmpty())) {
      return null;
    }

    List<ContextPropagator> listToUse =
        MoreObjects.firstNonNull(
            workflowOptionsContextPropagators, workflowClientContextPropagators);
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : listToUse) {
      result.putAll(propagator.serializeContext(propagator.getCurrentContext()));
    }
    return new io.temporal.common.interceptors.Header(result);
  }

  private static String generateUniqueId() {
    return UUID.randomUUID().toString();
  }
}
