package planner;

import planner.auth.Accounts;
import planner.db.Db;
import planner.db.Migrations;
import planner.domain.Planner;
import planner.domain.Store;
import planner.http.Server;
import planner.web.Web;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the engine checks, then drives the real web app over HTTP against a throwaway
 * PostgreSQL database: sign-up, CSRF, validation, isolation between accounts, planning.
 */
public final class TestRunner {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        EngineVerification.main(args);
        Config base = Config.load();
        String url = base.dbUrl().replaceAll("/[A-Za-z0-9_]+(\\?.*)?$", "/planner_test$1");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            App.ensureDatabase(new Config(url, base.dbUser(), base.dbPassword(), 0));
        } catch (Exception e) {
            System.out.println("\nSKIPPED web tests: PostgreSQL isn't reachable (" + e.getMessage() + ")");
            return;
        }
        try (Connection c = DriverManager.getConnection(url, base.dbUser(), base.dbPassword());
             Statement st = c.createStatement()) {
            st.execute("drop schema public cascade; create schema public");
        }

        Db db = new Db(url, base.dbUser(), base.dbPassword(), 5);
        Migrations.apply(db);
        Store store = new Store(db);
        Accounts accounts = new Accounts(db);
        Planner planner = new Planner(store);
        Server server = new Server(Web.routes(store, planner, accounts, Clock.systemDefaultZone()), accounts, Web::errorPage)
                .assets(List.of("app.css", "app.js", "favicon.svg", "recursive.woff2", "recursive-OFL.txt", "theme.js"));
        int port = 18000 + (int) (Math.random() * 1000);
        server.start(port);
        String site = "http://localhost:" + port;

        System.out.println("\nWeb app against PostgreSQL");
        try {
            run(site);
        } finally {
            server.stop();
            db.close();
        }
        System.out.println("\nRESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void run(String site) throws Exception {
        Browser a = new Browser(site);
        check("signed-out visitors are sent to sign in", a.get("/tasks").statusCode() == 303
                && a.last.headers().firstValue("location").orElse("").startsWith("/signin"));
        check("static files are served", a.get("/app.css").statusCode() == 200);

        a.get("/signup");
        check("posting without the form token is refused",
                a.postRaw("/signup", "name=X&email=x@y.com&password=longenough").statusCode() == 403);
        HttpResponse<String> bad = a.post("/signup", "name", "", "email", "nope", "password", "short");
        check("bad sign-up is shown again with reasons", bad.statusCode() == 400
                && bad.body().contains("Use at least 8 characters") && bad.body().contains("look like an email"));
        check("sign-up succeeds", a.post("/signup", "name", "Asha Rao", "email", "asha@example.com",
                "password", "correct horse").statusCode() == 303);
        check("the welcome page shows", a.get("/").body().contains("Welcome to Margin"));

        Browser dup = new Browser(site);
        dup.get("/signup");
        check("an email can't be registered twice", dup.post("/signup", "name", "Other", "email", "ASHA@example.com",
                "password", "another one").body().contains("already an account"));

        check("adding a subject works", a.post("/subjects", "code", "cs301", "name", "Algorithms",
                "difficulty", "4", "highlighter", "pink").statusCode() == 303);
        check("subject codes are upper-cased", a.get("/subjects").body().contains("CS301"));
        check("a duplicate subject code is explained", a.post("/subjects", "code", "CS301", "name", "Again",
                "difficulty", "3", "highlighter", "blue").body().contains("already have a subject with this code"));
        String subjectId = find(a.get("/subjects").body(), "href=\"/subjects/(\\d+)\"");

        check("adding a class works", a.post("/timetable", "kind", "LECTURE", "subject", subjectId, "day", "1",
                "start", "09:00", "end", "10:30", "room", "LT-2").statusCode() == 303);
        HttpResponse<String> clash = a.post("/timetable", "kind", "OTHER", "label", "Gym", "day", "1",
                "start", "10:00", "end", "11:00", "room", "");
        check("an overlapping class is refused with the reason", clash.statusCode() == 400
                && clash.body().contains("overlaps"));
        check("a class that ends before it starts is refused", a.post("/timetable", "kind", "OTHER", "label", "Gym",
                "day", "2", "start", "11:00", "end", "10:00").body().contains("end after it starts"));

        String due = LocalDate.now().plusDays(3).toString();
        check("adding a task works", a.post("/tasks", "title", "Problem set 4", "subject", subjectId,
                "dueDate", due, "dueTime", "23:59", "minutes", "180", "priority", "HIGH", "splittable", "1").statusCode() == 303);
        check("a task without a title is refused", a.post("/tasks", "title", "", "dueDate", due,
                "minutes", "60", "priority", "LOW").body().contains("Give the task a name"));
        String taskId = find(a.get("/tasks").body(), "href=\"/tasks/(\\d+)\"");

        a.get("/plan");
        check("a plan can be previewed", a.post("/plan/preview", "mode", "DEADLINE").statusCode() == 303);
        String preview = a.get("/plan?drawn=1").body();
        check("the preview shows the task and why", preview.contains("Problem set 4") && preview.contains("Ranked"));
        check("the preview can be saved", a.post("/plan/save").statusCode() == 303);
        String weeks = a.get("/").body() + a.get("/?week=" + LocalDate.now().plusDays(7)).body();
        check("the saved plan is on the week", weeks.contains("class=\"block study"));
        String blockId = find(weeks, "action=\"/sessions/(\\d+)/done\"");
        check("a block can be marked done", a.post("/sessions/" + blockId + "/done", "back", "/").statusCode() == 303);
        check("its time counts towards the task", a.get("/tasks").body().contains("left of 3 h"));

        // A second account must never see or change the first one's data.
        Browser b = new Browser(site);
        b.get("/signup");
        b.post("/signup", "name", "Ben", "email", "ben@example.com", "password", "another secret");
        check("another account can't open the task", b.get("/tasks/" + taskId).statusCode() == 404);
        check("another account can't edit the task", b.post("/tasks/" + taskId, "title", "Hacked", "dueDate", due,
                "minutes", "60", "priority", "LOW", "completed", "0").statusCode() == 404);
        check("another account can't tick the task", b.post("/tasks/" + taskId + "/done", "done", "1").statusCode() == 404);
        check("another account can't delete the subject", b.post("/subjects/" + subjectId + "/delete").statusCode() == 404);
        check("another account can't resolve the block", b.post("/sessions/" + blockId + "/missed").statusCode() == 404);
        check("another account can't use the subject", b.post("/tasks", "title", "Sneaky", "subject", subjectId,
                "dueDate", due, "minutes", "60", "priority", "LOW").statusCode() == 404);
        check("the first account's task is untouched", a.get("/tasks/" + taskId).body().contains("Problem set 4"));

        check("ticking a task off works", a.post("/tasks/" + taskId + "/done", "done", "1").statusCode() == 303
                && a.get("/tasks").body().contains("is-done"));
        check("settings are validated", a.post("/settings", "wake", "09:00", "sleep", "08:00", "maxDaily", "300",
                "minSession", "30", "maxSession", "90", "breakMinutes", "10", "maxHard", "2",
                "focusStart", "17:00", "focusEnd", "21:00").body().contains("Bedtime has to be after"));
        check("settings save", a.post("/settings", "wake", "07:30", "sleep", "23:00", "maxDaily", "240",
                "minSession", "30", "maxSession", "90", "breakMinutes", "10", "maxHard", "2",
                "focusStart", "17:00", "focusEnd", "21:00", "weekend", "1").statusCode() == 303
                && a.get("/settings").body().contains("value=\"07:30\""));

        Browser c = new Browser(site);
        c.get("/signup");
        c.post("/signup", "name", "Cara", "email", "cara@example.com", "password", "sample please");
        c.get("/");
        check("the sample semester loads", c.post("/sample").statusCode() == 303);
        String week = c.get("/").body();
        check("the sample has a plan", week.contains("block study"));
        for (String page : List.of("/plan", "/tasks", "/exams", "/timetable", "/subjects", "/settings")) {
            check(page + " renders", c.get(page).statusCode() == 200);
        }
        // Importing a timetable page, in the grid layout college portals use.
        String page = "<html><body><table><tr><th>Day</th><th>08:00-08:50</th><th>09:00-09:50</th><th>10:00-10:50</th><th>01:00-01:50</th><th>02:00-02:50</th></tr>"
                + "<tr><td>MON</td><td>CSE 301<br>LH-101</td><td>CSE 301</td><td></td><td>MAT 201</td><td></td></tr>"
                + "<tr><td>TUE</td><td></td><td>MAT 201</td><td></td><td colspan=\"2\">CSE 302L Lab</td></tr>"
                + "<tr><td>Wednesday</td><td>ENG 110</td><td></td><td></td><td></td><td></td></tr></table>"
                + "<table><tr><td>CSE 301</td><td>Design and Analysis of Algorithms</td></tr><tr><td>MAT 201</td><td>Probability and Statistics</td></tr></table></body></html>";
        var found = planner.portal.TimetableParser.parse(page);
        check("the parser finds 5 classes in a day-by-time grid", found.size() == 5);
        check("back-to-back slots merge into one class", found.stream().anyMatch(f -> f.code().equals("CSE301")
                && f.start().toString().equals("08:00") && f.end().toString().equals("09:50")));
        check("afternoon hours written as 01:00 become 13:00", found.stream().anyMatch(f -> f.code().equals("MAT201")
                && f.start().toString().equals("13:00")));
        check("course names come from the course list", found.stream().anyMatch(f -> f.name().equals("Design and Analysis of Algorithms")));
        check("labs are recognised", found.stream().anyMatch(f -> f.code().equals("CSE302L") && f.lab()));
        String flipped = "<table><tr><th>Time</th><th>Monday</th><th>Tuesday</th><th>Wednesday</th></tr>"
                + "<tr><td>9:00 - 10:00</td><td>PHY101</td><td></td><td>PHY101</td></tr></table>";
        check("the parser also reads a time-by-day grid", planner.portal.TimetableParser.parse(flipped).size() == 2);

        // SRM AP's own layout: corner cell spans the slot-number and time rows, rooms in brackets, names in capitals.
        String srm = "<table><tr><th rowspan=\"2\"></th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th><th>6</th><th>7</th><th>8</th></tr>"
                + "<tr><th>09:00 To 09:50</th><th>10:00 To 10:50</th><th>11:00 To 11:50</th><th>12:00 To 12:50</th><th>01:00 To 01:50</th><th>02:00 To 02:50</th><th>03:00 To 03:50</th><th>04:00 To 05:30</th></tr>"
                + "<tr><td>Monday</td><td></td><td></td><td>CSE 213(S 408)</td><td>CSE 213(S 408)</td><td></td><td>CSE 204(C 805)</td><td>CSE 204(C 805)</td><td>POL 245(C 909)</td></tr>"
                + "<tr><td>Tuesday</td><td>CSE 202(C 804)</td><td>CSE 202(C 804)</td><td>SEC 203(S 408)</td><td>CSE 203(S 408)</td><td></td><td>CSE 207(X 208)</td><td>CSE 207(X 208)</td><td>POL 245(C 909)</td></tr>"
                + "<tr><td>Saturday</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr></table>"
                + "<table><tr><th colspan=\"2\">Subjects Description</th><th>L-T-P-C</th><th>Faculty Name</th><th>Class Room Name</th></tr>"
                + "<tr><td>CSE 202</td><td>OBJECT ORIENTED PROGRAMMING WITH C++</td><td>3-0-1-4</td><td>Dr. A ( 25184 )</td><td>(C 804),(S 408)</td></tr>"
                + "<tr><td>SEC 203</td><td>APTITUDE FOR ENGINEERS - III</td><td>1-0-1-2</td><td>Mr. B</td><td>(S 408)</td></tr></table>";
        var srmFound = planner.portal.TimetableParser.parse(srm);
        check("SRM layout: 8 classes after merging back-to-back slots", srmFound.size() == 8);
        check("SRM layout: Monday CSE 213 runs 11:00 to 12:50 in S 408", srmFound.stream().anyMatch(f -> f.code().equals("CSE213")
                && f.day() == java.time.DayOfWeek.MONDAY && f.start().toString().equals("11:00") && f.end().toString().equals("12:50")
                && "S 408".equals(f.room())));
        check("SRM layout: the last slot is 16:00 to 17:30", srmFound.stream().anyMatch(f -> f.code().equals("POL245")
                && f.start().toString().equals("16:00") && f.end().toString().equals("17:30")));
        check("SRM layout: capitalised names are tidied", srmFound.stream().anyMatch(f -> f.name().equals("Object Oriented Programming with C++"))
                && srmFound.stream().anyMatch(f -> f.name().equals("Aptitude for Engineers - III")));

        // JSON, Gemini answers and Classroom data, checked offline.
        Object j = planner.json.Json.parse("{\"a\":[1,2.5,{\"b\":\"x\\\"y\\u00e9\"}],\"t\":true,\"n\":null}");
        check("JSON: nested values are read", planner.json.Json.num(planner.json.Json.at(j, "a", 0)) == 1
                && "x\"y\u00e9".equals(planner.json.Json.at(j, "a", 2, "b")) && Boolean.TRUE.equals(planner.json.Json.at(j, "t")));
        check("JSON: quoting round-trips", "a\"b\n".equals(planner.json.Json.parse(planner.json.Json.quote("a\"b\n"))));
        var subj = java.util.List.of(new planner.domain.Records.Subject(1, "CSE204", "Design and Analysis of Algorithms", 4,
                planner.domain.Records.Highlighter.pink, 0, 0));
        java.time.LocalDateTime noon = java.time.LocalDateTime.of(2026, 11, 9, 12, 0);
        var drafts = planner.ai.Assistant.read("""
                {"items":[{"kind":"task","title":"DAA assignment 3","subject":"CSE 204","date":"2026-11-13","time":"23:59","minutes":183,"priority":"HIGH","note":"Start with the DP questions."},
                {"kind":"exam","title":"Mid-semester","subject":"CSE204","date":"2026-11-20","time":null,"minutes":99999,"priority":"bogus"},
                {"kind":"task","title":"No date here","date":"someday"}]}""", subj, noon);
        check("AI answer: items without a usable date are dropped", drafts.size() == 2);
        check("AI answer: subject codes match with or without a space", "CSE204".equals(drafts.get(0).subjectCode()));
        check("AI answer: minutes are rounded and capped", drafts.get(0).minutes() == 185 && drafts.get(1).minutes() == 6000);
        check("AI answer: unknown priority becomes Normal", drafts.get(1).priority() == planner.domain.Records.Priority.MEDIUM && drafts.get(1).exam());
        Object cw = planner.json.Json.parse("""
                {"id":"77","title":"Lab 4","alternateLink":"https://classroom.google.com/c/x/a/77","dueDate":{"year":2026,"month":11,"day":12},"dueTime":{"hours":18,"minutes":29}}""");
        var work = planner.google.Classroom.work(cw, "CSE 204 DAA", java.util.Map.of("77", "TURNED_IN"), java.time.ZoneId.of("Asia/Kolkata"));
        check("Classroom: UTC due time becomes local time", work.due().toString().equals("2026-11-12T23:59"));
        check("Classroom: turned-in work is recognised", work.turnedIn() && work.id().equals("classroom:77"));

        check("Gemini: the newest plain flash model is picked from the list", "gemini-3.6-flash".equals(planner.ai.Gemini.pickFlash("""
                {"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent","countTokens"]},
                {"name":"models/gemini-3.6-flash-lite","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-9.0-flash","supportedGenerationMethods":["embedContent"]}]}""")));

        check("Gemini: fallbacks are newest first, plain before lite", planner.ai.Gemini.flashModels("""
                {"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-3.6-flash-lite","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-3.8-flash-lite-preview","supportedGenerationMethods":["generateContent"]},
                {"name":"models/gemini-3.8-flash-image","supportedGenerationMethods":["generateContent"]}]}""")
                .equals(java.util.List.of("gemini-3.8-flash-lite-preview", "gemini-3.6-flash", "gemini-3.6-flash-lite", "gemini-2.5-flash")));

        String[] neon = Config.fromDatabaseUrl("postgresql://margin_owner:p%40ss@ep-cool-1.ap-southeast-1.aws.neon.tech/margin?sslmode=require&channel_binding=require");
        check("hosted DATABASE_URL becomes a JDBC URL", neon[0].equals("jdbc:postgresql://ep-cool-1.ap-southeast-1.aws.neon.tech/margin?sslmode=require")
                && neon[1].equals("margin_owner") && neon[2].equals("p@ss"));

        Browser e = new Browser(site);
        e.get("/signup");
        e.post("/signup", "name", "Eve", "email", "eve@example.com", "password", "auto plan please");
        e.get("/subjects");
        e.post("/subjects", "code", "CSE204", "name", "Algorithms", "difficulty", "4", "highlighter", "pink");
        HttpResponse<String> autoAdd = e.post("/tasks", "title", "Auto planned work", "dueDate", LocalDate.now().plusDays(4).toString(),
                "dueTime", "23:59", "minutes", "120", "priority", "HIGH", "splittable", "1");
        String afterAdd = e.get("/").body() + e.get("/?week=" + LocalDate.now().plusDays(7)).body();
        check("adding a task re-plans the week by itself", autoAdd.statusCode() == 303 && afterAdd.contains("Auto planned work")
                && afterAdd.contains("block study"));
        e.get("/exams");
        check("a datesheet upload without a file is refused politely", e.post("/ai/datesheet", "data", "", "mime", "image/png")
                .headers().firstValue("location").orElse("").equals("/exams"));
        check("a datesheet of the wrong type is refused", e.post("/ai/datesheet", "data", "QUJD", "mime", "text/html")
                .headers().firstValue("location").orElse("").equals("/exams"));
        check("automatic planning can be switched off", e.post("/settings/auto", "mode", "EXAM").statusCode() == 303
                && !e.get("/settings").body().contains("name=\"auto\" value=\"1\" checked"));

        Browser d = new Browser(site);
        d.get("/signup");
        d.post("/signup", "name", "Dev", "email", "dev@example.com", "password", "import please");
        check("the import page renders", d.get("/import").statusCode() == 200);
        check("a pasted page is read", d.post("/import/paste", "html", page).statusCode() == 303
                && d.get("/import/review").body().contains("Found 5 classes"));
        check("importing adds subjects and classes", d.post("/import/apply", "replace", "1").statusCode() == 303
                && d.get("/timetable").body().contains("Design and Analysis of Algorithms")
                && d.get("/subjects").body().contains("MAT201"));

        check("text is escaped", a.post("/tasks", "title", "<script>x</script>", "dueDate", due, "minutes", "30",
                "priority", "LOW").statusCode() == 303 && a.get("/tasks").body().contains("&lt;script&gt;x"));
        check("signing out ends the session", a.post("/signout").statusCode() == 303 && a.get("/").statusCode() == 303);
        check("signing in works", a.get("/signin").statusCode() == 200
                && a.post("/signin", "email", "asha@example.com", "password", "correct horse", "next", "/tasks")
                .headers().firstValue("location").orElse("").equals("/tasks"));
        check("a wrong password is refused", new Browser(site).signinWith("asha@example.com", "wrong password") == 400);
    }

    private static String find(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        if (!m.find()) {
            throw new AssertionError("Not found in page: " + regex);
        }
        return m.group(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    /** A cookie-keeping client that copies the page's form token into every POST. */
    private static final class Browser {
        final String site;
        // Java's CookieManager mangles cookies for "localhost", so keep a plain jar like a browser does.
        final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        final java.util.Map<String, String> jar = new java.util.LinkedHashMap<>();
        HttpResponse<String> last;
        String csrf = "";

        Browser(String site) {
            this.site = site;
        }

        HttpResponse<String> get(String path) throws Exception {
            last = send(HttpRequest.newBuilder(URI.create(site + path)));
            Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(last.body());
            if (m.find()) {
                csrf = m.group(1);
            }
            return last;
        }

        HttpResponse<String> post(String path, String... pairs) throws Exception {
            StringBuilder body = new StringBuilder("_csrf=").append(enc(csrf));
            for (int i = 0; i < pairs.length; i += 2) {
                body.append('&').append(enc(pairs[i])).append('=').append(enc(pairs[i + 1]));
            }
            return postRaw(path, body.toString());
        }

        HttpResponse<String> postRaw(String path, String body) throws Exception {
            last = send(HttpRequest.newBuilder(URI.create(site + path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)));
            return last;
        }

        private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
            if (!jar.isEmpty()) {
                StringBuilder header = new StringBuilder();
                jar.forEach((k, v) -> header.append(header.length() == 0 ? "" : "; ").append(k).append('=').append(v));
                request.header("Cookie", header.toString());
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String set : response.headers().allValues("set-cookie")) {
                String pair = set.split(";", 2)[0];
                int eq = pair.indexOf('=');
                String name = pair.substring(0, eq);
                String value = pair.substring(eq + 1);
                if (value.isEmpty() || set.contains("Max-Age=0")) {
                    jar.remove(name);
                } else {
                    jar.put(name, value);
                }
            }
            return response;
        }

        int signinWith(String email, String password) throws Exception {
            get("/signin");
            return post("/signin", "email", email, "password", password).statusCode();
        }

        private static String enc(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }
    }
}
