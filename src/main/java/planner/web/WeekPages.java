package planner.web;

import planner.domain.Planner;
import planner.domain.Records.BlockStatus;
import planner.domain.Records.Exam;
import planner.domain.Records.Highlighter;
import planner.domain.Records.PlannedBlock;
import planner.domain.Records.Preferences;
import planner.domain.Records.Task;
import planner.domain.Records.TimetableEntry;
import planner.domain.Text;
import planner.engine.SchedulingEngine;
import planner.engine.model.AtRiskTask;
import planner.engine.model.ScheduledSession;
import planner.engine.model.SchedulingMode;
import planner.engine.model.ScoreBreakdown;
import planner.http.Html;
import planner.http.Request;
import planner.http.Response;
import planner.http.Router;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The week view, making a plan, and saying whether a block happened. */
final class WeekPages {

    private final Ctx ctx;

    WeekPages(Ctx ctx) {
        this.ctx = ctx;
    }

    void routes(Router r) {
        r.get("/", this::week);
        r.get("/plan", this::plan);
        r.post("/plan/preview", this::preview);
        r.post("/plan/save", this::save);
        r.post("/plan/discard", req -> {
            ctx.planner().discard(req.session().id());
            return Response.redirect("/plan").flash("Discarded. Your saved week hasn't changed.");
        });
        r.post("/sessions/{id}/done", req -> resolve(req, BlockStatus.DONE));
        r.post("/sessions/{id}/missed", req -> resolve(req, BlockStatus.MISSED));
        r.post("/sample", this::sample);
    }

    /* ======================= This week ======================= */

