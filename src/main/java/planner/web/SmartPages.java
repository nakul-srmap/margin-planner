package planner.web;

import planner.ai.Assistant;
import planner.ai.Assistant.Draft;
import planner.ai.Gemini;
import planner.domain.Records.Subject;
import planner.domain.Store;
import planner.domain.Text;
import planner.google.Classroom;
import planner.http.Html;
import planner.http.Request;
import planner.http.Response;
import planner.http.Router;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** AI quick add (Gemini) and Google Classroom sync. */
final class SmartPages {

    private final Ctx ctx;
    private final Map<String, List<Draft>> drafts = new ConcurrentHashMap<>();

    SmartPages(Ctx ctx) {
        this.ctx = ctx;
    }

    void routes(Router r) {
        r.post("/ai/understand", this::understand);
        r.get("/ai/review", req -> Response.html(review(req)));
        r.post("/ai/apply", this::apply);
        r.post("/ai/datesheet", this::datesheet);
        r.get("/classroom/connect", this::connect);
        r.get("/classroom/callback", this::callback);
        r.post("/classroom/sync", req -> sync(req, "Synced"));
        r.post("/classroom/disconnect", req -> {
            ctx.store().deleteGoogleAccount(req.userId());
            return Response.redirect("/tasks").flash("Disconnected from Google Classroom. Tasks already brought in were kept.");
        });
        r.post("/settings/auto", req -> {
            String mode = List.of("BALANCED", "DEADLINE", "EXAM").contains(req.text("mode")) ? req.text("mode") : "BALANCED";
            boolean on = "1".equals(req.text("auto"));
            ctx.store().setAutoPlan(req.userId(), on, mode);
            return Response.redirect("/settings").flash(on ? "Margin will re-plan your week whenever you add something." : "Automatic re-planning is off.");
        });
    }

    /* ======================= Shared panels ======================= */

    /** The "tell Margin what's coming up" box, shown on the week and task pages. */
    static String quickAdd(Request req) {
        if (!Gemini.configured()) {
            return """
                    <section class="panel ai-box off"><h2>Quick add with AI</h2>
                    <p class="note">Type "DAA assignment due Friday, about 3 hours" and Margin adds it and plans it. To turn this on, add a free Gemini API key to planner.properties as <code>gemini.api.key</code> (see the README).</p></section>""";
        }
        return Html.t("""
                <form class="panel ai-box" method="post" action="/ai/understand">{{{csrf}}}
                  <label for="ai-text"><h2>Quick add with AI</h2></label>
                  <textarea id="ai-text" name="text" rows="2" maxlength="8000" required
                    placeholder="DAA assignment due Friday, about 3 hours. DBMS mid-sem on the 20th. Or paste your Classroom to-do list."></textarea>
                  <div class="form-foot"><button class="button" type="submit" data-busy="Reading...">Add with AI</button>
                  <span class="note">Gemini reads it and estimates the time; Margin then fits it into your free time.</span></div>
                </form>""", "csrf", Html.csrf(req));
    }

    String classroomPanel(Request req) {
        if (!Classroom.configured()) {
            return """
                    <section class="panel classroom"><h2>Google Classroom</h2>
                    <p class="note">Bring assignments in automatically. Needs a one-time Google setup: add google.client.id and google.client.secret to planner.properties (the README has the steps).</p></section>""";
        }
        var account = ctx.store().googleAccount(req.userId());
        if (account.isEmpty()) {
            return """
                    <section class="panel classroom"><h2>Google Classroom</h2>
                    <p style="margin-bottom:1rem">Bring your assignments and due dates in, and keep them in sync.</p>
                    <a class="button" href="/classroom/connect">Connect Google Classroom</a>
                    <p class="note" style="margin-top:.8rem">Read-only. If your college blocks it, copy your Classroom to-do list into Quick add instead.</p></section>""";
        }
        Instant synced = account.get().syncedAt();
        String when = synced == null ? "Not synced yet." : "Last synced " + Text.day(LocalDateTime.ofInstant(synced, ctx.clock().getZone()).toLocalDate(), ctx.now().toLocalDate())
                + " at " + Text.time(LocalDateTime.ofInstant(synced, ctx.clock().getZone()).toLocalTime()) + ".";
        return Html.t("""
                <section class="panel classroom"><h2>Google Classroom</h2>
                  <p class="note" style="margin-bottom:1rem">Connected. {{when}}</p>
                  <div class="actions">{{{sync}}}{{{off}}}</div>
                </section>""", "when", when,
                "sync", Layout.button(req, "/classroom/sync", "Sync now", "button", null),
                "off", Layout.button(req, "/classroom/disconnect", "Disconnect", "link-button", "Disconnect Google Classroom?"));
    }

