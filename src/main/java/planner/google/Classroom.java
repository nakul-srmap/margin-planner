package planner.google;

import planner.Config;
import planner.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a student's Google Classroom assignments with the official API, read-only. Needs an
 * OAuth client from Google Cloud: google.client.id and google.client.secret in planner.properties.
 */
public final class Classroom {

    public static final String SCOPES = "https://www.googleapis.com/auth/classroom.courses.readonly "
            + "https://www.googleapis.com/auth/classroom.coursework.me.readonly";
    private static final String API = "https://classroom.googleapis.com/v1/";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** An assignment with a due date, in the student's own time zone. */
    public record Work(String id, String course, String title, String description, LocalDateTime due,
                       String link, boolean turnedIn) { }

    public record Tokens(String refresh, String access, Instant expires) { }

    private Classroom() { }

    public static boolean configured() {
        return id() != null && secret() != null;
    }

    private static String id() {
        return Config.setting("GOOGLE_CLIENT_ID", "google.client.id");
    }

    private static String secret() {
        return Config.setting("GOOGLE_CLIENT_SECRET", "google.client.secret");
    }

    public static String authUrl(String redirect, String state) {
        return "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&access_type=offline&prompt=consent"
                + "&client_id=" + enc(id()) + "&redirect_uri=" + enc(redirect) + "&scope=" + enc(SCOPES) + "&state=" + enc(state);
    }

    public static Tokens exchange(String code, String redirect) throws IOException, InterruptedException {
        return token("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirect), null);
    }

    public static Tokens refresh(String refreshToken) throws IOException, InterruptedException {
        return token("grant_type=refresh_token&refresh_token=" + enc(refreshToken), refreshToken);
    }

    private static Tokens token(String form, String keepRefresh) throws IOException, InterruptedException {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form + "&client_id=" + enc(id()) + "&client_secret=" + enc(secret())))
                .build(), HttpResponse.BodyHandlers.ofString());
        Object json = Json.parse(res.body());
        if (res.statusCode() != 200) {
            String err = Json.str(Json.at(json, "error"));
            throw new ClassroomException("invalid_grant".equals(err)
                    ? "Google access was removed or expired. Connect Classroom again."
                    : "Google sign-in failed: " + (err == null ? res.statusCode() : err) + ".");
        }
        String refresh = Json.str(Json.at(json, "refresh_token"));
        Integer life = Json.num(Json.at(json, "expires_in"));
        return new Tokens(refresh != null ? refresh : keepRefresh, Json.str(Json.at(json, "access_token")),
                Instant.now().plusSeconds(life == null ? 3000 : life - 60));
    }

    /** Every assignment with a due date in the student's active classes. */
    public static List<Work> assignments(String access, ZoneId zone) throws IOException, InterruptedException {
        List<Work> out = new ArrayList<>();
        for (Object course : pages(access, API + "courses?studentId=me&courseStates=ACTIVE&pageSize=100", "courses")) {
            String cid = Json.str(Json.at(course, "id"));
            String name = Json.str(Json.at(course, "name"));
            String section = Json.str(Json.at(course, "section"));
            String label = section == null ? name : name + " " + section;
            Map<String, String> states = new HashMap<>();
            for (Object sub : pages(access, API + "courses/" + cid + "/courseWork/-/studentSubmissions?userId=me&pageSize=200", "studentSubmissions")) {
                states.put(Json.str(Json.at(sub, "courseWorkId")), Json.str(Json.at(sub, "state")));
            }
            for (Object w : pages(access, API + "courses/" + cid + "/courseWork?pageSize=100", "courseWork")) {
                Work work = work(w, label, states, zone);
                if (work != null) {
                    out.add(work);
                }
            }
        }
        return out;
    }

    /** One coursework item from the API, or null when it has no due date. */
    public static Work work(Object w, String course, Map<String, String> states, ZoneId zone) {
        Object date = Json.at(w, "dueDate");
        if (date == null) {
            return null;
        }
        Integer y = Json.num(Json.at(date, "year"));
        Integer mo = Json.num(Json.at(date, "month"));
        Integer d = Json.num(Json.at(date, "day"));
        if (y == null || mo == null || d == null) {
            return null;
        }
        Object time = Json.at(w, "dueTime");
        LocalDateTime due;
        if (time == null || Json.obj(time).isEmpty()) {
            due = LocalDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(23, 59));
        } else {
            Integer h = Json.num(Json.at(time, "hours"));
            Integer mi = Json.num(Json.at(time, "minutes"));
            // Classroom gives due times in UTC.
            due = LocalDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h == null ? 0 : h, mi == null ? 0 : mi))
                    .atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime();
        }
        String id = Json.str(Json.at(w, "id"));
        String state = states.get(id);
        String link = Json.str(Json.at(w, "alternateLink"));
        return new Work("classroom:" + id, course, Json.str(Json.at(w, "title")), Json.str(Json.at(w, "description")),
                due, link != null && link.startsWith("https://") ? link : null,
                "TURNED_IN".equals(state) || "RETURNED".equals(state));
    }

    private static List<Object> pages(String access, String url, String field) throws IOException, InterruptedException {
        List<Object> all = new ArrayList<>();
        String token = null;
        do {
            String u = token == null ? url : url + "&pageToken=" + enc(token);
            HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + access).GET().build(), HttpResponse.BodyHandlers.ofString());
            Object json = Json.parse(res.body());
            if (res.statusCode() == 403 || res.statusCode() == 401) {
                String msg = Json.str(Json.at(json, "error", "message"));
                throw new ClassroomException("Google Classroom refused access" + (msg == null ? "." : ": " + msg));
            }
            if (res.statusCode() != 200) {
                throw new ClassroomException("Google Classroom didn't answer (" + res.statusCode() + "). Try again shortly.");
            }
            all.addAll(Json.arr(Json.at(json, field)));
            token = Json.str(Json.at(json, "nextPageToken"));
        } while (token != null && all.size() < 2000);
        return all;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /** A problem the student can act on. */
    public static final class ClassroomException extends RuntimeException {
        public ClassroomException(String message) {
            super(message);
        }
    }
}
