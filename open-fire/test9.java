// =====================================================================================
// Minutes recurrence tests - paste into ReminderServiceTest, next to testDailyReminders()
//
// Check before running:
//  1. MinutesRecurrencePattern constructor: match the argument order your DailyRecurrencePattern
//     constructor uses (recurrencePatternType first, then the count). If there's no all-args
//     constructor, use: new MinutesRecurrencePattern().recurrencePatternType("minute").minutes(15)
//  2. The 0-minutes test only applies if you kept the guard in setupCronExpression.
//  3. Imports needed (most already exist in this test class):
//     java.time.OffsetDateTime, static org.junit.jupiter.api.Assertions.assertNull,
//     static org.junit.jupiter.api.Assertions.assertThrows
// =====================================================================================

@Test
@DisplayName("Test notification trigger for minute reminders with a start date in the past.")
void testMinutesRemindersStartInPast() {
    final SimpleTriggerContext triggerContext = new SimpleTriggerContext();

    // Set current date-time to 3/1/2024 10:15am
    final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
    final Instant currentTime = Instant.from(formatter.parse("2024-01-03T10:15:00.00Z"));

    final NotificationTrigger trigger = new NotificationTrigger();
    trigger.setClock(Clock.fixed(currentTime, ZoneId.of("UTC")));

    // Set start date-time to 3/1/2024 9:07am
    final Reminder reminder = createReminder("2024-01-03T09:07:00.00Z");

    final MinutesRecurrencePattern minutesRecurrencePattern = new MinutesRecurrencePattern("minute", 15);

    final Recurrence recurrence = new Recurrence(minutesRecurrencePattern, new RecurrenceRange());
    reminder.getSchedule().setRecurrence(recurrence);
    trigger.setReminderTask(reminder);

    // Every 15 minutes starting at 9:07 means trigger on
    // 9:07, 9:22, 9:37, 9:52, 10:07, 10:22, 10:37 etc.
    // If current time is 10:15, then nextExecution should be at 10:22.
    assertEquals(trigger.getNextOccurrence().toString(), "2024-01-03T10:22:00Z");

    // First call should be 10:22, with nextOccurrence at 10:37
    Instant next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T10:22:00Z");
    assertEquals(trigger.getNextOccurrence().toString(), "2024-01-03T10:37:00Z");

    // Update clock to 10:22.
    // Next execution should be at 10:37, with nextOccurrence at 10:52
    trigger.setClock(Clock.fixed(next, ZoneId.of("UTC")));
    next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T10:37:00Z");
    assertEquals(trigger.getNextOccurrence().toString(), "2024-01-03T10:52:00Z");
}

@Test
@DisplayName("Test notification trigger for minute reminders with a start date in the future.")
void testMinutesRemindersStartInFuture() {
    final SimpleTriggerContext triggerContext = new SimpleTriggerContext();

    // Set current date-time to 3/1/2024 10:15am
    final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
    final Instant currentTime = Instant.from(formatter.parse("2024-01-03T10:15:00.00Z"));

    final NotificationTrigger trigger = new NotificationTrigger();
    trigger.setClock(Clock.fixed(currentTime, ZoneId.of("UTC")));

    // Set start date-time to 3/1/2024 11:00am
    final Reminder reminder = createReminder("2024-01-03T11:00:00.00Z");

    final MinutesRecurrencePattern minutesRecurrencePattern = new MinutesRecurrencePattern("minute", 30);

    final Recurrence recurrence = new Recurrence(minutesRecurrencePattern, new RecurrenceRange());
    reminder.getSchedule().setRecurrence(recurrence);
    trigger.setReminderTask(reminder);

    // Start is in the future, so the first notification is at the start time.
    assertEquals(trigger.getNextOccurrence().toString(), "2024-01-03T11:00:00Z");

    // First call should be 11:00, with nextOccurrence at 11:30
    Instant next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T11:00:00Z");
    assertEquals(trigger.getNextOccurrence().toString(), "2024-01-03T11:30:00Z");

    // Update clock to 11:00.
    // Next execution should be at 11:30, with nextOccurrence at 12:00
    trigger.setClock(Clock.fixed(next, ZoneId.of("UTC")));
    next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T11:30:00Z");
    assertEquals(trigger.getNextOccurrence().toString(), "2024-01-03T12:00:00Z");
}

@Test
@DisplayName("Test notification trigger for minute reminders that don't divide evenly into an hour.")
void testMinutesRemindersAcrossHourBoundary() {
    final SimpleTriggerContext triggerContext = new SimpleTriggerContext();

    // Set current date-time to 3/1/2024 10:15am
    final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
    final Instant currentTime = Instant.from(formatter.parse("2024-01-03T10:15:00.00Z"));

    final NotificationTrigger trigger = new NotificationTrigger();
    trigger.setClock(Clock.fixed(currentTime, ZoneId.of("UTC")));

    // Set start date-time to 3/1/2024 10:00am
    final Reminder reminder = createReminder("2024-01-03T10:00:00.00Z");

    final MinutesRecurrencePattern minutesRecurrencePattern = new MinutesRecurrencePattern("minute", 7);

    final Recurrence recurrence = new Recurrence(minutesRecurrencePattern, new RecurrenceRange());
    reminder.getSchedule().setRecurrence(recurrence);
    trigger.setReminderTask(reminder);

    // Every 7 minutes starting at 10:00 means trigger on 10:00, 10:07, 10:14, 10:21 ...
    // If current time is 10:15, then nextExecution should be at 10:21.
    assertEquals(trigger.getNextOccurrence().toString(), "2024-01-03T10:21:00Z");

    // Gaps must stay exactly 7 minutes across the 11:00 boundary
    // (a cron "*/7" would wrongly jump from 10:56 to 11:00).
    final String[] expected = {
            "2024-01-03T10:21:00Z",
            "2024-01-03T10:28:00Z",
            "2024-01-03T10:35:00Z",
            "2024-01-03T10:42:00Z",
            "2024-01-03T10:49:00Z",
            "2024-01-03T10:56:00Z",
            "2024-01-03T11:03:00Z",
            "2024-01-03T11:10:00Z"
    };
    for (final String expectedTime : expected) {
        final Instant next = trigger.nextExecution(triggerContext);
        assertNotNull(next);
        assertEquals(next.toString(), expectedTime);
        trigger.setClock(Clock.fixed(next, ZoneId.of("UTC")));
    }
}

