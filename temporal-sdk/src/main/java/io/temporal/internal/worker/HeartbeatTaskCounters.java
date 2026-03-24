package io.temporal.internal.worker;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe counters for task processing stats reported in worker heartbeats. Tracks only
 * cumulative totals; interval deltas are computed externally by comparing successive snapshots
 * (same approach as the Go SDK).
 */
public class HeartbeatTaskCounters {
  private final AtomicInteger totalProcessed = new AtomicInteger();
  private final AtomicInteger totalFailed = new AtomicInteger();

  public void recordProcessed() {
    totalProcessed.incrementAndGet();
  }

  public void recordFailed() {
    totalFailed.incrementAndGet();
  }

  public Snapshot snapshot() {
    return new Snapshot(totalProcessed.get(), totalFailed.get());
  }

  public static class Snapshot {
    private final int totalProcessed;
    private final int totalFailed;

    public Snapshot(int totalProcessed, int totalFailed) {
      this.totalProcessed = totalProcessed;
      this.totalFailed = totalFailed;
    }

    public int getTotalProcessed() {
      return totalProcessed;
    }

    public int getTotalFailed() {
      return totalFailed;
    }
  }
}
