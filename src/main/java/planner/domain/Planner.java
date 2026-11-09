package planner.domain;

import planner.domain.Records.Exam;
import planner.domain.Records.Preferences;
import planner.domain.Records.Subject;
import planner.domain.Records.Task;
import planner.domain.Records.TimetableEntry;
import planner.engine.ScheduleRequest;
import planner.engine.SchedulingEngine;
import planner.engine.TimetableValidator;
import planner.engine.model.ClassSession;
import planner.engine.model.Commitment;
import planner.engine.model.ExamEvent;
import planner.engine.model.GeneratedSchedule;
import planner.engine.model.Priority;
import planner.engine.model.ScheduledSession;
import planner.engine.model.SchedulingMode;
import planner.engine.model.StudyTask;
import planner.engine.model.TimeWindow;
import planner.engine.model.UserPreferences;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connects the student's records to the scheduling engine.
 *
 * <p>The engine in {@code planner.engine} knows nothing about the database; this class
 * loads what it needs, converts it, runs the engine, and turns the result back into rows.
 * A generated plan is held as a preview until the student saves it, so the plan that is
 * saved is exactly the one they looked at — not a fresh run a few minutes later.
 */
public final class Planner {

    public static final int HORIZON_DAYS = 7;
    private static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(30);

    /** A plan the student has seen but not yet saved. */
    public record Preview(SchedulingMode mode, GeneratedSchedule schedule, LocalDateTime made,
                          Map<String, Long> subjectIds, Map<String, String> subjectNames) { }

    private final Store store;
    private final SchedulingEngine engine = new SchedulingEngine();
    private final Map<String, Preview> previews = new ConcurrentHashMap<>();

    public Planner(Store store) {
        this.store = store;
    }

    public Preview preview(String sessionId, long userId, SchedulingMode mode, LocalDateTime now) {
        Inputs inputs = load(userId, now);
        GeneratedSchedule schedule = engine.generate(inputs.request().mode(mode));
        Preview preview = new Preview(mode, schedule, now, inputs.subjectIds(), inputs.subjectNames());
        previews.put(sessionId, preview);
        return preview;
    }

