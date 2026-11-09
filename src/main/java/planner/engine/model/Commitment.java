package planner.engine.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A non-academic block the student is unavailable for — commute, job, sport, family time.
 *
 * <p>Supports both a weekly recurring form (give {@code day}, leave {@code date} null) and a
 * one-off form (give {@code date}, leave {@code day} null). The scheduler subtracts these from
 * the available day exactly as it does class sessions.
 */
public record Commitment(String title, DayOfWeek day, LocalDate date,
                         LocalTime start, LocalTime end) {

    public Commitment {
        if (day == null && date == null) {
            throw new IllegalArgumentException("Commitment needs either a weekday or a date");
        }
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("Commitment must end after it starts");
        }
    }

    public static Commitment weekly(String title, DayOfWeek day, int startHour, int endHour) {
        return new Commitment(title, day, null, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }

    public static Commitment onDate(String title, LocalDate date, LocalTime start, LocalTime end) {
        return new Commitment(title, null, date, start, end);
    }

    public boolean appliesTo(LocalDate candidate) {
        return date != null ? date.equals(candidate) : day == candidate.getDayOfWeek();
    }

    public TimeWindow window() {
        return new TimeWindow(start, end);
    }
}
