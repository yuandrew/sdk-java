package io.temporal.spring.boot.autoconfigure;

import io.opentracing.Tracer;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.ClientTemplate;
import io.temporal.spring.boot.autoconfigure.template.NamespaceTemplate;
import io.temporal.spring.boot.autoconfigure.template.TestWorkflowEnvironmentAdapter;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkflowImplementationOptions;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@AutoConfigureAfter({ServiceStubsAutoConfiguration.class, OpenTracingAutoConfiguration.class})
@ConditionalOnBean(ServiceStubsAutoConfiguration.class)
@ConditionalOnExpression(
    "${spring.temporal.test-server.enabled:false} || '${spring.temporal.connection.target:}'.length() > 0")
public class RootNamespaceAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(RootNamespaceAutoConfiguration.class);

  private final ConfigurableListableBeanFactory beanFactory;

  public RootNamespaceAutoConfiguration(ConfigurableListableBeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  @Bean(name = "temporalRootNamespaceTemplate")
  public NamespaceTemplate rootNamespaceTemplate(
      TemporalProperties properties,
      WorkflowServiceStubs workflowServiceStubs,
      @Autowired(required = false) @Nullable Map<String, DataConverter> dataConverters,
      @Qualifier("mainDataConverter") @Autowired(required = false) @Nullable
          DataConverter mainDataConverter,
      @Autowired(required = false) @Nullable Tracer otTracer,
      @Autowired(required = false) @Nullable
          List<WorkflowClientInterceptor> workflowClientInterceptors,
      @Autowired(required = false) @Nullable
          List<ScheduleClientInterceptor> scheduleClientInterceptors,
      @Autowired(required = false) @Nullable List<WorkerInterceptor> workerInterceptors,
      @Qualifier("temporalTestWorkflowEnvironmentAdapter") @Autowired(required = false) @Nullable
          TestWorkflowEnvironmentAdapter testWorkflowEnvironment,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<WorkerFactoryOptions.Builder>>
              workerFactoryCustomizerMap,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<WorkerOptions.Builder>> workerCustomizerMap,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<WorkflowClientOptions.Builder>> clientCustomizerMap,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<ScheduleClientOptions.Builder>>
              scheduleCustomizerMap,
      @Autowired(required = false) @Nullable
          Map<String, TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>>
              workflowImplementationCustomizerMap) {
    DataConverter chosenDataConverter =
        AutoConfigurationUtils.chooseDataConverter(dataConverters, mainDataConverter, properties);
    List<WorkflowClientInterceptor> chosenClientInterceptors =
        AutoConfigurationUtils.chooseWorkflowClientInterceptors(
            workflowClientInterceptors, properties);
    List<ScheduleClientInterceptor> chosenScheduleClientInterceptors =
        AutoConfigurationUtils.chooseScheduleClientInterceptors(
            scheduleClientInterceptors, properties);
    List<WorkerInterceptor> chosenWorkerInterceptors =
        AutoConfigurationUtils.chooseWorkerInterceptors(workerInterceptors, properties);
    TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> workerFactoryCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBean(
            workerFactoryCustomizerMap, WorkerFactoryOptions.Builder.class, properties);
    TemporalOptionsCustomizer<WorkerOptions.Builder> workerCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBean(
            workerCustomizerMap, WorkerOptions.Builder.class, properties);
    TemporalOptionsCustomizer<WorkflowClientOptions.Builder> clientCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBean(
            clientCustomizerMap, WorkflowClientOptions.Builder.class, properties);
    TemporalOptionsCustomizer<ScheduleClientOptions.Builder> scheduleCustomizer =
        AutoConfigurationUtils.chooseTemporalCustomizerBean(
            scheduleCustomizerMap, ScheduleClientOptions.Builder.class, properties);
    TemporalOptionsCustomizer<WorkflowImplementationOptions.Builder>
        workflowImplementationCustomizer =
            AutoConfigurationUtils.chooseTemporalCustomizerBean(
                workflowImplementationCustomizerMap,
                WorkflowImplementationOptions.Builder.class,
                properties);

    return new NamespaceTemplate(
        properties,
        workflowServiceStubs,
        chosenDataConverter,
        chosenClientInterceptors,
        chosenScheduleClientInterceptors,
        chosenWorkerInterceptors,
        otTracer,
        testWorkflowEnvironment,
        workerFactoryCustomizer,
        workerCustomizer,
        clientCustomizer,
        scheduleCustomizer,
        workflowImplementationCustomizer);
  }

  /** Client */
  @Primary
  @Bean(name = "temporalClientTemplate")
  public ClientTemplate clientTemplate(
      @Qualifier("temporalRootNamespaceTemplate") NamespaceTemplate rootNamespaceTemplate) {
    return rootNamespaceTemplate.getClientTemplate();
  }

  @Primary
  @Bean(name = "temporalWorkflowClient")
  public WorkflowClient client(ClientTemplate clientTemplate) {
    return clientTemplate.getWorkflowClient();
  }

  @Primary
  @Bean(name = "temporalScheduleClient")
  public ScheduleClient scheduleClient(ClientTemplate clientTemplate) {
    return clientTemplate.getScheduleClient();
  }

  /** Workers */
  @Primary
  @Bean(name = "temporalWorkersTemplate")
  @Conditional(WorkersPresentCondition.class)
  // add an explicit dependency on the existence of the expected client bean,
  // so we don't initialize a client that user doesn't expect
  @DependsOn("temporalClientTemplate")
  public WorkersTemplate workersTemplate(
      @Qualifier("temporalRootNamespaceTemplate") NamespaceTemplate temporalRootNamespaceTemplate) {
    return temporalRootNamespaceTemplate.getWorkersTemplate();
  }

  @Primary
  @Bean(name = "temporalWorkerFactory", destroyMethod = "shutdown")
  @Conditional(WorkersPresentCondition.class)
  public WorkerFactory workerFactory(
      @Qualifier("temporalWorkersTemplate") WorkersTemplate workersTemplate) {
    return workersTemplate.getWorkerFactory();
  }

  @Primary
  @Bean(name = "temporalWorkers")
  @Conditional(WorkersPresentCondition.class)
  public Collection<Worker> workers(
      @Qualifier("temporalWorkersTemplate") WorkersTemplate workersTemplate) {
    Collection<Worker> workers = workersTemplate.getWorkers();
    workers.forEach(
        worker -> beanFactory.registerSingleton("temporalWorker-" + worker.getTaskQueue(), worker));
    return workers;
  }

  @Primary
  @ConditionalOnProperty(prefix = "spring.temporal", name = "start-workers", matchIfMissing = true)
  @Conditional(WorkersPresentCondition.class)
  @Bean
  public WorkerFactoryStarter workerFactoryStarter(WorkerFactory workerFactory) {
    return new WorkerFactoryStarter(workerFactory);
  }

  public static class WorkerFactoryStarter implements ApplicationListener<ApplicationReadyEvent> {

    private final WorkerFactory workerFactory;

    public WorkerFactoryStarter(WorkerFactory workerFactory) {
      this.workerFactory = workerFactory;
    }

    @Override
    public void onApplicationEvent(@Nonnull ApplicationReadyEvent event) {
      workerFactory.start();
    }
  }
}
