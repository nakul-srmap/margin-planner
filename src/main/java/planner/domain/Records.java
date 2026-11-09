package planner.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * The application's data, as immutable records. Each mirrors a table; derived values are
 * computed here rather than stored, so they can never disagree with the data they come from.
 */
public final class Records {

    private Records() { }

    /** The six highlighters a subject can be drawn in. Matches the CHECK in the schema. */
    public enum Highlighter {
        yellow, pink, green, blue, orange, violet;

        public static Highlighter parse(String raw) {
            try {
                return raw == null ? yellow : valueOf(raw);
            } catch (IllegalArgumentException e) {
                return yellow;
            }
        }
    }

    public enum Priority {
        LOW("Low"), MEDIUM("Normal"), HIGH("High"), CRITICAL("Urgent");

        private final String label;

        Priority(String label) { this.label = label; }

        public String label() { return label; }

        public static Priority parse(String raw) {
            try {
                return raw == null ? MEDIUM : valueOf(raw);
            } catch (IllegalArgumentException e) {
                return MEDIUM;
            }
        }
    }

    public enum Kind {
        LECTURE("Lecture"), LAB("Lab"), TUTORIAL("Tutorial"), OTHER("Other commitment");

        private final String label;

        Kind(String label) { this.label = label; }

        public String label() { return label; }

        public boolean isClass() { return this != OTHER; }
    }

    public record Subject(long id, String code, String name, int difficulty,
                          Highlighter highlighter, double preparedness, int openTasks) { }

    public record TimetableEntry(long id, Long subjectId, String subjectCode, String subjectName,
                                 Highlighter highlighter, Kind kind, String label,
                                 DayOfWeek day, LocalTime start, LocalTime end, String room) {

        /** What to print on the block: the subject code for a class, the label otherwise. */
        public String title() {
            return kind.isClass() ? subjectCode : label;
        }

        public int minutes() {
            return (int) Duration.between(start, end).toMinutes();
        }
    }

    public record Task(long id, Long subjectId, String subjectCode, Highlighter highlighter,
                       String title, String notes, Priority priority, LocalDateTime dueAt,
                       int estimatedMinutes, int completedMinutes, int missedSessions,
                       boolean splittable, LocalDateTime doneAt) {

        public boolean done() { return doneAt != null; }

        public int remainingMinutes() { return Math.max(0, estimatedMinutes - completedMinutes); }

        public boolean overdue(LocalDateTime now) { return !done() && dueAt.isBefore(now); }

        public double progress() {
            return estimatedMinutes == 0 ? 0 : Math.min(1.0, completedMinutes / (double) estimatedMinutes);
        }
    }

    public record Exam(long id, long subjectId, String subjectCode, String subjectName,
                       Highlighter highlighter, String title, LocalDate date, LocalTime start,
                       int weightage, int chapters, int chaptersDone) {

        public double preparedness() {
            return chapters == 0 ? 0 : chaptersDone / (double) chapters;
        }
    }

    public record Chapter(long id, long examId, String name, boolean done) { }

    public record Preferences(LocalTime wake, LocalTime sleep, int maxDailyMinutes,
                              int minSessionMinutes, int maxSessionMinutes, int breakMinutes,
                              int maxHardPerDay, boolean weekendAvailable,
                              LocalTime focusStart, LocalTime focusEnd) { }

    public enum BlockStatus { PLANNED, DONE, MISSED }

    /** A saved study block on the week. */
    public record PlannedBlock(long id, Long taskId, String subjectCode, Highlighter highlighter,
                               String title, LocalDate day, LocalTime start, LocalTime end,
                               double score, boolean revision, int part, int parts, String note,
                               BlockStatus status) {

        public int minutes() {
            return (int) Duration.between(start, end).toMinutes();
        }

        public boolean past(LocalDateTime now) {
            return LocalDateTime.of(day, end).isBefore(now);
        }
    }
}
