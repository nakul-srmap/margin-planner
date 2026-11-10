package planner.web;

import planner.db.DataException;
import planner.domain.Records.Chapter;
import planner.domain.Records.Exam;
import planner.domain.Records.Highlighter;
import planner.domain.Records.Kind;
import planner.domain.Records.Preferences;
import planner.domain.Records.Subject;
import planner.domain.Records.TimetableEntry;
import planner.domain.Text;
import planner.http.HttpException;
import planner.http.Html;
import planner.http.Request;
import planner.http.Response;
import planner.http.Router;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Subjects, the weekly timetable, exams and planning settings. */
final class SetupPages {

    private final Ctx ctx;

    SetupPages(Ctx ctx) {
        this.ctx = ctx;
    }

    void routes(Router r) {
        r.get("/subjects", req -> Response.html(subjects(req, null)));
        r.post("/subjects", this::addSubject);
        r.get("/subjects/{id}", req -> {
            Subject s = ctx.store().subject(req.userId(), req.id("id")).orElseThrow(HttpException::notFound);
            return Response.html(editSubject(req, s, Form.blank().set("code", s.code()).set("name", s.name())
                    .set("difficulty", s.difficulty()).set("highlighter", s.highlighter())));
        });
        r.post("/subjects/{id}", this::updateSubject);
        r.post("/subjects/{id}/delete", req -> {
            if (!ctx.store().deleteSubject(req.userId(), req.id("id"))) {
                throw HttpException.notFound();
            }
            return Response.redirect("/subjects").flash("Subject deleted, with its classes and exams. Its tasks were kept.");
        });

        r.get("/timetable", req -> Response.html(timetable(req, null)));
        r.post("/timetable", this::addClass);
        r.post("/timetable/{id}/delete", req -> {
            if (!ctx.store().deleteTimetableEntry(req.userId(), req.id("id"))) {
                throw HttpException.notFound();
            }
            return Response.redirect("/timetable").flash("Removed from your timetable.");
        });

        r.get("/exams", req -> Response.html(exams(req, null)));
        r.post("/exams", this::addExam);
        r.post("/exams/{id}/delete", req -> {
            if (!ctx.store().deleteExam(req.userId(), req.id("id"))) {
                throw HttpException.notFound();
            }
            return Response.redirect("/exams").flash("Exam deleted.");
        });
        r.post("/exams/{id}/chapters", req -> {
            long examId = req.id("id");
            List<String> names = Arrays.stream(req.text("chapters").split("\\R"))
                    .map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.length() > 140 ? s.substring(0, 140) : s)
                    .limit(60).toList();
            if (names.isEmpty()) {
                return Response.redirect("/exams#exam-" + examId).flash("Type at least one chapter, one per line.");
            }
            if (!ctx.store().addChapters(req.userId(), examId, names)) {
                throw HttpException.notFound();
            }
            return Response.redirect("/exams#exam-" + examId).flash("Added " + Text.plural(names.size(), "chapter", "chapters") + ".");
        });
        r.post("/chapters/{id}/done", req -> {
            if (!ctx.store().setChapterDone(req.userId(), req.id("id"), "1".equals(req.text("done")))) {
                throw HttpException.notFound();
            }
            return Response.redirect(Ctx.safe(req.text("back"), "/exams"));
        });
        r.post("/chapters/{id}/delete", req -> {
            if (!ctx.store().deleteChapter(req.userId(), req.id("id"))) {
                throw HttpException.notFound();
            }
            return Response.redirect(Ctx.safe(req.text("back"), "/exams")).flash("Chapter removed.");
        });

