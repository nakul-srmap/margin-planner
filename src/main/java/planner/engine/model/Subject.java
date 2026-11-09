package planner.engine.model;

/**
 * A course the student is enrolled in.
 *
 * @param code         short code used to link tasks, classes and exams (e.g. "DAA")
 * @param name         display name
 * @param difficulty   student-rated difficulty, 1 (easy) to 5 (hard)
 * @param preparedness fraction of the syllabus already covered, 0.0 to 1.0
 */
public record Subject(String code, String name, int difficulty, double preparedness) {

    public Subject {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Subject code is required");
        }
        if (difficulty < 1 || difficulty > 5) {
            throw new IllegalArgumentException("Difficulty must be 1..5, got " + difficulty);
        }
        if (preparedness < 0.0 || preparedness > 1.0) {
            throw new IllegalArgumentException("Preparedness must be 0..1, got " + preparedness);
        }
    }

    public static Subject of(String code, String name, int difficulty) {
        return new Subject(code, name, difficulty, 0.0);
    }

    /** Difficulty rescaled onto the 0-100 axis used by the priority calculator. */
    public double difficultyScore() {
        return difficulty * 20.0;
    }
}
