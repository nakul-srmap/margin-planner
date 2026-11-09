package planner.engine.model;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A recurring weekly timetable entry (lecture, tutorial or lab).
 *
 * <p>The scheduler treats these as immovable: study sessions are never placed on top of them.
 */
public record ClassSession(DayOfWeek day, LocalTime start, LocalTime end,
                           String subjectCode, String faculty, String room, boolean lab) {

    public ClassSession {
        if (day == null) {
            throw new IllegalArgumentException("Day of week is required");
        }
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("Class must end after it starts: " + start + "-" + end);
        }
    }

    public static ClassSession lecture(DayOfWeek day, int startHour, int endHour, String subjectCode) {
        return new ClassSession(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0),
                subjectCode, null, null, false);
    }

    public static ClassSession lab(DayOfWeek day, int startHour, int endHour, String subjectCode) {
        return new ClassSession(day, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0),
                subjectCode, null, null, true);
    }

    public TimeWindow window() {
        return new TimeWindow(start, end);
    }

    /** True when two entries claim the same room-time or student-time, i.e. a timetable clash. */
    public boolean conflictsWith(ClassSession other) {
        return day == other.day && window().overlaps(other.window());
    }
}
