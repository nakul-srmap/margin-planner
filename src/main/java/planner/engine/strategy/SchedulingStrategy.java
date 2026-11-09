package planner.engine.strategy;

import planner.engine.PriorityWeights;
import planner.engine.model.FreeSlot;
import planner.engine.model.SchedulingMode;
import planner.engine.model.ScoreBreakdown;
import planner.engine.model.StudyTask;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Strategy pattern: the policy half of the scheduler.
 *
 * <p>{@code SchedulingEngine} owns the mechanics that must hold for every plan — never
 * double-booking, never exceeding the daily cap, never scheduling past a deadline. A strategy
 * supplies the judgement calls: how to weight the priority components, what order to work
 * through tasks, and which of several legal slots to actually use. Adding a fourth planning
 * mode therefore means adding one class here and nothing else.
 */
public interface SchedulingStrategy {

    SchedulingMode mode();

    /** The priority weighting this mode scores tasks with. */
    PriorityWeights weights();

    /**
     * Whether revision for upcoming exams should be allocated before coursework, claiming the
     * best slots first.
     */
    default boolean bookRevisionFirst() {
        return false;
    }

    /** Highest-scoring task first; ties broken by the nearer deadline. */
    default List<StudyTask> orderTasks(List<StudyTask> tasks, Map<String, ScoreBreakdown> scores) {
        Comparator<StudyTask> byScore = Comparator.comparingDouble(
                (StudyTask t) -> scores.containsKey(t.id()) ? scores.get(t.id()).total() : 0.0).reversed();
        Comparator<StudyTask> byDeadline = Comparator.comparing(
                StudyTask::deadline, Comparator.nullsLast(Comparator.naturalOrder()));
        return tasks.stream().sorted(byScore.thenComparing(byDeadline)).toList();
    }

    /**
     * Chooses which legal slot to use for the next session of {@code task}.
     *
     * @param candidates slots already filtered to those that can legally hold the session,
     *                   earliest first; never empty
     * @param dailyLoad  minutes already booked per date in this run
     */
    FreeSlot chooseSlot(List<FreeSlot> candidates, StudyTask task, Map<LocalDate, Integer> dailyLoad);

    /** A short note explaining the placement, appended to the session's explanation. */
    default String placementNote(FreeSlot slot) {
        return slot.preferred()
                ? "Put in your focus hours."
                : "Put in the first free gap in your timetable.";
    }
}
