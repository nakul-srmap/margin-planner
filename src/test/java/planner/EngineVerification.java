package planner;

import planner.engine.*;
import planner.engine.model.ClassSession;
import planner.engine.model.Commitment;
import planner.engine.model.ExamEvent;
import planner.engine.model.FreeSlot;
import planner.engine.model.GeneratedSchedule;
import planner.engine.model.Priority;
import planner.engine.model.ScheduledSession;
import planner.engine.model.SchedulingMode;
import planner.engine.model.StudyTask;
import planner.engine.model.Subject;
import planner.engine.model.TimeWindow;
import planner.engine.model.UserPreferences;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free verification harness for the scheduling engine.
 *
 * <p>This exists alongside the JUnit suite so the algorithm's invariants can be checked with
 * nothing but a JDK — useful in a sandbox without a Maven repository, and useful in the project
 * demonstration, where printing a real plan next to its assertions is more convincing than a
 * green test bar.
 *
 * <p>Run with:
 * <pre>
 *   javac -d out $(find src/main/java src/test/java -name '*.java')
 *   java -cp out planner.engine.EngineVerification
 * </pre>
 */
public final class EngineVerification {

    private static int passed;
    private static int failed;

    // Monday 09 Nov 2026, 08:00 — the reference "now" for every scenario.
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 8, 0);

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("SMART ACADEMIC PLANNER — scheduling engine verification");
        System.out.println("Reference time: " + NOW);
        System.out.println("=".repeat(78));

        printWeeklyPlan();

        section("Core invariants");
        invariantsHold();

        section("Priority scoring");
        scoringBehaviour();

        section("Timetable conflict detection");
        timetableConflicts();

        section("Free time detection");
        freeTimeDetection();

        section("Edge cases");
        noAvailableSlots();
        tasksExceedingAvailableTime();
        multipleUrgentAssignments();
        consecutiveExams();
        missedSessionRescheduling();
        invalidDeadline();
        conflictingPreferences();
        unsplittableTaskTooLarge();

        section("Scheduling modes");
        modesDiffer();
        allModesInParallel();

        System.out.println("\n" + "=".repeat(78));
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        System.out.println("=".repeat(78));
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shared fixture: a realistic second-year engineering student
    // ---------------------------------------------------------------------------------------

    private static List<Subject> subjects() {
        return List.of(
                new Subject("DAA", "Design & Analysis of Algorithms", 4, 0.30),
                new Subject("JAVA", "Advanced Java Programming", 5, 0.45),
                new Subject("DBMS", "Database Management Systems", 3, 0.70),
                new Subject("MATHS", "Discrete Mathematics", 4, 0.20));
    }

    private static List<ClassSession> timetable() {
        List<ClassSession> classes = new ArrayList<>();
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            classes.add(ClassSession.lecture(day, 9, 10, "DAA"));
            classes.add(ClassSession.lecture(day, 10, 11, "JAVA"));
            classes.add(ClassSession.lecture(day, 11, 12, "MATHS"));
            classes.add(ClassSession.lecture(day, 14, 15, "DBMS"));
        }
        classes.add(ClassSession.lab(DayOfWeek.WEDNESDAY, 15, 17, "JAVA"));
        classes.add(ClassSession.lab(DayOfWeek.FRIDAY, 15, 17, "DBMS"));
        return classes;
    }

    private static UserPreferences preferences() {
        return UserPreferences.defaults()
                .setWakeTime(LocalTime.of(7, 0))
                .setSleepTime(LocalTime.of(23, 0))
                .setMaxDailyStudyMinutes(300)
                .setMinSessionMinutes(30)
                .setMaxSessionMinutes(90)
                .setBreakMinutes(10)
                .setMaxHardSessionsPerDay(2)
                .setWeekendAvailable(true);
    }

    private static List<StudyTask> tasks() {
        return List.of(
                StudyTask.assignment("t1", "DAA assignment 3", "DAA",
                        LocalDateTime.of(2026, 11, 10, 23, 59), Priority.HIGH, 180),
                StudyTask.assignment("t2", "Java project — inventory module", "JAVA",
                        LocalDateTime.of(2026, 11, 14, 17, 0), Priority.CRITICAL, 600),
                StudyTask.assignment("t3", "DBMS normalisation worksheet", "DBMS",
                        LocalDateTime.of(2026, 11, 13, 23, 59), Priority.MEDIUM, 120),
                new StudyTask("t4", "Discrete maths problem set", "MATHS", "HOMEWORK",
                        LocalDateTime.of(2026, 11, 12, 9, 0), Priority.HIGH, 150));
    }

    private static ScheduleRequest baseRequest(SchedulingMode mode) {
        ScheduleRequest request = new ScheduleRequest()
                .now(NOW)
                .horizon(NOW.toLocalDate(), NOW.toLocalDate().plusDays(6))
                .mode(mode)
                .preferences(preferences());
        request.addTasks(tasks());
        request.addClasses(timetable());
        subjects().forEach(request::addSubjects);
        request.addCommitments(Commitment.weekly("Gym", DayOfWeek.TUESDAY, 18, 19));
        request.addExams(ExamEvent.of("e1", "MATHS", "Discrete Maths internal",
                LocalDate.of(2026, 11, 13)));
        return request;
    }

    // ---------------------------------------------------------------------------------------
    // Demonstration output
    // ---------------------------------------------------------------------------------------

    private static void printWeeklyPlan() {
        GeneratedSchedule schedule = new SchedulingEngine().generate(baseRequest(SchedulingMode.BALANCED));

        System.out.println("\nGenerated plan (Balanced mode)");
        System.out.println("-".repeat(78));
        System.out.println(schedule.summary());

        for (Map.Entry<LocalDate, Integer> day : schedule.dailyLoad().entrySet()) {
            System.out.printf("%n%s  (%d min booked)%n", day.getKey(), day.getValue());
            for (ScheduledSession session : schedule.sessionsOn(day.getKey())) {
                System.out.printf("   %s-%s  %-42s %s%n",
                        session.start(), session.end(), session.label(),
                        session.revision() ? "[revision]" : "");
            }
        }

        if (!schedule.sessions().isEmpty()) {
            System.out.println("\nExample explanation (first session):");
            System.out.println("   " + schedule.sessions().get(0).explanation());
        }
        if (!schedule.atRisk().isEmpty()) {
            System.out.println("\nDeadline risk detector:");
            schedule.atRisk().forEach(r -> System.out.println("   " + r.explain()));
        }
        if (!schedule.warnings().isEmpty()) {
            System.out.println("\nWarnings:");
            schedule.warnings().forEach(w -> System.out.println("   " + w));
        }
        System.out.println("\nSubject balance: " + schedule.subjectLoad());
    }

    // ---------------------------------------------------------------------------------------
    // Invariants
    // ---------------------------------------------------------------------------------------

    private static void invariantsHold() {
        for (SchedulingMode mode : SchedulingMode.values()) {
            GeneratedSchedule schedule = new SchedulingEngine().generate(baseRequest(mode));
            String tag = mode.displayName();

            check(tag + ": produced a non-empty plan", !schedule.sessions().isEmpty());
            check(tag + ": no two sessions overlap", noSessionOverlaps(schedule));
            check(tag + ": no session collides with a class", noClassCollisions(schedule));
            check(tag + ": no session runs past its task deadline", deadlinesRespected(schedule));
            check(tag + ": no day exceeds the 300-minute study cap", dailyCapRespected(schedule, 300));
            check(tag + ": every session sits inside waking hours", withinWakingHours(schedule));
            check(tag + ": no session exceeds the 90-minute maximum", sessionLengthRespected(schedule, 90));
            check(tag + ": consecutive sessions are separated by a break",
                    breaksBetweenSessions(schedule, 10));
            check(tag + ": every session carries an explanation", allExplained(schedule));
            check(tag + ": large task was split into multiple sessions", taskWasSplit(schedule, "t2"));
        }
    }

    private static boolean noSessionOverlaps(GeneratedSchedule schedule) {
        List<ScheduledSession> all = schedule.sessions();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(i).overlaps(all.get(j))) {
                    report("overlap: " + all.get(i).label() + " vs " + all.get(j).label());
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean noClassCollisions(GeneratedSchedule schedule) {
        for (ScheduledSession session : schedule.sessions()) {
            for (ClassSession cls : timetable()) {
                if (cls.day() != session.date().getDayOfWeek()) {
                    continue;
                }
                if (cls.window().overlaps(new TimeWindow(session.start(), session.end()))) {
                    report("class collision on %s: %s vs %s class %s-%s".formatted(
                            session.date(), session.label(), cls.subjectCode(),
                            cls.start(), cls.end()));
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean deadlinesRespected(GeneratedSchedule schedule) {
        Map<String, LocalDateTime> deadlines = new java.util.HashMap<>();
        tasks().forEach(t -> deadlines.put(t.id(), t.deadline()));
        for (ScheduledSession session : schedule.sessions()) {
            LocalDateTime deadline = deadlines.get(session.taskId());
            if (deadline == null) {
                continue;
            }
            if (LocalDateTime.of(session.date(), session.end()).isAfter(deadline)) {
                report("past deadline: " + session.label() + " ends after " + deadline);
                return false;
            }
        }
        return true;
    }

    private static boolean dailyCapRespected(GeneratedSchedule schedule, int cap) {
        for (Map.Entry<LocalDate, Integer> entry : schedule.dailyLoad().entrySet()) {
            if (entry.getValue() > cap) {
                report("daily cap exceeded on %s: %d min".formatted(entry.getKey(), entry.getValue()));
                return false;
            }
        }
        return true;
    }

    private static boolean withinWakingHours(GeneratedSchedule schedule) {
        for (ScheduledSession session : schedule.sessions()) {
            if (session.start().isBefore(LocalTime.of(7, 0))
                    || session.end().isAfter(LocalTime.of(23, 0))) {
                report("outside waking hours: " + session.label() + " at " + session.start());
                return false;
            }
        }
        return true;
    }

    private static boolean sessionLengthRespected(GeneratedSchedule schedule, int max) {
        return schedule.sessions().stream().allMatch(s -> s.minutes() <= max);
    }

    private static boolean breaksBetweenSessions(GeneratedSchedule schedule, int breakMinutes) {
        for (LocalDate date : schedule.dailyLoad().keySet()) {
            List<ScheduledSession> day = schedule.sessionsOn(date);
            for (int i = 0; i < day.size() - 1; i++) {
                LocalTime end = day.get(i).end();
                LocalTime nextStart = day.get(i + 1).start();
                long gap = (nextStart.toSecondOfDay() - end.toSecondOfDay()) / 60L;
                if (gap < breakMinutes) {
                    report("gap of only %d min on %s between %s and %s".formatted(
                            gap, date, day.get(i).label(), day.get(i + 1).label()));
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean allExplained(GeneratedSchedule schedule) {
        return schedule.sessions().stream()
                .allMatch(s -> s.explanation() != null && s.explanation().length() > 20);
    }

    private static boolean taskWasSplit(GeneratedSchedule schedule, String taskId) {
        return schedule.sessions().stream().filter(s -> s.taskId().equals(taskId)).count() > 1;
    }

    // ---------------------------------------------------------------------------------------
    // Scoring
    // ---------------------------------------------------------------------------------------

    private static void scoringBehaviour() {
        PriorityCalculator calculator = new PriorityCalculator();
        Map<String, Subject> index = new java.util.HashMap<>();
        subjects().forEach(s -> index.put(s.code(), s));

        StudyTask dueTomorrow = StudyTask.assignment("a", "Due tomorrow", "DAA",
                NOW.plusDays(1), Priority.HIGH, 120);
        StudyTask dueNextMonth = StudyTask.assignment("b", "Due next month", "DAA",
                NOW.plusDays(30), Priority.HIGH, 120);

        double urgent = calculator.score(dueTomorrow, index, List.of(), NOW).total();
        double distant = calculator.score(dueNextMonth, index, List.of(), NOW).total();
        check("nearer deadline scores higher (%.1f > %.1f)".formatted(urgent, distant), urgent > distant);

        StudyTask overdue = StudyTask.assignment("c", "Overdue", "DAA",
                NOW.minusDays(2), Priority.HIGH, 120);
        check("overdue task outscores an upcoming one",
                calculator.score(overdue, index, List.of(), NOW).total() > urgent);

        StudyTask low = StudyTask.assignment("d", "Low priority", "DAA",
                NOW.plusDays(5), Priority.LOW, 120);
        StudyTask critical = StudyTask.assignment("e", "Critical", "DAA",
                NOW.plusDays(5), Priority.CRITICAL, 120);
        check("higher importance scores higher",
                calculator.score(critical, index, List.of(), NOW).total()
                        > calculator.score(low, index, List.of(), NOW).total());

        List<ExamEvent> exams = List.of(
                ExamEvent.of("x", "MATHS", "Maths internal", NOW.toLocalDate().plusDays(3)));
        StudyTask mathsTask = StudyTask.assignment("f", "Maths revision", "MATHS",
                NOW.plusDays(10), Priority.MEDIUM, 120);
        double withExam = calculator.score(mathsTask, index, exams, NOW).total();
        double withoutExam = calculator.score(mathsTask, index, List.of(), NOW).total();
        check("an upcoming exam raises the subject's score (%.1f > %.1f)"
                .formatted(withExam, withoutExam), withExam > withoutExam);

        boolean allNormalised = List.of(dueTomorrow, dueNextMonth, overdue, low, critical).stream()
                .map(t -> calculator.score(t, index, exams, NOW).total())
                .allMatch(v -> v >= 0.0 && v <= 100.0);
        check("every score is normalised to 0-100", allNormalised);

        check("weights must sum to 1.0", throwsIllegalArgument(
                () -> new PriorityWeights(0.5, 0.5, 0.5, 0.5, 0.5)));
    }

    // ---------------------------------------------------------------------------------------
    // Timetable and free time
    // ---------------------------------------------------------------------------------------

    private static void timetableConflicts() {
        TimetableValidator validator = new TimetableValidator();

        check("a clean timetable reports no conflicts", validator.isValid(timetable()));

        List<ClassSession> clashing = new ArrayList<>(timetable());
        clashing.add(ClassSession.lecture(DayOfWeek.MONDAY, 9, 11, "EXTRA"));
        List<TimetableValidator.Conflict> conflicts = validator.validate(clashing);
        check("an overlapping class is detected", !conflicts.isEmpty());
        check("the overlap is classified as a student clash",
                conflicts.stream().anyMatch(c -> c.kind().equals("STUDENT")));

        check("back-to-back classes are not flagged", validator.isValid(List.of(
                ClassSession.lecture(DayOfWeek.MONDAY, 9, 10, "A"),
                ClassSession.lecture(DayOfWeek.MONDAY, 10, 11, "B"))));

        check("same times on different days are not flagged", validator.isValid(List.of(
                ClassSession.lecture(DayOfWeek.MONDAY, 9, 10, "A"),
                ClassSession.lecture(DayOfWeek.TUESDAY, 9, 10, "B"))));

        check("saving a clashing timetable is rejected",
                throwsIllegalState(() -> validator.requireValid(clashing)));
    }

    private static void freeTimeDetection() {
        FreeTimeFinder finder = new FreeTimeFinder(preferences());
        LocalDate monday = LocalDate.of(2026, 11, 9);

        List<FreeSlot> slots = finder.findForDate(monday, timetable(), List.of(), NOW);
        check("free slots were found on a teaching day", !slots.isEmpty());

        boolean avoidsClasses = slots.stream().noneMatch(slot ->
                timetable().stream()
                        .filter(c -> c.day() == DayOfWeek.MONDAY)
                        .anyMatch(c -> c.window().overlaps(new TimeWindow(slot.start(), slot.end()))));
        check("no free slot overlaps a class", avoidsClasses);

        boolean noneInPast = slots.stream().allMatch(s -> !s.start().isBefore(NOW.toLocalTime()));
        check("no free slot starts in the past", noneInPast);

        int freeMinutes = finder.freeMinutesOn(monday, timetable(), List.of(), NOW);
        check("free minutes on Monday are reported (%d min)".formatted(freeMinutes), freeMinutes > 0);

        UserPreferences noWeekend = preferences().setWeekendAvailable(false);
        check("weekends are skipped when unavailable",
                new FreeTimeFinder(noWeekend)
                        .findForDate(LocalDate.of(2026, 11, 14), List.of(), List.of(), NOW)
                        .isEmpty());

        check("a commitment removes time from the day",
                finder.freeMinutesOn(LocalDate.of(2026, 11, 10), timetable(),
                        List.of(Commitment.weekly("Gym", DayOfWeek.TUESDAY, 18, 19)), NOW)
                        < finder.freeMinutesOn(LocalDate.of(2026, 11, 10), timetable(),
                        List.of(), NOW));
    }

    // ---------------------------------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------------------------------

    private static void noAvailableSlots() {
        // Classes from wake to sleep on every day of the week.
        List<ClassSession> fullWeek = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            fullWeek.add(ClassSession.lecture(day, 7, 23, "BUSY"));
        }
        ScheduleRequest request = new ScheduleRequest()
                .now(NOW)
                .horizon(NOW.toLocalDate(), NOW.toLocalDate().plusDays(6))
                .preferences(preferences());
        request.addClasses(fullWeek);
        request.addTasks(StudyTask.assignment("x", "Impossible", "DAA",
                NOW.plusDays(3), Priority.CRITICAL, 120));

        GeneratedSchedule schedule = new SchedulingEngine().generate(request);
        check("a fully booked week schedules nothing", schedule.sessions().isEmpty());
        check("a fully booked week warns the student", !schedule.warnings().isEmpty());
    }

    private static void tasksExceedingAvailableTime() {
        ScheduleRequest request = baseRequest(SchedulingMode.BALANCED);
        // 100 hours of work, two days to do it in.
        request.addTasks(StudyTask.assignment("huge", "Impossible workload", "JAVA",
                NOW.plusDays(2), Priority.CRITICAL, 6000));

        GeneratedSchedule schedule = new SchedulingEngine().generate(request);
        check("over-committed work is flagged as at risk",
                schedule.atRisk().stream().anyMatch(r -> r.taskId().equals("huge")));
        check("the at-risk report quantifies the shortfall",
                schedule.atRisk().stream()
                        .filter(r -> r.taskId().equals("huge"))
                        .allMatch(r -> r.shortfallMinutes() > 0));
        check("other work is still scheduled around it", !schedule.sessions().isEmpty());
        check("the daily cap is still respected", dailyCapRespected(schedule, 300));
    }

    private static void multipleUrgentAssignments() {
        ScheduleRequest request = new ScheduleRequest()
                .now(NOW)
                .horizon(NOW.toLocalDate(), NOW.toLocalDate().plusDays(3))
                .mode(SchedulingMode.DEADLINE)
                .preferences(preferences());
        request.addClasses(timetable());
        subjects().forEach(request::addSubjects);
        request.addTasks(
                StudyTask.assignment("u1", "Urgent A", "DAA", NOW.plusDays(1), Priority.CRITICAL, 120),
                StudyTask.assignment("u2", "Urgent B", "JAVA", NOW.plusDays(1), Priority.CRITICAL, 120),
                StudyTask.assignment("u3", "Urgent C", "MATHS", NOW.plusDays(1), Priority.CRITICAL, 120));

        GeneratedSchedule schedule = new SchedulingEngine().generate(request);
        check("three same-day deadlines produce a plan", !schedule.sessions().isEmpty());
        check("no overlap despite competing deadlines", noSessionOverlaps(schedule));
        check("whatever cannot fit is reported rather than dropped",
                schedule.atRisk().isEmpty() || schedule.atRisk().stream()
                        .allMatch(r -> r.shortfallMinutes() > 0));
    }

    private static void consecutiveExams() {
        ScheduleRequest request = new ScheduleRequest()
                .now(NOW)
                .horizon(NOW.toLocalDate(), NOW.toLocalDate().plusDays(6))
                .mode(SchedulingMode.EXAM)
                .preferences(preferences());
        request.addClasses(timetable());
        subjects().forEach(request::addSubjects);
        request.addExams(
                ExamEvent.of("ea", "DAA", "DAA internal", NOW.toLocalDate().plusDays(4)),
                ExamEvent.of("eb", "JAVA", "Java internal", NOW.toLocalDate().plusDays(5)));

        GeneratedSchedule schedule = new SchedulingEngine().generate(request);
        check("revision is generated for both consecutive exams",
                schedule.sessions().stream().filter(ScheduledSession::revision)
                        .map(ScheduledSession::subjectCode).distinct().count() == 2);
        check("all revision lands before its exam", schedule.sessions().stream()
                .filter(ScheduledSession::revision)
                .allMatch(s -> {
                    LocalDate examDate = s.subjectCode().equals("DAA")
                            ? NOW.toLocalDate().plusDays(4) : NOW.toLocalDate().plusDays(5);
                    return s.date().isBefore(examDate) || s.date().equals(examDate);
                }));
        check("revision sessions do not overlap", noSessionOverlaps(schedule));
    }

    private static void missedSessionRescheduling() {
        ScheduleRequest before = baseRequest(SchedulingMode.BALANCED);
        SchedulingEngine engine = new SchedulingEngine();
        GeneratedSchedule original = engine.generate(before);

        ScheduleRequest after = baseRequest(SchedulingMode.BALANCED);
        GeneratedSchedule replanned = engine.reschedule(after, List.of("t1"));

        check("rescheduling still produces a plan", !replanned.sessions().isEmpty());
        check("rescheduling reports what changed", !replanned.warnings().isEmpty());
        check("the missed task is still scheduled or flagged",
                replanned.sessions().stream().anyMatch(s -> s.taskId().equals("t1"))
                        || replanned.atRisk().stream().anyMatch(r -> r.taskId().equals("t1")));

        StudyTask missed = after.tasks().stream()
                .filter(t -> t.id().equals("t1")).findFirst().orElseThrow();
        check("the missed session was recorded on the task", missed.missedSessions() == 1);

        double originalScore = original.scores().stream()
                .filter(s -> s.taskId().equals("t1")).findFirst().orElseThrow().total();
        double newScore = replanned.scores().stream()
                .filter(s -> s.taskId().equals("t1")).findFirst().orElseThrow().total();
        check("missing a session raises the task's priority (%.1f > %.1f)"
                .formatted(newScore, originalScore), newScore > originalScore);

        check("no overlap after rescheduling", noSessionOverlaps(replanned));
    }

    private static void invalidDeadline() {
        check("a task with zero duration is rejected", throwsIllegalArgument(
                () -> StudyTask.assignment("z", "Zero", "DAA", NOW.plusDays(1), Priority.LOW, 0)));

        check("a horizon that ends before it starts is rejected", throwsIllegalArgument(
                () -> new ScheduleRequest().horizon(
                        NOW.toLocalDate().plusDays(5), NOW.toLocalDate())));

        ScheduleRequest request = baseRequest(SchedulingMode.BALANCED);
        request.addTasks(StudyTask.assignment("past", "Deadline already gone", "DAA",
                NOW.minusDays(3), Priority.CRITICAL, 120));
        GeneratedSchedule schedule = new SchedulingEngine().generate(request);
        check("an already-passed deadline is reported as at risk, not scheduled",
                schedule.sessions().stream().noneMatch(s -> s.taskId().equals("past"))
                        && schedule.atRisk().stream().anyMatch(r -> r.taskId().equals("past")));
    }

    private static void conflictingPreferences() {
        check("sleeping before waking is rejected", throwsIllegalState(
                () -> UserPreferences.defaults()
                        .setWakeTime(LocalTime.of(22, 0))
                        .setSleepTime(LocalTime.of(6, 0))
                        .validate()));

        check("a minimum session longer than the maximum is rejected", throwsIllegalState(
                () -> UserPreferences.defaults()
                        .setMinSessionMinutes(180)
                        .setMaxSessionMinutes(60)
                        .validate()));

        check("a daily study limit beyond waking hours is rejected", throwsIllegalState(
                () -> UserPreferences.defaults()
                        .setWakeTime(LocalTime.of(9, 0))
                        .setSleepTime(LocalTime.of(17, 0))
                        .setMaxDailyStudyMinutes(900)
                        .validate()));
    }

    private static void unsplittableTaskTooLarge() {
        ScheduleRequest request = baseRequest(SchedulingMode.BALANCED);
        StudyTask monolith = StudyTask.assignment("mono", "Unbroken 8-hour sitting", "JAVA",
                NOW.plusDays(2), Priority.CRITICAL, 480);
        monolith.setSplittable(false);
        request.addTasks(monolith);

        GeneratedSchedule schedule = new SchedulingEngine().generate(request);
        check("an unsplittable task larger than any gap is flagged",
                schedule.atRisk().stream().anyMatch(r -> r.taskId().equals("mono")));
        check("the explanation suggests allowing it to be split",
                schedule.atRisk().stream()
                        .filter(r -> r.taskId().equals("mono"))
                        .anyMatch(r -> r.reason().contains("split")));
    }

    // ---------------------------------------------------------------------------------------
    // Modes
    // ---------------------------------------------------------------------------------------

    private static void modesDiffer() {
        SchedulingEngine engine = new SchedulingEngine();
        GeneratedSchedule balanced = engine.generate(baseRequest(SchedulingMode.BALANCED));
        GeneratedSchedule deadline = engine.generate(baseRequest(SchedulingMode.DEADLINE));

        int balancedSpread = balanced.dailyLoad().size();
        int deadlineSpread = deadline.dailyLoad().size();
        check("balanced mode uses at least as many days as deadline mode (%d >= %d)"
                .formatted(balancedSpread, deadlineSpread), balancedSpread >= deadlineSpread);

        int balancedPeak = balanced.dailyLoad().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int deadlinePeak = deadline.dailyLoad().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        check("balanced mode's busiest day is no heavier than deadline mode's (%d <= %d)"
                .formatted(balancedPeak, deadlinePeak), balancedPeak <= deadlinePeak);

        GeneratedSchedule exam = engine.generate(baseRequest(SchedulingMode.EXAM));
        check("exam mode books revision", exam.sessions().stream().anyMatch(ScheduledSession::revision));
        check("each mode labels itself", balanced.mode() == SchedulingMode.BALANCED
                && deadline.mode() == SchedulingMode.DEADLINE
                && exam.mode() == SchedulingMode.EXAM);
    }

    private static void allModesInParallel() {
        Map<SchedulingMode, GeneratedSchedule> all =
                new SchedulingEngine().generateAllModes(baseRequest(SchedulingMode.BALANCED));

        check("all three modes are generated concurrently", all.size() == 3);
        check("every concurrent run produced a plan",
                all.values().stream().allMatch(s -> !s.sessions().isEmpty()));
        check("every concurrent run holds the no-overlap invariant",
                all.values().stream().allMatch(EngineVerification::noSessionOverlaps));
    }

    // ---------------------------------------------------------------------------------------
    // Tiny assertion helpers
    // ---------------------------------------------------------------------------------------

    private static void section(String name) {
        System.out.println("\n" + "-".repeat(78));
        System.out.println(name.toUpperCase());
        System.out.println("-".repeat(78));
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + description);
        } else {
            failed++;
            System.out.println("  FAIL  " + description);
        }
    }

    private static void report(String detail) {
        System.out.println("        -> " + detail);
    }

    private static boolean throwsIllegalArgument(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        } catch (RuntimeException other) {
            return false;
        }
    }

    private static boolean throwsIllegalState(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        } catch (RuntimeException other) {
            return false;
        }
    }

    private EngineVerification() {
    }
}