    private Response week(Request req) {
        long uid = req.userId();
        LocalDateTime now = ctx.now();
        LocalDate today = now.toLocalDate();

        if (!ctx.store().hasAnyData(uid)) {
            return Response.html(Layout.page(req, "/", "This week", firstRun(req)));
        }

        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monday = thisMonday;
        String asked = req.query("week");
        if (asked != null) {
            try {
                monday = LocalDate.parse(asked).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            } catch (DateTimeParseException ignored) {
                // show this week
            }
        }
        boolean current = monday.equals(thisMonday);

        Preferences prefs = ctx.store().preferences(uid);
        List<PlannedBlock> blocks = ctx.store().plan(uid, monday, monday.plusDays(6));
        List<Task> open = ctx.store().openTasks(uid);
        List<Exam> exams = ctx.store().upcomingExams(uid, today);

        List<WeekGrid.Cell> cells = classes(uid, monday);
        for (PlannedBlock b : blocks) {
            cells.add(blockCell(req, b, now));
        }

        String nav = Html.t("""
                <a class="button quiet small" href="/?week={{prev}}">Previous week</a>{{{thisWeek}}}<a class="button quiet small" href="/?week={{next}}">Next week</a>""",
                "prev", monday.minusWeeks(1), "next", monday.plusWeeks(1),
                "thisWeek", current ? "" : "<a class=\"button quiet small\" href=\"/\">This week</a>");

        String body = Layout.head(current ? "This week" : "Week of " + monday.getDayOfMonth() + " " + monday.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH),
                Text.week(monday), nav)
                + (current ? stats(blocks, open, exams, now) : "")
                + status(blocks, open, now, current)
                + (current ? SmartPages.quickAdd(req) : "")
                + (current ? followUp(req, ctx.store().unresolved(uid, now), today) : "")
                + WeekGrid.render(monday, prefs, cells, now, false)
                + Html.t("""
                <div class="split section">
                  <section><h2>Due soon</h2>{{{due}}}</section>
                  <section><h2>Exams ahead</h2>{{{exams}}}</section>
                </div>""", "due", dueSoon(open, now), "exams", examsAhead(exams, today));
        return Response.html(Layout.page(req, "/", "This week", body));
    }

    private String status(List<PlannedBlock> blocks, List<Task> open, LocalDateTime now, boolean current) {
        int left = 0;
        int done = 0;
        for (PlannedBlock b : blocks) {
            if (b.status() == BlockStatus.DONE) {
                done += b.minutes();
            } else if (b.status() == BlockStatus.PLANNED && !b.past(now)) {
                left += b.minutes();
            }
        }
        long overdue = open.stream().filter(t -> t.overdue(now)).count();
        StringBuilder s = new StringBuilder("<p class=\"status\">");
        if (blocks.isEmpty()) {
            s.append(open.isEmpty()
                    ? "Nothing is planned for this week, and you have no open tasks."
                    : "Nothing is planned for this week yet. <a href=\"/plan\">Make a plan</a> from your "
                    + Text.plural(open.size(), "open task", "open tasks") + ".");
        } else if (current) {
            PlannedBlock next = blocks.stream()
                    .filter(b -> b.status() == BlockStatus.PLANNED && !b.past(now))
                    .findFirst().orElse(null);
            int hour = now.getHour();
            s.append(hour < 12 ? "Good morning. " : hour < 17 ? "Good afternoon. " : "Good evening. ");
            if (next == null) {
                s.append("Nothing else is planned this week.");
            } else {
                boolean running = !LocalDateTime.of(next.day(), next.start()).isAfter(now);
                s.append(running ? "Right now: <strong>" : "Next up: <strong>").append(Html.esc(next.title())).append("</strong>")
                        .append(running ? ", until " + Text.time(next.end()) + "."
                                : ", " + Text.day(next.day(), now.toLocalDate()) + " at " + Text.time(next.start()) + ".");
            }
        } else {
            s.append("<strong>").append(Text.duration(left + done)).append("</strong> of study is planned this week.");
        }
        if (overdue > 0) {
            s.append(" <span class=\"problem\">").append(overdue == 1 ? "1 task is overdue." : overdue + " tasks are overdue.")
                    .append("</span>");
        }
        return s.append("</p>").toString();
    }

    private String stats(List<PlannedBlock> blocks, List<Task> open, List<Exam> exams, LocalDateTime now) {
        int left = 0;
        int done = 0;
        for (PlannedBlock b : blocks) {
            if (b.status() == BlockStatus.DONE) {
                done += b.minutes();
            } else if (b.status() == BlockStatus.PLANNED && !b.past(now)) {
                left += b.minutes();
            }
        }
        long due = open.stream().filter(t -> !t.overdue(now) && t.dueAt().isBefore(now.plusDays(7))).count();
        int pct = left + done == 0 ? 0 : Math.round(done * 100f / (left + done));
        String exam = "None yet";
        String examLabel = "Next exam";
        if (!exams.isEmpty()) {
            Exam e = exams.get(0);
            long days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), e.date());
            exam = days == 0 ? "Today" : days + (days == 1 ? " day" : " days");
            examLabel = "Until " + (e.subjectCode() == null ? "" : e.subjectCode() + " ") + e.title();
        }
        return Html.t("""
                <div class="stats">
                  <div class="stat"><div><b data-count="{{left}}" data-minutes>{{leftText}}</b><span>Study left this week</span></div><div class="ring" data-v="{{pct}}" title="{{pct}}% done"></div></div>
                  <div class="stat c-green"><div><b data-count="{{done}}" data-minutes>{{doneText}}</b><span>Done this week</span></div></div>
                  <div class="stat c-pink"><div><b data-count="{{due}}">{{due}}</b><span>Due in the next 7 days</span></div></div>
                  <div class="stat c-yellow"><div><b>{{exam}}</b><span>{{examLabel}}</span></div></div>
                </div>""",
                "left", left, "leftText", Text.duration(left), "pct", pct, "done", done, "doneText", Text.duration(done),
                "due", due, "exam", exam, "examLabel", examLabel);
    }

    private String followUp(Request req, List<PlannedBlock> unresolved, LocalDate today) {
        if (unresolved.isEmpty()) {
            return "";
        }
        StringBuilder items = new StringBuilder();
        for (PlannedBlock b : unresolved.subList(0, Math.min(5, unresolved.size()))) {
            items.append(Html.t("""
                    <li>{{{code}}} <span>{{title}}, {{when}} {{from}} to {{to}}</span>{{{done}}}{{{missed}}}</li>""",
                    "code", Layout.hl(b.subjectCode(), b.highlighter()), "title", b.title(),
                    "when", Text.day(b.day(), today), "from", Text.time(b.start()), "to", Text.time(b.end()),
                    "done", Layout.button(req, "/sessions/" + b.id() + "/done", "I did this", "button small", null, "back", "/"),
                    "missed", Layout.button(req, "/sessions/" + b.id() + "/missed", "I missed it", "button quiet small", null, "back", "/")));
        }
        return Html.t("""
                <section class="followup">
                  <h2>How did these go?</h2>
                  <ul>{{{items}}}</ul>
                </section>""", "items", items.toString());
    }

    private String dueSoon(List<Task> open, LocalDateTime now) {
        List<Task> soon = open.stream().filter(t -> t.dueAt().isBefore(now.plusDays(7)))
                .sorted(Comparator.comparing(Task::dueAt)).limit(6).toList();
        if (soon.isEmpty()) {
            return "<p class=\"note\">Nothing due in the next seven days.</p>";
        }
        StringBuilder out = new StringBuilder("<ul class=\"rows\">");
        for (Task t : soon) {
            boolean late = t.overdue(now);
            out.append(Html.t("""
                    <li><div class="row"><span>{{{code}}}</span><a class="title" href="/tasks/{{id}}">{{title}}</a><span class="side{{{late}}}">{{due}}</span></div></li>""",
                    "code", Layout.hl(t.subjectCode(), t.highlighter()), "id", t.id(), "title", t.title(),
                    "late", late ? " overdue" : "", "due", Text.due(t.dueAt(), now)));
        }
        return out.append("</ul>").toString();
    }

    private String examsAhead(List<Exam> exams, LocalDate today) {
        if (exams.isEmpty()) {
            return "<p class=\"note\">No exams coming up. <a href=\"/exams\">Add one</a> and revision gets planned for it.</p>";
        }
        StringBuilder out = new StringBuilder("<ul class=\"rows\">");
        for (Exam e : exams.subList(0, Math.min(4, exams.size()))) {
            out.append(Html.t("""
                    <li><div class="row"><span>{{{code}}}</span><a class="title" href="/exams#exam-{{id}}">{{title}}</a><span class="side">{{when}}, {{ready}} ready</span></div></li>""",
                    "code", Layout.hl(e.subjectCode(), e.highlighter()), "id", e.id(), "title", e.title(),
                    "when", Text.countdown(e.date(), today), "ready", Math.round(e.preparedness() * 100) + "%"));
        }
        return out.append("</ul>").toString();
    }

    private String firstRun(Request req) {
        return Layout.head("Welcome to Margin", null, null) + Html.t("""
                <p class="lede">Margin reads your timetable, your deadlines and your exams, then fits study into the gaps. Every block says why it was put there.</p>
                <div class="split section">
                  <section class="empty">
                    <h2>Start with your own subjects</h2>
                    <p>Add the courses you're taking, then your weekly classes and what's due. It takes about five minutes.</p>
                    <a class="button" href="/subjects">Add subjects</a>
                  </section>
                  <section class="empty">
                    <h2>Or look around first</h2>
                    <p>Fill your account with a sample semester: four subjects, a timetable, six tasks and an exam, with a plan already made. You can delete it all later.</p>
                    {{{sample}}}
                  </section>
                </div>""", "sample", Layout.button(req, "/sample", "Load a sample week", "button quiet", null));
    }

    /* ======================= Plan ======================= */

    private Response plan(Request req) {
        long uid = req.userId();
        LocalDateTime now = ctx.now();
        Optional<Planner.Preview> preview = ctx.planner().currentPreview(req.session().id(), now);
        String body = preview.isPresent()
                ? previewPage(req, preview.get(), now, "1".equals(req.query("drawn")))
                : choosePage(req, uid, now);
        return Response.html(Layout.page(req, "/plan", "Plan", body));
    }

    private String choosePage(Request req, long uid, LocalDateTime now) {
        int open = ctx.store().openTasks(uid).size();
        int exams = ctx.store().upcomingExams(uid, now.toLocalDate()).size();
        if (open == 0 && exams == 0) {
            return Layout.head("Make a plan", null, null) + """
                    <section class="empty">
                      <h2>There's nothing to plan yet</h2>
                      <p>Plans are made from your open tasks and upcoming exams. Add a task with a due date and come back.</p>
                      <a class="button" href="/tasks">Add a task</a>
                    </section>""";
        }
        StringBuilder modes = new StringBuilder();
        String[][] described = {
                {"BALANCED", "Balanced", "Spreads work evenly so no day is overloaded. A good default.",
                        "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2.2' stroke-linecap='round'><path d='M12 4v16M5 8h14M5 8l-2 6h4zM19 8l-2 6h4z'/></svg>"},
                {"DEADLINE", "Deadlines first", "Front-loads whatever is due soonest. Use it in a crunch week.",
                        "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2.2' stroke-linecap='round'><circle cx='12' cy='13' r='8'/><path d='M12 9v4l3 2M9 2h6'/></svg>"},
                {"EXAM", "Exam revision", "Books revision for upcoming exams before anything else.",
                        "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'><path d='M2 9l10-5 10 5-10 5z'/><path d='M6 11v5c3 2 9 2 12 0v-5'/></svg>"},
        };
        for (String[] m : described) {
            modes.append(Html.t("""
                    <label class="mode"><input type="radio" name="mode" value="{{v}}"{{{c}}}><i>{{{icon}}}</i><strong>{{n}}</strong><span>{{d}}</span></label>""",
                    "v", m[0], "c", Html.checked(m[0].equals("BALANCED")), "n", m[1], "d", m[2], "icon", m[3]));
        }
        return Layout.head("Make a plan", "For the next seven days, starting now.", null) + Html.t("""
                <div class="split-wide">
                  <form class="sheet" method="post" action="/plan/preview">
                    {{{csrf}}}
                    <fieldset class="modes"><legend class="visually-hidden">Planning style</legend>{{{modes}}}</fieldset>
                    <button class="button" type="submit">Preview the plan</button>
                  </form>
                  <aside>
                    <p class="note">Planning from {{open}} and {{exams}}. Classes, sleep and your daily limit are never planned over. Nothing is saved until you say so.</p>
                  </aside>
                </div>""", "csrf", Html.csrf(req), "modes", modes.toString(),
                "open", Text.plural(open, "open task", "open tasks"), "exams", Text.plural(exams, "upcoming exam", "upcoming exams"));
    }

    private String previewPage(Request req, Planner.Preview p, LocalDateTime now, boolean drawn) {
        long uid = req.userId();
        Map<String, Highlighter> colours = ctx.colours(uid);
        LocalDate from = now.toLocalDate();

        List<WeekGrid.Cell> cells = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            LocalDate date = from.plusDays(d);
            for (TimetableEntry e : ctx.store().timetable(uid)) {
                if (e.day() == date.getDayOfWeek()) {
                    cells.add(new WeekGrid.Cell(date, e.start(), e.end(), true, e.title(), e.kind().label(), hlName(e), null, null));
                }
            }
        }
        Map<String, String> titles = new HashMap<>();
        for (Task t : ctx.store().openTasks(uid)) {
            titles.put(String.valueOf(t.id()), t.title());
        }
        for (ScheduledSession s : p.schedule().sessions()) {
            titles.putIfAbsent(s.taskId(), s.title());
            Highlighter h = colours.getOrDefault(s.subjectCode(), Highlighter.yellow);
            String part = s.sessionCount() > 1 ? "part " + s.sessionIndex() + " of " + s.sessionCount() : null;
            String slip = Html.t("""
                    <h3>{{title}}</h3><p class="when">{{{code}}} {{day}}, {{from}} to {{to}}{{part}}</p><p class="note">{{why}}</p>""",
                    "title", s.title(), "code", Layout.hl(s.subjectCode(), h), "day", Text.date(s.date()),
                    "from", Text.time(s.start()), "to", Text.time(s.end()), "part", part == null ? "" : ", " + part,
                    "why", s.explanation());
            cells.add(new WeekGrid.Cell(s.date(), s.start(), s.end(), false, s.title(), part, h.name(),
                    s.revision() ? "revision" : null, slip));
        }

        String risk = "";
        if (!p.schedule().atRisk().isEmpty()) {
            StringBuilder items = new StringBuilder();
            for (AtRiskTask a : p.schedule().atRisk()) {
                items.append("<li><strong>").append(Html.esc(a.title())).append("</strong>: ")
                        .append(Html.esc(Text.duration(a.scheduledMinutes()))).append(" planned of ")
                        .append(Html.esc(Text.duration(a.requiredMinutes()))).append(" needed. ")
                        .append(Html.esc(a.reason())).append("</li>");
            }
            risk = "<section class=\"risk\" role=\"alert\"><h2>"
                    + (p.schedule().atRisk().size() == 1 ? "One task won't fit" : p.schedule().atRisk().size() + " tasks won't fit")
                    + "</h2><ul>" + items + "</ul></section>";
        }
        String warnings = p.schedule().warnings().isEmpty() ? ""
                : "<p class=\"note\" style=\"margin-bottom:1rem\">" + Html.esc(String.join(" ", p.schedule().warnings())) + "</p>";

        StringBuilder ranking = new StringBuilder();
        List<ScoreBreakdown> scores = new ArrayList<>(p.schedule().scores());
        scores.sort(Comparator.comparingDouble(ScoreBreakdown::total).reversed());
        for (ScoreBreakdown s : scores) {
            ranking.append(Html.t("""
                    <li><span class="name">{{title}}</span><span class="score">{{score}}</span><span class="bar" style="--v:{{score}}"><i></i></span><span class="why note">{{reason}}.</span></li>""",
                    "title", titles.getOrDefault(s.taskId(), "Revision"), "score", Math.round(s.total()),
                    "reason", s.taskId().startsWith("revision-") ? "Revision, booked because the exam is coming up"
                            : "M" + SchedulingEngine.reason(s).substring(1)));
        }

        StringBuilder others = new StringBuilder();
        for (SchedulingMode m : SchedulingMode.values()) {
            if (m != p.mode()) {
                others.append(Layout.button(req, "/plan/preview", "Try " + modeName(m).toLowerCase(), "button quiet small", null, "mode", m.name()));
            }
        }
        int total = p.schedule().totalMinutes();
        String actions = Layout.button(req, "/plan/save", "Save this plan", "button", null)
                + Layout.button(req, "/plan/discard", "Discard", "button quiet", null);

        return Layout.head("Plan for the next seven days",
                modeName(p.mode()) + ": " + Text.duration(total) + " of study in "
                        + Text.plural(p.schedule().sessions().size(), "block", "blocks") + ".", actions)
                + risk + warnings
                + "<p class=\"note\" style=\"margin-bottom:1rem\">This is a preview. Saving replaces any study blocks that haven't started yet; finished and missed ones are kept.</p>"
                + WeekGrid.render(from, ctx.store().preferences(uid), cells, now, drawn)
                + Html.t("""
                <div class="split-wide section">
                  <section>
                    <h2>What comes first, and why</h2>
                    <p class="note" style="margin-bottom:.8rem">Each task scores out of 100 from its deadline, the priority you set, its exam, how hard the subject is and anything missed.</p>
                    <ol class="ranking">{{{ranking}}}</ol>
                  </section>
                  <section>
                    <h2>Try a different style</h2>
                    <div class="actions">{{{others}}}</div>
                  </section>
                </div>""", "ranking", ranking.length() == 0 ? "<li><span>No tasks to rank.</span></li>" : ranking.toString(),
                "others", others.toString());
    }

    static String modeName(SchedulingMode m) {
        return switch (m) {
            case BALANCED -> "Balanced";
            case DEADLINE -> "Deadlines first";
            case EXAM -> "Exam revision";
        };
    }

    private Response preview(Request req) {
        SchedulingMode mode;
        try {
            mode = SchedulingMode.valueOf(req.text("mode"));
        } catch (IllegalArgumentException e) {
            mode = SchedulingMode.BALANCED;
        }
        ctx.planner().preview(req.session().id(), req.userId(), mode, ctx.now());
        return Response.redirect("/plan?drawn=1");
    }

    private Response save(Request req) {
        Optional<Integer> saved = ctx.planner().save(req.session().id(), req.userId(), ctx.now());
        if (saved.isEmpty()) {
            return Response.redirect("/plan").flash("That preview had expired, so nothing was saved. Preview the plan again.");
        }
        return Response.redirect("/").flash("Saved " + Text.plural(saved.get(), "study block", "study blocks") + " to your week.");
    }

    private Response resolve(Request req, BlockStatus status) {
        if (!ctx.store().resolveBlock(req.userId(), req.id("id"), status)) {
            throw planner.http.HttpException.notFound();
        }
        String back = Ctx.safe(req.text("back"), "/");
        return Response.redirect(back).flash(status == BlockStatus.DONE
                ? "Marked as done. The time counts towards the task."
                : "Marked as missed. That task gets more weight next time you plan.");
    }

    private Response sample(Request req) {
        long uid = req.userId();
        if (ctx.store().hasAnyData(uid)) {
            return Response.redirect("/").flash("Your account already has data, so the sample wasn't added.");
        }
        DemoData.load(ctx, uid, req.session().id());
        return Response.redirect("/").flash("Sample semester loaded. Select any coloured block to see why it's there.");
    }

    /* ======================= shared ======================= */

    /** This week's classes and commitments as grid cells. */
    private List<WeekGrid.Cell> classes(long uid, LocalDate monday) {
        List<WeekGrid.Cell> cells = new ArrayList<>();
        for (TimetableEntry e : ctx.store().timetable(uid)) {
            LocalDate date = monday.plusDays(e.day().getValue() - 1);
            String meta = e.room() == null || e.room().isEmpty() ? e.kind().label() : e.kind().label() + ", " + e.room();
            cells.add(new WeekGrid.Cell(date, e.start(), e.end(), true, e.title(), meta, hlName(e), null, null));
        }
        return cells;
    }

    private WeekGrid.Cell blockCell(Request req, PlannedBlock b, LocalDateTime now) {
        String part = b.parts() > 1 ? "part " + b.part() + " of " + b.parts() : null;
        String state = switch (b.status()) {
            case DONE -> "<p class=\"when\"><strong>Done.</strong></p>";
            case MISSED -> "<p class=\"when overdue\">Missed.</p>";
            case PLANNED -> "";
        };
        String actions = b.status() != BlockStatus.PLANNED ? "" : "<div class=\"actions\">"
                + Layout.button(req, "/sessions/" + b.id() + "/done", b.past(now) ? "I did this" : "Done", "button small", null, "back", "/?week=" + b.day())
                + Layout.button(req, "/sessions/" + b.id() + "/missed", "I missed it", "button quiet small", null, "back", "/?week=" + b.day())
                + "</div>";
        String slip = Html.t("""
                <h3>{{title}}</h3><p class="when">{{{code}}} {{day}}, {{from}} to {{to}}{{part}}</p>{{{state}}}<p class="note">{{why}}</p>{{{actions}}}""",
                "title", b.title(), "code", Layout.hl(b.subjectCode(), b.highlighter()), "day", Text.date(b.day()),
                "from", Text.time(b.start()), "to", Text.time(b.end()), "part", part == null ? "" : ", " + part,
                "state", state, "why", b.note() == null ? "" : b.note(), "actions", actions);
        String cls = (b.revision() ? "revision " : "") + switch (b.status()) {
            case DONE -> "done";
            case MISSED -> "missed";
            case PLANNED -> "";
        };
        Highlighter h = b.highlighter() == null ? Highlighter.yellow : b.highlighter();
        return new WeekGrid.Cell(b.day(), b.start(), b.end(), false, b.title(),
                part, h.name(), cls.trim(), slip);
    }

    static String hlName(TimetableEntry e) {
        return !e.kind().isClass() || e.highlighter() == null ? null : e.highlighter().name();
    }

    static long daysBetween(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b);
    }
}
