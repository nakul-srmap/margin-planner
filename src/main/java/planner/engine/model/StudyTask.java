package planner.engine.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A unit of academic work the scheduler has to find time for.
 *
 * <p>This is the engine's own task representation, deliberately free of JPA and Spring
 * annotations so the scheduling algorithm can be compiled, unit-tested and reasoned about
 * in isolation from the persistence layer. The service layer maps the {@code Task} entity
 * onto this type before scheduling.
 */
public class StudyTask {

    private final String id;
    private final String title;
    private final String subjectCode;
    private final String category;
    private final LocalDateTime deadline;
    private final Priority priority;
    private final int estimatedMinutes;

    private int completedMinutes;
    private int missedSessions;
    private boolean completed;
    private boolean splittable = true;
    private final List<String> tags = new ArrayList<>();

    public StudyTask(String id, String title, String subjectCode, String category,
                     LocalDateTime deadline, Priority priority, int estimatedMinutes) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Task id is required");
        }
        if (estimatedMinutes <= 0) {
            throw new IllegalArgumentException("Estimated duration must be positive for task " + id);
        }
        this.id = id;
        this.title = title;
        this.subjectCode = subjectCode;
        this.category = category == null ? "GENERAL" : category;
        this.deadline = deadline;
        this.priority = priority == null ? Priority.MEDIUM : priority;
        this.estimatedMinutes = estimatedMinutes;
    }

    /** Convenience factory for the common assignment case. */
    public static StudyTask assignment(String id, String title, String subjectCode,
                                       LocalDateTime deadline, Priority priority, int minutes) {
        return new StudyTask(id, title, subjectCode, "ASSIGNMENT", deadline, priority, minutes);
    }

    public String id() { return id; }

    public String title() { return title; }

    public String subjectCode() { return subjectCode; }

    public String category() { return category; }

    public LocalDateTime deadline() { return deadline; }

    public Priority priority() { return priority; }

    public int estimatedMinutes() { return estimatedMinutes; }

    public int completedMinutes() { return completedMinutes; }

    public void setCompletedMinutes(int completedMinutes) {
        this.completedMinutes = Math.max(0, completedMinutes);
    }

    public int missedSessions() { return missedSessions; }

    public void setMissedSessions(int missedSessions) {
        this.missedSessions = Math.max(0, missedSessions);
    }

    public boolean isCompleted() { return completed; }

    public void setCompleted(boolean completed) { this.completed = completed; }

    public boolean isSplittable() { return splittable; }

    public void setSplittable(boolean splittable) { this.splittable = splittable; }

    public List<String> tags() { return tags; }

    public StudyTask withTags(String... values) {
        tags.addAll(List.of(values));
        return this;
    }

    /** Work still outstanding, in minutes. */
    public int remainingMinutes() {
        return Math.max(0, estimatedMinutes - completedMinutes);
    }

    public double progressFraction() {
        return estimatedMinutes == 0 ? 0.0
                : Math.min(1.0, completedMinutes / (double) estimatedMinutes);
    }

    public boolean isOverdue(LocalDateTime now) {
        return !completed && deadline != null && deadline.isBefore(now);
    }

    /** Hours between {@code now} and the deadline; negative once overdue. */
    public double hoursUntilDeadline(LocalDateTime now) {
        if (deadline == null) {
            return Double.MAX_VALUE;
        }
        return Duration.between(now, deadline).toMinutes() / 60.0;
    }

    @Override
    public String toString() {
        return "%s [%s] %d min due %s".formatted(title, subjectCode, remainingMinutes(), deadline);
    }
}
