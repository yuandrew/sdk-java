package io.temporal.internal.worker;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks runtime polling metrics for worker heartbeats: the number of currently in-flight poll RPCs
 * and the timestamp of the last successful poll (one that returned a task).
 */
public class PollerTracker {
  private final AtomicInteger inFlightPolls = new AtomicInteger();
  private final AtomicReference<Instant> lastSuccessfulPollTime = new AtomicReference<>();

  public void pollStarted() {
    inFlightPolls.incrementAndGet();
  }

  public void pollCompleted() {
    inFlightPolls.decrementAndGet();
  }

  public void pollSucceeded() {
    lastSuccessfulPollTime.set(Instant.now());
  }

  public int getInFlightPolls() {
    return inFlightPolls.get();
  }

  public Instant getLastSuccessfulPollTime() {
    return lastSuccessfulPollTime.get();
  }
}
