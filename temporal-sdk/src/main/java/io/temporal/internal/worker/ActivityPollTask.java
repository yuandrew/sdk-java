package io.temporal.internal.worker;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.protobuf.DoubleValue;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkerVersionCapabilities;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.taskqueue.v1.TaskQueueMetadata;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponse;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.MetricsType;
import io.temporal.worker.tuning.*;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ActivityPollTask implements Poller.PollTask<ActivityTask> {
  private static final Logger log = LoggerFactory.getLogger(ActivityPollTask.class);

  private final WorkflowServiceStubs service;
  private final TrackingSlotSupplier<ActivitySlotInfo> slotSupplier;
  private final Scope metricsScope;
  private final PollActivityTaskQueueRequest pollRequest;

  @SuppressWarnings("deprecation")
  public ActivityPollTask(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String taskQueue,
      @Nonnull String identity,
      @Nullable String buildId,
      boolean useBuildIdForVersioning,
      double activitiesPerSecond,
      @Nonnull TrackingSlotSupplier<ActivitySlotInfo> slotSupplier,
      @Nonnull Scope metricsScope,
      @Nonnull Supplier<GetSystemInfoResponse.Capabilities> serverCapabilities) {
    this.service = Objects.requireNonNull(service);
    this.slotSupplier = slotSupplier;
    this.metricsScope = Objects.requireNonNull(metricsScope);

    PollActivityTaskQueueRequest.Builder pollRequest =
        PollActivityTaskQueueRequest.newBuilder()
            .setNamespace(namespace)
            .setIdentity(identity)
            .setTaskQueue(TaskQueue.newBuilder().setName(taskQueue));
    if (activitiesPerSecond > 0) {
      pollRequest.setTaskQueueMetadata(
          TaskQueueMetadata.newBuilder()
              .setMaxTasksPerSecond(DoubleValue.newBuilder().setValue(activitiesPerSecond).build())
              .build());
    }

    if (serverCapabilities.get().getBuildIdBasedVersioning()) {
      pollRequest.setWorkerVersionCapabilities(
          WorkerVersionCapabilities.newBuilder()
              .setBuildId(buildId)
              .setUseVersioning(useBuildIdForVersioning)
              .build());
    }
    this.pollRequest = pollRequest.build();
  }

  @Override
  @SuppressWarnings("deprecation")
  public ActivityTask poll() {
    if (log.isTraceEnabled()) {
      log.trace("poll request begin: " + pollRequest);
    }
    PollActivityTaskQueueResponse response;
    SlotPermit permit;
    SlotSupplierFuture future;
    boolean isSuccessful = false;
    try {
      future =
          slotSupplier.reserveSlot(
              new SlotReservationData(
                  pollRequest.getTaskQueue().getName(),
                  pollRequest.getIdentity(),
                  pollRequest.getWorkerVersionCapabilities().getBuildId()));
    } catch (Exception e) {
      log.warn("Error while trying to reserve a slot for an activity", e.getCause());
      return null;
    }
    permit = Poller.getSlotPermitAndHandleInterrupts(future, slotSupplier);
    if (permit == null) return null;

    try {
      response =
          service
              .blockingStub()
              .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
              .pollActivityTaskQueue(pollRequest);

      if (response == null || response.getTaskToken().isEmpty()) {
        metricsScope.counter(MetricsType.ACTIVITY_POLL_NO_TASK_COUNTER).inc(1);
        return null;
      }
      metricsScope
          .timer(MetricsType.ACTIVITY_SCHEDULE_TO_START_LATENCY)
          .record(
              ProtobufTimeUtils.toM3Duration(
                  response.getStartedTime(), response.getCurrentAttemptScheduledTime()));
      isSuccessful = true;
      return new ActivityTask(
          response,
          permit,
          () -> slotSupplier.releaseSlot(SlotReleaseReason.taskComplete(), permit));
    } finally {
      if (!isSuccessful) slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), permit);
    }
  }
}
