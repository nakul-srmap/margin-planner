package planner.engine.model;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The student's availability and study-habit settings. Every constraint the scheduler
 * enforces on a realistic plan comes from here, so these values are what stop the engine
 * producing a 14-hour study day.
 */
public class UserPreferences {

    private LocalTime wakeTime = LocalTime.of(7, 0);
    private LocalTime sleepTime = LocalTime.of(23, 0);
    private int maxDailyStudyMinutes = 300;
    private int minSessionMinutes = 25;
    private int maxSessionMinutes = 90;
    private int breakMinutes = 10;
    private int focusMinutes = 25;
    private boolean weekendAvailable = true;
    private int maxHardSessionsPerDay = 2;

    /** Windows the student actually prefers to study in. Treated as a soft preference. */
    private final List<TimeWindow> preferredWindows = new ArrayList<>();

    public static UserPreferences defaults() {
        UserPreferences prefs = new UserPreferences();
        prefs.preferredWindows.add(TimeWindow.of(9, 12));
        prefs.preferredWindows.add(TimeWindow.of(17, 21));
        return prefs;
    }

    public LocalTime wakeTime() { return wakeTime; }

    public UserPreferences setWakeTime(LocalTime wakeTime) {
        this.wakeTime = wakeTime;
        return this;
    }

    public LocalTime sleepTime() { return sleepTime; }

    public UserPreferences setSleepTime(LocalTime sleepTime) {
        this.sleepTime = sleepTime;
        return this;
    }

    public int maxDailyStudyMinutes() { return maxDailyStudyMinutes; }

    public UserPreferences setMaxDailyStudyMinutes(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Max daily study minutes must be positive");
        }
        this.maxDailyStudyMinutes = value;
        return this;
    }

    public int minSessionMinutes() { return minSessionMinutes; }

    public UserPreferences setMinSessionMinutes(int value) {
        this.minSessionMinutes = value;
        return this;
    }

    public int maxSessionMinutes() { return maxSessionMinutes; }

    public UserPreferences setMaxSessionMinutes(int value) {
        this.maxSessionMinutes = value;
        return this;
    }

    public int breakMinutes() { return breakMinutes; }

    public UserPreferences setBreakMinutes(int value) {
        this.breakMinutes = value;
        return this;
    }

    public int focusMinutes() { return focusMinutes; }

    public UserPreferences setFocusMinutes(int value) {
        this.focusMinutes = value;
        return this;
    }

    public boolean weekendAvailable() { return weekendAvailable; }

    public UserPreferences setWeekendAvailable(boolean value) {
        this.weekendAvailable = value;
        return this;
    }

    public int maxHardSessionsPerDay() { return maxHardSessionsPerDay; }

    public UserPreferences setMaxHardSessionsPerDay(int value) {
        this.maxHardSessionsPerDay = value;
        return this;
    }

    public List<TimeWindow> preferredWindows() { return preferredWindows; }

    public UserPreferences addPreferredWindow(TimeWindow window) {
        this.preferredWindows.add(window);
        return this;
    }

    /**
     * Validates the settings against each other. Called by the engine so that a contradictory
     * configuration surfaces as a clear error instead of an empty schedule.
     */
    public void validate() {
        if (!sleepTime.isAfter(wakeTime)) {
            throw new IllegalStateException(
                    "Sleep time (%s) must be after wake time (%s)".formatted(sleepTime, wakeTime));
        }
        if (minSessionMinutes > maxSessionMinutes) {
            throw new IllegalStateException(
                    "Minimum session length (%d) exceeds the maximum (%d)"
                            .formatted(minSessionMinutes, maxSessionMinutes));
        }
        int awakeMinutes = (sleepTime.toSecondOfDay() - wakeTime.toSecondOfDay()) / 60;
        if (maxDailyStudyMinutes > awakeMinutes) {
            throw new IllegalStateException(
                    "Max daily study (%d min) exceeds waking hours (%d min)"
                            .formatted(maxDailyStudyMinutes, awakeMinutes));
        }
    }
}
