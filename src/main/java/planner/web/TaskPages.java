package planner.web;

import planner.domain.Records.Priority;
import planner.domain.Records.Subject;
import planner.domain.Records.Task;
import planner.domain.Text;
import planner.http.HttpException;
import planner.http.Html;
import planner.http.Request;
import planner.http.Response;
import planner.http.Router;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assignments and other work with a due date. */
final class TaskPages {

    static SmartPages smart;
    private final Ctx ctx;

    TaskPages(Ctx ctx) {
        this.ctx = ctx;
    }

    void routes(Router r) {
        r.get("/tasks", req -> Response.html(list(req, null)));
        r.post("/tasks", this::add);
        r.get("/tasks/{id}", req -> {
            Task t = ctx.store().task(req.userId(), req.id("id")).orElseThrow(HttpException::notFound);
            return Response.html(edit(req, t, fromTask(t)));
        });
        r.post("/tasks/{id}", this::update);
        r.post("/tasks/{id}/done", req -> {
            boolean done = "1".equals(req.text("done"));
            if (!ctx.store().setTaskDone(req.userId(), req.id("id"), done)) {
                throw HttpException.notFound();
            }
            return Response.redirect(Ctx.safe(req.text("back"), "/tasks"))
                    .flash(done ? "Ticked off. Its future study blocks were cleared." : "Marked as not done.");
        });
        r.post("/tasks/{id}/delete", req -> {
            if (!ctx.store().deleteTask(req.userId(), req.id("id"))) {
                throw HttpException.notFound();
            }
            return Response.redirect("/tasks").flash("Task deleted.");
        });
    }

    private Form defaults() {
        LocalDate due = ctx.now().toLocalDate().plusDays(7);
        return Form.of(Map.of("dueDate", due.toString(), "dueTime", "23:59", "minutes", "120",
                "priority", "MEDIUM", "splittable", "1"));
    }

    private static Form fromTask(Task t) {
        Form f = Form.blank();
        f.set("title", t.title()).set("notes", t.notes()).set("subject", t.subjectId())
                .set("priority", t.priority().name()).set("dueDate", t.dueAt().toLocalDate())
                .set("dueTime", t.dueAt().toLocalTime()).set("minutes", t.estimatedMinutes())
                .set("completed", t.completedMinutes()).set("splittable", t.splittable() ? "1" : "");
        return f;
    }

    /** The values every task form shares. */
    private record Fields(Long subjectId, String title, String notes, Priority priority,
                          LocalDateTime due, int minutes, boolean splittable) { }

    private static Fields read(Form f) {
        String title = f.required("title", 160, "Give the task a name.");
        String notes = f.optional("notes", 2000);
        LocalDate date = f.date("dueDate", "Choose the date it's due.");
        LocalTime time = f.has("dueTime") ? f.time("dueTime", "Use a time like 23:59.") : LocalTime.of(23, 59);
        int minutes = f.integer("minutes", 5, 6000, "Enter the minutes needed, between 5 and 6000.");
        return new Fields(f.id("subject"), title, notes, Priority.parse(f.v("priority")),
                LocalDateTime.of(date, time), minutes, f.bool("splittable"));
    }

    private Response add(Request req) {
        Form f = Form.submitted(req);
        Fields v = read(f);
        if (!f.ok()) {
            return Response.html(400, list(req, f));
        }
        ctx.store().addTask(req.userId(), v.subjectId(), v.title(), v.notes(), v.priority(), v.due(), v.minutes(), v.splittable());
        return Response.redirect("/tasks").flash("Added “" + v.title() + "”." + ctx.autoPlan(req.userId()));
    }

    private Response update(Request req) {
        long uid = req.userId();
        Task t = ctx.store().task(uid, req.id("id")).orElseThrow(HttpException::notFound);
        Form f = Form.submitted(req);
        Fields v = read(f);
        int completed = f.integer("completed", 0, 6000, "Enter minutes done, from 0.");
        if (!f.ok()) {
            return Response.html(400, edit(req, t, f));
        }
        ctx.store().updateTask(uid, t.id(), v.subjectId(), v.title(), v.notes(), v.priority(), v.due(),
                v.minutes(), Math.min(completed, v.minutes()), v.splittable());
        return Response.redirect("/tasks").flash("Saved changes to “" + v.title() + "”." + ctx.autoPlan(uid));
    }

    /* ---------------- pages ---------------- */

