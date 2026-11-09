package planner.engine.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One study block the engine has committed to the plan.
 *
 * @param sessionIndex which part of a split task this is, 1-based
 * @param sessionCount how many parts the task was split into
 * @param explanation  why this task was placed in this slot, shown verbatim in the UI
 */
public record ScheduledSession(String taskId, String title, String subjectCode,
                               LocalDate date, LocalTime start, LocalTime end,
                               double priorityScore, boolean revision,
                               int sessionIndex, int sessionCount, String explanation) {

    public int minutes() {
        return (end.toSecondOfDay() - start.toSecondOfDay()) / 60;
    }

    public String label() {
        return sessionCount > 1
                ? "%s (part %d of %d)".formatted(title, sessionIndex, sessionCount)
                : title;
    }

    public boolean overlaps(ScheduledSession other) {
        return date.equals(other.date)
                && start.isBefore(other.end)
                && other.start.isBefore(end);
    }
}
