// =====================================================================================
// NotificationTrigger.java - minutes recurrence support (JTTHFC2-6527 follow-up)
//
// Reconstructed from a screen recording, so typos are possible in the UNCHANGED code.
// Safest approach: copy only the blocks marked "// NEW" / "// CHANGED" into your file.
//
// Package and imports: keep your existing ones. Make sure these are present:
//   import java.time.temporal.ChronoUnit;   // used by the NEW MINUTES case
// =====================================================================================

/**
 * Class to create notification-triggers from a reminder schedule or notification postponement.
 */
public class NotificationTrigger implements Trigger {

    private enum RecurTypes {
        NONE,
        MINUTES,
        HOURS,
        DAYS,
        WEEKS,
        MONTHS,
        YEARS
    }

    private enum CronDayOfWeek {
        MONDAY(1),
        TUESDAY(2),
        WEDNESDAY(3),
        THURSDAY(4),
        FRIDAY(5),
        SATURDAY(6),
        SUNDAY(7);

        final int value;

        CronDayOfWeek(final int val) { value = val; }

        public static String valueOf(final CronDayOfWeek dow) { return String.valueOf(dow.value); }
    }

    /*
     * Map of DayInstance enums to cron equivalents.
     */
    private static final Map<DayInstance, String> dayInstanceMap = new HashMap<>();

    static {
        dayInstanceMap.put(DayInstance.FIRST, "#1");
        dayInstanceMap.put(DayInstance.SECOND, "#2");
        dayInstanceMap.put(DayInstance.THIRD, "#3");
        dayInstanceMap.put(DayInstance.FOURTH, "#4");
        dayInstanceMap.put(DayInstance.LAST, "L");
    }

    /*
     * Map of DayOfWeek enums to cron equivalents.
     */
    private static final Map<DayOfWeek, Integer> dayOfWeekMap = new HashMap<>();

    static {
        dayOfWeekMap.put(DayOfWeek.MON, 1);
        dayOfWeekMap.put(DayOfWeek.TUE, 2);
        dayOfWeekMap.put(DayOfWeek.WED, 3);
        dayOfWeekMap.put(DayOfWeek.THU, 4);
        dayOfWeekMap.put(DayOfWeek.FRI, 5);
        dayOfWeekMap.put(DayOfWeek.SAT, 6);
        dayOfWeekMap.put(DayOfWeek.SUN, 7);
    }

    private Duration duration;
    private int occurrencesRemaining;
    private CronExpression cronExpression;
    private String cronString;
    private Instant startDateTime;
    private Instant endDateTime;
    private RecurTypes recurType;
    private int recurCount;

    /**
     * Return whether this trigger is a recurring trigger.
     */
    @Getter
    private boolean recurring;

    /**
     * Return the next occurrence calculated by this trigger.
     */
    @Getter
    private Instant nextOccurrence;

    /**
     * The runnable that is executed when a trigger is completed.
     */
    @Setter
    private Runnable onCompleted;

    /**
     * The clock to use for this notification trigger. By default, this is the system UTC based
     * clock. -- SETTER -- Allow the system clock to be overridden for debugging purposes.
     */
    @Setter
    private Clock clock = Clock.systemUTC();

    /**
     * Setup a simple trigger for postponing a notification.
     *
     * @param seconds The number of seconds before the trigger is fired.
     */
    public void setNextExecution(final int seconds) {
        this.duration = Duration.ofSeconds(seconds);
        this.occurrencesRemaining = 1;
        this.cronString = null;
        this.cronExpression = null;
        this.startDateTime = null;
        this.endDateTime = null;
        this.recurring = false;
        this.nextOccurrence = Instant.now(clock).plusSeconds(seconds);
        this.onCompleted = null;
        this.recurType = RecurTypes.NONE;
        this.recurCount = 0;
    }