    private String list(Request req, Form submitted) {
        long uid = req.userId();
        LocalDateTime now = ctx.now();
        LocalDate today = now.toLocalDate();
        List<Subject> subjects = ctx.store().subjects(uid);
        Form f = submitted == null ? defaults() : submitted;

        Map<String, List<Task>> groups = new LinkedHashMap<>();
        for (String g : List.of("Overdue", "Today", "Tomorrow", "Later this week", "Later", "Done")) {
            groups.put(g, new ArrayList<>());
        }
        for (Task t : ctx.store().tasks(uid)) {
            LocalDate d = t.dueAt().toLocalDate();
            String g = t.done() ? "Done"
                    : t.overdue(now) ? "Overdue"
                    : d.equals(today) ? "Today"
                    : d.equals(today.plusDays(1)) ? "Tomorrow"
                    : d.isBefore(today.plusDays(7)) ? "Later this week" : "Later";
            groups.get(g).add(t);
        }

        StringBuilder lists = new StringBuilder();
        groups.forEach((name, tasks) -> {
            if (tasks.isEmpty()) {
                return;
            }
            lists.append("<div class=\"group-head").append(name.equals("Overdue") ? " red" : "").append("\"><h2>")
                    .append(name).append("</h2><span class=\"count\">").append(tasks.size()).append("</span></div><ul class=\"rows\">");
            List<Task> shown = name.equals("Done") ? tasks.subList(0, Math.min(10, tasks.size())) : tasks;
            for (Task t : shown) {
                lists.append("<li data-title=\"").append(Html.esc(t.title().toLowerCase())).append("\" data-subject=\"")
                        .append(Html.esc(t.subjectCode() == null ? "" : t.subjectCode())).append("\">")
                        .append(row(req, t, now)).append("</li>");
            }
            lists.append("</ul>");
        });
        if (lists.length() == 0) {
            lists.append("""
                    <section class="empty section"><h2>No tasks yet</h2><p>Add assignments, reports and problem sets with their due dates. Margin plans the work back from the deadline.</p></section>""");
        }

        String form = Html.t("""
                <form class="sheet" method="post" action="/tasks" novalidate>
                  {{{csrf}}}{{{summary}}}
                  <div class="fields">
                    {{{title}}}{{{subject}}}{{{dueDate}}}{{{dueTime}}}{{{minutes}}}{{{priority}}}
                  </div>
                  <details class="section" style="margin-top:1rem"{{{openMore}}}><summary>Notes and splitting</summary>
                    <div class="fields" style="margin-top:.8rem">{{{notes}}}<div class="wide">{{{split}}}</div></div>
                  </details>
                  <div class="form-foot"><button class="button" type="submit">Add task</button>{{{noSubjects}}}</div>
                </form>""",
                "csrf", Html.csrf(req), "summary", f.summary(),
                "title", f.input("title", "Task", "text", null, "required maxlength=\"160\" placeholder=\"Algorithms problem set 4\"")
                        .replace("class=\"field\"", "class=\"field wide\""),
                "subject", f.select("subject", "Subject", null, Ctx.subjectOptions(subjects, "No subject")),
                "dueDate", f.input("dueDate", "Due", "date", null, "required"),
                "dueTime", f.input("dueTime", "At", "time", null, ""),
                "minutes", f.input("minutes", "Time needed", "number", "minutes", "min=\"5\" max=\"6000\" step=\"5\" required"),
                "priority", f.select("priority", "Priority", null, priorityOptions()),
                "openMore", f.error("notes") != null ? " open" : "",
                "notes", f.textarea("notes", "Notes", null, "maxlength=\"2000\""),
                "split", f.checkbox("splittable", "Can be split over several sessions"),
                "noSubjects", subjects.isEmpty() ? "<span class=\"note\"><a href=\"/subjects\">Add subjects</a> to colour-code tasks.</span>" : "");

        StringBuilder chips = new StringBuilder("<button type=\"button\" class=\"chip all\" data-subject=\"\" aria-pressed=\"true\">All</button>");
        for (Subject s : subjects) {
            chips.append("<button type=\"button\" class=\"chip\" data-subject=\"").append(Html.esc(s.code()))
                    .append("\" aria-pressed=\"false\" style=\"--c:var(--hl-").append(s.highlighter().name()).append(")\">")
                    .append(Html.esc(s.code())).append("</button>");
        }
        String filters = "<div class=\"filters\"><input type=\"search\" data-filter placeholder=\"Filter tasks\" aria-label=\"Filter tasks\">"
                + chips + "<span class=\"kbd\">Press <kbd>/</kbd> to search, <kbd>n</kbd> for a new task</span></div>";
        String body = Layout.head("Tasks", null, null)
                + SmartPages.quickAdd(req)
                + "<div class=\"split-wide\"><div>" + filters + lists + "</div><aside><h2 style=\"margin-bottom:.8rem\">Add a task</h2>" + form
                + (smart == null ? "" : smart.classroomPanel(req)) + "</aside></div>";
        return Layout.page(req, "/tasks", "Tasks", body);
    }

