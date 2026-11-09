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
 * Protects examination revision.
 *
 * <p>Revision blocks are allocated before coursework so they claim the student's best hours,
 * and the weighting leans heavily (45%) on exam relevance. Within the legal slots this
 * strategy still balances across days, because cramming a subject into one sitting is worse
 * for retention than spacing it out.
 */
public class ExamStrategy implements SchedulingStrategy {

    @Override
    public SchedulingMode mode() {
        return SchedulingMode.EXAM;
    }

    @Override
    public PriorityWeights weights() {
        return PriorityWeights.examFocused();
    }

    @Override
    public boolean bookRevisionFirst() {
        return true;
    }

    @Override
    public FreeSlot chooseSlot(List<FreeSlot> candidates, StudyTask task,
                               Map<LocalDate, Integer> dailyLoad) {
        return candidates.stream()
                .min(Comparator
                        // Preferred hours first: revision needs the student's sharpest time.
                        .comparingInt((FreeSlot slot) -> slot.preferred() ? 0 : 1)
                        // Then spread, so revision is spaced rather than crammed.
                        .thenComparingInt(slot -> dailyLoad.getOrDefault(slot.date(), 0))
                        .thenComparing(FreeSlot::date)
                        .thenComparing(FreeSlot::start))
                .orElseThrow();
    }

    @Override
    public String placementNote(FreeSlot slot) {
        return slot.preferred()
                ? "Revision goes in your focus hours, when you're sharpest."
                : "Spread over the days before the exam, which helps it stick.";
    }
}
