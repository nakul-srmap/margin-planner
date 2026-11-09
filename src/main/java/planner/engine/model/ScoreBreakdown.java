package planner.engine.model;

/**
 * The per-component result of a priority calculation. Carrying the components rather than
 * just the total is what makes the scheduler's output explainable: the UI can show the student
 * exactly which factor pushed a task to the top.
 */
public record ScoreBreakdown(String taskId, double deadlineUrgency, double importance,
                             double examRelevance, double difficulty, double overdueFactor,
                             double total, String dominantFactor) {

    /** A one-line, student-readable justification for the score. */
    public String explain(String taskTitle) {
        return "\"%s\" scored %.1f/100 — driven mainly by %s."
                .formatted(taskTitle, total, dominantFactor);
    }
}
