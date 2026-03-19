package io.temporal.internal.worker;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;

public class HeartbeatTaskCountersTest {

  @Test
  public void testRecordAndSnapshot() {
    HeartbeatTaskCounters counters = new HeartbeatTaskCounters();
    counters.recordProcessed();
    counters.recordProcessed();
    counters.recordFailed();

    HeartbeatTaskCounters.Snapshot snap = counters.snapshotAndResetInterval();
    assertEquals(2, snap.getTotalProcessed());
    assertEquals(1, snap.getTotalFailed());
    assertEquals(2, snap.getIntervalProcessed());
    assertEquals(1, snap.getIntervalFailed());
  }

  @Test
  public void testIntervalReset() {
    HeartbeatTaskCounters counters = new HeartbeatTaskCounters();
    counters.recordProcessed();
    counters.recordProcessed();
    counters.recordFailed();

    // First snapshot captures interval
    counters.snapshotAndResetInterval();

    // Record more
    counters.recordProcessed();

    HeartbeatTaskCounters.Snapshot snap = counters.snapshotAndResetInterval();
    // Totals accumulate
    assertEquals(3, snap.getTotalProcessed());
    assertEquals(1, snap.getTotalFailed());
    // Interval only has new counts
    assertEquals(1, snap.getIntervalProcessed());
    assertEquals(0, snap.getIntervalFailed());
  }

  @Test
  public void testConcurrentAccess() throws Exception {
    HeartbeatTaskCounters counters = new HeartbeatTaskCounters();
    int threadCount = 8;
    int opsPerThread = 1000;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    List<Future<?>> futures = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(
              () -> {
                for (int j = 0; j < opsPerThread; j++) {
                  counters.recordProcessed();
                  if (j % 10 == 0) {
                    counters.recordFailed();
                  }
                }
              }));
    }

    for (Future<?> f : futures) {
      f.get(5, TimeUnit.SECONDS);
    }
    executor.shutdown();

    HeartbeatTaskCounters.Snapshot snap = counters.snapshotAndResetInterval();
    assertEquals(threadCount * opsPerThread, snap.getTotalProcessed());
    assertEquals(threadCount * (opsPerThread / 10), snap.getTotalFailed());
  }
}
