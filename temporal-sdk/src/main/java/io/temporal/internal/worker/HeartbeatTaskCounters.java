package io.temporal.internal.worker;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe counters for task processing stats reported in worker heartbeats. Tracks both
 * cumulative totals and per-interval deltas that reset on each heartbeat tick.
 */
public class HeartbeatTaskCounters {
  private final AtomicInteger totalProcessed = new AtomicInteger();
  private final AtomicInteger totalFailed = new AtomicInteger();
  private final AtomicInteger intervalProcessed = new AtomicInteger();
  private final AtomicInteger intervalFailed = new AtomicInteger();

  public void recordProcessed() {
    totalProcessed.incrementAndGet();
    intervalProcessed.incrementAndGet();
  }

  public void recordFailed() {
    totalFailed.incrementAndGet();
    intervalFailed.incrementAndGet();
  }

  public Snapshot snapshotAndResetInterval() {
    return new Snapshot(
        totalProcessed.get(),
        totalFailed.get(),
        intervalProcessed.getAndSet(0),
        intervalFailed.getAndSet(0));
  }

  public static class Snapshot {
    private final int totalProcessed;
    private final int totalFailed;
    private final int intervalProcessed;
    private final int intervalFailed;

    public Snapshot(
        int totalProcessed, int totalFailed, int intervalProcessed, int intervalFailed) {
      this.totalProcessed = totalProcessed;
      this.totalFailed = totalFailed;
      this.intervalProcessed = intervalProcessed;
      this.intervalFailed = intervalFailed;
    }

    public int getTotalProcessed() {
      return totalProcessed;
    }

    public int getTotalFailed() {
      return totalFailed;
    }

    public int getIntervalProcessed() {
      return intervalProcessed;
    }

    public int getIntervalFailed() {
      return intervalFailed;
    }
  }
}