    private String row(Request req, Task t, LocalDateTime now) {
        String tick = Html.t("""
                <form method="post" action="/tasks/{{id}}/done" data-async="task">{{{csrf}}}<input type="hidden" name="done" value="{{next}}"><button class="tick{{{on}}}" type="submit" aria-label="{{label}}" title="{{label}}"></button></form>""",
                "id", t.id(), "csrf", Html.csrf(req), "next", t.done() ? "0" : "1", "on", t.done() ? " on" : "",
                "label", (t.done() ? "Mark not done: " : "Mark done: ") + t.title());
        List<String> meta = new ArrayList<>();
        if (!t.done()) {
            meta.add(t.completedMinutes() > 0
                    ? Text.duration(t.remainingMinutes()) + " left of " + Text.duration(t.estimatedMinutes())
                    : Text.duration(t.estimatedMinutes()));
            if (t.priority() == Priority.HIGH || t.priority() == Priority.CRITICAL) {
                meta.add(t.priority().label() + " priority");
            }
            if (t.missedSessions() > 0) {
                meta.add(Text.plural(t.missedSessions(), "missed session", "missed sessions"));
            }
        }
        StringBuilder metaHtml = new StringBuilder();
        for (String m : meta) {
            metaHtml.append("<span>").append(Html.esc(m)).append("</span>");
        }
        return Html.t("""
                <div class="row{{{done}}}">{{{tick}}}<div><div><a class="title" href="/tasks/{{id}}">{{title}}</a> {{{code}}}</div><div class="meta">{{{meta}}}</div></div><span class="side{{{late}}}">{{due}}</span></div>""",
                "done", t.done() ? " is-done" : "", "tick", tick, "id", t.id(), "title", t.title(),
                "code", Layout.hl(t.subjectCode(), t.highlighter()), "meta", metaHtml.toString(),
                "late", t.overdue(now) ? " overdue" : "",
                "due", t.done() ? "Done" : Text.due(t.dueAt(), now));
    }

    private String edit(Request req, Task t, Form f) {
        List<Subject> subjects = ctx.store().subjects(req.userId());
        String body = Layout.head(t.title(), t.done() ? "Done" : "Due " + Text.due(t.dueAt(), ctx.now()), null) + Html.t("""
                <div class="split-wide">
                  <form class="sheet" method="post" action="/tasks/{{id}}" novalidate>
                    {{{csrf}}}{{{summary}}}
                    <div class="fields">
                      {{{title}}}{{{subject}}}{{{priority}}}{{{dueDate}}}{{{dueTime}}}{{{minutes}}}{{{completed}}}{{{notes}}}
                      <div class="wide">{{{split}}}</div>
                    </div>
                    <div class="form-foot"><button class="button" type="submit">Save changes</button><a href="/tasks">Cancel</a></div>
                  </form>
                  <aside>
                    <p class="note" style="margin-bottom:1rem">Changes take effect the next time you make a plan.</p>
                    {{{delete}}}
                  </aside>
                </div>""",
                "id", t.id(), "csrf", Html.csrf(req), "summary", f.summary(),
                "title", f.input("title", "Task", "text", null, "required maxlength=\"160\"").replace("class=\"field\"", "class=\"field wide\""),
                "subject", f.select("subject", "Subject", null, Ctx.subjectOptions(subjects, "No subject")),
                "priority", f.select("priority", "Priority", null, priorityOptions()),
                "dueDate", f.input("dueDate", "Due", "date", null, "required"),
                "dueTime", f.input("dueTime", "At", "time", null, ""),
                "minutes", f.input("minutes", "Time needed", "number", "minutes", "min=\"5\" max=\"6000\" step=\"5\" required"),
                "completed", f.input("completed", "Done so far", "number", "minutes", "min=\"0\" max=\"6000\" step=\"5\""),
                "notes", f.textarea("notes", "Notes", null, "maxlength=\"2000\""),
                "split", f.checkbox("splittable", "Can be split over several sessions"),
                "delete", Layout.button(req, "/tasks/" + t.id() + "/delete", "Delete task", "button danger", "Delete this task and its planned study?"));
        return Layout.page(req, "/tasks", t.title(), body);
    }

    private static String[] priorityOptions() {
        Priority[] all = Priority.values();
        String[] out = new String[all.length * 2];
        for (int i = 0; i < all.length; i++) {
            out[i * 2] = all[i].name();
            out[i * 2 + 1] = all[i].label();
        }
        return out;
    }
}
