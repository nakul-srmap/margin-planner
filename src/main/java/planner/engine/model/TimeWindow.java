package planner.engine.model;

import java.time.LocalTime;

/** A start/end time pair within a single day. */
public record TimeWindow(LocalTime start, LocalTime end) {

    public TimeWindow {
        if (start == null || end == null) {
            throw new IllegalArgumentException("Window bounds are required");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Window end must be after start: " + start + "-" + end);
        }
    }

    public static TimeWindow of(int startHour, int endHour) {
        return new TimeWindow(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }

    public boolean contains(LocalTime time) {
        return !time.isBefore(start) && time.isBefore(end);
    }

    /** True when this window shares at least one minute with {@code other}. */
    public boolean overlaps(TimeWindow other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public int minutes() {
        return (end.toSecondOfDay() - start.toSecondOfDay()) / 60;
    }
}
