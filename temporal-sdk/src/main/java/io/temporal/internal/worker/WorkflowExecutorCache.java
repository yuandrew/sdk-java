package io.temporal.internal.worker;

import static io.temporal.internal.common.WorkflowExecutionUtils.isFullHistory;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder;
import io.temporal.internal.replay.WorkflowRunTaskHandler;
import io.temporal.worker.MetricsType;
import java.util.Objects;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
public final class WorkflowExecutorCache {
  private final Logger log = LoggerFactory.getLogger(WorkflowExecutorCache.class);
  private final WorkflowRunLockManager runLockManager;
  private final Cache<String, WorkflowRunTaskHandler> cache;
  private final Scope metricsScope;

  public WorkflowExecutorCache(
      int workflowCacheSize, WorkflowRunLockManager runLockManager, Scope scope) {
    Preconditions.checkArgument(workflowCacheSize > 0, "Max cache size must be greater than 0");
    this.runLockManager = runLockManager;
    this.cache =
        CacheBuilder.newBuilder()
            .maximumSize(workflowCacheSize)
            // TODO this number is taken out of the blue.
            //  This number should be calculated based on the number of all workers workflow task
            //  processors.
            .concurrencyLevel(128)
            .removalListener(
                e -> {
                  WorkflowRunTaskHandler entry = (WorkflowRunTaskHandler) e.getValue();
                  if (entry != null) {
                    try {
                      log.trace(
                          "Closing workflow execution for runId {}, cause {}",
                          e.getKey(),
                          e.getCause());
                      entry.close();
                      log.trace("Workflow execution for runId {} closed", e);
                    } catch (Throwable t) {
                      log.error("Workflow execution closure failed with an exception", t);
                      throw t;
                    }
                  }
                })
            .build();
    this.metricsScope = Objects.requireNonNull(scope);
    this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
  }

  public WorkflowRunTaskHandler getOrCreate(
      PollWorkflowTaskQueueResponseOrBuilder workflowTask,
      Scope workflowTypeScope,
      Callable<WorkflowRunTaskHandler> workflowExecutorFn)
      throws Exception {
    WorkflowExecution execution = workflowTask.getWorkflowExecution();
    String runId = execution.getRunId();
    if (isFullHistory(workflowTask)) {
      invalidate(execution, metricsScope, "full history", null);
      log.trace(
          "New Workflow Executor {}-{} has been created for a full history run",
          execution.getWorkflowId(),
          runId);
      return workflowExecutorFn.call();
    }

    @Nullable WorkflowRunTaskHandler workflowRunTaskHandler = cache.getIfPresent(runId);

    if (workflowRunTaskHandler != null) {
      workflowTypeScope.counter(MetricsType.STICKY_CACHE_HIT).inc(1);
      return workflowRunTaskHandler;
    }

    log.trace(
        "Workflow Executor {}-{} wasn't found in cache and a new executor has been created",
        execution.getWorkflowId(),
        runId);
    workflowTypeScope.counter(MetricsType.STICKY_CACHE_MISS).inc(1);

    return workflowExecutorFn.call();
  }

  public void addToCache(
      WorkflowExecution workflowExecution, WorkflowRunTaskHandler workflowRunTaskHandler) {
    cache.put(workflowExecution.getRunId(), workflowRunTaskHandler);
    log.trace(
        "Workflow Execution {}-{} has been added to cache",
        workflowExecution.getWorkflowId(),
        workflowExecution.getRunId());
    this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
  }

  /**
   * @param workflowTypeScope accepts workflow metric scope (tagged with task queue and workflow
   *     type)
   */
  @SuppressWarnings("deprecation")
  public boolean evictAnyNotInProcessing(
      WorkflowExecution inFavorOfExecution, Scope workflowTypeScope) {
    try {
      String inFavorOfRunId = inFavorOfExecution.getRunId();
      for (String key : cache.asMap().keySet()) {
        if (key.equals(inFavorOfRunId)) continue;
        boolean locked = runLockManager.tryLock(key);
        // if we were able to take a lock here, it means that the workflow is not in processing
        // currently on workers of this WorkerFactory and can be evicted
        if (locked) {
          try {
            log.trace(
                "Workflow Execution {}-{} caused eviction of Workflow Execution with runId {}",
                inFavorOfExecution.getWorkflowId(),
                inFavorOfRunId,
                key);
            cache.invalidate(key);
            workflowTypeScope.counter(MetricsType.STICKY_CACHE_THREAD_FORCED_EVICTION).inc(1);
            workflowTypeScope.counter(MetricsType.STICKY_CACHE_TOTAL_FORCED_EVICTION).inc(1);
            return true;
          } finally {
            runLockManager.unlock(key);
          }
        }
      }

      log.trace("Failed to evict from Workflow Execution cache, cache size is {}", cache.size());
      return false;
    } finally {
      this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
    }
  }

  @SuppressWarnings("deprecation")
  public void invalidate(
      WorkflowExecution execution, Scope workflowTypeScope, String reason, Throwable cause) {
    String runId = execution.getRunId();
    @Nullable WorkflowRunTaskHandler present = cache.getIfPresent(runId);
    if (log.isTraceEnabled()) {
      log.trace(
          "Invalidating {}-{} because of '{}', value is present in the cache: {}",
          execution.getWorkflowId(),
          runId,
          reason,
          present,
          cause);
    }
    cache.invalidate(runId);
    if (present != null) {
      workflowTypeScope.counter(MetricsType.STICKY_CACHE_TOTAL_FORCED_EVICTION).inc(1);
      this.metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
    }
  }

  public long size() {
    return cache.size();
  }

  public void invalidateAll() {
    cache.invalidateAll();
    metricsScope.gauge(MetricsType.STICKY_CACHE_SIZE).update(size());
  }
}
