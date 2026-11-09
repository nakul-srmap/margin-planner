package planner.engine.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * A scheduled examination. Drives the exam-relevance component of the priority score and the
 * automatic allocation of revision sessions in the days leading up to the paper.
 *
 * @param weightage share of the final grade, used to scale how aggressively revision is booked
 */
public record ExamEvent(String id, String subjectCode, String title,
                        LocalDate date, LocalTime start, int weightage) {

    public ExamEvent {
        if (subjectCode == null || subjectCode.isBlank()) {
            throw new IllegalArgumentException("Exam must belong to a subject");
        }
        if (date == null) {
            throw new IllegalArgumentException("Exam date is required");
        }
    }

    public static ExamEvent of(String id, String subjectCode, String title, LocalDate date) {
        return new ExamEvent(id, subjectCode, title, date, LocalTime.of(10, 0), 100);
    }

    public long daysUntil(LocalDate today) {
        return ChronoUnit.DAYS.between(today, date);
    }

    public boolean isUpcoming(LocalDate today) {
        return !date.isBefore(today);
    }
}
