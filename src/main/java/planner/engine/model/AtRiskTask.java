package planner.engine.model;

/**
 * Output of the deadline risk detector: a task the engine could not fully fit before its
 * deadline given the student's real availability.
 *
 * @param scheduledMinutes how much of the work the engine did manage to place
 * @param requiredMinutes  how much the task needs in total
 */
public record AtRiskTask(String taskId, String title, int scheduledMinutes,
                         int requiredMinutes, String reason) {

    public int shortfallMinutes() {
        return Math.max(0, requiredMinutes - scheduledMinutes);
    }

    public double coverage() {
        return requiredMinutes == 0 ? 1.0 : scheduledMinutes / (double) requiredMinutes;
    }

    public String explain() {
        return "\"%s\" is at risk: only %d of %d minutes could be scheduled before the deadline. %s"
                .formatted(title, scheduledMinutes, requiredMinutes, reason);
    }
}
