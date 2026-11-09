package planner.engine.model;

/** Task importance levels, each mapped to a 0-100 importance weight. */
public enum Priority {
    LOW(25), MEDIUM(50), HIGH(75), CRITICAL(100);

    private final int weight;

    Priority(int weight) { this.weight = weight; }

    public int weight() { return weight; }
}