    /* ======================= AI quick add ======================= */

    private Response understand(Request req) {
        String text = req.text("text");
        if (text.isBlank()) {
            return Response.redirect("/tasks").flash("Type what's coming up first.");
        }
        try {
            List<Draft> found = Assistant.understand(text.length() > 8000 ? text.substring(0, 8000) : text,
                    ctx.store().subjects(req.userId()), ctx.now());
            if (found.isEmpty()) {
                return Response.redirect("/tasks").flash("Gemini didn't find anything with a date in that. Try adding when it's due.");
            }
            drafts.put(req.session().id(), found);
            return Response.redirect("/ai/review");
        } catch (Gemini.AiException e) {
            return Response.redirect("/tasks").flash(e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            System.err.println("Gemini timed out: " + e);
            return Response.redirect("/tasks").flash("Gemini took longer than 90 seconds to answer. Try again; it's usually quicker on a second go.");
        } catch (IOException | InterruptedException e) {
            System.err.println("Gemini request failed: " + e);
            e.printStackTrace();
            return Response.redirect("/tasks").flash("Couldn't reach Gemini (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ").");
        }
    }

    /** The datesheet upload box on the Exams page. The file is read in the browser and sent as base64. */
    static String datesheetBox(Request req) {
        if (!Gemini.configured()) {
            return "";
        }
        return Html.t("""
                <form class="panel ai-box" method="post" action="/ai/datesheet" data-datesheet>{{{csrf}}}
                  <label for="ds-file"><h2>Upload your datesheet</h2></label>
                  <p class="note" style="margin-bottom:.8rem">A photo, screenshot or PDF of your exam timetable. Gemini finds the exams for your subjects; you check them before they're added.</p>
                  <input id="ds-file" type="file" accept="image/png,image/jpeg,image/webp,application/pdf" required data-file-to="ds">
                  <input type="hidden" name="data" id="ds-data"><input type="hidden" name="mime" id="ds-mime">
                  <div class="form-foot"><button class="button" type="submit" data-busy="Reading the datesheet...">Read datesheet</button>
                  <span class="note">Up to 8 MB.</span></div>
                </form>""", "csrf", Html.csrf(req));
    }

    private Response datesheet(Request req) {
        String data = req.param("data");
        String mime = req.text("mime");
        if (data == null || data.isBlank()) {
            return Response.redirect("/exams").flash("Choose a datesheet file first.");
        }
        if (!java.util.List.of("image/png", "image/jpeg", "image/webp", "application/pdf").contains(mime)) {
            return Response.redirect("/exams").flash("Use a PNG, JPG, WEBP image or a PDF.");
        }
        if (data.length() > 11_000_000) {
            return Response.redirect("/exams").flash("That file is over 8 MB. Use a smaller screenshot or PDF.");
        }
        List<Subject> subjects = ctx.store().subjects(req.userId());
        if (subjects.isEmpty()) {
            return Response.redirect("/subjects").flash("Add your subjects first, so Margin knows which exams on the datesheet are yours.");
        }
        try {
            List<Draft> found = Assistant.datesheet(data.trim(), mime, subjects, ctx.now());
            if (found.isEmpty()) {
                return Response.redirect("/exams").flash("No exams for your subjects were found on that datesheet. Check your subject codes match it.");
            }
            drafts.put(req.session().id(), found);
            return Response.redirect("/ai/review");
        } catch (Gemini.AiException e) {
            return Response.redirect("/exams").flash(e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            return Response.redirect("/exams").flash("Gemini took too long to read the datesheet. Try again, or use a smaller image.");
        } catch (IOException | InterruptedException e) {
            return Response.redirect("/exams").flash("Couldn't reach Gemini (" + e.getClass().getSimpleName() + ").");
        }
    }

    private String review(Request req) {
        List<Draft> list = drafts.get(req.session().id());
        if (list == null) {
            return Layout.page(req, "/tasks", "Quick add", Layout.head("Nothing to review", null, null)
                    + "<p class=\"lede\">That quick add has expired. <a href=\"/tasks\">Try again</a>.</p>");
        }
        Map<String, Subject> subjects = subjects(req.userId());
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            Draft d = list.get(i);
            Subject s = d.subjectCode() == null ? null : subjects.get(d.subjectCode());
            rows.append(Html.t("""
                    <li><label class="draft"><input type="checkbox" name="pick" value="{{i}}" checked>
                      <span class="kind kind-{{kindCls}}">{{kind}}</span>
                      <span><b>{{title}}</b> {{{code}}}<br><span class="meta">{{due}}{{mins}} · {{priority}} priority</span>{{{note}}}</span>
                    </label></li>""",
                    "i", i, "kindCls", d.exam() ? "exam" : "task", "kind", d.exam() ? "Exam" : "Task", "title", d.title(),
                    "code", s == null ? "" : Layout.hl(s.code(), s.highlighter()),
                    "due", (d.exam() ? "On " : "Due ") + Text.date(d.date()) + (d.time() == null ? "" : " at " + Text.time(d.time())),
                    "mins", d.exam() ? (d.minutes() > 0 ? " · about " + Text.duration(d.minutes()) + " of revision" : "") : " · " + Text.duration(d.minutes()),
                    "priority", d.priority().label(),
                    "note", d.note() == null ? "" : "<span class=\"note\">" + Html.esc(d.note()) + "</span>"));
        }
        String body = Layout.head("Gemini found " + Text.plural(list.size(), "thing", "things"), "Untick anything you don't want, then add.", null)
                + Html.t("""
                <form method="post" action="/ai/apply" style="max-width:48rem">{{{csrf}}}
                  <ul class="rows drafts">{{{rows}}}</ul>
                  <div class="form-foot"><button class="button" type="submit">Add and plan</button><a href="/tasks">Cancel</a></div>
                </form>""", "csrf", Html.csrf(req), "rows", rows.toString());
        return Layout.page(req, "/tasks", "Quick add", body);
    }

    private Response apply(Request req) {
        long uid = req.userId();
        List<Draft> list = drafts.remove(req.session().id());
        if (list == null) {
            return Response.redirect("/tasks").flash("That quick add has expired. Try again.");
        }
        Map<String, Subject> subjects = subjects(uid);
        int tasks = 0;
        int exams = 0;
        int duplicates = 0;
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (var ex : ctx.store().exams(uid)) {
            existing.add(ex.subjectCode() + "|" + ex.date());
        }
        for (String pick : req.params("pick")) {
            int i;
            try {
                i = Integer.parseInt(pick);
            } catch (NumberFormatException e) {
                continue;
            }
            if (i < 0 || i >= list.size()) {
                continue;
            }
            Draft d = list.get(i);
            Subject s = d.subjectCode() == null ? null : subjects.get(d.subjectCode());
            if (d.exam() && s != null) {
                if (!existing.add(s.code() + "|" + d.date())) {
                    duplicates++;   // already have this subject's exam on that day
                    continue;
                }
                ctx.store().addExam(uid, s.id(), d.title(), d.date(), d.time(), 30);
                exams++;
            } else {
                LocalDateTime due = LocalDateTime.of(d.date(), d.time() == null ? LocalTime.of(23, 59) : d.time());
                ctx.store().addTask(uid, s == null ? null : s.id(), d.exam() ? "Prepare for " + d.title() : d.title(),
                        d.note(), d.priority(), due, d.minutes(), true);
                tasks++;
            }
        }
        String added = (tasks > 0 ? Text.plural(tasks, "task", "tasks") : "")
                + (tasks > 0 && exams > 0 ? " and " : "") + (exams > 0 ? Text.plural(exams, "exam", "exams") : "");
        String dup = duplicates == 0 ? "" : " Skipped " + Text.plural(duplicates, "exam", "exams") + " you already had.";
        if (added.isEmpty()) {
            return Response.redirect(duplicates > 0 ? "/exams" : "/tasks").flash(duplicates > 0 ? dup.trim() : "Nothing was ticked, so nothing was added.");
        }
        return Response.redirect(exams > 0 && tasks == 0 ? "/exams" : "/").flash("Added " + added + "." + dup + ctx.autoPlan(uid));
    }

    private Map<String, Subject> subjects(long uid) {
        Map<String, Subject> out = new HashMap<>();
        for (Subject s : ctx.store().subjects(uid)) {
            out.put(s.code(), s);
        }
        return out;
    }

    /* ======================= Google Classroom ======================= */

    private static String redirect(Request req) {
        String site = planner.Config.setting("PUBLIC_URL", "public.url");
        if (site != null) {
            return site.replaceAll("/+$", "") + "/classroom/callback";
        }
        String host = req.header("Host");
        return "http://" + (host == null || !host.matches("(localhost|127\\.0\\.0\\.1)(:\\d+)?") ? "localhost:8080" : host) + "/classroom/callback";
    }

    private Response connect(Request req) {
        if (!Classroom.configured()) {
            return Response.redirect("/tasks").flash("Google Classroom isn't set up yet. Add google.client.id and google.client.secret to planner.properties.");
        }
        return Response.redirect(Classroom.authUrl(redirect(req), req.csrf()));
    }

    private Response callback(Request req) {
        if (req.query("error") != null) {
            return Response.redirect("/tasks").flash("access_denied".equals(req.query("error"))
                    ? "Google Classroom wasn't connected. If Google said access is blocked, your college hasn't allowed this app; use Quick add instead."
                    : "Google sign-in stopped: " + req.query("error") + ".");
        }
        if (req.query("code") == null || !req.csrf().equals(req.query("state"))) {
            return Response.redirect("/tasks").flash("That Google sign-in didn't match this session. Try Connect again.");
        }
        try {
            Classroom.Tokens t = Classroom.exchange(req.query("code"), redirect(req));
            if (t.refresh() == null) {
                return Response.redirect("/tasks").flash("Google didn't grant lasting access. Try Connect again.");
            }
            ctx.store().saveGoogleTokens(req.userId(), t.refresh(), t.access(), t.expires());
        } catch (Classroom.ClassroomException e) {
            return Response.redirect("/tasks").flash(e.getMessage());
        } catch (IOException | InterruptedException e) {
            return Response.redirect("/tasks").flash("Couldn't reach Google. Check your internet connection.");
        }
        return sync(req, "Connected. Brought in");
    }

    private Response sync(Request req, String verb) {
        long uid = req.userId();
        var account = ctx.store().googleAccount(uid);
        if (account.isEmpty()) {
            return Response.redirect("/tasks").flash("Connect Google Classroom first.");
        }
        try {
            String access = account.get().accessToken();
            if (access == null || account.get().expiresAt() == null || account.get().expiresAt().isBefore(Instant.now())) {
                Classroom.Tokens t = Classroom.refresh(account.get().refreshToken());
                ctx.store().saveGoogleTokens(uid, t.refresh(), t.access(), t.expires());
                access = t.access();
            }
            List<Classroom.Work> work = Classroom.assignments(access, ctx.clock().getZone());
            List<Subject> subjects = ctx.store().subjects(uid);
            LocalDateTime cutoff = ctx.now().minusDays(14);
            int created = 0;
            int total = 0;
            for (Classroom.Work w : work) {
                if (w.due().isBefore(cutoff) || w.title() == null) {
                    continue;   // old coursework would only clutter the list
                }
                Subject s = match(w.course(), subjects);
                String title = w.title().length() > 160 ? w.title().substring(0, 160) : w.title();
                String notes = w.description() == null ? null : w.description().length() > 2000 ? w.description().substring(0, 2000) : w.description();
                if (ctx.store().upsertExternalTask(uid, w.id(), s == null ? null : s.id(), title, notes, w.due(), 120, w.link(), w.turnedIn())) {
                    created++;
                }
                total++;
            }
            ctx.store().markGoogleSynced(uid);
            return Response.redirect("/tasks").flash(verb + " " + Text.plural(total, "assignment", "assignments")
                    + " from Classroom (" + created + " new). Check the time each one needs." + (created > 0 ? ctx.autoPlan(uid) : ""));
        } catch (Classroom.ClassroomException e) {
            return Response.redirect("/tasks").flash(e.getMessage());
        } catch (IOException | InterruptedException e) {
            return Response.redirect("/tasks").flash("Couldn't reach Google Classroom. Check your internet connection.");
        }
    }

    /** "CSE 204 - DAA (Section B)" belongs to the subject coded CSE204, or the one named in it. */
    static Subject match(String course, List<Subject> subjects) {
        if (course == null) {
            return null;
        }
        String flat = course.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        for (Subject s : subjects) {
            if (flat.contains(s.code().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""))) {
                return s;
            }
        }
        String words = course.toLowerCase(Locale.ROOT);
        for (Subject s : subjects) {
            String name = s.name().toLowerCase(Locale.ROOT);
            if (name.length() >= 4 && (words.contains(name) || name.contains(words.trim()))) {
                return s;
            }
        }
        return null;
    }
}
