package io.temporal.internal.worker;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared mutable state for poller metrics, serving as the single source of truth for both the Tally
 * metrics gauge and heartbeat read-back.
 *
 * <p>This follows the same "capturing gauge" pattern used by Go's {@code capturingCounter} and
 * Rust's {@code gauge_with_in_mem}: a single counter that both feeds the metrics system and is
 * readable for heartbeat population. Without this, the poll task's internal gauge and the heartbeat
 * builder would need separate counters tracking the same value.
 *
 * <p>This object exists because poll tasks are created inside {@code start()} and passed directly
 * to pollers without the intermediate worker retaining a reference. The shared object bridges the
 * gap: the intermediate worker (e.g. ActivityWorker) creates it, passes it to the poll task, and
 * later reads it when building heartbeats.
 *
 * <p>{@link #pollStarted()} and {@link #pollCompleted()} return the updated count so callers can
 * forward the value to the Tally gauge in a single operation, eliminating the need for a separate
 * {@code AtomicInteger} in the poll task.
 */
public class PollerTracker {
  private final AtomicInteger inFlightPolls = new AtomicInteger();
  private final AtomicReference<Instant> lastSuccessfulPollTime = new AtomicReference<>();

  /** Increments in-flight count. Returns the new value for forwarding to the Tally gauge. */
  public int pollStarted() {
    return inFlightPolls.incrementAndGet();
  }

  /** Decrements in-flight count. Returns the new value for forwarding to the Tally gauge. */
  public int pollCompleted() {
    return inFlightPolls.decrementAndGet();
  }

  /** Records the current time as the last successful poll (a poll that returned a task). */
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
