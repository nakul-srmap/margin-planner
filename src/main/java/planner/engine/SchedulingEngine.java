package planner.engine;

import planner.engine.model.AtRiskTask;
import planner.engine.model.ExamEvent;
import planner.engine.model.FreeSlot;
import planner.engine.model.GeneratedSchedule;
import planner.engine.model.Priority;
import planner.engine.model.ScheduledSession;
import planner.engine.model.SchedulingMode;
import planner.engine.model.ScoreBreakdown;
import planner.engine.model.StudyTask;
import planner.engine.model.Subject;
import planner.engine.model.UserPreferences;
import planner.engine.strategy.BalancedStrategy;
import planner.engine.strategy.DeadlineStrategy;
import planner.engine.strategy.ExamStrategy;
import planner.engine.strategy.SchedulingStrategy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The core scheduling algorithm.
 *
 * <p>The engine is deliberately split from the policy that drives it. This class owns the
 * invariants that must hold for <em>every</em> generated plan:
 *
 * <ul>
 *   <li>no study session ever overlaps a class, a personal commitment, or another session;</li>
 *   <li>no session is placed outside the student's waking hours;</li>
 *   <li>no day exceeds the configured maximum study minutes;</li>
 *   <li>no session runs past the deadline of the task it belongs to;</li>
 *   <li>large tasks are split into sessions no longer than the configured maximum, each
 *       followed by a break;</li>
 *   <li>work that cannot be fitted is reported as at-risk rather than silently dropped.</li>
 * </ul>
 *
 * <p>Which of several legal placements is actually chosen is delegated to a
 * {@link SchedulingStrategy}, selected by {@link SchedulingMode}. The strategies are held in a
 * registry so a new mode can be added without touching the allocation loop.
 *
 * <p>Nothing here depends on Spring, JPA or MySQL, which is what allows the algorithm to be
 * unit-tested directly and run from a plain {@code main} method.
 */
public class SchedulingEngine {

    /** Revision minutes allocated per difficulty point, scaled by how much syllabus is left. */
    private static final int REVISION_MINUTES_PER_DIFFICULTY = 60;

    /** Exams beyond this many days get no revision blocks yet. */
    private static final int REVISION_HORIZON_DAYS = 21;

