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
 * Front-loads the plan: urgent submissions get the earliest available time.
 *
 * <p>Uses a deadline-heavy weighting (55% urgency) and strict earliest-fit placement, so work
 * banks as much slack as possible before it is due. This is the mode to pick in submission week.
 */
public class DeadlineStrategy implements SchedulingStrategy {

    @Override
    public SchedulingMode mode() {
        return SchedulingMode.DEADLINE;
    }

    @Override
    public PriorityWeights weights() {
        return PriorityWeights.deadlineFocused();
    }

    @Override
    public FreeSlot chooseSlot(List<FreeSlot> candidates, StudyTask task,
                               Map<LocalDate, Integer> dailyLoad) {
        return candidates.stream()
                .min(Comparator
                        .comparing(FreeSlot::date)
                        .thenComparing(FreeSlot::start))
                .orElseThrow();
    }

    @Override
    public String placementNote(FreeSlot slot) {
        return "Put in the first free gap, to leave room before the deadline.";
    }
}
