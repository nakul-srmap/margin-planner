package planner.ai;

import planner.domain.Records.Priority;
import planner.domain.Records.Subject;
import planner.json.Json;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns plain English (or text pasted from Google Classroom) into tasks and exams. The AI only
 * reads and estimates; the scheduling engine still decides where study goes.
 */
public final class Assistant {

    /** Something to add, as understood from the student's words. */
    public record Draft(boolean exam, String title, String subjectCode, LocalDate date, LocalTime time,
                        int minutes, Priority priority, String note) { }

    private Assistant() { }

    public static List<Draft> understand(String text, List<Subject> subjects, LocalDateTime now)
            throws IOException, InterruptedException {
        return read(Gemini.json(prompt(text, subjects, now)), subjects, now);
    }

    static String prompt(String text, List<Subject> subjects, LocalDateTime now) {
        StringBuilder list = new StringBuilder();
        for (Subject s : subjects) {
            list.append("- ").append(s.code()).append(": ").append(s.name()).append(" (difficulty ").append(s.difficulty()).append("/5)\n");
        }
        return """
                You help a university student plan study. Read their note and list every assignment, task or exam in it.
                Today is %s, %s. Resolve relative dates ("Friday", "next week", "in 3 days") against today.
                Their subjects:
                %s
                Reply with JSON only, shaped like:
                {"items":[{"kind":"task" or "exam","title":"short title","subject":"one subject code from the list or null",
                "date":"YYYY-MM-DD","time":"HH:MM or null","minutes":number,"priority":"LOW|MEDIUM|HIGH|CRITICAL","note":"one short tip"}]}
                Rules: "date" is the due date for a task and the exam date for an exam.
                "minutes" is the total study time needed. Use the student's own estimate if they gave one; otherwise
                estimate realistically for an undergraduate (a short worksheet 45-90, a lab report 150-240, a project
                600+, exam revision 240-900 depending on the subject's difficulty).
                Priority is CRITICAL only for things due within a day or worth a lot; LOW for optional work.
                The note is one practical sentence on how to approach it. Skip anything with no date you can work out.
                Student's note:
                \"\"\"%s\"\"\"
                """.formatted(now.toLocalDate(), now.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH),
                list.length() == 0 ? "(none yet)\n" : list, text);
    }

    /** Checks every field the model sent back and fills safe defaults, so a bad answer can't add nonsense. */
    public static List<Draft> read(String json, List<Subject> subjects, LocalDateTime now) {
        Object root;
        try {
            root = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new Gemini.AiException("Gemini's answer couldn't be read. Try again.");
        }
        List<Object> items = root instanceof List<?> ? Json.arr(root) : Json.arr(Json.at(root, "items"));
        List<Draft> out = new ArrayList<>();
        for (Object o : items) {
            Map<String, Object> m = Json.obj(o);
            String title = Json.str(m.get("title"));
            LocalDate date = date(Json.str(m.get("date")));
            if (title == null || title.isBlank() || date == null || date.isBefore(now.toLocalDate().minusDays(1))
                    || date.isAfter(now.toLocalDate().plusYears(1))) {
                continue;
            }
            boolean exam = "exam".equalsIgnoreCase(Json.str(m.get("kind")));
            String code = Json.str(m.get("subject"));
            String matched = null;
            for (Subject s : subjects) {
                if (code != null && s.code().replace(" ", "").equalsIgnoreCase(code.replace(" ", ""))) {
                    matched = s.code();
                }
            }
            Integer minutes = Json.num(m.get("minutes"));
            int mins = minutes == null ? (exam ? 360 : 120) : Math.max(15, Math.min(6000, Math.round(minutes / 5f) * 5));
            Priority p;
            try {
                p = Priority.valueOf(String.valueOf(Json.str(m.get("priority"))).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                p = Priority.MEDIUM;
            }
            String note = Json.str(m.get("note"));
            out.add(new Draft(exam, cut(title.trim(), exam ? 120 : 160), matched, date, time(Json.str(m.get("time"))),
                    mins, p, note == null ? null : cut(note.trim(), 300)));
        }
        return out;
    }

    private static LocalDate date(String s) {
        try {
            return s == null ? null : LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalTime time(String s) {
        try {
            return s == null || s.isBlank() || s.equals("null") ? null : LocalTime.parse(s.trim().length() == 4 ? "0" + s.trim() : s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
