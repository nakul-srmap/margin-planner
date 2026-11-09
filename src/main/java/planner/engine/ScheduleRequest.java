package planner.engine;

import planner.engine.model.ClassSession;
import planner.engine.model.Commitment;
import planner.engine.model.ExamEvent;
import planner.engine.model.SchedulingMode;
import planner.engine.model.StudyTask;
import planner.engine.model.Subject;
import planner.engine.model.UserPreferences;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything the engine needs for one scheduling run, assembled by the service layer from the
 * student's database records. Fluent setters keep the call site readable.
 */
public class ScheduleRequest {

    private LocalDate from = LocalDate.now();
    private LocalDate to = LocalDate.now().plusDays(6);
    private LocalDateTime now = LocalDateTime.now();
    private SchedulingMode mode = SchedulingMode.BALANCED;
    private UserPreferences preferences = UserPreferences.defaults();

    private final List<StudyTask> tasks = new ArrayList<>();
    private final List<Subject> subjects = new ArrayList<>();
    private final List<ClassSession> classes = new ArrayList<>();
    private final List<ExamEvent> exams = new ArrayList<>();
    private final List<Commitment> commitments = new ArrayList<>();

    public static ScheduleRequest forWeek(LocalDateTime now) {
        return new ScheduleRequest()
                .now(now)
                .horizon(now.toLocalDate(), now.toLocalDate().plusDays(6));
    }

    public ScheduleRequest horizon(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Horizon end " + to + " precedes start " + from);
        }
        this.from = from;
        this.to = to;
        return this;
    }

    public ScheduleRequest now(LocalDateTime now) {
        this.now = now;
        return this;
    }

    public ScheduleRequest mode(SchedulingMode mode) {
        this.mode = mode;
        return this;
    }

    public ScheduleRequest preferences(UserPreferences preferences) {
        this.preferences = preferences;
        return this;
    }

    public ScheduleRequest addTasks(StudyTask... values) {
        tasks.addAll(List.of(values));
        return this;
    }

    public ScheduleRequest addTasks(List<StudyTask> values) {
        tasks.addAll(values);
        return this;
    }

    public ScheduleRequest addSubjects(Subject... values) {
        subjects.addAll(List.of(values));
        return this;
    }

    public ScheduleRequest addClasses(ClassSession... values) {
        classes.addAll(List.of(values));
        return this;
    }

    public ScheduleRequest addClasses(List<ClassSession> values) {
        classes.addAll(values);
        return this;
    }

    public ScheduleRequest addExams(ExamEvent... values) {
        exams.addAll(List.of(values));
        return this;
    }

    public ScheduleRequest addCommitments(Commitment... values) {
        commitments.addAll(List.of(values));
        return this;
    }

    public LocalDate from() { return from; }

    public LocalDate to() { return to; }

    public LocalDateTime now() { return now; }

    public SchedulingMode mode() { return mode; }

    public UserPreferences preferences() { return preferences; }

    public List<StudyTask> tasks() { return tasks; }

    public List<Subject> subjects() { return subjects; }

    public List<ClassSession> classes() { return classes; }

    public List<ExamEvent> exams() { return exams; }

    public List<Commitment> commitments() { return commitments; }

    /** Subjects keyed by code for O(1) lookup during scoring. */
    public Map<String, Subject> subjectIndex() {
        return subjects.stream().collect(Collectors.toMap(
                Subject::code, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /** Only the tasks worth scheduling: incomplete and with work remaining. */
    public List<StudyTask> pendingTasks() {
        return tasks.stream()
                .filter(t -> !t.isCompleted())
                .filter(t -> t.remainingMinutes() > 0)
                .toList();
    }
}
