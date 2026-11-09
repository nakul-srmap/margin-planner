package planner.engine.model;

/** The three planning styles the student can choose between. */
public enum SchedulingMode {

    /** Spread work evenly so no single day is overloaded. */
    BALANCED("Balanced", "Study time is spread evenly across the available days."),

    /** Push the most urgent submissions to the front. */
    DEADLINE("Deadline", "Work closest to its deadline is scheduled first."),

    /** Protect revision time for upcoming examinations. */
    EXAM("Exam", "Revision for upcoming examinations is booked before other work.");

    private final String displayName;
    private final String description;

    SchedulingMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }

    public String description() { return description; }
}
