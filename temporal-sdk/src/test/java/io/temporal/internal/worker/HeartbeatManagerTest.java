package io.temporal.internal.worker;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.temporal.api.namespace.v1.NamespaceInfo;
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

  private void enableCapability() {
    DescribeNamespaceResponse response =
        DescribeNamespaceResponse.newBuilder()
            .setNamespaceInfo(
                NamespaceInfo.newBuilder()
                    .setCapabilities(
                        NamespaceInfo.Capabilities.newBuilder().setWorkerHeartbeats(true).build())
                    .build())
            .build();
    manager.checkCapability(response);
  }

  private void disableCapability() {
    DescribeNamespaceResponse response =
        DescribeNamespaceResponse.newBuilder()
            .setNamespaceInfo(
                NamespaceInfo.newBuilder()
                    .setCapabilities(
                        NamespaceInfo.Capabilities.newBuilder().setWorkerHeartbeats(false).build())
                    .build())
            .build();
    manager.checkCapability(response);
  }

  @Test
  public void testHeartbeatRpcSentAtInterval() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));
    enableCapability();

    WorkerHeartbeat hb =
        WorkerHeartbeat.newBuilder()
            .setWorkerInstanceKey("worker-1")
            .setTaskQueue("test-queue")
            .build();
    manager.registerWorker("worker-1", () -> hb);

    // Wait for at least one heartbeat tick
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
    enableCapability();

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

    verify(blockingStub, timeout(3000).atLeastOnce()).recordWorkerHeartbeat(any());

    ArgumentCaptor<RecordWorkerHeartbeatRequest> captor =
        ArgumentCaptor.forClass(RecordWorkerHeartbeatRequest.class);
    verify(blockingStub, atLeastOnce()).recordWorkerHeartbeat(captor.capture());

    // Find a request with both workers
    boolean foundBoth =
        captor.getAllValues().stream().anyMatch(req -> req.getWorkerHeartbeatCount() == 2);
    assertTrue("Expected at least one RPC with 2 worker heartbeats", foundBoth);
  }

  @Test
  public void testCapabilityGatingDisablesRpc() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));
    disableCapability();

    WorkerHeartbeat hb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();
    manager.registerWorker("worker-1", () -> hb);

    // Wait a bit to ensure no RPCs are sent
    Thread.sleep(2000);
    verify(blockingStub, never()).recordWorkerHeartbeat(any());
  }

  @Test
  public void testCapabilityGatingEnablesRpc() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));
    enableCapability();

    WorkerHeartbeat hb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();
    manager.registerWorker("worker-1", () -> hb);

    verify(blockingStub, timeout(3000).atLeastOnce()).recordWorkerHeartbeat(any());
  }

  @Test
  public void testUnregisterStopsRpcWhenEmpty() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));
    enableCapability();

    WorkerHeartbeat hb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();
    manager.registerWorker("worker-1", () -> hb);

    // Wait for at least one heartbeat
    verify(blockingStub, timeout(3000).atLeastOnce()).recordWorkerHeartbeat(any());

    // Unregister and reset mock
    manager.unregisterWorker("worker-1");
    clearInvocations(blockingStub);

    // Wait and verify no more RPCs
    Thread.sleep(2000);
    verify(blockingStub, never()).recordWorkerHeartbeat(any());
  }

  @Test
  public void testElapsedSinceLastHeartbeat() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));
    enableCapability();

    WorkerHeartbeat hb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();
    manager.registerWorker("worker-1", () -> hb);

    // Wait for at least 2 heartbeat ticks
    verify(blockingStub, timeout(5000).atLeast(2)).recordWorkerHeartbeat(any());

    ArgumentCaptor<RecordWorkerHeartbeatRequest> captor =
        ArgumentCaptor.forClass(RecordWorkerHeartbeatRequest.class);
    verify(blockingStub, atLeast(2)).recordWorkerHeartbeat(captor.capture());

    // The second+ request should have elapsed_since_last_heartbeat set
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
    enableCapability();

    WorkerHeartbeat hb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();
    manager.registerWorker("worker-1", () -> hb);

    // Wait for at least 2 ticks — proves the scheduler survived the exception
    verify(blockingStub, timeout(5000).atLeast(2)).recordWorkerHeartbeat(any());
  }

  @Test
  public void testSendFinalHeartbeat() {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(30));
    enableCapability();

    WorkerHeartbeat finalHb =
        WorkerHeartbeat.newBuilder()
            .setWorkerInstanceKey("worker-1")
            .setStatus(io.temporal.api.enums.v1.WorkerStatus.WORKER_STATUS_SHUTTING_DOWN)
            .build();

    manager.sendFinalHeartbeat(finalHb);

    ArgumentCaptor<RecordWorkerHeartbeatRequest> captor =
        ArgumentCaptor.forClass(RecordWorkerHeartbeatRequest.class);
    verify(blockingStub).recordWorkerHeartbeat(captor.capture());

    RecordWorkerHeartbeatRequest request = captor.getValue();
    assertEquals(1, request.getWorkerHeartbeatCount());
    assertEquals(
        io.temporal.api.enums.v1.WorkerStatus.WORKER_STATUS_SHUTTING_DOWN,
        request.getWorkerHeartbeat(0).getStatus());
  }

  @Test
  public void testSendFinalHeartbeatSkippedWhenCapabilityDisabled() {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(30));
    disableCapability();

    WorkerHeartbeat finalHb = WorkerHeartbeat.newBuilder().setWorkerInstanceKey("worker-1").build();

    manager.sendFinalHeartbeat(finalHb);

    verify(blockingStub, never()).recordWorkerHeartbeat(any());
  }

  @Test
  public void testLifecycleNoExecutorWhenEmpty() throws Exception {
    manager = new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(1));
    enableCapability();

    // No workers registered — no RPCs should be sent
    Thread.sleep(2000);
    verify(blockingStub, never()).recordWorkerHeartbeat(any());
  }

  @Test
  public void testIntervalValidation() {
    // 0 defaults to 60s via WorkflowClientOptions, not HeartbeatManager directly.
    // Negative disables at WorkflowClientOptions level.
    // HeartbeatManager just uses the resolved duration.
    // This test verifies HeartbeatManager accepts normal durations.
    HeartbeatManager hm =
        new HeartbeatManager(service, "default", "test-identity", Duration.ofSeconds(30));
    assertNotNull(hm);
    hm.shutdown();
  }
}
