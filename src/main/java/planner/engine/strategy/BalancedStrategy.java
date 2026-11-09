package planner.engine.strategy;

import planner.engine.PriorityWeights;
import planner.engine.model.FreeSlot;
import planner.engine.model.SchedulingMode;
import planner.engine.model.StudyTask;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Spreads work as evenly as the deadlines allow.
 *
 * <p>Of the slots that could legally hold the next session, this strategy picks the one on the
 * least-loaded day. That keeps a student from finding four hours stacked on Tuesday while
 * Wednesday sits empty, which is the usual failure mode of a plain earliest-fit scheduler.
 */
public class BalancedStrategy implements SchedulingStrategy {

    @Override
    public SchedulingMode mode() {
        return SchedulingMode.BALANCED;
    }

    @Override
    public PriorityWeights weights() {
        return PriorityWeights.defaults();
    }

    @Override
    public FreeSlot chooseSlot(List<FreeSlot> candidates, StudyTask task,
                               Map<LocalDate, Integer> dailyLoad) {
        return candidates.stream()
                .min(Comparator
                        // Least-loaded day wins.
                        .comparingInt((FreeSlot slot) -> dailyLoad.getOrDefault(slot.date(), 0))
                        // Then prefer the student's chosen study hours.
                        .thenComparing(slot -> slot.preferred() ? 0 : 1)
                        // Then stay as early as possible, to keep slack before the deadline.
                        .thenComparing(FreeSlot::date)
                        .thenComparing(FreeSlot::start))
                .orElseThrow();
    }

    @Override
    public String placementNote(FreeSlot slot) {
        return slot.preferred()
                ? "Put on your lightest day, in your focus hours."
                : "Put on your lightest day, so no day gets overloaded.";
    }
}
