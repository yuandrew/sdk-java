package io.temporal.internal.worker;

import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.api.workflowservice.v1.RecordWorkerHeartbeatRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages worker heartbeat lifecycle across namespaces. Created by WorkflowClientInternalImpl when
 * workerHeartbeatInterval is configured and positive.
 *
 * <p>Routes workers to per-namespace {@link SharedNamespaceWorker} instances, each with its own
 * scheduler. This matches the Go and Rust SDK architecture where heartbeats are scoped per
 * namespace.
 *
 * <p>Capability gating happens externally: WorkerFactory checks DescribeNamespace capabilities and
 * calls disableHeartbeatManager() on the client if the server doesn't support heartbeats.
 */
public class HeartbeatManager {
  private static final Logger log = LoggerFactory.getLogger(HeartbeatManager.class);

  private final WorkflowServiceStubs service;
  private final String identity;
  private final Duration interval;
  private final ConcurrentHashMap<String, SharedNamespaceWorker> namespaceWorkers =
      new ConcurrentHashMap<>();

  private volatile boolean disabled;
  private final Object lock = new Object();

  public HeartbeatManager(WorkflowServiceStubs service, String identity, Duration interval) {
    this.service = service;
    this.identity = identity;
    this.interval = interval;
  }

  /**
   * Register a worker's heartbeat callback. Creates a per-namespace SharedNamespaceWorker if this
   * is the first worker for the given namespace.
   */
  public void registerWorker(
      String namespace, String workerInstanceKey, Supplier<WorkerHeartbeat> callback) {
    synchronized (lock) {
      if (disabled) return;
      namespaceWorkers.compute(
          namespace,
          (ns, existing) -> {
            if (existing != null && !existing.isShutdown()) {
              existing.registerWorker(workerInstanceKey, callback);
              return existing;
            }
            SharedNamespaceWorker nsWorker =
                new SharedNamespaceWorker(service, ns, identity, interval);
            nsWorker.registerWorker(workerInstanceKey, callback);
            return nsWorker;
          });
    }
  }

  /** Unregister a worker. Stops the namespace worker if no workers remain for that namespace. */
  public void unregisterWorker(String namespace, String workerInstanceKey) {
    synchronized (lock) {
      SharedNamespaceWorker nsWorker = namespaceWorkers.get(namespace);
      if (nsWorker == null) return;
      nsWorker.unregisterWorker(workerInstanceKey);
      if (nsWorker.isEmpty()) {
        nsWorker.shutdown();
        namespaceWorkers.remove(namespace);
      }
    }
  }

  public void disable() {
    synchronized (lock) {
      disabled = true;
      for (SharedNamespaceWorker nsWorker : namespaceWorkers.values()) {
        nsWorker.shutdown();
      }
      namespaceWorkers.clear();
    }
  }

  public void shutdown() {
    disable();
  }

  /**
   * Handles heartbeating for all workers in a specific namespace. Each instance owns its own
   * scheduler thread and callback map.
   */
  static class SharedNamespaceWorker {
    private final WorkflowServiceStubs service;
    private final String namespace;
    private final String identity;
    private final ConcurrentHashMap<String, Supplier<WorkerHeartbeat>> callbacks =
        new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    SharedNamespaceWorker(
        WorkflowServiceStubs service, String namespace, String identity, Duration interval) {
      this.service = service;
      this.namespace = namespace;
      this.identity = identity;
      this.scheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "worker-heartbeat-" + namespace);
                t.setDaemon(true);
                return t;
              });
      scheduler.scheduleAtFixedRate(
          this::heartbeatTick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void registerWorker(String workerInstanceKey, Supplier<WorkerHeartbeat> callback) {
      callbacks.put(workerInstanceKey, callback);
    }

    void unregisterWorker(String workerInstanceKey) {
      callbacks.remove(workerInstanceKey);
    }

    boolean isEmpty() {
      return callbacks.isEmpty();
    }

    boolean isShutdown() {
      return scheduler.isShutdown();
    }

    void shutdown() {
      scheduler.shutdown();
      try {
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void heartbeatTick() {
      if (callbacks.isEmpty()) return;

      try {
        List<WorkerHeartbeat> heartbeats = new ArrayList<>();
        for (Supplier<WorkerHeartbeat> callback : callbacks.values()) {
          heartbeats.add(callback.get());
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
      } catch (io.grpc.StatusRuntimeException e) {
        if (e.getStatus().getCode() == io.grpc.Status.Code.UNIMPLEMENTED) {
          log.warn(
              "Server does not support worker heartbeats for namespace {}, disabling", namespace);
          shutdown();
          return;
        }
        log.warn("Failed to send worker heartbeat for namespace {}", namespace, e);
      } catch (Exception e) {
        log.warn("Failed to send worker heartbeat for namespace {}", namespace, e);
      }
    }
  }
}
