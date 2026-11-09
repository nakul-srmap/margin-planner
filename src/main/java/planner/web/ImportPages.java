package planner.web;

import planner.domain.Records.Kind;
import planner.domain.Records.Subject;
import planner.domain.Records.TimetableEntry;
import planner.domain.Text;
import planner.http.HttpException;
import planner.http.Html;
import planner.http.Request;
import planner.http.Response;
import planner.http.Router;
import planner.portal.SrmPortal;
import planner.portal.TimetableParser;
import planner.portal.TimetableParser.Found;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bringing a timetable in from the SRM AP Student Corner, by signing in or by pasting the page. */
final class ImportPages {

    private record Pending(List<Found> classes, String raw, String source) { }

    private final Ctx ctx;
    private final SrmPortal portal = new SrmPortal();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    ImportPages(Ctx ctx) {
        this.ctx = ctx;
    }

    void routes(Router r) {
        r.get("/import", req -> Response.html(start(req, null)));
        r.get("/import/captcha", req -> {
            try {
                SrmPortal.Captcha c = portal.captcha(req.session().id());
                return Response.bytes(200, c.image(), c.type()).header("Cache-Control", "no-store");
            } catch (IOException e) {
                throw new HttpException(502, "Couldn't reach the SRM portal.");
            }
        });
        r.post("/import/srm", this::signIn);
        r.post("/import/paste", req -> {
            String html = req.param("html");
            if (html == null || html.isBlank()) {
                return Response.html(400, start(req, "Paste the timetable page's source, or choose the saved file."));
            }
            pending.put(req.session().id(), new Pending(TimetableParser.parse(html), html, "the pasted page"));
            return Response.redirect("/import/review");
        });
        r.get("/import/review", req -> Response.html(review(req)));
        r.get("/import/raw", req -> {
            Pending p = Optional.ofNullable(pending.get(req.session().id())).orElseThrow(HttpException::notFound);
            return Response.bytes(200, p.raw().getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8")
                    .header("Content-Disposition", "attachment; filename=\"srm-portal-pages.html\"");
        });
        r.post("/import/apply", this::apply);
    }

    private Response signIn(Request req) {
        String user = req.text("user").toUpperCase();
        String password = req.param("password") == null ? "" : req.param("password");
        String code = req.text("captcha").toUpperCase();
        if (user.isEmpty() || password.isEmpty() || code.isEmpty()) {
            return Response.html(400, start(req, "Fill in your register number, password and the captcha."));
        }
        try {
            SrmPortal.Result result = portal.importTimetable(req.session().id(), user, password, code);
            pending.put(req.session().id(), new Pending(result.classes(), result.pages(), "your SRM portal"));
            return Response.redirect("/import/review");
        } catch (SrmPortal.PortalException e) {
            return Response.html(400, start(req, e.getMessage()));
        } catch (IOException | InterruptedException e) {
            return Response.html(502, start(req, "Couldn't reach the SRM portal. Check your internet connection and try again."));
        }
    }

    private String start(Request req, String problem) {
        String body = Layout.head("Import from SRM", "Bring your class timetable in from the SRM AP Student Corner.", null) + Html.t("""
                {{{problem}}}
                <div class="split">
                  <form class="sheet" method="post" action="/import/srm" autocomplete="off">
                    {{{csrf}}}
                    <h2 style="margin-bottom:.4rem">Sign in to Student Corner</h2>
                    <p class="note" style="margin-bottom:1rem">Your password goes straight to SRM and is never saved in Margin.</p>
                    <div class="fields" style="grid-template-columns:1fr">
                      <label class="field"><span class="label">Register number</span><input type="text" name="user" maxlength="13" required placeholder="AP23110010000" autocomplete="username"></label>
                      <label class="field"><span class="label">Password</span><input type="password" name="password" required autocomplete="current-password"></label>
                      <div class="field captcha-row">
                        <img src="/import/captcha" alt="Captcha from the SRM portal" class="captcha" data-captcha width="150" height="50">
                        <button type="button" class="link-button" data-captcha-refresh>New captcha</button>
                      </div>
                      <label class="field"><span class="label">Captcha</span><input type="text" name="captcha" maxlength="5" required placeholder="Type the letters above" style="text-transform:uppercase"></label>
                    </div>
                    <div class="form-foot"><button class="button" type="submit">Sign in and import</button></div>
                  </form>
                  <form class="sheet" method="post" action="/import/paste">
                    {{{csrf}}}
                    <h2 style="margin-bottom:.4rem">Or use a saved page</h2>
                    <p class="note" style="margin-bottom:1rem">Open your timetable on the portal, press Ctrl+S and choose "Webpage, HTML only". Then pick that file here.</p>
                    <div class="fields" style="grid-template-columns:1fr">
                      <label class="field"><span class="label">Saved timetable page</span><input type="file" accept=".html,.htm,text/html" data-load-into="html"></label>
                      <label class="field"><span class="label">Or paste the page source <span class="hint">Ctrl+U, Ctrl+A, Ctrl+C on the timetable page</span></span><textarea name="html" id="html" rows="6"></textarea></label>
                    </div>
                    <div class="form-foot"><button class="button quiet" type="submit">Read this page</button></div>
                  </form>
                </div>""",
                "problem", problem == null ? "" : "<p class=\"form-error\" role=\"alert\">" + Html.esc(problem) + "</p>",
                "csrf", Html.csrf(req));
        return Layout.page(req, "/timetable", "Import from SRM", body);
    }

    private String review(Request req) {
        Pending p = pending.get(req.session().id());
        if (p == null) {
            return start(req, "That import has expired. Start again.");
        }
        if (p.classes().isEmpty()) {
            String body = Layout.head("No classes found", null, null) + Html.t("""
                    <section class="empty">
                      <h2>Margin couldn't find a timetable in {{source}}</h2>
                      <p>The sign-in worked, but the pages it read didn't contain a grid of days, times and course codes. Download what was read and send it to whoever set Margin up, so the reader can learn this layout. Or try the saved-page option with your timetable page open.</p>
                      <div class="actions"><a class="button" href="/import/raw">Download what was read</a><a class="button quiet" href="/import">Back</a></div>
                    </section>""", "source", p.source());
            return Layout.page(req, "/timetable", "Import from SRM", body);
        }
        StringBuilder rows = new StringBuilder();
        for (Found f : p.classes()) {
            rows.append(Html.t("""
                    <tr><td>{{day}}</td><td class="nowrap">{{from}} to {{to}}</td><td><span class="hl hl-violet">{{code}}</span> {{name}}</td><td>{{kind}}</td><td>{{room}}</td></tr>""",
                    "day", Text.dayName(f.day()), "from", Text.time(f.start()), "to", Text.time(f.end()),
                    "code", f.code(), "name", f.name().equals(f.code()) ? "" : f.name(),
                    "kind", f.lab() ? "Lab" : "Lecture", "room", f.room() == null ? "" : f.room()));
        }
        long subjects = p.classes().stream().map(Found::code).distinct().count();
        String body = Layout.head("Found " + Text.plural(p.classes().size(), "class", "classes"),
                "From " + p.source() + ", across " + Text.plural(subjects, "subject", "subjects") + ". Check them, then import.", null)
                + Html.t("""
                <div class="split-wide">
                  <table class="ledger"><thead><tr><th>Day</th><th>Time</th><th>Course</th><th>Type</th><th>Room</th></tr></thead><tbody>{{{rows}}}</tbody></table>
                  <aside>
                    <form class="sheet" method="post" action="/import/apply">
                      {{{csrf}}}
                      <p style="margin-bottom:1rem">New course codes become subjects. You can rename them and set their difficulty on the Subjects page afterwards.</p>
                      <label class="check"><input type="checkbox" name="replace" value="1" checked> Replace the classes already in my timetable</label>
                      <p class="note" style="margin:.6rem 0 0">Other commitments, like a job or practice, are kept.</p>
                      <div class="form-foot"><button class="button" type="submit">Import {{n}}</button><a href="/import">Start over</a></div>
                    </form>
                  </aside>
                </div>""", "rows", rows.toString(), "csrf", Html.csrf(req),
                "n", Text.plural(p.classes().size(), "class", "classes"));
        return Layout.page(req, "/timetable", "Import from SRM", body);
    }

    private Response apply(Request req) {
        long uid = req.userId();
        Pending p = pending.remove(req.session().id());
        if (p == null || p.classes().isEmpty()) {
            return Response.redirect("/import").flash("That import has expired. Start again.");
        }
        Map<String, Long> ids = new HashMap<>();
        for (Subject s : ctx.store().subjects(uid)) {
            ids.put(s.code().replace(" ", "").toUpperCase(), s.id());
        }
        int newSubjects = 0;
        for (Found f : p.classes()) {
            if (!ids.containsKey(f.code())) {
                String name = f.name().length() > 100 ? f.name().substring(0, 100) : f.name();
                ids.put(f.code(), ctx.store().addSubject(uid, f.code().length() > 12 ? f.code().substring(0, 12) : f.code(),
                        name, 3, ctx.store().nextHighlighter(uid)));
                newSubjects++;
            }
        }
        if ("1".equals(req.text("replace"))) {
            for (TimetableEntry e : ctx.store().timetable(uid)) {
                if (e.kind().isClass()) {
                    ctx.store().deleteTimetableEntry(uid, e.id());
                }
            }
        }
        int added = 0;
        int skipped = 0;
        for (Found f : p.classes()) {
            if (ctx.planner().clash(uid, f.day(), f.start(), f.end()).isPresent()) {
                skipped++;
                continue;
            }
            String room = f.room() == null ? null : f.room().length() > 40 ? f.room().substring(0, 40) : f.room();
            ctx.store().addTimetableEntry(uid, ids.get(f.code()), f.lab() ? Kind.LAB : Kind.LECTURE, null,
                    f.day(), f.start(), f.end(), room);
            added++;
        }
        String msg = "Imported " + Text.plural(added, "class", "classes")
                + (newSubjects > 0 ? " and " + Text.plural(newSubjects, "new subject", "new subjects") : "") + "."
                + (skipped > 0 ? " Skipped " + skipped + " that overlapped something already there." : "");
        return Response.redirect("/timetable").flash(msg + (added > 0 ? ctx.autoPlan(uid) : ""));
    }
}