    /**
     * Create the cron string and corresponding parsed cron expression if applicable.
     *
     * @param reminder The reminder containing the scheduling information.
     */
    void setupCronExpression(final Reminder reminder) {

        cronString = null;
        cronExpression = null;
        recurType = RecurTypes.NONE;
        recurCount = 0;
        startDateTime = reminder.getSchedule().getStartUTC().toInstant();
        endDateTime = null;
        onCompleted = null;
        duration = Duration.between(Instant.now(clock), startDateTime);

        // Ignore end timestamp if it is before the specified start.
        final Schedule schedule = reminder.getSchedule();
        if (schedule.getRecurrence() != null
                && schedule.getRecurrence().getRange().getEndByUTC() != null) {
            endDateTime = schedule.getRecurrence().getRange().getEndByUTC().toInstant();
        }

        if (endDateTime != null && endDateTime.isBefore(startDateTime)) {
            endDateTime = null;
        }

        if (schedule.getRecurrence() == null) {
            // The reminder will activate once at the specified startDateTime.
            return;
        }

        // Construct a cron string and expression
        final Recurrence recurrence = schedule.getRecurrence();
        if (recurrence.getPattern() instanceof final DailyRecurrencePattern dailyRecurrencePattern) {
            // Recurrence is to issue a notification every number of days.
            recurType = RecurTypes.DAYS;
            recurCount = dailyRecurrencePattern.getDays();
        } else if (recurrence.getPattern() instanceof final MinutesRecurrencePattern minutesRecurrencePattern) {
            // Recurrence is to issue a notification every N minutes.                       // CHANGED (comment)
            // No cron is used: cron steps reset every hour, so e.g. "every 7 minutes"     // NEW
            // can't be expressed. Fixed-interval handling is used instead, like DAYS.     // NEW
            final Integer minutes = minutesRecurrencePattern.getMinutes();                  // NEW
            if (minutes == null || minutes < 1) {                                            // NEW
                throw new IllegalArgumentException("Minutes recurrence must be at least 1"); // NEW
            }                                                                                // NEW
            recurType = RecurTypes.MINUTES;
            recurCount = minutes;                                                            // CHANGED
        } else if (recurrence.getPattern() instanceof final WeeklyRecurrencePattern weeklyRecurrencePattern) {
            cronString = createWeeklyPattern(weeklyRecurrencePattern, schedule.getStartUTC());
        } else if (recurrence.getPattern() instanceof final MonthlyRecurrencePattern monthlyRecurrencePattern) {
            cronString = createMonthlyPattern(monthlyRecurrencePattern, schedule.getStartUTC());
        } else if (recurrence.getPattern() instanceof final YearlyRecurrencePattern yearlyRecurrencePattern) {
            cronString = createYearlyPattern(yearlyRecurrencePattern, schedule.getStartUTC());
        }

        if (cronString != null) {
            cronExpression = CronExpression.parse(cronString);
        }
    }

    /**
     * Create the weekly cron expression from the weekly recurrence pattern and start date-time.
     *
     * @param weeklyRecurrencePattern Contains the days of the week to be notified.
     * @param startDateTime           Contains the minute and hour of the days of the week to be notified.
     * @return The cron expression on success or null if no days of the week were specified.
     */
    String createWeeklyPattern(
            final WeeklyRecurrencePattern weeklyRecurrencePattern, final OffsetDateTime startDateTime) {
        this.recurType = RecurTypes.WEEKS;
        this.recurCount = weeklyRecurrencePattern.getWeeks();
        final List<CronDayOfWeek> dayList = new ArrayList<>();
        if (Boolean.TRUE.equals(weeklyRecurrencePattern.getDays().getMon())) dayList.add(CronDayOfWeek.MONDAY);
        if (Boolean.TRUE.equals(weeklyRecurrencePattern.getDays().getTue())) dayList.add(CronDayOfWeek.TUESDAY);
        if (Boolean.TRUE.equals(weeklyRecurrencePattern.getDays().getWed())) dayList.add(CronDayOfWeek.WEDNESDAY);
        if (Boolean.TRUE.equals(weeklyRecurrencePattern.getDays().getThu())) dayList.add(CronDayOfWeek.THURSDAY);
        if (Boolean.TRUE.equals(weeklyRecurrencePattern.getDays().getFri())) dayList.add(CronDayOfWeek.FRIDAY);
        if (Boolean.TRUE.equals(weeklyRecurrencePattern.getDays().getSat())) dayList.add(CronDayOfWeek.SATURDAY);
        if (Boolean.TRUE.equals(weeklyRecurrencePattern.getDays().getSun())) dayList.add(CronDayOfWeek.SUNDAY);

        if (dayList.size() == 0) {
            return null;
        }

        final String dayListString =
                dayList.stream().map(CronDayOfWeek::valueOf).collect(Collectors.joining(","));

        return String.format("0 %d %d ? * %s", startDateTime.getMinute(), startDateTime.getHour(), dayListString);
    }

