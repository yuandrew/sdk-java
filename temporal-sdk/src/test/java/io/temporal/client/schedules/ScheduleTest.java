package io.temporal.client.schedules;

import static org.junit.Assume.assumeTrue;

import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.interceptors.ScheduleClientInterceptor;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ScheduleTest {
  static final SearchAttributeKey<String> CUSTOM_KEYWORD_SA =
      SearchAttributeKey.forKeyword("CustomKeywordField");

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ScheduleTest.QuickWorkflowImpl.class)
          .build();

  private ScheduleClient createScheduleClient(ScheduleClientInterceptor... interceptors) {
    return new ScheduleClientImpl(
        testWorkflowRule.getWorkflowServiceStubs(),
        ScheduleClientOptions.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setIdentity(testWorkflowRule.getWorkflowClient().getOptions().getIdentity())
            .setDataConverter(testWorkflowRule.getWorkflowClient().getOptions().getDataConverter())
            .setInterceptors(Arrays.asList(interceptors))
            .build());
  }

  private void waitForActions(ScheduleHandle handle, long actions) {
    while (true) {
      if (handle.describe().getInfo().getNumActions() >= actions) {
        return;
      }
      testWorkflowRule.sleep(Duration.ofSeconds(1));
    }
  }

  private Schedule.Builder createTestSchedule() {
    WorkflowOptions wfOptions =
        WorkflowOptions.newBuilder()
            .setWorkflowId("test-schedule-id")
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setMemo(Collections.singletonMap("memokey1", "memoval1"))
            .build();
    return Schedule.newBuilder()
        .setAction(
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType("TestWorkflow1")
                .setArguments("arg")
                .setOptions(wfOptions)
                .build())
        .setSpec(
            ScheduleSpec.newBuilder()
                .setIntervals(Arrays.asList(new ScheduleIntervalSpec(Duration.ofSeconds(1))))
                .build());
  }

  @Before
  public void checkRealServer() {
    assumeTrue("skipping for test server", SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void createSchedule() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options = ScheduleOptions.newBuilder().build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule = createTestSchedule().build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    ScheduleDescription description = handle.describe();
    Assert.assertEquals(scheduleId, description.getId());
    // Verify the schedule description has the correct (i.e. no) memo
    Assert.assertNull(description.getMemo("memokey1", String.class));
    // Try to create a schedule that already exists
    Assert.assertThrows(
        ScheduleAlreadyRunningException.class,
        () -> client.createSchedule(scheduleId, schedule, options));
    // Clean up schedule
    handle.delete();
    // Describe a deleted schedule
    try {
      handle.describe();
      Assert.fail();
    } catch (ScheduleException e) {
    }
    // Create a handle to a non-existent schedule, creating the handle should not throw
    // but any operations on it should.
    try {
      ScheduleHandle badHandle = client.getHandle(UUID.randomUUID().toString());
      badHandle.delete();
      Assert.fail();
    } catch (ScheduleException e) {
    }
  }

  @Test
  public void pauseUnpauseSchedule() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options = ScheduleOptions.newBuilder().build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule = createTestSchedule().build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    ScheduleDescription description = handle.describe();
    // Verify the initial state of the schedule
    Assert.assertEquals("", description.getSchedule().getState().getNote());
    Assert.assertEquals(false, description.getSchedule().getState().isPaused());
    // Pause the schedule
    handle.pause();
    description = handle.describe();
    Assert.assertEquals("Paused via Java SDK", description.getSchedule().getState().getNote());
    Assert.assertEquals(true, description.getSchedule().getState().isPaused());

    handle.unpause();
    description = handle.describe();
    Assert.assertEquals("Unpaused via Java SDK", description.getSchedule().getState().getNote());
    Assert.assertEquals(false, description.getSchedule().getState().isPaused());

    handle.pause("pause via test");
    description = handle.describe();
    Assert.assertEquals("pause via test", description.getSchedule().getState().getNote());
    Assert.assertEquals(true, description.getSchedule().getState().isPaused());

    handle.pause("");
    description = handle.describe();
    Assert.assertEquals("Paused via Java SDK", description.getSchedule().getState().getNote());
    Assert.assertEquals(true, description.getSchedule().getState().isPaused());

    handle.unpause("unpause via test");
    description = handle.describe();
    Assert.assertEquals("unpause via test", description.getSchedule().getState().getNote());
    Assert.assertEquals(false, description.getSchedule().getState().isPaused());

    handle.unpause("");
    description = handle.describe();
    Assert.assertEquals("Unpaused via Java SDK", description.getSchedule().getState().getNote());
    Assert.assertEquals(false, description.getSchedule().getState().isPaused());
    // Cleanup schedule
    handle.delete();
    // Try to unpause a deleted schedule
    try {
      handle.unpause("");
      Assert.fail();
    } catch (ScheduleException e) {
    }
  }

  @Test
  public void limitedActionSchedule() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options = ScheduleOptions.newBuilder().build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule =
        createTestSchedule()
            .setState(
                ScheduleState.newBuilder().setLimitedAction(true).setRemainingActions(3).build())
            .build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    waitForActions(handle, 3);
    // Verify all 3 actions have run
    ScheduleDescription description = handle.describe();
    Assert.assertEquals(0, description.getSchedule().getState().getRemainingActions());
    Assert.assertEquals(true, description.getSchedule().getState().isLimitedAction());
    // Cleanup schedule
    handle.delete();
  }

  @Test
  public void triggerSchedule() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options = ScheduleOptions.newBuilder().setTriggerImmediately(true).build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule =
        createTestSchedule()
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_BUFFER_ONE)
                    .build())
            .setState(ScheduleState.newBuilder().setPaused(true).build())
            .build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    waitForActions(handle, 1);
    Assert.assertEquals(1, handle.describe().getInfo().getNumActions());
    // Trigger the schedule and verify a new action was run
    handle.trigger();
    waitForActions(handle, 2);
    Assert.assertEquals(2, handle.describe().getInfo().getNumActions());
    // Trigger the schedule and verify a new action was run
    handle.trigger();
    waitForActions(handle, 3);
    Assert.assertEquals(3, handle.describe().getInfo().getNumActions());
    // Cleanup schedule
    handle.delete();
    // Try to trigger a deleted schedule
    try {
      handle.trigger();
      Assert.fail();
    } catch (ScheduleException e) {
    }
  }

  @Test
  public void triggerScheduleNoPolicy() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options = ScheduleOptions.newBuilder().setTriggerImmediately(true).build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule =
        createTestSchedule().setState(ScheduleState.newBuilder().setPaused(true).build()).build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    waitForActions(handle, 1);
    // Cleanup schedule
    handle.delete();
  }

  @Test(timeout = 30000)
  public void backfillSchedules() {
    Instant backfillTime = Instant.ofEpochSecond(100000);
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options =
        ScheduleOptions.newBuilder()
            .setBackfills(
                Arrays.asList(
                    new ScheduleBackfill(
                        backfillTime.minusMillis(20500), backfillTime.minusMillis(10000))))
            .build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule =
        createTestSchedule()
            .setState(ScheduleState.newBuilder().setPaused(true).build())
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_BUFFER_ALL)
                    .build())
            .build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    waitForActions(handle, 10);

    handle.backfill(
        Arrays.asList(
            new ScheduleBackfill(backfillTime.minusMillis(5500), backfillTime.minusMillis(2500)),
            new ScheduleBackfill(backfillTime.minusMillis(2500), backfillTime)));
    waitForActions(handle, 15);
    // Cleanup schedule
    handle.delete();
    // Try to backfill a deleted schedule
    try {
      handle.backfill(
          Arrays.asList(
              new ScheduleBackfill(backfillTime.minusMillis(5500), backfillTime.minusMillis(2500)),
              new ScheduleBackfill(backfillTime.minusMillis(2500), backfillTime)));
      Assert.fail();
    } catch (ScheduleException e) {
    }
  }

  @Test
  public void describeSchedules() {
    ScheduleClient client = createScheduleClient();
    // Create schedule
    ScheduleOptions options =
        ScheduleOptions.newBuilder()
            .setMemo(Collections.singletonMap("memokey2", "memoval2"))
            .build();
    String scheduleId = UUID.randomUUID().toString();
    RetryOptions retryOptions =
        RetryOptions.newBuilder().setMaximumAttempts(1).validateBuildWithDefaults();
    WorkflowOptions wfOptions =
        WorkflowOptions.newBuilder()
            .setWorkflowId("test")
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setRetryOptions(retryOptions)
            .setMemo(Collections.singletonMap("memokey1", "memoval1"))
            .build();

    Schedule schedule =
        Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType(TestWorkflows.TestWorkflow1.class)
                    .setArguments("arg")
                    .setOptions(wfOptions)
                    .build())
            .setSpec(
                ScheduleSpec.newBuilder()
                    .setCalendars(
                        Arrays.asList(
                            ScheduleCalendarSpec.newBuilder()
                                .setSeconds(Arrays.asList(new ScheduleRange(1, 0, 1)))
                                .setMinutes(Arrays.asList(new ScheduleRange(2, 3)))
                                .setHour(Arrays.asList(new ScheduleRange(4, 5, 6)))
                                .setDayOfMonth(Arrays.asList(new ScheduleRange(7)))
                                .setMonth(Arrays.asList(new ScheduleRange(9)))
                                .setYear(Arrays.asList(new ScheduleRange(2080)))
                                // Intentionally leave day of week absent to check default
                                .setComment("spec comment 1")
                                .build()))
                    .setCronExpressions(Arrays.asList("0 12 * * MON"))
                    .setSkip(
                        Arrays.asList(
                            ScheduleCalendarSpec.newBuilder()
                                .setYear(Arrays.asList(new ScheduleRange(2050)))
                                .build()))
                    .setIntervals(
                        Arrays.asList(
                            new ScheduleIntervalSpec(Duration.ofDays(10), Duration.ofDays(2))))
                    .setStartAt(Instant.parse("2060-12-03T10:15:30.00Z"))
                    .setJitter(Duration.ofSeconds(80))
                    .build())
            .setState(
                ScheduleState.newBuilder()
                    .setRemainingActions(30)
                    .setPaused(true)
                    .setNote("sched note 1")
                    .build())
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_BUFFER_ONE)
                    .setPauseOnFailure(true)
                    .setCatchupWindow(Duration.ofMinutes(5))
                    .build())
            .build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    ScheduleDescription description = handle.describe();
    //
    Assert.assertEquals(scheduleId, description.getId());
    Assert.assertEquals("memoval2", description.getMemo("memokey2", String.class));
    // Assert action
    Assert.assertEquals(
        ScheduleActionStartWorkflow.class, description.getSchedule().getAction().getClass());
    ScheduleActionStartWorkflow startWfAction =
        (ScheduleActionStartWorkflow) description.getSchedule().getAction();
    Assert.assertEquals("TestWorkflow1", startWfAction.getWorkflowType());
    EncodedValues parameters = startWfAction.getArguments();
    Assert.assertEquals("arg", parameters.get(0, String.class));
    EncodedValues encodedMemo =
        (EncodedValues) startWfAction.getOptions().getMemo().get("memokey1");
    String memoValue = encodedMemo.get(0, String.class);
    Assert.assertEquals("memoval1", memoValue);
    Assert.assertEquals(retryOptions, startWfAction.getOptions().getRetryOptions());
    //
    Assert.assertEquals(
        ScheduleSpec.newBuilder(description.getSchedule().getSpec())
            .setCronExpressions(Collections.emptyList())
            .setCalendars(
                Arrays.asList(
                    description.getSchedule().getSpec().getCalendars().get(0),
                    ScheduleCalendarSpec.newBuilder()
                        .setSeconds(Arrays.asList(new ScheduleRange(0, 0, 1)))
                        .setMinutes(Arrays.asList(new ScheduleRange(0, 0, 1)))
                        .setHour(Arrays.asList(new ScheduleRange(12, 12, 1)))
                        .setDayOfMonth(Arrays.asList(new ScheduleRange(1, 31, 1)))
                        .setMonth(Arrays.asList(new ScheduleRange(1, 12, 1)))
                        .setDayOfWeek(Arrays.asList(new ScheduleRange(1, 1, 1)))
                        .build()))
            .build(),
        description.getSchedule().getSpec());
    Assert.assertEquals(schedule.getPolicy(), description.getSchedule().getPolicy());
    Assert.assertEquals(schedule.getState(), description.getSchedule().getState());
    // Cleanup schedule
    handle.delete();
    // Try to describe a deleted schedule
    try {
      handle.describe();
      Assert.fail();
    } catch (ScheduleException e) {
    }
  }

  @Test
  public void updateSchedules() {
    ScheduleClient client = createScheduleClient();
    // Create the schedule
    String keywordSAValue = "keyword";
    ScheduleOptions options =
        ScheduleOptions.newBuilder()
            .setMemo(Collections.singletonMap("memokey2", "memoval2"))
            .setTypedSearchAttributes(
                SearchAttributes.newBuilder().set(CUSTOM_KEYWORD_SA, keywordSAValue).build())
            .build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule = createTestSchedule().build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);

    ScheduleDescription description = handle.describe();
    Assert.assertEquals("memoval2", description.getMemo("memokey2", String.class));
    ScheduleActionStartWorkflow startWfAction =
        ((ScheduleActionStartWorkflow) description.getSchedule().getAction());
    EncodedValues encodedMemo =
        (EncodedValues) startWfAction.getOptions().getMemo().get("memokey1");
    String memoValue = encodedMemo.get(0, String.class);
    Assert.assertEquals("memoval1", memoValue);

    handle.update(
        (ScheduleUpdateInput input) -> {
          Schedule.Builder builder = Schedule.newBuilder(input.getDescription().getSchedule());
          ScheduleActionStartWorkflow wfAction =
              ((ScheduleActionStartWorkflow) input.getDescription().getSchedule().getAction());
          WorkflowOptions wfOptions =
              WorkflowOptions.newBuilder(wfAction.getOptions())
                  .setWorkflowTaskTimeout(Duration.ofMinutes(7))
                  .setMemo(Collections.singletonMap("memokey3", "memoval3"))
                  .build();
          builder.setAction(
              ScheduleActionStartWorkflow.newBuilder(wfAction).setOptions(wfOptions).build());
          return new ScheduleUpdate(builder.build());
        });
    description = handle.describe();
    Assert.assertEquals(
        ScheduleActionStartWorkflow.class, description.getSchedule().getAction().getClass());
    Assert.assertEquals("memoval2", description.getMemo("memokey2", String.class));
    startWfAction = ((ScheduleActionStartWorkflow) description.getSchedule().getAction());
    encodedMemo = (EncodedValues) startWfAction.getOptions().getMemo().get("memokey3");
    memoValue = encodedMemo.get(0, String.class);
    Assert.assertEquals("memoval3", memoValue);

    Assert.assertEquals(
        Duration.ofMinutes(7),
        ((ScheduleActionStartWorkflow) description.getSchedule().getAction())
            .getOptions()
            .getWorkflowTaskTimeout());
    // Update the schedule state
    Instant expectedUpdateTime = description.getInfo().getLastUpdatedAt();
    handle.update(
        (ScheduleUpdateInput input) -> {
          Schedule.Builder builder =
              Schedule.newBuilder()
                  .setAction(input.getDescription().getSchedule().getAction())
                  .setSpec(ScheduleSpec.newBuilder().build());
          builder.setState(ScheduleState.newBuilder().setPaused(true).build());
          return new ScheduleUpdate(builder.build(), null);
        });
    description = handle.describe();
    //
    Assert.assertEquals("memoval2", description.getMemo("memokey2", String.class));
    startWfAction = ((ScheduleActionStartWorkflow) description.getSchedule().getAction());
    encodedMemo = (EncodedValues) startWfAction.getOptions().getMemo().get("memokey3");
    memoValue = encodedMemo.get(0, String.class);
    Assert.assertEquals("memoval3", memoValue);
    //
    Assert.assertNotEquals(expectedUpdateTime, description.getInfo().getLastUpdatedAt());
    Assert.assertEquals(true, description.getSchedule().getState().isPaused());
    Assert.assertEquals(1, description.getTypedSearchAttributes().size());
    Assert.assertEquals(
        keywordSAValue, description.getTypedSearchAttributes().get(CUSTOM_KEYWORD_SA));
    // Update the schedule search attribute by clearing them
    handle.update(
        (ScheduleUpdateInput input) ->
            new ScheduleUpdate(input.getDescription().getSchedule(), SearchAttributes.EMPTY));
    Eventually.assertEventually(
        Duration.ofSeconds(1),
        () -> {
          ScheduleDescription desc = handle.describe();
          Assert.assertEquals(0, desc.getTypedSearchAttributes().size());
        });
    // Update the schedule search attribute by adding a new search attribute
    handle.update(
        (ScheduleUpdateInput input) ->
            new ScheduleUpdate(
                input.getDescription().getSchedule(),
                SearchAttributes.newBuilder().set(CUSTOM_KEYWORD_SA, "newkeyword").build()));
    Eventually.assertEventually(
        Duration.ofSeconds(1),
        () -> {
          ScheduleDescription desc = handle.describe();
          Assert.assertEquals(1, desc.getTypedSearchAttributes().size());
          Assert.assertEquals("newkeyword", desc.getTypedSearchAttributes().get(CUSTOM_KEYWORD_SA));
        });
    // Cleanup schedule
    handle.delete();
  }

  @Test
  public void listSchedules() {
    ScheduleClient client = createScheduleClient();
    // Create the schedule
    ScheduleOptions.Builder optionsBuilder =
        ScheduleOptions.newBuilder()
            .setMemo(Collections.singletonMap("memokey2", "memoval2"))
            .setTypedSearchAttributes(
                SearchAttributes.newBuilder().set(CUSTOM_KEYWORD_SA, "keyword").build());
    ScheduleOptions options = optionsBuilder.build();
    Schedule schedule =
        createTestSchedule()
            .setState(ScheduleState.newBuilder().setPaused(true).setNote("schedule list").build())
            .build();
    // Append a unique prefix to the ID to avoid conflict with other schedules.
    String scheduleIdPrefix = UUID.randomUUID().toString();
    String scheduleId = scheduleIdPrefix + "/" + UUID.randomUUID();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    // Add delay for schedules to appear
    testWorkflowRule.sleep(Duration.ofSeconds(2));
    // List all schedules and filter
    Stream<ScheduleListDescription> scheduleStream = client.listSchedules();
    List<ScheduleListDescription> listedSchedules =
        scheduleStream
            .filter(s -> s.getScheduleId().startsWith(scheduleIdPrefix))
            .collect(Collectors.toList());
    Assert.assertEquals(1, listedSchedules.size());
    // Verify the schedule description
    ScheduleListDescription listDescription = listedSchedules.get(0);
    Assert.assertEquals("memoval2", listDescription.getMemo("memokey2", String.class));
    Assert.assertEquals(scheduleId, listDescription.getScheduleId());
    // Verify the state
    Assert.assertEquals("schedule list", listDescription.getSchedule().getState().getNote());
    Assert.assertEquals(true, listDescription.getSchedule().getState().isPaused());
    // Verify the spec
    Assert.assertEquals(
        ScheduleSpec.newBuilder(schedule.getSpec())
            .setIntervals(
                Arrays.asList(
                    new ScheduleIntervalSpec(Duration.ofSeconds(1), Duration.ofSeconds(0))))
            .setCalendars(Collections.emptyList())
            .setCronExpressions(Collections.emptyList())
            .setSkip(Collections.emptyList())
            .setTimeZoneName("")
            .build(),
        listDescription.getSchedule().getSpec());
    // Verify the action
    Assert.assertEquals(
        ScheduleListActionStartWorkflow.class,
        listDescription.getSchedule().getAction().getClass());
    ScheduleListActionStartWorkflow action =
        (ScheduleListActionStartWorkflow) listDescription.getSchedule().getAction();
    Assert.assertEquals("TestWorkflow1", action.getWorkflow());
    // Create two additional schedules
    optionsBuilder = optionsBuilder.setTypedSearchAttributes(null);
    client.createSchedule(scheduleIdPrefix + UUID.randomUUID(), schedule, optionsBuilder.build());
    client.createSchedule(scheduleIdPrefix + UUID.randomUUID(), schedule, optionsBuilder.build());
    // Add delay for schedules to appear
    testWorkflowRule.sleep(Duration.ofSeconds(2));
    // List all schedules and filter
    scheduleStream = client.listSchedules(10);
    long listedSchedulesCount =
        scheduleStream.filter(s -> s.getScheduleId().startsWith(scheduleIdPrefix)).count();
    Assert.assertEquals(3, listedSchedulesCount);
    // List all schedules with a null filter
    scheduleStream = client.listSchedules(null, 10);
    listedSchedulesCount =
        scheduleStream.filter(s -> s.getScheduleId().startsWith(scheduleIdPrefix)).count();
    Assert.assertEquals(3, listedSchedulesCount);
    // List schedules with a query
    scheduleStream = client.listSchedules("CustomKeywordField = 'keyword'", null);
    listedSchedulesCount =
        scheduleStream.filter(s -> s.getScheduleId().startsWith(scheduleIdPrefix)).count();
    Assert.assertEquals(1, listedSchedulesCount);
    // Cleanup all schedules
    scheduleStream = client.listSchedules(null, null);
    scheduleStream
        .filter(s -> s.getScheduleId().startsWith(scheduleIdPrefix))
        .forEach(
            s -> {
              client.getHandle(s.getScheduleId()).delete();
            });
  }

  @Test
  public void testInterceptors() {
    TracingScheduleInterceptor.FilteredTrace ft = new TracingScheduleInterceptor.FilteredTrace();
    String scheduleId = UUID.randomUUID().toString();
    TracingScheduleInterceptor interceptor = new TracingScheduleInterceptor(ft);
    interceptor.setExpected(
        "createSchedule: " + scheduleId,
        "listSchedules",
        "describeSchedule: " + scheduleId,
        "pauseSchedule: " + scheduleId,
        "unpauseSchedule: " + scheduleId,
        "triggerSchedule: " + scheduleId,
        "backfillSchedule: " + scheduleId,
        "describeSchedule: " + scheduleId, // Updating a schedule implicitly calls describe.
        "updateSchedule: " + scheduleId,
        "deleteSchedule: " + scheduleId);
    ScheduleClient client = createScheduleClient(interceptor);
    ScheduleHandle handle =
        client.createSchedule(
            scheduleId, createTestSchedule().build(), ScheduleOptions.newBuilder().build());
    try {
      // Add delay for schedule to appear
      testWorkflowRule.sleep(Duration.ofSeconds(2));
      // List all schedules and filter
      Stream<ScheduleListDescription> scheduleStream = client.listSchedules();
      List<ScheduleListDescription> listedSchedules =
          scheduleStream
              .filter(s -> s.getScheduleId().equals(scheduleId))
              .collect(Collectors.toList());
      Assert.assertEquals(1, listedSchedules.size());
      // Verify the schedule description
      handle.describe();
      handle.pause();
      handle.unpause();
      handle.trigger();
      handle.backfill(Arrays.asList(new ScheduleBackfill(Instant.now(), Instant.now())));
      handle.update(input -> new ScheduleUpdate(createTestSchedule().build()));
    } finally {
      handle.delete();
    }
    interceptor.assertExpected();
  }

  public static class QuickWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String arg) {
      return null;
    }
  }
}