    public Optional<Preview> currentPreview(String sessionId, LocalDateTime now) {
        Preview preview = previews.get(sessionId);
        if (preview == null || preview.made().plus(PREVIEW_LIFETIME).isBefore(now)) {
            previews.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(preview);
    }

    public void discard(String sessionId) {
        previews.remove(sessionId);
    }

    /** Writes the previewed plan, replacing any blocks that haven't started yet. */
    public Optional<Integer> save(String sessionId, long userId, LocalDateTime now) {
        Optional<Preview> preview = currentPreview(sessionId, now);
        if (preview.isEmpty()) {
            return Optional.empty();
        }
        Preview p = preview.get();
        List<Store.NewBlock> blocks = new ArrayList<>();

        for (ScheduledSession s : p.schedule().sessions()) {
            if (LocalDateTime.of(s.date(), s.start()).isBefore(now)) {
                continue;   // the preview is a few minutes old; drop anything now in the past
            }
            Long taskId = s.taskId().startsWith("revision-") ? null : Long.valueOf(s.taskId());
            blocks.add(new Store.NewBlock(taskId, p.subjectIds().get(s.subjectCode()), s.title(),
                    s.date(), s.start(), s.end(), Math.round(s.priorityScore() * 10) / 10.0,
                    s.revision(), s.sessionIndex(), s.sessionCount(), s.explanation()));
        }
        int saved = store.replacePlan(userId, now, blocks);
        previews.remove(sessionId);
        return Optional.of(saved);
    }

    /** Makes and saves a fresh plan straight away; used when auto re-planning is on. */
    public int replan(long userId, SchedulingMode mode, LocalDateTime now) {
        String key = "auto-" + userId;
        preview(key, userId, mode, now);
        return save(key, userId, now).orElse(0);
    }

    /**
     * Checks a new timetable entry against the existing ones using the engine's own
     * validator, so the clash rules here and in the scheduler can never differ.
     */
    public Optional<String> clash(long userId, java.time.DayOfWeek day, LocalTime start, LocalTime end) {
        for (TimetableEntry existing : store.timetable(userId)) {
            ClassSession a = new ClassSession(existing.day(), existing.start(), existing.end(),
                    existing.title(), null, null, false);
            ClassSession b = new ClassSession(day, start, end, "new", null, null, false);
            if (!new TimetableValidator().validate(List.of(a, b)).isEmpty()) {
                return Optional.of("That overlaps %s on %s, %s to %s.".formatted(
                        existing.kind().isClass()
                                ? "your " + existing.subjectCode() + " " + existing.kind().label().toLowerCase()
                                : existing.label(),
                        Text.dayName(existing.day()), existing.start(), existing.end()));
            }
        }
        return Optional.empty();
    }

    /* ------------------------------------------------------------------------- */

    private record Inputs(ScheduleRequest request, Map<String, Long> subjectIds,
                          Map<String, String> subjectNames) { }

    private Inputs load(long userId, LocalDateTime now) {
        Preferences prefs = store.preferences(userId);
        List<Subject> subjects = store.subjects(userId);
        List<TimetableEntry> timetable = store.timetable(userId);
        List<Task> tasks = store.openTasks(userId);
        List<Exam> exams = store.upcomingExams(userId, now.toLocalDate());

        UserPreferences enginePrefs = new UserPreferences()
                .setWakeTime(prefs.wake())
                .setSleepTime(prefs.sleep())
                .setMaxDailyStudyMinutes(prefs.maxDailyMinutes())
                .setMinSessionMinutes(prefs.minSessionMinutes())
                .setMaxSessionMinutes(prefs.maxSessionMinutes())
                .setBreakMinutes(prefs.breakMinutes())
                .setMaxHardSessionsPerDay(prefs.maxHardPerDay())
                .setWeekendAvailable(prefs.weekendAvailable());
        if (prefs.focusStart() != null && prefs.focusEnd() != null) {
            enginePrefs.addPreferredWindow(new TimeWindow(prefs.focusStart(), prefs.focusEnd()));
        }

        LocalDate today = now.toLocalDate();
        ScheduleRequest request = new ScheduleRequest()
                .now(now)
                .horizon(today, today.plusDays(HORIZON_DAYS - 1))
                .preferences(enginePrefs);

        Map<String, Long> subjectIds = new HashMap<>();
        Map<String, String> subjectNames = new HashMap<>();
        for (Subject s : subjects) {
            subjectIds.put(s.code(), s.id());
            subjectNames.put(s.code(), s.name());
            request.addSubjects(new planner.engine.model.Subject(
                    s.code(), s.name(), s.difficulty(), Math.min(1.0, Math.max(0.0, s.preparedness()))));
        }

        for (TimetableEntry e : timetable) {
            if (e.kind().isClass()) {
                request.addClasses(new ClassSession(e.day(), e.start(), e.end(), e.subjectCode(),
                        null, e.room(), e.kind() == Records.Kind.LAB));
            } else {
                request.addCommitments(new Commitment(e.label(), e.day(), null, e.start(), e.end()));
            }
        }

        for (Task t : tasks) {
            if (t.remainingMinutes() <= 0) {
                continue;
            }
            StudyTask st = new StudyTask(String.valueOf(t.id()), t.title(), t.subjectCode(), "TASK",
                    t.dueAt(), Priority.valueOf(t.priority().name()), t.estimatedMinutes());
            st.setCompletedMinutes(t.completedMinutes());
            st.setMissedSessions(t.missedSessions());
            st.setSplittable(t.splittable());
            request.addTasks(st);
        }

        for (Exam e : exams) {
            request.addExams(new ExamEvent(String.valueOf(e.id()), e.subjectCode(), e.title(),
                    e.date(), e.start(), e.weightage()));
        }

        return new Inputs(request, subjectIds, subjectNames);
    }
}