    /**
     * Create the monthly cron expression from the monthly recurrence pattern and start date-time.
     *
     * @param monthlyRecurrencePattern Contains the day of month information.
     * @param startDateTime            Contains the minute and hour of the day of month to be notified.
     * @return The cron expression on success or null if no monthly information was provided.
     */
    String createMonthlyPattern(
            final MonthlyRecurrencePattern monthlyRecurrencePattern, final OffsetDateTime startDateTime) {
        this.recurType = RecurTypes.MONTHS;
        this.recurCount = monthlyRecurrencePattern.getMonths();

        if (monthlyRecurrencePattern.getOnDay() != null) {
            final MonthlyRecurrencePatternOnDay onDay = monthlyRecurrencePattern.getOnDay();
            final String dayType = dayInstanceMap.get(onDay.getDayInstance());
            final int dayOfWeek = dayOfWeekMap.get(onDay.getDay());

            return String.format(
                    "0 %d %d ? * %d%s", startDateTime.getMinute(), startDateTime.getHour(), dayOfWeek, dayType);
        }

        final MonthlyRecurrencePatternOnDate onDate = monthlyRecurrencePattern.getOnDate();
        if (onDate == null) {
            return null;
        }

        return String.format("0 %d %d %d * ?", startDateTime.getMinute(), startDateTime.getHour(), onDate.getDate());
    }

    /**
     * Create the yearly cron expression from the yearly recurrence pattern and start date-time.
     *
     * @param yearlyRecurrencePattern Contains the day of year to be notified.
     * @param startDateTime           Contains the minute and hour of the day of the year to be notified.
     * @return The cron expression on success or null if no yearly information was provided.
     */
    String createYearlyPattern(
            final YearlyRecurrencePattern yearlyRecurrencePattern, final OffsetDateTime startDateTime) {
        this.recurType = RecurTypes.YEARS;
        this.recurCount = yearlyRecurrencePattern.getYears();

        if (yearlyRecurrencePattern.getOnDay() != null) {
            final YearlyRecurrencePatternOnDay onDay = yearlyRecurrencePattern.getOnDay();
            final int dayOfWeek = dayOfWeekMap.get(onDay.getDay());
            final String dayType = dayInstanceMap.get(onDay.getDayInstance());
            final int monthOfYear = onDay.getMonth();

            return String.format(
                    "0 %d %d ? %d %d%s",
                    startDateTime.getMinute(), startDateTime.getHour(), monthOfYear, dayOfWeek, dayType);
        }

        final YearlyRecurrencePatternOnDate onDate = yearlyRecurrencePattern.getOnDate();
        if (onDate == null) {
            return null;
        }
        final int dayOfMonth = onDate.getDate();
        final int monthOfYear = onDate.getMonth();

        return String.format(
                "0 %d %d %d %d ?", startDateTime.getMinute(), startDateTime.getHour(), dayOfMonth, monthOfYear);
    }

