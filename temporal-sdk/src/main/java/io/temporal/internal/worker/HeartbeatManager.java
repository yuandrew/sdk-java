package io.temporal.internal.worker;

import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.api.workflowservice.v1.RecordWorkerHeartbeatRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages worker heartbeat lifecycle. Created by WorkflowClientInternalImpl when
 * workerHeartbeatInterval is configured and positive.
 *
 * <p>The ScheduledExecutorService is started lazily when the first worker registers and stopped
 * when the last worker unregisters.
 */
public class HeartbeatManager {
  private static final Logger log = LoggerFactory.getLogger(HeartbeatManager.class);

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final String identity;
  private final Duration interval;
  private final String workerGroupingKey;
  private final ConcurrentHashMap<String, Supplier<WorkerHeartbeat>> callbacks =
      new ConcurrentHashMap<>();

  private volatile Instant lastHeartbeatTime;

  @Nullable private ScheduledExecutorService scheduler;
  private final Object lifecycleLock = new Object();

  public HeartbeatManager(
      WorkflowServiceStubs service, String namespace, String identity, Duration interval) {
    this.service = service;
    this.namespace = namespace;
    this.identity = identity;
    this.interval = interval;
    this.workerGroupingKey = UUID.randomUUID().toString();
  }

  public String getWorkerGroupingKey() {
    return workerGroupingKey;
  }

  /**
   * Register a worker's heartbeat callback. Starts the heartbeat scheduler if this is the first
   * registered worker.
   */
  public void registerWorker(String workerInstanceKey, Supplier<WorkerHeartbeat> callback) {
    callbacks.put(workerInstanceKey, callback);
    ensureSchedulerRunning();
  }

  /**
   * Unregister a worker. Stops the scheduler if no workers remain.
   */
  public void unregisterWorker(String workerInstanceKey) {
    callbacks.remove(workerInstanceKey);
    maybeStopScheduler();
  }

  private void ensureSchedulerRunning() {
    synchronized (lifecycleLock) {
      if (scheduler == null || scheduler.isShutdown()) {
        scheduler =
            Executors.newSingleThreadScheduledExecutor(
                r -> {
                  Thread t = new Thread(r, "worker-heartbeat");
                  t.setDaemon(true);
                  return t;
                });
        scheduler.scheduleAtFixedRate(
            this::heartbeatTick, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
  }

  private void maybeStopScheduler() {
    synchronized (lifecycleLock) {
      if (callbacks.isEmpty() && scheduler != null) {
        scheduler.shutdown();
        scheduler = null;
      }
    }
  }

  public void shutdown() {
    synchronized (lifecycleLock) {
      if (scheduler != null) {
        scheduler.shutdown();
        scheduler = null;
      }
    }
  }

  private void heartbeatTick() {
    if (callbacks.isEmpty()) return;

    try {
      List<WorkerHeartbeat> heartbeats = new ArrayList<>();
      Instant now = Instant.now();

      for (Supplier<WorkerHeartbeat> callback : callbacks.values()) {
        WorkerHeartbeat hb = callback.get();
        WorkerHeartbeat.Builder builder = hb.toBuilder();

        if (lastHeartbeatTime != null) {
          Duration elapsed = Duration.between(lastHeartbeatTime, now);
          builder.setElapsedSinceLastHeartbeat(
              com.google.protobuf.Duration.newBuilder()
                  .setSeconds(elapsed.getSeconds())
                  .setNanos(elapsed.getNano())
                  .build());
        }
        heartbeats.add(builder.build());
      }

      if (!heartbeats.isEmpty()) {
        service
            .blockingStub()
            .recordWorkerHeartbeat(
                RecordWorkerHeartbeatRequest.newBuilder()
                    .setNamespace(namespace)
                    .setIdentity(identity)
                    .addAllWorkerHeartbeat(heartbeats)
                    .build());
      }
      lastHeartbeatTime = now;
    } catch (Exception e) {
      log.debug("Failed to send worker heartbeat", e);
    }
  }
}
