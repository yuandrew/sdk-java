package io.temporal.authorization;

import static org.junit.Assert.*;

import io.grpc.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class AuthorizationTokenTest {
  private static Metadata.Key<String> TEMPORAL_NAMESPACE_HEADER_KEY =
      Metadata.Key.of("temporal-namespace", Metadata.ASCII_STRING_MARSHALLER);
  private static final String TASK_QUEUE = "test-workflow";
  private static final String AUTH_TOKEN = "Bearer <token>";

  private TestWorkflowEnvironment testEnvironment;

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          System.err.println(testEnvironment.getDiagnostics());
        }
      };

  private final List<GrpcRequest> loggedRequests = Collections.synchronizedList(new ArrayList<>());

  @Before
  public void setUp() {
    loggedRequests.clear();
    WorkflowServiceStubsOptions stubOptions =
        WorkflowServiceStubsOptions.newBuilder()
            .addGrpcClientInterceptor(
                new ClientInterceptor() {
                  @Override
                  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                    return new LoggingClientCall<>(method, next.newCall(method, callOptions));
                  }

                  class LoggingClientCall<ReqT, RespT>
                      extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

                    private final MethodDescriptor<ReqT, RespT> method;

                    LoggingClientCall(
                        MethodDescriptor<ReqT, RespT> method, ClientCall<ReqT, RespT> call) {
                      super(call);
                      this.method = method;
                    }

                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                      loggedRequests.add(
                          new GrpcRequest(
                              method.getBareMethodName(),
                              headers.get(
                                  AuthorizationGrpcMetadataProvider.AUTHORIZATION_HEADER_KEY),
                              headers.get(TEMPORAL_NAMESPACE_HEADER_KEY)));
                      super.start(responseListener, headers);
                    }
                  }
                })
            .addGrpcMetadataProvider(new AuthorizationGrpcMetadataProvider(() -> AUTH_TOKEN))
            .build();

    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder().setWorkflowServiceStubsOptions(stubOptions).build();

    testEnvironment = TestWorkflowEnvironment.newInstance(options);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void allRequestsShouldHaveAnAuthToken() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(EmptyWorkflowImpl.class);
    testEnvironment.start();
    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflows.TestWorkflow1 workflow =
        client.newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    String result = workflow.execute("input1");
    assertEquals("TestWorkflow1-input1", result);

    assertFalse(loggedRequests.isEmpty());
    // These methods are not namespace specific
    List<String> methodsToSkip =
        Arrays.asList("GetSystemInfo", "Check", "UnlockTimeSkipping", "LockTimeSkipping");
    for (GrpcRequest grpcRequest : loggedRequests) {
      assertEquals(
          "All requests should have an auth token", AUTH_TOKEN, grpcRequest.authTokenValue);
      if (!methodsToSkip.contains(grpcRequest.methodName)) {
        assertEquals(
            "All requests should have a namespace " + grpcRequest.methodName,
            testEnvironment.getNamespace(),
            grpcRequest.namespace);
      }
    }
  }

  public static class EmptyWorkflowImpl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String input) {
      Workflow.sleep(Duration.ofMinutes(5)); // test time skipping
      return Workflow.getInfo().getWorkflowType() + "-" + input;
    }
  }

  private static class GrpcRequest {
    final String methodName;
    final String authTokenValue;
    final String namespace;

    GrpcRequest(String methodName, String authTokenValue, String namespace) {
      this.methodName = methodName;
      this.authTokenValue = authTokenValue;
      this.namespace = namespace;
    }
  }
}
