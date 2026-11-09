package planner.portal;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signs in to the SRM AP Student Corner on the student's behalf. The student types the captcha
 * themselves; Margin only carries the portal's session cookie between the captcha and the
 * login, then reads the timetable page. The password is sent once and never stored.
 */
public final class SrmPortal {

    public static final String BASE = "https://student.srmap.edu.in/srmapstudentcorner/";
    private static final Duration IDLE = Duration.ofMinutes(15);
    private static final Pattern LINK = Pattern.compile("(?i)(?:href|action)\\s*=\\s*[\"']([^\"'#]+)[\"']");
    private static final Pattern ONCLICK_PATH = Pattern.compile("(?i)['\"](/?srmapstudentcorner/[^'\"]+|[A-Za-z/]*[Tt]ime[Tt]able[^'\"]*)['\"]");
    private static final Pattern MESSAGE = Pattern.compile("(?is)id=[\"']divmsg[\"'][^>]*>(.*?)</div>");

    public record Captcha(byte[] image, String type) { }

    /** What a sign-in produced: the classes found, and every page read, for troubleshooting. */
    public record Result(List<TimetableParser.Found> classes, String pages) { }

    private record Conn(HttpClient http, Instant used) { }

    private final Map<String, Conn> conns = new ConcurrentHashMap<>();

    private HttpClient client(String key, boolean fresh) {
        conns.values().removeIf(c -> c.used().plus(IDLE).isBefore(Instant.now()));
        Conn c = fresh ? null : conns.get(key);
        if (c == null) {
            c = new Conn(HttpClient.newBuilder().cookieHandler(new CookieManager())
                    .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build(), Instant.now());
        }
        conns.put(key, new Conn(c.http(), Instant.now()));
        return c.http();
    }

    /** Opens a fresh portal session and returns its captcha image. */
    public Captcha captcha(String key) throws IOException, InterruptedException {
        HttpClient http = client(key, true);
        get(http, BASE + "HRDSystem");
        HttpResponse<byte[]> img = http.send(request(BASE + "captchas").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Captcha(img.body(), img.headers().firstValue("content-type").orElse("image/jpeg"));
    }

    /** Signs in with the captcha the student typed, then looks for the timetable. */
    public Result importTimetable(String key, String user, String password, String captcha)
            throws IOException, InterruptedException {
        Conn c = conns.get(key);
        if (c == null) {
            throw new PortalException("The captcha expired. Load a new one and try again.");
        }
        HttpClient http = c.http();
        String form = "txtUserName=" + enc(user) + "&txtAuthKey=" + enc(password) + "&ccode=" + enc(captcha);
        HttpResponse<String> res = http.send(request(BASE + "StudentLoginToPortal")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", BASE + "HRDSystem")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        String home = res.body();
        if (home.contains("id=\"frmSL\"") || home.contains("StudentLoginToPortal")) {
            Matcher m = MESSAGE.matcher(home);
            String why = m.find() ? m.group(1).replaceAll("<[^>]+>", "").trim() : "";
            conns.remove(key);
            throw new PortalException(why.isEmpty()
                    ? "The portal didn't accept that sign-in. Check your register number, password and the captcha."
                    : "The portal said: " + why);
        }

        StringBuilder pages = new StringBuilder("<!-- after sign-in: " + res.uri() + " -->\n").append(home);

        // The portal's menu loads each section with $.post("students/report/studentreportresources.jsp", {ids: n}):
        // 10 is Time Table, 2 is Student Wise Subjects (which has the full course names).
        String timetable = report(http, 10);
        String subjects = report(http, 2);
        pages.append("\n\n<!-- Time Table (ids=10) -->\n").append(timetable)
                .append("\n\n<!-- Student Wise Subjects (ids=2) -->\n").append(subjects);
        List<TimetableParser.Found> best = TimetableParser.parse(timetable + "\n" + subjects);
        if (best.isEmpty()) {
            best = TimetableParser.parse(home);
        }
        for (String url : candidates(home, res.uri())) {
            if (!best.isEmpty()) {
                break;
            }
            String page;
            try {
                page = get(http, url);
            } catch (IOException e) {
                continue;
            }
            pages.append("\n\n<!-- ").append(url).append(" -->\n").append(page);
            best = TimetableParser.parse(page);
            if (best.isEmpty()) {
                // The menu may lead to one more page before the timetable itself.
                for (String inner : candidates(page, URI.create(url))) {
                    String p2;
                    try {
                        p2 = get(http, inner);
                    } catch (IOException e) {
                        continue;
                    }
                    pages.append("\n\n<!-- ").append(inner).append(" -->\n").append(p2);
                    best = TimetableParser.parse(p2);
                    if (!best.isEmpty()) {
                        break;
                    }
                }
            }
        }
        conns.remove(key);
        return new Result(best, pages.toString());
    }

    /** One section of the student menu, as the portal's own page loads it. */
    private static String report(HttpClient http, int id) {
        try {
            return http.send(request(BASE + "students/report/studentreportresources.jsp")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", BASE + "StudentLoginToPortal")
                    .POST(HttpRequest.BodyPublishers.ofString("ids=" + id)).build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) {
            return "<!-- could not load section " + id + ": " + e.getMessage() + " -->";
        }
    }

    /** Links on a page that look like they lead to the timetable, most likely first. */
    static List<String> candidates(String html, URI base) {
        Set<String> out = new LinkedHashSet<>();
        List<Matcher> ms = List.of(LINK.matcher(html), ONCLICK_PATH.matcher(html));
        for (Matcher m : ms) {
            while (m.find()) {
                String href = m.group(1);
                String low = href.toLowerCase();
                if ((low.contains("timetable") || low.contains("time_table") || low.contains("classschedule"))
                        && !low.startsWith("javascript") && !low.endsWith(".js") && !low.endsWith(".css")) {
                    try {
                        out.add(base.resolve(href.replace(" ", "%20")).toString());
                    } catch (IllegalArgumentException ignored) {
                        // not a usable link
                    }
                }
            }
        }
        List<String> list = new ArrayList<>(out);
        list.removeIf(u -> !u.startsWith("https://student.srmap.edu.in/"));
        return list.subList(0, Math.min(6, list.size()));
    }

    private static String get(HttpClient http, String url) throws IOException, InterruptedException {
        return http.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                .header("User-Agent", "Mozilla/5.0 (Margin academic planner)");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public Optional<Boolean> has(String key) {
        return Optional.of(conns.containsKey(key));
    }

    /** A problem the student can act on, shown on the page as it is. */
    public static final class PortalException extends RuntimeException {
        public PortalException(String message) {
            super(message);
        }
    }
}