@Test
@DisplayName("Test notification trigger for minute reminders that end after a number of occurrences.")
void testMinutesRemindersEndAfter() {
    final SimpleTriggerContext triggerContext = new SimpleTriggerContext();

    // Set current date-time to 3/1/2024 10:15am
    final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
    final Instant currentTime = Instant.from(formatter.parse("2024-01-03T10:15:00.00Z"));

    final NotificationTrigger trigger = new NotificationTrigger();
    trigger.setClock(Clock.fixed(currentTime, ZoneId.of("UTC")));

    // Set start date-time to 3/1/2024 11:00am
    final Reminder reminder = createReminder("2024-01-03T11:00:00.00Z");

    final MinutesRecurrencePattern minutesRecurrencePattern = new MinutesRecurrencePattern("minute", 10);

    // Repeat 3 times only
    final RecurrenceRange range = new RecurrenceRange();
    range.setEndAfter(3);

    final Recurrence recurrence = new Recurrence(minutesRecurrencePattern, range);
    reminder.getSchedule().setRecurrence(recurrence);
    trigger.setReminderTask(reminder);

    // Should fire at 11:00, 11:10, 11:20 and then stop.
    Instant next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T11:00:00Z");

    trigger.setClock(Clock.fixed(next, ZoneId.of("UTC")));
    next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T11:10:00Z");

    trigger.setClock(Clock.fixed(next, ZoneId.of("UTC")));
    next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T11:20:00Z");

    // Fourth call returns null - no more occurrences
    trigger.setClock(Clock.fixed(next, ZoneId.of("UTC")));
    next = trigger.nextExecution(triggerContext);
    assertNull(next);
}

@Test
@DisplayName("Test notification trigger for minute reminders that end by a date-time.")
void testMinutesRemindersEndBy() {
    final SimpleTriggerContext triggerContext = new SimpleTriggerContext();

    // Set current date-time to 3/1/2024 10:15am
    final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
    final Instant currentTime = Instant.from(formatter.parse("2024-01-03T10:15:00.00Z"));

    final NotificationTrigger trigger = new NotificationTrigger();
    trigger.setClock(Clock.fixed(currentTime, ZoneId.of("UTC")));

    // Set start date-time to 3/1/2024 11:00am
    final Reminder reminder = createReminder("2024-01-03T11:00:00.00Z");

    final MinutesRecurrencePattern minutesRecurrencePattern = new MinutesRecurrencePattern("minute", 10);

    // End by 3/1/2024 11:25am
    final RecurrenceRange range = new RecurrenceRange();
    range.setEndByUTC(OffsetDateTime.parse("2024-01-03T11:25:00Z"));

    final Recurrence recurrence = new Recurrence(minutesRecurrencePattern, range);
    reminder.getSchedule().setRecurrence(recurrence);
    trigger.setReminderTask(reminder);

    // Should fire at 11:00, 11:10, 11:20. 11:30 is after the end time, so it stops.
    Instant next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T11:00:00Z");

    trigger.setClock(Clock.fixed(next, ZoneId.of("UTC")));
    next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T11:10:00Z");

    trigger.setClock(Clock.fixed(next, ZoneId.of("UTC")));
    next = trigger.nextExecution(triggerContext);
    assertNotNull(next);
    assertEquals(next.toString(), "2024-01-03T11:20:00Z");

    trigger.setClock(Clock.fixed(next, ZoneId.of("UTC")));
    next = trigger.nextExecution(triggerContext);
    assertNull(next);
}

// Only include this test if you kept the minutes guard in setupCronExpression.
@Test
@DisplayName("Test notification trigger rejects a minute reminder of 0 minutes.")
void testMinutesRemindersZeroMinutesRejected() {
    final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
    final Instant currentTime = Instant.from(formatter.parse("2024-01-03T10:15:00.00Z"));

    final NotificationTrigger trigger = new NotificationTrigger();
    trigger.setClock(Clock.fixed(currentTime, ZoneId.of("UTC")));

    final Reminder reminder = createReminder("2024-01-03T09:00:00.00Z");

    final MinutesRecurrencePattern minutesRecurrencePattern = new MinutesRecurrencePattern("minute", 0);

    final Recurrence recurrence = new Recurrence(minutesRecurrencePattern, new RecurrenceRange());
    reminder.getSchedule().setRecurrence(recurrence);

    assertThrows(IllegalArgumentException.class, () -> trigger.setReminderTask(reminder));
}
