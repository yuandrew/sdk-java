package io.temporal.client.schedules;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import com.uber.m3.tally.Scope;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.client.NamespaceInjectWorkflowServiceStubs;
import io.temporal.internal.client.RootScheduleClientInvoker;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.client.external.GenericWorkflowClientImpl;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;

final class ScheduleClientImpl implements ScheduleClient {
  private final WorkflowServiceStubs workflowServiceStubs;
  private final ScheduleClientOptions options;
  private final GenericWorkflowClient genericClient;
  private final Scope metricsScope;
  private final ScheduleClientCallsInterceptor scheduleClientCallsInvoker;
  private final List<ScheduleClientInterceptor> interceptors;

  /**
   * Creates client that connects to an instance of the Temporal Service. Cannot be used from within
   * workflow code.
   *
   * @param service client to the Temporal Service endpoint.
   * @param options Options (like {@link io.temporal.common.converter.DataConverter} override) for
   *     configuring client.
   * @return client to interact with schedules
   */
  public static ScheduleClient newInstance(
      WorkflowServiceStubs service, ScheduleClientOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new ScheduleClientImpl(service, options), ScheduleClient.class);
  }

  ScheduleClientImpl(WorkflowServiceStubs workflowServiceStubs, ScheduleClientOptions options) {
    workflowServiceStubs =
        new NamespaceInjectWorkflowServiceStubs(workflowServiceStubs, options.getNamespace());
    this.workflowServiceStubs = workflowServiceStubs;
    this.options = options;
    this.metricsScope =
        workflowServiceStubs
            .getOptions()
            .getMetricsScope()
            .tagged(MetricsTag.defaultTags(options.getNamespace()));
    this.genericClient = new GenericWorkflowClientImpl(workflowServiceStubs, metricsScope);
    this.interceptors = options.getInterceptors();
    this.scheduleClientCallsInvoker = initializeClientInvoker();
  }

  private ScheduleClientCallsInterceptor initializeClientInvoker() {
    ScheduleClientCallsInterceptor scheduleClientInvoker =
        new RootScheduleClientInvoker(genericClient, options);
    for (ScheduleClientInterceptor clientInterceptor : interceptors) {
      scheduleClientInvoker =
          clientInterceptor.scheduleClientCallsInterceptor(scheduleClientInvoker);
    }
    return scheduleClientInvoker;
  }

  @Override
  public ScheduleHandle createSchedule(
      String scheduleID, Schedule schedule, ScheduleOptions options) {
    scheduleClientCallsInvoker.createSchedule(
        new ScheduleClientCallsInterceptor.CreateScheduleInput(scheduleID, schedule, options));
    return new ScheduleHandleImpl(scheduleClientCallsInvoker, scheduleID);
  }

  @Override
  public ScheduleHandle getHandle(String scheduleID) {
    return new ScheduleHandleImpl(scheduleClientCallsInvoker, scheduleID);
  }

  @Override
  public Stream<ScheduleListDescription> listSchedules() {
    return this.listSchedules(null, null);
  }

  @Override
  public Stream<ScheduleListDescription> listSchedules(@Nullable Integer pageSize) {
    return this.listSchedules(null, pageSize);
  }

  @Override
  public Stream<ScheduleListDescription> listSchedules(
      @Nullable String query, @Nullable Integer pageSize) {
    return scheduleClientCallsInvoker
        .listSchedules(
            new ScheduleClientCallsInterceptor.ListSchedulesInput(
                query, pageSize == null ? 100 : pageSize))
        .getStream();
  }
}
