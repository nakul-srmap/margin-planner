package planner.web;

import planner.auth.Accounts;
import planner.auth.Session;
import planner.domain.Records.Preferences;
import planner.http.Html;
import planner.http.Request;
import planner.http.Response;
import planner.http.Router;
import planner.http.Server;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Sign in, create an account, sign out. */
final class AuthPages {

    private final Accounts accounts;

    AuthPages(Accounts accounts) {
        this.accounts = accounts;
    }

    void routes(Router r) {
        r.publicGet("/signin", req -> signedIn(req).orElseGet(() -> Response.html(signIn(req, Form.blank(), null))));
        r.publicPost("/signin", this::doSignIn);
        r.publicGet("/signup", req -> signedIn(req).orElseGet(() -> Response.html(signUp(req, Form.blank()))));
        r.publicPost("/signup", this::doSignUp);
        r.post("/signout", req -> {
            accounts.endSession(req.session().id());
            return Response.redirect("/signin").cookie(Server.clearSessionCookie()).flash("You're signed out.");
        });
    }

    private static Optional<Response> signedIn(Request req) {
        return req.signedIn() ? Optional.of(Response.redirect("/")) : Optional.empty();
    }

    private Response doSignIn(Request req) {
        Form f = Form.submitted(req);
        String email = f.required("email", 200, "Enter the email you signed up with.");
        String password = f.required("password", 200, "Enter your password.");
        if (!f.ok()) {
            return Response.html(400, signIn(req, f, null));
        }
        Optional<Long> user = accounts.authenticate(email, password);
        if (user.isEmpty()) {
            return Response.html(400, signIn(req, f, "That email and password don't match an account."));
        }
        Session session = accounts.startSession(user.get());
        return Response.redirect(safeNext(f.v("next"))).cookie(Server.sessionCookie(session.id()));
    }

    private Response doSignUp(Request req) {
        Form f = Form.submitted(req);
        String name = f.required("name", 80, "Tell us what to call you.");
        String email = f.required("email", 200, "Enter an email address.");
        String password = f.v("password");
        if (!email.isEmpty() && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            f.fail("email", "That doesn't look like an email address.");
        }
        if (password.length() < 8) {
            f.fail("password", "Use at least 8 characters.");
        } else if (password.length() > 200) {
            f.fail("password", "Keep this under 200 characters.");
        }
        if (!f.ok()) {
            return Response.html(400, signUp(req, f));
        }
        try {
            long id = accounts.register(name, email, password);
            Session session = accounts.startSession(id);
            return Response.redirect("/").cookie(Server.sessionCookie(session.id()))
                    .flash("Welcome, " + session.firstName() + ". Start by adding your subjects, or load a sample week to look around.");
        } catch (Accounts.EmailTaken e) {
            f.fail("email", "There's already an account with this email. Sign in instead.");
            return Response.html(400, signUp(req, f));
        }
    }

    /** Only ever redirect to a path on this site. */
    private static String safeNext(String next) {
        return next != null && next.startsWith("/") && !next.startsWith("//") && !next.contains("\\") ? next : "/";
    }

    private String signIn(Request req, Form f, String problem) {
        if (!f.submittedForm()) {
            f.set("next", req.query("next") == null ? "/" : req.query("next"));
        }
        String body = Html.t("""
                <div class="auth">
                  <section>
                    <h1>Sign in</h1>
                    <p class="lede">Your week, planned around your classes.</p>
                    {{{problem}}}{{{summary}}}
                    <form class="sheet" method="post" action="/signin" novalidate>
                      {{{csrf}}}<input type="hidden" name="next" value="{{next}}">
                      <div class="fields">{{{email}}}{{{password}}}</div>
                      <div class="form-foot"><button class="button" type="submit">Sign in</button></div>
                    </form>
                    <p class="switch">New here? <a href="/signup">Create an account</a></p>
                  </section>
                  {{{sample}}}
                </div>""",
                "problem", problem == null ? "" : "<p class=\"form-error\" role=\"alert\">" + Html.esc(problem) + "</p>",
                "summary", f.summary(), "csrf", Html.csrf(req), "next", f.v("next"),
                "email", f.input("email", "Email", "email", null, "autocomplete=\"email\" required autofocus"),
                "password", f.input("password", "Password", "password", null, "autocomplete=\"current-password\" required"),
                "sample", sample());
        return Layout.page(req, "", "Sign in", body);
    }

    private String signUp(Request req, Form f) {
        String body = Html.t("""
                <div class="auth">
                  <section>
                    <h1>Create an account</h1>
                    <p class="lede">Add your timetable and what's due. Margin works out when to study, and tells you why.</p>
                    {{{summary}}}
                    <form class="sheet" method="post" action="/signup" novalidate>
                      {{{csrf}}}
                      <div class="fields">{{{name}}}{{{email}}}{{{password}}}</div>
                      <div class="form-foot"><button class="button" type="submit">Create account</button></div>
                    </form>
                    <p class="switch">Already have an account? <a href="/signin">Sign in</a></p>
                  </section>
                  {{{sample}}}
                </div>""",
                "summary", f.summary(), "csrf", Html.csrf(req),
                "name", f.input("name", "Your name", "text", null, "autocomplete=\"name\" required autofocus"),
                "email", f.input("email", "Email", "email", null, "autocomplete=\"email\" required"),
                "password", f.input("password", "Password", "password", "At least 8 characters", "autocomplete=\"new-password\" required"),
                "sample", sample());
        return Layout.page(req, "", "Create an account", body);
    }

    /** A real week grid, filled with an example, so the first screen shows what the product does. */
    private static String sample() {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Preferences prefs = new Preferences(LocalTime.of(8, 0), LocalTime.of(21, 0), 240, 30, 90, 10, 2,
                false, LocalTime.of(17, 0), LocalTime.of(21, 0));
        List<WeekGrid.Cell> cells = new ArrayList<>();
        Object[][] classes = {
                {0, 9, 11, "DAA"}, {0, 13, 14, "DBMS"}, {1, 10, 12, "MATH"}, {2, 9, 11, "DAA"},
                {2, 14, 16, "DBMS lab"}, {3, 11, 13, "MATH"}, {4, 9, 10, "WRIT"},
        };
        for (Object[] c : classes) {
            cells.add(new WeekGrid.Cell(monday.plusDays((int) c[0]), LocalTime.of((int) c[1], 0),
                    LocalTime.of((int) c[2], 0), true, (String) c[3], null, null, null, null));
        }
        Object[][] study = {
                {0, "17:00", "18:30", "DAA problem set", "pink"}, {1, "17:30", "19:00", "Probability revision", "green"},
                {2, "17:00", "18:00", "ER diagram", "blue"}, {3, "17:00", "18:30", "DAA problem set", "pink"},
                {4, "11:00", "12:00", "Essay draft", "orange"}, {1, "14:00", "15:00", "ER diagram", "blue"},
        };
        for (Object[] s : study) {
            cells.add(new WeekGrid.Cell(monday.plusDays((int) s[0]), LocalTime.parse((String) s[1]),
                    LocalTime.parse((String) s[2]), false, (String) s[3], null, (String) s[4], null, null));
        }
        LocalDateTime noon = LocalDateTime.of(monday.minusDays(1), LocalTime.NOON);
        return "<figure class=\"auth-sample\" aria-hidden=\"true\">" + WeekGrid.render(monday, prefs, cells, noon, false)
                .replaceAll("(?s)<p class=\"legend\">.*?</p>", "")
                + "<figcaption class=\"note\">An example week. Grey is class; colour is study Margin planned for you.</figcaption></figure>";
    }
}
