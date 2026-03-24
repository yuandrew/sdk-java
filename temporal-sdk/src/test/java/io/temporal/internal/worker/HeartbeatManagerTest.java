package io.temporal.internal.worker;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class HeartbeatManagerTest {

  private WorkflowServiceStubs service;
  private WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  private HeartbeatManager manager;

  @Before
  public void setUp() {
    service = mock(WorkflowServiceStubs.class);
    blockingStub = mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    when(service.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.recordWorkerHeartbeat(any()))
        .thenReturn(RecordWorkerHeartbeatResponse.getDefaultInstance());
  }

  @After
  public void tearDown() {
    if (manager != null) {
      manager.shutdown();
    }
  }

  @Test
  public void testHeartbeatRpcSentAtInterval() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));

    WorkerHeartbeat hb =
        WorkerHeartbeat.newBuilder()
            .setWorkerInstanceKey("worker-1")
            .setTaskQueue("test-queue")
            .build();
    manager.registerWorker("worker-1", () -> hb);

    verify(blockingStub, timeout(3000).atLeastOnce()).recordWorkerHeartbeat(any());

    ArgumentCaptor<RecordWorkerHeartbeatRequest> captor =
        ArgumentCaptor.forClass(RecordWorkerHeartbeatRequest.class);
    verify(blockingStub, atLeastOnce()).recordWorkerHeartbeat(captor.capture());

    RecordWorkerHeartbeatRequest request = captor.getValue();
    assertEquals("default", request.getNamespace());
    assertEquals("test-identity", request.getIdentity());
    assertTrue(request.getWorkerHeartbeatCount() > 0);
    assertEquals("test-queue", request.getWorkerHeartbeat(0).getTaskQueue());
  }

  @Test
  public void testMultipleWorkersInSingleRpc() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));

    WorkerHeartbeat hb1 =
        WorkerHeartbeat.newBuilder()
            .setWorkerInstanceKey("worker-1")
            .setTaskQueue("queue-1")
            .build();
    WorkerHeartbeat hb2 =
        WorkerHeartbeat.newBuilder()
            .setWorkerInstanceKey("worker-2")
            .setTaskQueue("queue-2")
            .build();
    manager.registerWorker("worker-1", () -> hb1);
    manager.registerWorker("worker-2", () -> hb2);

    // Wait for enough ticks so both workers are captured in at least one RPC
    verify(blockingStub, timeout(5000).atLeast(2)).recordWorkerHeartbeat(any());

    ArgumentCaptor<RecordWorkerHeartbeatRequest> captor =
        ArgumentCaptor.forClass(RecordWorkerHeartbeatRequest.class);
    verify(blockingStub, atLeast(2)).recordWorkerHeartbeat(captor.capture());

    boolean foundBoth =
        captor.getAllValues().stream().anyMatch(req -> req.getWorkerHeartbeatCount() == 2);
    assertTrue("Expected at least one RPC with 2 worker heartbeats", foundBoth);
  }

  @Test
  public void testUnregisterStopsRpcWhenEmpty() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));

    WorkerHeartbeat hb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();
    manager.registerWorker("worker-1", () -> hb);

    verify(blockingStub, timeout(3000).atLeastOnce()).recordWorkerHeartbeat(any());

    manager.unregisterWorker("worker-1");
    clearInvocations(blockingStub);

    Thread.sleep(2000);
    verify(blockingStub, never()).recordWorkerHeartbeat(any());
  }

  @Test
  public void testElapsedSinceLastHeartbeat() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));

    WorkerHeartbeat hb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();
    manager.registerWorker("worker-1", () -> hb);

    verify(blockingStub, timeout(5000).atLeast(2)).recordWorkerHeartbeat(any());

    ArgumentCaptor<RecordWorkerHeartbeatRequest> captor =
        ArgumentCaptor.forClass(RecordWorkerHeartbeatRequest.class);
    verify(blockingStub, atLeast(2)).recordWorkerHeartbeat(captor.capture());

    boolean foundElapsed =
        captor.getAllValues().stream()
            .skip(1)
            .anyMatch(
                req ->
                    req.getWorkerHeartbeatCount() > 0
                        && req.getWorkerHeartbeat(0).hasElapsedSinceLastHeartbeat());
    assertTrue("Expected elapsed_since_last_heartbeat on subsequent heartbeats", foundElapsed);
  }

  @Test
  public void testExceptionsCaughtAndLogged() throws Exception {
    when(blockingStub.recordWorkerHeartbeat(any())).thenThrow(new RuntimeException("test error"));

    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));

    WorkerHeartbeat hb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();
    manager.registerWorker("worker-1", () -> hb);

    // Wait for at least 2 ticks — proves the scheduler survived the exception
    verify(blockingStub, timeout(5000).atLeast(2)).recordWorkerHeartbeat(any());
  }

  @Test
  public void testLifecycleNoExecutorWhenEmpty() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));

    Thread.sleep(2000);
    verify(blockingStub, never()).recordWorkerHeartbeat(any());
  }

  @Test
  public void testUnimplementedStopsScheduler() throws Exception {
    when(blockingStub.recordWorkerHeartbeat(any()))
        .thenThrow(new io.grpc.StatusRuntimeException(io.grpc.Status.UNIMPLEMENTED));

    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));

    WorkerHeartbeat hb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();
    manager.registerWorker("worker-1", () -> hb);

    // Wait for the first tick to hit UNIMPLEMENTED
    verify(blockingStub, timeout(3000).atLeastOnce()).recordWorkerHeartbeat(any());

    // After UNIMPLEMENTED, scheduler should stop — no more RPCs
    clearInvocations(blockingStub);
    Thread.sleep(2000);
    verify(blockingStub, never()).recordWorkerHeartbeat(any());
  }

  @Test
  public void testIntervalValidation() {
    HeartbeatManager hm =
        new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(30));
    assertNotNull(hm);
    hm.shutdown();
  }
}