        r.get("/settings", req -> Response.html(settings(req, fromPrefs(ctx.store().preferences(req.userId())))));
        r.post("/settings", this::saveSettings);
    }

    /* ======================= Subjects ======================= */

    private record SubjectFields(String code, String name, int difficulty, Highlighter colour) { }

    private static SubjectFields readSubject(Form f) {
        String code = f.required("code", 12, "Give it a short code, like CS301.").toUpperCase();
        String name = f.required("name", 100, "Give the subject its full name.");
        int difficulty = f.integer("difficulty", 1, 5, "Choose how hard it is.");
        return new SubjectFields(code, name, difficulty, Highlighter.parse(f.v("highlighter")));
    }

    private Response addSubject(Request req) {
        Form f = Form.submitted(req);
        SubjectFields v = readSubject(f);
        if (f.ok()) {
            try {
                ctx.store().addSubject(req.userId(), v.code(), v.name(), v.difficulty(), v.colour());
                return Response.redirect("/subjects").flash("Added " + v.code() + ".");
            } catch (DataException e) {
                if (!e.isUniqueViolation()) throw e;
                f.fail("code", "You already have a subject with this code.");
            }
        }
        return Response.html(400, subjects(req, f));
    }

    private Response updateSubject(Request req) {
        Subject s = ctx.store().subject(req.userId(), req.id("id")).orElseThrow(HttpException::notFound);
        Form f = Form.submitted(req);
        SubjectFields v = readSubject(f);
        if (f.ok()) {
            try {
                ctx.store().updateSubject(req.userId(), s.id(), v.code(), v.name(), v.difficulty(), v.colour());
                return Response.redirect("/subjects").flash("Saved " + v.code() + ".");
            } catch (DataException e) {
                if (!e.isUniqueViolation()) throw e;
                f.fail("code", "You already have a subject with this code.");
            }
        }
        return Response.html(400, editSubject(req, s, f));
    }

    private String subjectForm(Request req, Form f, String action, String button) {
        return Html.t("""
                <form class="sheet" method="post" action="{{action}}" novalidate>
                  {{{csrf}}}{{{summary}}}
                  <div class="fields two">{{{code}}}{{{difficulty}}}{{{name}}}</div>
                  <fieldset class="swatches section" style="margin-top:1rem"><legend>Highlighter</legend>{{{swatches}}}</fieldset>
                  <div class="form-foot"><button class="button" type="submit">{{button}}</button></div>
                </form>""",
                "action", action, "csrf", Html.csrf(req), "summary", f.summary(),
                "code", f.input("code", "Code", "text", null, "required maxlength=\"12\" placeholder=\"CS301\""),
                "difficulty", f.select("difficulty", "Difficulty", null,
                        "1", "1, easy", "2", "2", "3", "3, average", "4", "4", "5", "5, very hard"),
                "name", f.input("name", "Name", "text", null, "required maxlength=\"100\" placeholder=\"Design and Analysis of Algorithms\"")
                        .replace("class=\"field\"", "class=\"field wide\""),
                "swatches", swatches(f.v("highlighter")), "button", button);
    }

    private static String swatches(String chosen) {
        StringBuilder out = new StringBuilder();
        for (Highlighter h : Highlighter.values()) {
            out.append("<label class=\"swatch\" style=\"--c:var(--hl-").append(h.name()).append(")\"><input type=\"radio\" name=\"highlighter\" value=\"")
                    .append(h.name()).append('"').append(Html.checked(h.name().equals(chosen)))
                    .append(" aria-label=\"").append(h.name()).append("\"><span></span></label>");
        }
        return out.toString();
    }

    private String subjects(Request req, Form submitted) {
        long uid = req.userId();
        List<Subject> list = ctx.store().subjects(uid);
        Form f = submitted != null ? submitted
                : Form.blank().set("difficulty", 3).set("highlighter", ctx.store().nextHighlighter(uid));
        StringBuilder rows = new StringBuilder();
        for (Subject s : list) {
            rows.append(Html.t("""
                    <tr><td>{{{code}}}</td><td><a href="/subjects/{{id}}">{{name}}</a></td><td class="num">{{difficulty}} of 5</td><td class="num">{{open}}</td><td style="width:9rem"><div class="meter" title="{{pct}} of chapters covered"><span style="width:{{pct}}"></span></div></td></tr>""",
                    "code", Layout.hl(s.code(), s.highlighter()), "id", s.id(), "name", s.name(),
                    "difficulty", s.difficulty(), "open", s.openTasks(),
                    "pct", Math.round(s.preparedness() * 100) + "%"));
        }
        String table = list.isEmpty()
                ? "<section class=\"empty\"><h2>No subjects yet</h2><p>Each subject gets a highlighter colour, and everything for it is marked in that colour: classes, tasks, exams and study.</p></section>"
                : "<table class=\"ledger\"><thead><tr><th>Code</th><th>Name</th><th class=\"num\">Difficulty</th><th class=\"num\">Open tasks</th><th>Exam prep</th></tr></thead><tbody>"
                + rows + "</tbody></table>";
        String body = Layout.head("Subjects", null, null)
                + "<div class=\"split-wide\"><div>" + table + "</div><aside><h2 style=\"margin-bottom:.8rem\">Add a subject</h2>"
                + subjectForm(req, f, "/subjects", "Add subject")
                + "<p class=\"note\" style=\"margin-top:1rem\">Harder subjects get a little more weight when planning, and no more than "
                + ctx.store().preferences(uid).maxHardPerDay() + " hard sessions land on one day.</p></aside></div>";
        return Layout.page(req, "/subjects", "Subjects", body);
    }

    private String editSubject(Request req, Subject s, Form f) {
        String body = Layout.head(s.code() + ", " + s.name(), null, null)
                + "<div class=\"split-wide\"><div>" + subjectForm(req, f, "/subjects/" + s.id(), "Save changes") + "</div><aside>"
                + Layout.button(req, "/subjects/" + s.id() + "/delete", "Delete subject", "button danger",
                "Delete " + s.code() + "? Its classes, exams and planned study go too. Tasks are kept without a subject.")
                + "</aside></div>";
        return Layout.page(req, "/subjects", s.code(), body);
    }

    /* ======================= Timetable ======================= */

    private Response addClass(Request req) {
        long uid = req.userId();
        Form f = Form.submitted(req);
        Kind kind;
        try {
            kind = Kind.valueOf(f.v("kind"));
        } catch (IllegalArgumentException e) {
            kind = Kind.LECTURE;
        }
        Long subjectId = f.id("subject");
        String label = f.optional("label", 60);
        if (kind.isClass() && subjectId == null) {
            f.fail("subject", "Choose which subject this class is for.");
        }
        if (!kind.isClass() && label == null) {
            f.fail("label", "Say what it is, like “Part-time job”.");
        }
        int dayNumber = f.integer("day", 1, 7, "Choose a day.");
        LocalTime start = f.time("start", "Use a time like 09:00.");
        LocalTime end = f.time("end", "Use a time like 10:30.");
        String room = f.optional("room", 40);
        if (f.ok() && !end.isAfter(start)) {
            f.fail("end", "It has to end after it starts.");
        }
        DayOfWeek day = DayOfWeek.of(dayNumber);
        if (f.ok()) {
            Optional<String> clash = ctx.planner().clash(uid, day, start, end);
            if (clash.isPresent()) {
                f.fail("start", clash.get());
            }
        }
        if (!f.ok()) {
            return Response.html(400, timetable(req, f));
        }
        ctx.store().addTimetableEntry(uid, kind.isClass() ? subjectId : null, kind, kind.isClass() ? null : label,
                day, start, end, room);
        return Response.redirect("/timetable").flash("Added to " + Text.dayName(day) + "." + ctx.autoPlan(uid));
    }

    private String timetable(Request req, Form submitted) {
        long uid = req.userId();
        List<Subject> subjects = ctx.store().subjects(uid);
        List<TimetableEntry> entries = ctx.store().timetable(uid);
        Form f = submitted != null ? submitted
                : Form.of(Map.of("kind", "LECTURE", "day", "1", "start", "09:00", "end", "10:00"));

        LocalDate monday = ctx.now().toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<WeekGrid.Cell> cells = new ArrayList<>();
        StringBuilder rows = new StringBuilder();
        for (TimetableEntry e : entries) {
            String meta = e.room() == null ? e.kind().label() : e.kind().label() + ", " + e.room();
            cells.add(new WeekGrid.Cell(monday.plusDays(e.day().getValue() - 1), e.start(), e.end(), true,
                    e.title(), meta, WeekPages.hlName(e), null, null));
            rows.append(Html.t("""
                    <tr><td>{{day}}</td><td class="nowrap">{{from}} to {{to}}</td><td>{{{what}}}</td><td>{{kind}}</td><td>{{room}}</td><td class="num">{{{remove}}}</td></tr>""",
                    "day", Text.dayName(e.day()), "from", Text.time(e.start()), "to", Text.time(e.end()),
                    "what", e.kind().isClass() ? Layout.hl(e.subjectCode(), e.highlighter()) + " " + Html.esc(e.subjectName()) : Html.esc(e.label()),
                    "kind", e.kind().label(), "room", e.room() == null ? "" : e.room(),
                    "remove", Layout.button(req, "/timetable/" + e.id() + "/delete", "Remove", "link-button danger", "Remove this from your timetable?")));
        }

        String[] days = new String[14];
        for (int i = 0; i < 7; i++) {
            days[i * 2] = String.valueOf(i + 1);
            days[i * 2 + 1] = Text.dayName(DayOfWeek.of(i + 1));
        }
        String form = Html.t("""
                <form class="sheet" method="post" action="/timetable" novalidate>
                  {{{csrf}}}{{{summary}}}
                  <div class="fields two">{{{kind}}}{{{subject}}}{{{label}}}{{{day}}}{{{room}}}{{{start}}}{{{end}}}</div>
                  <div class="form-foot"><button class="button" type="submit">Add to timetable</button></div>
                </form>""",
                "csrf", Html.csrf(req), "summary", f.summary(),
                "kind", f.select("kind", "What", null, "LECTURE", "Lecture", "LAB", "Lab", "TUTORIAL", "Tutorial", "OTHER", "Something else"),
                "subject", f.select("subject", "Subject", "for classes", Ctx.subjectOptions(subjects, "Choose")),
                "label", f.input("label", "Name", "text", "for something else", "maxlength=\"60\" placeholder=\"Part-time job\""),
                "day", f.select("day", "Day", null, days),
                "room", f.input("room", "Room", "text", "optional", "maxlength=\"40\""),
                "start", f.input("start", "Starts", "time", null, "required"),
                "end", f.input("end", "Ends", "time", null, "required"));

        String body = Layout.head("Timetable", "Your regular week. Study is never planned over anything here.",
                "<a class=\"button\" href=\"/import\">Import from SRM portal</a>")
                + (entries.isEmpty() ? "" : WeekGrid.render(monday, ctx.store().preferences(uid), cells, ctx.now(), false).replaceAll("(?s)<p class=\"legend\">.*?</p>", ""))
                + "<div class=\"split-wide section\"><div>"
                + (entries.isEmpty()
                ? "<section class=\"empty\"><h2>Your timetable is empty</h2><p>Add your lectures, labs and tutorials, plus anything else that takes up regular time, like a job or practice.</p></section>"
                : "<h2 style=\"margin-bottom:.8rem\">Every entry</h2><table class=\"ledger\"><thead><tr><th>Day</th><th>Time</th><th>What</th><th>Type</th><th>Room</th><th></th></tr></thead><tbody>" + rows + "</tbody></table>")
                + "</div><aside><h2 style=\"margin-bottom:.8rem\">Add to the timetable</h2>" + form
                + (subjects.isEmpty() ? "<p class=\"note\" style=\"margin-top:1rem\"><a href=\"/subjects\">Add a subject</a> before adding its classes.</p>" : "")
                + "</aside></div>";
        return Layout.page(req, "/timetable", "Timetable", body);
    }

    /* ======================= Exams ======================= */

    private Response addExam(Request req) {
        long uid = req.userId();
        Form f = Form.submitted(req);
        Long subjectId = f.id("subject");
        if (subjectId == null) {
            f.fail("subject", "Choose the subject.");
        }
        String title = f.required("title", 120, "Name the exam, like “End-semester”.");
        LocalDate date = f.date("date", "Choose the date.");
        LocalTime start = f.has("start") ? f.time("start", "Use a time like 10:00.") : null;
        int weight = f.has("weightage") ? f.integer("weightage", 1, 100, "Enter a percentage from 1 to 100.") : 30;
        if (f.ok() && date.isBefore(ctx.now().toLocalDate())) {
            f.fail("date", "That date has already passed.");
        }
        if (!f.ok()) {
            return Response.html(400, exams(req, f));
        }
        long id = ctx.store().addExam(uid, subjectId, title, date, start, weight);
        return Response.redirect("/exams#exam-" + id).flash("Added. List its chapters so revision can be tracked." + ctx.autoPlan(uid));
    }

    private String exams(Request req, Form submitted) {
        long uid = req.userId();
        LocalDate today = ctx.now().toLocalDate();
        List<Subject> subjects = ctx.store().subjects(uid);
        Form f = submitted != null ? submitted : Form.of(Map.of("date", today.plusDays(14).toString(), "start", "10:00", "weightage", "30"));

        StringBuilder list = new StringBuilder();
        for (Exam e : ctx.store().exams(uid)) {
            long days = ChronoUnit.DAYS.between(today, e.date());
            boolean past = days < 0;
            StringBuilder chapters = new StringBuilder();
            for (Chapter c : ctx.store().chapters(uid, e.id())) {
                chapters.append(Html.t("""
                        <li class="{{{done}}}"><form method="post" action="/chapters/{{id}}/done" data-async="chapter">{{{csrf}}}<input type="hidden" name="done" value="{{next}}"><input type="hidden" name="back" value="/exams#exam-{{exam}}"><button class="tick{{{on}}}" type="submit" aria-label="{{label}}" title="{{label}}"></button></form><span>{{name}}</span></li>""",
                        "done", c.done() ? "done" : "", "id", c.id(), "csrf", Html.csrf(req), "next", c.done() ? "0" : "1",
                        "exam", e.id(), "on", c.done() ? " on" : "", "label", (c.done() ? "Not covered yet: " : "Covered: ") + c.name(),
                        "name", c.name()));
            }
            list.append(Html.t("""
                    <article class="exam" id="exam-{{id}}">
                      <div class="exam-head">
                        <div class="days-left{{{close}}}"><b>{{days}}</b><span>{{unit}}</span></div>
                        <div>
                          <h2>{{{code}}} {{title}}</h2>
                          <div class="meta"><span>{{date}}{{time}}</span><span>{{subject}}</span>{{{weight}}}<span data-covered>{{covered}}</span></div>
                          <div class="meter" style="margin-top:.7rem;max-width:22rem"><span style="width:{{pct}}%"></span></div>
                        </div>
                        <div>{{{delete}}}</div>
                      </div>
                      <ul class="chapters">{{{chapters}}}</ul>
                      <details style="margin-top:.8rem"><summary>Add chapters</summary>
                        <form method="post" action="/exams/{{id}}/chapters" style="margin-top:.6rem;max-width:30rem">{{{csrf}}}
                          <label class="field">One per line<textarea name="chapters" rows="4" placeholder="Divide and conquer&#10;Greedy algorithms&#10;Dynamic programming"></textarea></label>
                          <div class="form-foot"><button class="button small" type="submit">Add chapters</button></div>
                        </form>
                      </details>
                    </article>""",
                    "id", e.id(), "close", !past && days <= 7 ? " close" : "",
                    "days", past ? "Done" : days == 0 ? "Today" : String.valueOf(days),
                    "unit", past || days == 0 ? "" : days == 1 ? "day to go" : "days to go",
                    "code", Layout.hl(e.subjectCode(), e.highlighter()), "title", e.title(),
                    "date", Text.date(e.date()), "time", e.start() == null ? "" : " at " + Text.time(e.start()),
                    "subject", e.subjectName(),
                    "weight", e.weightage() > 0 ? "<span>" + e.weightage() + "% of the grade</span>" : "",
                    "covered", e.chapters() == 0 ? "No chapters listed" : e.chaptersDone() + " of " + e.chapters() + " chapters covered",
                    "pct", Math.round(e.preparedness() * 100),
                    "delete", Layout.button(req, "/exams/" + e.id() + "/delete", "Delete", "link-button danger", "Delete this exam and its chapters?"),
                    "chapters", chapters.toString(), "csrf", Html.csrf(req)));
        }
        if (list.length() == 0) {
            list.append("<section class=\"empty\"><h2>No exams yet</h2><p>Add an exam and list its chapters. When you plan in exam mode, revision is booked in the days before it, weighted towards what you haven't covered.</p></section>");
        }

        String form = Html.t("""
                <form class="sheet" method="post" action="/exams" novalidate>
                  {{{csrf}}}{{{summary}}}
                  <div class="fields two">{{{subject}}}{{{title}}}{{{date}}}{{{start}}}{{{weight}}}</div>
                  <div class="form-foot"><button class="button" type="submit">Add exam</button></div>
                </form>""",
                "csrf", Html.csrf(req), "summary", f.summary(),
                "subject", f.select("subject", "Subject", null, Ctx.subjectOptions(subjects, "Choose")),
                "title", f.input("title", "Exam", "text", null, "required maxlength=\"120\" placeholder=\"Mid-semester\""),
                "date", f.input("date", "Date", "date", null, "required"),
                "start", f.input("start", "Starts", "time", "optional", ""),
                "weight", f.input("weightage", "Weight", "number", "% of the grade", "min=\"1\" max=\"100\""));

        String body = Layout.head("Exams", null, null) + SmartPages.datesheetBox(req)
                + "<div class=\"split-wide\"><div>" + list + "</div><aside><h2 style=\"margin-bottom:.8rem\">Add an exam</h2>" + form
                + (subjects.isEmpty() ? "<p class=\"note\" style=\"margin-top:1rem\"><a href=\"/subjects\">Add a subject</a> first.</p>" : "")
                + "</aside></div>";
        return Layout.page(req, "/exams", "Exams", body);
    }

    /* ======================= Settings ======================= */

    private static Form fromPrefs(Preferences p) {
        return Form.blank().set("wake", Text.time(p.wake())).set("sleep", Text.time(p.sleep()))
                .set("maxDaily", p.maxDailyMinutes()).set("minSession", p.minSessionMinutes())
                .set("maxSession", p.maxSessionMinutes()).set("breakMinutes", p.breakMinutes())
                .set("maxHard", p.maxHardPerDay()).set("weekend", p.weekendAvailable() ? "1" : "")
                .set("focusStart", Text.time(p.focusStart())).set("focusEnd", Text.time(p.focusEnd()));
    }

    private Response saveSettings(Request req) {
        Form f = Form.submitted(req);
        LocalTime wake = f.time("wake", "Use a time like 07:00.");
        LocalTime sleep = f.time("sleep", "Use a time like 23:00.");
        int maxDaily = f.integer("maxDaily", 30, 720, "Between 30 and 720 minutes.");
        int minSession = f.integer("minSession", 10, 120, "Between 10 and 120 minutes.");
        int maxSession = f.integer("maxSession", 20, 240, "Between 20 and 240 minutes.");
        int breakMinutes = f.integer("breakMinutes", 0, 60, "Between 0 and 60 minutes.");
        int maxHard = f.integer("maxHard", 1, 6, "Between 1 and 6.");
        LocalTime focusStart = f.time("focusStart", "Use a time like 17:00.");
        LocalTime focusEnd = f.time("focusEnd", "Use a time like 21:00.");
        if (f.ok() && !sleep.isAfter(wake)) f.fail("sleep", "Bedtime has to be after you wake up.");
        if (f.ok() && minSession > maxSession) f.fail("maxSession", "This can't be shorter than the shortest session.");
        if (f.ok() && !focusEnd.isAfter(focusStart)) f.fail("focusEnd", "Focus hours have to end after they start.");
        if (!f.ok()) {
            return Response.html(400, settings(req, f));
        }
        ctx.store().savePreferences(req.userId(), new planner.domain.Records.Preferences(wake, sleep, maxDaily, minSession,
                maxSession, breakMinutes, maxHard, f.bool("weekend"), focusStart, focusEnd));
        return Response.redirect("/settings").flash("Settings saved." + ctx.autoPlan(req.userId()));
    }

    private String settings(Request req, Form f) {
        String body = Layout.head("Settings", "How Margin plans your days.", null) + Html.t("""
                <form class="sheet" method="post" action="/settings" novalidate style="max-width:46rem">
                  {{{csrf}}}{{{summary}}}
                  <h2 style="margin-bottom:.8rem">Your day</h2>
                  <div class="fields">{{{wake}}}{{{sleep}}}{{{focusStart}}}{{{focusEnd}}}</div>
                  <p class="note" style="margin:.6rem 0 0">Focus hours are when you work best. The planner fills them first.</p>
                  <h2 style="margin:1.75rem 0 .8rem">Study sessions</h2>
                  <div class="fields">{{{maxDaily}}}{{{minSession}}}{{{maxSession}}}{{{breakMinutes}}}{{{maxHard}}}</div>
                  <div style="margin-top:1rem">{{{weekend}}}</div>
                  <div class="form-foot"><button class="button" type="submit">Save settings</button></div>
                </form>""",
                "csrf", Html.csrf(req), "summary", f.summary(),
                "wake", f.input("wake", "Wake up", "time", null, "required"),
                "sleep", f.input("sleep", "Bedtime", "time", null, "required"),
                "focusStart", f.input("focusStart", "Focus hours from", "time", null, "required"),
                "focusEnd", f.input("focusEnd", "until", "time", null, "required"),
                "maxDaily", f.input("maxDaily", "Most study in a day", "number", "minutes", "min=\"30\" max=\"720\" step=\"15\""),
                "minSession", f.input("minSession", "Shortest session", "number", "minutes", "min=\"10\" max=\"120\" step=\"5\""),
                "maxSession", f.input("maxSession", "Longest session", "number", "minutes", "min=\"20\" max=\"240\" step=\"5\""),
                "breakMinutes", f.input("breakMinutes", "Break between", "number", "minutes", "min=\"0\" max=\"60\" step=\"5\""),
                "maxHard", f.input("maxHard", "Hard sessions a day", "number", "at most", "min=\"1\" max=\"6\""),
                "weekend", f.checkbox("weekend", "Plan study at weekends"));
        planner.domain.Store.AutoPlan a = ctx.store().autoPlan(req.userId());
        body += Html.t("""
                <form class="sheet section" method="post" action="/settings/auto" style="max-width:46rem">{{{csrf}}}
                  <h2 style="margin-bottom:.8rem">Automatic planning</h2>
                  <label class="check"><input type="checkbox" name="auto" value="1"{{{on}}}> Re-plan my week whenever I add or change a task, exam or class</label>
                  <div class="fields" style="margin-top:1rem;max-width:20rem"><label class="field"><span class="label">Planning style</span>
                    <select name="mode">{{{modes}}}</select></label></div>
                  <div class="form-foot"><button class="button" type="submit">Save</button></div>
                </form>""", "csrf", Html.csrf(req), "on", Html.checked(a.on()),
                "modes", "<option value=\"BALANCED\"" + Html.selected(a.mode().equals("BALANCED")) + ">Balanced</option>"
                        + "<option value=\"DEADLINE\"" + Html.selected(a.mode().equals("DEADLINE")) + ">Deadlines first</option>"
                        + "<option value=\"EXAM\"" + Html.selected(a.mode().equals("EXAM")) + ">Exam revision</option>");
        return Layout.page(req, "/settings", "Settings", body);
    }
}
