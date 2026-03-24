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

    HeartbeatTaskCounters.Snapshot snap = counters.snapshot();
    assertEquals(2, snap.getTotalProcessed());
    assertEquals(1, snap.getTotalFailed());
  }

  @Test
  public void testCumulativeTotalsNeverReset() {
    HeartbeatTaskCounters counters = new HeartbeatTaskCounters();
    counters.recordProcessed();
    counters.recordProcessed();
    counters.recordFailed();

    HeartbeatTaskCounters.Snapshot snap1 = counters.snapshot();
    assertEquals(2, snap1.getTotalProcessed());
    assertEquals(1, snap1.getTotalFailed());

    // Record more after snapshot
    counters.recordProcessed();
    counters.recordFailed();

    HeartbeatTaskCounters.Snapshot snap2 = counters.snapshot();
    assertEquals(3, snap2.getTotalProcessed());
    assertEquals(2, snap2.getTotalFailed());
  }

  @Test
  public void testDeltaComputedExternally() {
    HeartbeatTaskCounters counters = new HeartbeatTaskCounters();
    counters.recordProcessed();
    counters.recordProcessed();
    counters.recordFailed();

    HeartbeatTaskCounters.Snapshot prev = counters.snapshot();

    counters.recordProcessed();
    counters.recordProcessed();
    counters.recordProcessed();
    counters.recordFailed();

    HeartbeatTaskCounters.Snapshot curr = counters.snapshot();

    // Delta = current - previous
    int deltaProcessed = curr.getTotalProcessed() - prev.getTotalProcessed();
    int deltaFailed = curr.getTotalFailed() - prev.getTotalFailed();
    assertEquals(3, deltaProcessed);
    assertEquals(1, deltaFailed);
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

    HeartbeatTaskCounters.Snapshot snap = counters.snapshot();
    assertEquals(threadCount * opsPerThread, snap.getTotalProcessed());
    assertEquals(threadCount * (opsPerThread / 10), snap.getTotalFailed());
  }
}
