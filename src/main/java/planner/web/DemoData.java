package planner.web;

import planner.domain.Records.Highlighter;
import planner.domain.Records.Kind;
import planner.domain.Records.Priority;
import planner.domain.Store;
import planner.engine.model.SchedulingMode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** A believable second-year computer science semester, so a new account has something to look at. */
final class DemoData {

    private DemoData() { }

    static void load(Ctx ctx, long uid, String sessionId) {
        Store s = ctx.store();
        LocalDateTime now = ctx.now();
        LocalDate today = now.toLocalDate();

        long daa = s.addSubject(uid, "CS301", "Design and Analysis of Algorithms", 5, Highlighter.pink);
        long dbms = s.addSubject(uid, "CS302", "Database Systems", 3, Highlighter.blue);
        long prob = s.addSubject(uid, "MA201", "Probability and Statistics", 4, Highlighter.green);
        long writ = s.addSubject(uid, "HS110", "Technical Writing", 2, Highlighter.orange);

        cls(s, uid, daa, Kind.LECTURE, DayOfWeek.MONDAY, "09:00", "10:30", "LT-2");
        cls(s, uid, dbms, Kind.LECTURE, DayOfWeek.MONDAY, "11:00", "12:00", "LT-4");
        cls(s, uid, prob, Kind.LECTURE, DayOfWeek.TUESDAY, "10:00", "11:30", "LT-1");
        cls(s, uid, dbms, Kind.LAB, DayOfWeek.TUESDAY, "14:00", "16:00", "Lab 3");
        cls(s, uid, daa, Kind.LECTURE, DayOfWeek.WEDNESDAY, "09:00", "10:30", "LT-2");
        cls(s, uid, writ, Kind.TUTORIAL, DayOfWeek.WEDNESDAY, "13:00", "14:00", "Room 210");
        cls(s, uid, prob, Kind.LECTURE, DayOfWeek.THURSDAY, "10:00", "11:30", "LT-1");
        cls(s, uid, daa, Kind.TUTORIAL, DayOfWeek.THURSDAY, "15:00", "16:00", "Room 104");
        cls(s, uid, dbms, Kind.LECTURE, DayOfWeek.FRIDAY, "09:00", "10:00", "LT-4");
        s.addTimetableEntry(uid, null, Kind.OTHER, "Football practice", DayOfWeek.WEDNESDAY,
                LocalTime.of(17, 30), LocalTime.of(19, 0), null);

        s.addTask(uid, daa, "Problem set 4: dynamic programming", "Questions 1 to 6. Question 5 needs a proof.",
                Priority.HIGH, at(today.plusDays(3), "23:59"), 300, true);
        s.addTask(uid, dbms, "ER diagram for the library project", null,
                Priority.MEDIUM, at(today.plusDays(5), "17:00"), 150, true);
        s.addTask(uid, prob, "Worksheet 6: conditional probability", null,
                Priority.MEDIUM, at(today.plusDays(2), "09:00"), 90, true);
        s.addTask(uid, writ, "Draft of the lab report", "Methods and results sections only.",
                Priority.LOW, at(today.plusDays(6), "23:59"), 120, true);
        s.addTask(uid, dbms, "SQL quiz practice", null,
                Priority.CRITICAL, at(today.plusDays(1), "12:00"), 60, false);
        s.addTask(uid, prob, "Read chapter 4 notes", null,
                Priority.LOW, at(today.minusDays(1), "18:00"), 45, false);

        long exam = s.addExam(uid, daa, "Mid-semester exam", today.plusDays(9), LocalTime.of(10, 0), 30);
        s.addChapters(uid, exam, List.of("Asymptotic analysis", "Divide and conquer", "Greedy algorithms",
                "Dynamic programming", "Graph search", "Shortest paths"));
        int i = 0;
        for (var c : s.chapters(uid, exam)) {
            if (i++ < 2) {
                s.setChapterDone(uid, c.id(), true);
            }
        }
        long exam2 = s.addExam(uid, prob, "Quiz 2", today.plusDays(4), LocalTime.of(14, 0), 10);
        s.addChapters(uid, exam2, List.of("Bayes' theorem", "Random variables", "Expectation"));

        ctx.planner().preview(sessionId, uid, SchedulingMode.BALANCED, now);
        ctx.planner().save(sessionId, uid, now);
    }

    private static void cls(Store s, long uid, long subject, Kind kind, DayOfWeek day, String from, String to, String room) {
        s.addTimetableEntry(uid, subject, kind, null, day, LocalTime.parse(from), LocalTime.parse(to), room);
    }

    private static LocalDateTime at(LocalDate date, String time) {
        return LocalDateTime.of(date, LocalTime.parse(time));
    }
}
