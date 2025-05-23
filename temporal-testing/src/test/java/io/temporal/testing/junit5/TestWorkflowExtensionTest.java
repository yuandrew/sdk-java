package io.temporal.testing.junit5;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.testing.WorkflowInitialTime;
import io.temporal.worker.Worker;
import io.temporal.workflow.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class TestWorkflowExtensionTest {

  @RegisterExtension
  public static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .registerWorkflowImplementationTypes(HelloWorkflowImpl.class)
          .setActivityImplementations(new HelloActivityImpl())
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setInitialTime(Instant.parse("2021-10-10T10:01:00Z"))
          .build();

  @Service
  public interface TestNexusService {
    @Operation
    String operation(String input);
  }

  @ServiceImpl(service = TestNexusService.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
    }
  }

  @ActivityInterface
  public interface HelloActivity {
    String buildGreeting(String name);
  }

  public static class HelloActivityImpl implements HelloActivity {
    @Override
    public String buildGreeting(String name) {
      ActivityInfo activityInfo = Activity.getExecutionContext().getInfo();
      return String.format(
          "Hello %s from activity %s and workflow %s",
          name, activityInfo.getActivityType(), activityInfo.getWorkflowType());
    }
  }

  @WorkflowInterface
  public interface HelloWorkflow {
    @WorkflowMethod
    String sayHello(String name);
  }

  public static class HelloWorkflowImpl implements HelloWorkflow {

    private static final Logger logger = Workflow.getLogger(HelloWorkflowImpl.class);

    private final HelloActivity helloActivity =
        Workflow.newActivityStub(
            HelloActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1)).build());

    private final TestNexusService nexusService =
        Workflow.newNexusServiceStub(
            TestNexusService.class,
            NexusServiceOptions.newBuilder()
                .setOperationOptions(
                    NexusOperationOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                        .build())
                .build());

    @Override
    public String sayHello(String name) {
      logger.info("Hello, {}", name);
      nexusService.operation(name);
      Workflow.sleep(Duration.ofHours(1));
      return helloActivity.buildGreeting(name);
    }
  }

  @Test
  @WorkflowInitialTime("2020-01-01T01:00:00Z")
  public void extensionShouldLaunchTestEnvironmentAndResolveParameters(
      TestWorkflowEnvironment testEnv,
      WorkflowClient workflowClient,
      WorkflowOptions workflowOptions,
      Worker worker,
      HelloWorkflow workflow) {

    assertAll(
        () -> assertTrue(testEnv.isStarted()),
        () -> assertNotNull(workflowClient),
        () -> assertNotNull(workflowOptions.getTaskQueue()),
        () -> assertNotNull(worker),
        () ->
            assertEquals(
                Instant.parse("2020-01-01T01:00:00Z"),
                Instant.ofEpochMilli(testEnv.currentTimeMillis()).truncatedTo(ChronoUnit.HOURS)),
        () ->
            assertEquals(
                "Hello World from activity BuildGreeting and workflow HelloWorkflow",
                workflow.sayHello("World")));
  }
}
