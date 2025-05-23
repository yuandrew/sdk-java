package io.temporal.client.schedules;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.workflow.Functions;
import java.util.List;
import javax.annotation.Nonnull;

/** Handle for interacting with a schedule. */
public interface ScheduleHandle {

  /**
   * Get this schedule's ID.
   *
   * @return the schedule's ID
   */
  String getId();

  /**
   * Backfill this schedule by going through the specified time periods as if they passed right now.
   *
   * @param backfills backfill requests to run
   */
  void backfill(List<ScheduleBackfill> backfills);

  /** Delete this schedule. */
  void delete();

  /**
   * Fetch this schedule's description.
   *
   * @return description of the schedule
   */
  ScheduleDescription describe();

  /**
   * Pause this schedule.
   *
   * @param note to set the schedule state.
   */
  void pause(@Nonnull String note);

  /** Pause this schedule. */
  void pause();

  /**
   * Trigger an action on this schedule to happen immediately.
   *
   * @param overlapPolicy override the schedule overlap policy.
   */
  void trigger(ScheduleOverlapPolicy overlapPolicy);

  /** Trigger an action on this schedule to happen immediately. */
  void trigger();

  /**
   * Unpause this schedule.
   *
   * @param note to set the schedule state.
   */
  void unpause(@Nonnull String note);

  /** Unpause this schedule. */
  void unpause();

  /**
   * Update this schedule. This is done via a callback which can be called multiple times in case of
   * conflict.
   *
   * @param updater Callback to invoke with the current update input. The result can be null to
   *     signify no update to perform, or a schedule update instance with a schedule to perform an
   *     update.
   */
  void update(Functions.Func1<ScheduleUpdateInput, ScheduleUpdate> updater);
}