    /**
     * Set up a custom trigger using the instructions defined in the given reminder.
     *
     * @param reminder Contains instructions for when to fire the trigger. Allows for recurring
     *                 reminders starting from a specified start date-time along with other custom scheduling
     *                 for minute-based, daily, weekly, monthly and yearly recurrences.      // CHANGED (doc)
     */
    public void setupTrigger(@NotNull final Reminder reminder) {   // <-- keep your real method name here
        final Instant now = Instant.now(clock);

        setupCronExpression(reminder);

        if (recurType == RecurTypes.NONE) {
            // Once off reminder is just triggered once at start date/time.
            // If start date/time is in the past, then don't do a reminder.
            occurrencesRemaining = duration.isNegative() ? 0 : 1;
            cronExpression = null;
            nextOccurrence = startDateTime;
        } else {
            // If start date is in the past then calculate the next suitable point in time.
            if (duration.isNegative()) {
                Instant nextTime;
                LocalDateTime next;

                switch (recurType) {
                    case MINUTES -> {                                                    // NEW
                        // Every N minutes from the start date/time.                     // NEW
                        // e.g. start 09:07, every 15, now 10:00 -> next is 10:07.       // NEW
                        nextTime = startDateTime;                                        // NEW
                        while (nextTime.isBefore(now)) {                                 // NEW
                            nextTime = nextTime.plus(recurCount, ChronoUnit.MINUTES);    // NEW
                        }                                                                // NEW
                        duration = Duration.between(now, nextTime);                      // NEW
                    }                                                                    // NEW
                    case HOURS -> {
                        // Offset by the minutes in the start date/time. So if start time is 12:17,
                        // this is an hourly task, and it is currently 4:30pm, then the next reminder
                        // will be at 5:17pm. The time to wait is the difference between now and the
                        // next reminder time.
                        next = cronExpression.next(LocalDateTime.now(clock));
                        final int minutes = startDateTime.atZone(ZoneOffset.UTC).getMinute();
                        next = Objects.requireNonNull(next).plusMinutes(minutes);
                        duration = Duration.between(LocalDateTime.now(clock), next);
                        // If current time is 5:00, then cron next will return 6:00 so bring the
                        // time back within the hour.
                        if (duration.toSeconds() > TimeUnit.HOURS.toSeconds(1)) {
                            duration = duration.minusSeconds(TimeUnit.HOURS.toSeconds(1));
                        }
                    }
                    case DAYS -> {
                        if (cronExpression != null) {
                            final LocalDateTime nextFromNow =
                                    cronExpression.next(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
                            duration = Duration.between(LocalDateTime.now(clock), nextFromNow);
                        } else {
                            // Recur every N days from start date/time.
                            nextTime = startDateTime;
                            while (nextTime.isBefore(now)) {
                                nextTime = nextTime.plus(recurCount, ChronoUnit.DAYS);
                            }
                            duration = Duration.between(now, nextTime);
                        }
                    }
                    case WEEKS -> {
                        if (recurCount > 1) {
                            // Get the first day of the week in startDateTime. If it is the 12/12/23
                            // then we want Sun 9/12/23 at time 0:00:00.
                            final TemporalField field =
                                    WeekFields.of(Locale.getDefault()).dayOfWeek();
                            final LocalDateTime localStart = LocalDateTime.ofInstant(startDateTime, ZoneOffset.UTC)
                                    .with(field, 1)
                                    .with(LocalTime.MIDNIGHT);

                            nextTime = localStart.toInstant(ZoneOffset.UTC);
                            while (nextTime.isBefore(now)) {
                                nextTime = nextTime.plus(recurCount * 7L, ChronoUnit.DAYS);
                            }
                        } else {
                            nextTime = now;
                        }
                        final LocalDateTime nextFromNow =
                                cronExpression.next(LocalDateTime.ofInstant(nextTime, ZoneOffset.UTC));
                        duration = Duration.between(LocalDateTime.now(clock), nextFromNow);
                    }
                    case MONTHS -> {
                        if (recurCount > 1) {
                            // Get the first day of the month in startDateTime. If it is the 12/12/23
                            // then we want Fri 1/12/23 at time 0:00:00.
                            final LocalDateTime localStart = LocalDateTime.ofInstant(startDateTime, ZoneOffset.UTC)
                                    .with(TemporalAdjusters.firstDayOfMonth())
                                    .with(LocalTime.MIDNIGHT);

                            nextTime = localStart.toInstant(ZoneOffset.UTC);
                            while (nextTime.isBefore(now)) {
                                nextTime = nextTime.atOffset(ZoneOffset.UTC)
                                        .plus(recurCount, ChronoUnit.MONTHS)
                                        .toInstant();
                            }
                        } else {
                            nextTime = now;
                        }
                        next = cronExpression.next(LocalDateTime.ofInstant(nextTime, ZoneOffset.UTC));
                        duration = Duration.between(LocalDateTime.now(clock), next);
                    }
                    case YEARS -> {
                        if (recurCount > 1) {
                            // Get the first day of the year in startDateTime. If it is the 12/12/23
                            // then we want 1/1/23 at time 0:00:00.
                            final LocalDateTime localStart = LocalDateTime.ofInstant(startDateTime, ZoneOffset.UTC)
                                    .with(TemporalAdjusters.firstDayOfYear())
                                    .with(LocalTime.MIDNIGHT);

                            nextTime = localStart.toInstant(ZoneOffset.UTC);
                            while (nextTime.isBefore(now)) {
                                nextTime = nextTime.atOffset(ZoneOffset.UTC)
                                        .plus(recurCount, ChronoUnit.YEARS)
                                        .toInstant();
                            }
                        } else {
                            nextTime = now;
                        }
                        next = cronExpression.next(LocalDateTime.ofInstant(nextTime, ZoneOffset.UTC));
                        duration = Duration.between(LocalDateTime.now(clock), next);
                    }
                    default -> {
                        // Evaluate all other types as cron expressions.
                        next = cronExpression.next(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
                        duration = Duration.between(LocalDateTime.now(clock), next);
                    }
                }
            }

            // Save the occurrences remaining. If there is no specified number of
            // occurrences set, then simplify the logic by setting this to INT_MAX,
            // which should be large enough to effectively be infinite.
            occurrencesRemaining = 0;
            assert reminder.getSchedule().getRecurrence() != null;
            if (reminder.getSchedule().getRecurrence().getRange().getEndAfter() != null) {
                occurrencesRemaining =
                        reminder.getSchedule().getRecurrence().getRange().getEndAfter();
            }
            if (occurrencesRemaining == 0) {
                occurrencesRemaining = Integer.MAX_VALUE;
            }

            recurring = recurType != RecurTypes.NONE;
            nextOccurrence = now.plus(duration);
        }

        reminder.getSchedule().setNextExecutionUTC(nextOccurrence.atOffset(ZoneOffset.UTC));
    }

    /**
     * Calculate and return the next time to execute the associated scheduled task.
     *
     * @param triggerContext Context encapsulating last execution times. Not used.
     * @return The next execution time as calculated by the trigger. Return null when the trigger is
     *         no longer going to fire.
     */
    @Override
    @Nullable
    public Instant nextExecution(final TriggerContext triggerContext) {

        if (occurrencesRemaining <= 0) {
            if (onCompleted != null) {
                onCompleted.run();
            }
            return null;
        }
        occurrencesRemaining -= 1;

        final Instant next = Instant.now(clock).plus(duration);
        if (endDateTime != null && next.isAfter(endDateTime)) {
            // Completed since the next notification will be after
            // the specified end date/time
            if (onCompleted != null) {
                onCompleted.run();
            }
            return null;
        }

        // Adjust duration for recurring executions.
        if (occurrencesRemaining > 0) {
            if (recurType == RecurTypes.MINUTES) {                                   // NEW
                // Fixed interval: fire again in N minutes.                         // NEW
                duration = Duration.ofMinutes(recurCount);                           // NEW
            } else if (recurType == RecurTypes.HOURS) {                              // CHANGED ("if" -> "else if")
                duration = Duration.ofSeconds(TimeUnit.HOURS.toSeconds(1));
            } else if (recurType == RecurTypes.DAYS) {
                if (cronExpression != null) {
                    final LocalDateTime nextExecution =
                            cronExpression.next(LocalDateTime.now(clock).plus(duration));
                    duration = Duration.between(LocalDateTime.ofInstant(next, ZoneOffset.UTC), nextExecution);
                } else {
                    duration = Duration.ofDays(recurCount);
                }
            } else if (recurType == RecurTypes.WEEKS) {
                LocalDateTime nextExecution =
                        cronExpression.next(LocalDateTime.now(clock).plus(duration));

                if (recurCount > 1) {
                    // Get the first day of the week for "next".
                    final TemporalField field =
                            WeekFields.of(Locale.getDefault()).dayOfWeek();
                    final LocalDateTime firstDayWeekNext = LocalDateTime.ofInstant(next, ZoneOffset.UTC)
                            .with(field, 1)
                            .with(LocalTime.MIDNIGHT);

                    // Get the first day of the week for "nextExecution".
                    assert nextExecution != null;
                    final LocalDateTime firstDayWeekNextExec =
                            nextExecution.with(field, 1).with(LocalTime.MIDNIGHT);

                    // If next exec has moved to the next week then add another (n-1) more weeks.
                    if (!firstDayWeekNext.isEqual(firstDayWeekNextExec)) {
                        nextExecution = nextExecution.plusWeeks(recurCount - 1);
                    }
                }

                duration = Duration.between(LocalDateTime.ofInstant(next, ZoneOffset.UTC), nextExecution);
            } else if (recurType == RecurTypes.MONTHS) {
                LocalDateTime nextExecution =
                        cronExpression.next(LocalDateTime.now(clock).plus(duration));

                if (recurCount > 1) {
                    // Get the first day of the month for "next".
                    final LocalDateTime firstDayMonthNext = LocalDateTime.ofInstant(next, ZoneOffset.UTC)
                            .with(TemporalAdjusters.firstDayOfMonth())
                            .with(LocalTime.MIDNIGHT);

                    // Get the first day of the month for "nextExecution".
                    assert nextExecution != null;
                    final LocalDateTime firstDayMonthNextExec = nextExecution
                            .with(TemporalAdjusters.firstDayOfMonth())
                            .with(LocalTime.MIDNIGHT);

                    // If next exec has moved to the next month then add another (n-1) more months.
                    if (!firstDayMonthNext.isEqual(firstDayMonthNextExec)) {
                        nextExecution = cronExpression.next(firstDayMonthNextExec.plusMonths(recurCount - 1));
                    }
                }

                duration = Duration.between(LocalDateTime.ofInstant(next, ZoneOffset.UTC), nextExecution);
            } else if (recurType == RecurTypes.YEARS) {
                LocalDateTime nextExecution =
                        cronExpression.next(LocalDateTime.now(clock).plus(duration));

                if (recurCount > 1) {
                    // Get the first day of the year for "next".
                    final LocalDateTime firstDayYearNext = LocalDateTime.ofInstant(next, ZoneOffset.UTC)
                            .with(TemporalAdjusters.firstDayOfYear())
                            .with(LocalTime.MIDNIGHT);

                    // Get the first day of the year for "nextExecution".
                    assert nextExecution != null;
                    final LocalDateTime firstDayYearNextExec = nextExecution
                            .with(TemporalAdjusters.firstDayOfYear())
                            .with(LocalTime.MIDNIGHT);

                    // If next exec has moved to the next year then add another (n-1) more years.
                    if (!firstDayYearNext.isEqual(firstDayYearNextExec)) {
                        nextExecution = cronExpression.next(firstDayYearNextExec.plusYears(recurCount - 1));
                    }
                }

                duration = Duration.between(LocalDateTime.ofInstant(next, ZoneOffset.UTC), nextExecution);
            } else {
                // Calculate next time from the cron expression.
                final LocalDateTime nextExecution =
                        cronExpression.next(LocalDateTime.now(clock).plus(duration));
                duration = Duration.between(LocalDateTime.ofInstant(next, ZoneOffset.UTC), nextExecution);
            }
        }

        if (recurring) {
            nextOccurrence = next.plus(duration);
        }

        return next;
    }
}