    /** A subject at or above this difficulty counts towards the daily "hard session" cap. */
    private static final int HARD_SUBJECT_THRESHOLD = 4;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH);

    private final Map<SchedulingMode, SchedulingStrategy> strategies =
            new EnumMap<>(SchedulingMode.class);

    public SchedulingEngine() {
        register(new BalancedStrategy());
        register(new DeadlineStrategy());
        register(new ExamStrategy());
    }

    /** Factory-style registration, so additional modes plug in without edits here. */
    public final void register(SchedulingStrategy strategy) {
        strategies.put(strategy.mode(), strategy);
    }

    public SchedulingStrategy strategyFor(SchedulingMode mode) {
        SchedulingStrategy strategy = strategies.get(mode);
        if (strategy == null) {
            throw new IllegalArgumentException("No strategy registered for mode " + mode);
        }
        return strategy;
    }

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    /** Builds a study plan for the request's horizon and mode. */
    public GeneratedSchedule generate(ScheduleRequest request) {
        UserPreferences prefs = request.preferences();
        prefs.validate();

        SchedulingStrategy strategy = strategyFor(request.mode());
        PriorityCalculator calculator = new PriorityCalculator(strategy.weights());

        GeneratedSchedule schedule =
                new GeneratedSchedule(request.mode(), request.from(), request.to());

        List<FreeSlot> pool = new ArrayList<>(new FreeTimeFinder(prefs)
                .find(request.from(), request.to(), request.classes(),
                        request.commitments(), request.now()));

        if (pool.isEmpty()) {
            schedule.addWarning("No free time was found in this period. "
                    + "Your classes, commitments and sleep schedule leave no usable gaps — "
                    + "try widening your waking hours or enabling weekend study.");
            return schedule;
        }

        List<StudyTask> revisionTasks = buildRevisionTasks(request);
        Set<String> revisionIds = new HashSet<>();
        revisionTasks.forEach(t -> revisionIds.add(t.id()));

        List<StudyTask> all = new ArrayList<>(request.pendingTasks());
        all.addAll(revisionTasks);

        if (all.isEmpty()) {
            schedule.addWarning("There is nothing to schedule — "
                    + "every task is complete and no examinations are close enough to revise for.");
            return schedule;
        }

        Map<String, ScoreBreakdown> scores = scoreAll(all, request, calculator);
        scores.values().forEach(schedule::addScore);

        List<StudyTask> ordered = orderForAllocation(all, revisionTasks, scores, strategy);

        allocate(ordered, revisionIds, scores, pool, request, strategy, schedule);
        return schedule;
    }

    /**
     * Dynamic rescheduling. Records the sessions the student missed, which raises those tasks'
     * overdue component, then rebuilds the plan over the remaining horizon. Urgent commitments
     * keep their priority because the score is recomputed from the new "now".
     *
     * @param missedTaskIds ids of tasks whose planned sessions were not completed
     */
    public GeneratedSchedule reschedule(ScheduleRequest request, Collection<String> missedTaskIds) {
        if (missedTaskIds != null && !missedTaskIds.isEmpty()) {
            Set<String> missed = new HashSet<>(missedTaskIds);
            for (StudyTask task : request.tasks()) {
                if (missed.contains(task.id())) {
                    task.setMissedSessions(task.missedSessions() + 1);
                }
            }
        }
        GeneratedSchedule schedule = generate(request);
        if (missedTaskIds != null && !missedTaskIds.isEmpty()) {
            schedule.addWarning("%d missed session(s) were detected and have been moved into the "
                    + "remaining free time. Priorities were recalculated, so the order of your "
                    + "other work may have changed.".formatted(missedTaskIds.size()));
        }
        return schedule;
    }

    /**
     * Generates all three plans concurrently so the UI can offer a side-by-side comparison
     * without three sequential round trips. Each run works on its own slot pool and touches no
     * shared mutable state, so the only coordination needed is joining the futures.
     */
    public Map<SchedulingMode, GeneratedSchedule> generateAllModes(ScheduleRequest request) {
        List<SchedulingMode> modes = List.of(
                SchedulingMode.BALANCED, SchedulingMode.DEADLINE, SchedulingMode.EXAM);

        // Not try-with-resources: ExecutorService only became AutoCloseable in Java 19,
        // and this project targets 17.
        ExecutorService pool = Executors.newFixedThreadPool(modes.size());
        try {
            Map<SchedulingMode, CompletableFuture<GeneratedSchedule>> futures = new LinkedHashMap<>();
            for (SchedulingMode mode : modes) {
                futures.put(mode, CompletableFuture.supplyAsync(
                        () -> generate(copyForMode(request, mode)), pool));
            }
            Map<SchedulingMode, GeneratedSchedule> results = new LinkedHashMap<>();
            futures.forEach((mode, future) -> results.put(mode, future.join()));
            return results;
        } finally {
            pool.shutdown();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Scoring and ordering
    // ---------------------------------------------------------------------------------------

    private Map<String, ScoreBreakdown> scoreAll(List<StudyTask> tasks, ScheduleRequest request,
                                                 PriorityCalculator calculator) {
        Map<String, Subject> subjects = request.subjectIndex();
        Map<String, ScoreBreakdown> scores = new LinkedHashMap<>();
        for (StudyTask task : tasks) {
            scores.put(task.id(),
                    calculator.score(task, subjects, request.exams(), request.now()));
        }
        return scores;
    }

    private List<StudyTask> orderForAllocation(List<StudyTask> all, List<StudyTask> revision,
                                               Map<String, ScoreBreakdown> scores,
                                               SchedulingStrategy strategy) {
        if (!strategy.bookRevisionFirst() || revision.isEmpty()) {
            return strategy.orderTasks(all, scores);
        }
        Set<String> revisionIds = new HashSet<>();
        revision.forEach(t -> revisionIds.add(t.id()));

        List<StudyTask> coursework = all.stream()
                .filter(t -> !revisionIds.contains(t.id()))
                .toList();

        List<StudyTask> ordered = new ArrayList<>(strategy.orderTasks(revision, scores));
        ordered.addAll(strategy.orderTasks(coursework, scores));
        return ordered;
    }

    /**
     * Creates revision work for each upcoming exam, sized by subject difficulty and by how much
     * of the syllabus the student still has left. A subject already marked fully prepared gets
     * no revision block, so the engine does not invent work.
     */
    private List<StudyTask> buildRevisionTasks(ScheduleRequest request) {
        List<StudyTask> revision = new ArrayList<>();
        Map<String, Subject> subjects = request.subjectIndex();
        LocalDate today = request.now().toLocalDate();

        for (ExamEvent exam : request.exams()) {
            if (!exam.isUpcoming(today) || exam.daysUntil(today) > REVISION_HORIZON_DAYS) {
                continue;
            }
            Subject subject = subjects.get(exam.subjectCode());
            double syllabusLeft = subject == null ? 1.0 : 1.0 - subject.preparedness();
            if (syllabusLeft <= 0.01) {
                continue;
            }
            int difficulty = subject == null ? 3 : subject.difficulty();
            int minutes = (int) Math.round(
                    REVISION_MINUTES_PER_DIFFICULTY * difficulty * syllabusLeft);
            minutes = Math.max(request.preferences().minSessionMinutes(), minutes);

            String name = subject == null ? exam.subjectCode() : subject.name();
            StudyTask task = new StudyTask(
                    "revision-" + exam.id(),
                    "Revision: " + name,
                    exam.subjectCode(),
                    "REVISION",
                    LocalDateTime.of(exam.date(), exam.start()),
                    Priority.CRITICAL,
                    minutes);
            revision.add(task);
        }
        return revision;
    }

    // ---------------------------------------------------------------------------------------
    // Allocation
    // ---------------------------------------------------------------------------------------

    /** A placement decided but not yet emitted, pending the final session count for labelling. */
    private record Placement(FreeSlot slot, LocalTime start, LocalTime end, int minutes) {}

    private void allocate(List<StudyTask> ordered, Set<String> revisionIds,
                          Map<String, ScoreBreakdown> scores, List<FreeSlot> pool,
                          ScheduleRequest request, SchedulingStrategy strategy,
                          GeneratedSchedule schedule) {

        UserPreferences prefs = request.preferences();
        Map<String, Subject> subjects = request.subjectIndex();
        Map<LocalDate, Integer> dailyLoad = new HashMap<>();
        Map<LocalDate, Integer> hardPerDay = new HashMap<>();

        for (StudyTask task : ordered) {
            boolean hard = isHard(task, subjects);
            int required = task.remainingMinutes();
            int remaining = required;
            List<Placement> placements = new ArrayList<>();

            while (remaining > 0) {
                // A final fragment shorter than the minimum session is allowed; otherwise a
                // candidate slot must be able to hold at least one full minimum session.
                int needed = Math.min(remaining, prefs.minSessionMinutes());

                List<FreeSlot> candidates = new ArrayList<>();
                for (FreeSlot slot : pool) {
                    int usable = usableMinutes(slot, task, prefs, dailyLoad, hardPerDay, hard);
                    if (usable < needed) {
                        continue;
                    }
                    // An unsplittable task needs a single slot big enough for all of it.
                    if (!task.isSplittable() && usable < remaining) {
                        continue;
                    }
                    candidates.add(slot);
                }
                if (candidates.isEmpty()) {
                    break;
                }

                FreeSlot chosen = strategy.chooseSlot(candidates, task, dailyLoad);
                int usable = usableMinutes(chosen, task, prefs, dailyLoad, hardPerDay, hard);
                int chunk = Math.min(Math.min(remaining, prefs.maxSessionMinutes()), usable);
                if (chunk <= 0) {
                    break;
                }

                LocalTime start = chosen.start();
                LocalTime end = start.plusMinutes(chunk);
                placements.add(new Placement(chosen, start, end, chunk));

                remaining -= chunk;
                dailyLoad.merge(chosen.date(), chunk, Integer::sum);
                if (hard) {
                    hardPerDay.merge(chosen.date(), 1, Integer::sum);
                }
                consume(pool, chosen, chunk + prefs.breakMinutes());
            }

            emitPlacements(task, placements, required, revisionIds.contains(task.id()),
                    scores.get(task.id()), strategy, request, schedule);
        }

        addLoadWarnings(schedule, dailyLoad, prefs);
    }

    /**
     * How many minutes of this task can legally start in this slot, after applying the daily
     * cap, the hard-session cap and the task's own deadline. Returns 0 when the slot is unusable,
     * which is the single place every hard constraint is enforced.
     */
    private int usableMinutes(FreeSlot slot, StudyTask task, UserPreferences prefs,
                              Map<LocalDate, Integer> dailyLoad,
                              Map<LocalDate, Integer> hardPerDay, boolean hard) {

        int dayCapacity = prefs.maxDailyStudyMinutes() - dailyLoad.getOrDefault(slot.date(), 0);
        if (dayCapacity <= 0) {
            return 0;
        }
        if (hard && hardPerDay.getOrDefault(slot.date(), 0) >= prefs.maxHardSessionsPerDay()) {
            return 0;
        }

        int usable = Math.min(slot.minutes(), dayCapacity);

        if (task.deadline() != null) {
            LocalDateTime slotStart = LocalDateTime.of(slot.date(), slot.start());
            if (!slotStart.isBefore(task.deadline())) {
                return 0;
            }
            long untilDeadline = Duration.between(slotStart, task.deadline()).toMinutes();
            usable = (int) Math.min(usable, untilDeadline);
        }
        return Math.max(0, usable);
    }

    /** Replaces a slot in the pool with whatever is left of it after a session plus its break. */
    private void consume(List<FreeSlot> pool, FreeSlot slot, int minutes) {
        int index = pool.indexOf(slot);
        if (index < 0) {
            return;
        }
        FreeSlot remainder = slot.consume(minutes);
        if (remainder == null || remainder.minutes() <= 0) {
            pool.remove(index);
        } else {
            pool.set(index, remainder);
        }
    }

    private void emitPlacements(StudyTask task, List<Placement> placements, int required,
                                boolean revision, ScoreBreakdown breakdown,
                                SchedulingStrategy strategy, ScheduleRequest request,
                                GeneratedSchedule schedule) {

        int scheduled = placements.stream().mapToInt(Placement::minutes).sum();
        double score = breakdown == null ? 0.0 : breakdown.total();

        // Strategies may place a later part on an earlier day, so number the parts
        // chronologically rather than in the order they happened to be allocated.
        List<Placement> chronological = new ArrayList<>(placements);
        chronological.sort(Comparator
                .comparing((Placement p) -> p.slot().date())
                .thenComparing(Placement::start));

        for (int i = 0; i < chronological.size(); i++) {
            Placement placement = chronological.get(i);
            schedule.addSession(new ScheduledSession(
                    task.id(), task.title(), task.subjectCode(),
                    placement.slot().date(), placement.start(), placement.end(),
                    score, revision, i + 1, chronological.size(),
                    explain(task, breakdown, placement, strategy, request)));
        }

        if (scheduled < required) {
            schedule.addAtRisk(new AtRiskTask(task.id(), task.title(), scheduled, required,
                    shortfallReason(task, scheduled, request)));
        }
    }

    /** Builds the student-facing justification shown against every session. */
    private String explain(StudyTask task, ScoreBreakdown breakdown, Placement placement,
                           SchedulingStrategy strategy, ScheduleRequest request) {
        StringBuilder sb = new StringBuilder();

        if (task.deadline() != null) {
            long minutes = Duration.between(request.now(), task.deadline()).toMinutes();
            String when = task.deadline().toLocalDate().equals(request.now().toLocalDate())
                    ? "today"
                    : task.deadline().toLocalDate().equals(request.now().toLocalDate().plusDays(1))
                    ? "tomorrow"
                    : task.deadline().format(DAY_FORMAT);
            String at = task.deadline().toLocalTime().toString();
            if (task.id().startsWith("revision-")) {
                sb.append("Revision for the exam %s at %s.".formatted(when, at));
            } else if (minutes < 0) {
                sb.append("Overdue since %s %s.".formatted(when, at));
            } else {
                sb.append("Due %s at %s.".formatted(when, at));
            }
        }
        sb.append(" About %s of work left.".formatted(duration(task.remainingMinutes())));
        if (breakdown != null) {
            sb.append(" Ranked %.0f of 100, %s.".formatted(breakdown.total(),
                    task.id().startsWith("revision-") ? "mostly because the exam is coming up" : reason(breakdown)));
        }
        sb.append(' ').append(strategy.placementNote(placement.slot()));
        return sb.toString().trim();
    }

    /** Turns the dominant priority factor into the end of a plain sentence. */
    public static String reason(ScoreBreakdown breakdown) {
        return switch (breakdown.dominantFactor()) {
            case "previously missed work" -> "mostly because a session for it was missed";
            case "an approaching deadline" -> "mostly because the deadline is close";
            case "an upcoming examination" -> "mostly because its exam is close";
            case "the priority you set" -> "mostly because of the priority you gave it";
            default -> "mostly because the subject is hard";
        };
    }

    /** 135 → "2 h 15 min", 45 → "45 min", 120 → "2 h". */
    static String duration(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) {
            return m + " min";
        }
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    private String shortfallReason(StudyTask task, int scheduled, ScheduleRequest request) {
        if (task.deadline() != null && task.deadline().isBefore(request.now())) {
            return "The deadline has already passed, so there's no time left before it.";
        }
        if (scheduled == 0 && !task.isSplittable()) {
            return "It has to be done in one sitting, and there's no gap that long before "
                    + "the deadline. Allow it to be split and it may fit.";
        }
        return "Your free time before the deadline, after classes, commitments and the daily "
                + "study limit, is not enough to cover it. Consider raising your daily study "
                + "limit, freeing a commitment, or renegotiating the deadline.";
    }

    private void addLoadWarnings(GeneratedSchedule schedule, Map<LocalDate, Integer> dailyLoad,
                                 UserPreferences prefs) {
        dailyLoad.forEach((date, minutes) -> {
            if (minutes >= prefs.maxDailyStudyMinutes()) {
                schedule.addWarning("%s is filled to your daily limit of %d minutes."
                        .formatted(date.format(DAY_FORMAT), prefs.maxDailyStudyMinutes()));
            }
        });
    }

    private boolean isHard(StudyTask task, Map<String, Subject> subjects) {
        if (task.subjectCode() == null) {
            return false;
        }
        Subject subject = subjects.get(task.subjectCode());
        return subject != null && subject.difficulty() >= HARD_SUBJECT_THRESHOLD;
    }

    /** Shallow copy sharing the task objects, used only to vary the mode across parallel runs. */
    private ScheduleRequest copyForMode(ScheduleRequest source, SchedulingMode mode) {
        ScheduleRequest copy = new ScheduleRequest()
                .horizon(source.from(), source.to())
                .now(source.now())
                .mode(mode)
                .preferences(source.preferences());
        copy.addTasks(source.tasks());
        copy.addClasses(source.classes());
        source.subjects().forEach(copy::addSubjects);
        source.exams().forEach(copy::addExams);
        source.commitments().forEach(copy::addCommitments);
        return copy;
    }
}
