package planner.engine;

/**
 * The configurable weighting of the five priority components. Defaults match the project
 * specification: 35% deadline urgency, 25% importance, 20% exam relevance, 10% difficulty,
 * 10% overdue factor.
 *
 * <p>Weights are expressed as fractions and must sum to 1.0, which is what keeps the final
 * score normalised onto 0-100 without a second rescaling pass.
 */
public record PriorityWeights(double deadline, double importance, double exam,
                              double difficulty, double overdue) {

    private static final double TOLERANCE = 0.0001;

    public PriorityWeights {
        double sum = deadline + importance + exam + difficulty + overdue;
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new IllegalArgumentException(
                    "Priority weights must sum to 1.0 but summed to " + sum);
        }
        if (deadline < 0 || importance < 0 || exam < 0 || difficulty < 0 || overdue < 0) {
            throw new IllegalArgumentException("Priority weights cannot be negative");
        }
    }

    public static PriorityWeights defaults() {
        return new PriorityWeights(0.35, 0.25, 0.20, 0.10, 0.10);
    }

    /** Deadline-first weighting used by {@code DeadlineStrategy}. */
    public static PriorityWeights deadlineFocused() {
        return new PriorityWeights(0.55, 0.20, 0.10, 0.05, 0.10);
    }

    /** Exam-first weighting used by {@code ExamStrategy}. */
    public static PriorityWeights examFocused() {
        return new PriorityWeights(0.20, 0.15, 0.45, 0.12, 0.08);
    }
}
