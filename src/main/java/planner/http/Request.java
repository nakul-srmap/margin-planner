package planner.http;

import com.sun.net.httpserver.HttpExchange;
import planner.auth.Session;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One incoming request, with its query string, form body, cookies and session decoded. */
public final class Request {

    private static final int MAX_FORM_BYTES = 3 * 1024 * 1024;   // room for a pasted portal page

    private final HttpExchange exchange;
    private final String method;
    private final String path;
    private final Map<String, List<String>> query;
    private final Map<String, String> cookies;
    private Map<String, List<String>> form;
    private Map<String, String> pathParams = Map.of();
    private Session session;
    private String csrfToken;
    private boolean csrfIssued;
    private String flash;

    Request(HttpExchange exchange) {
        this.exchange = exchange;
        this.method = exchange.getRequestMethod().toUpperCase();
        String rawPath = exchange.getRequestURI().getRawPath();
        this.path = rawPath == null || rawPath.isEmpty() ? "/" : decode(rawPath);
        this.query = parse(exchange.getRequestURI().getRawQuery());
        this.cookies = parseCookies(exchange.getRequestHeaders().getFirst("Cookie"));
    }

    public String method() { return method; }

    public String path() { return path; }

    public String header(String name) { return exchange.getRequestHeaders().getFirst(name); }

    public String cookie(String name) { return cookies.get(name); }

    public Session session() { return session; }

    void session(Session session) { this.session = session; }

    public boolean signedIn() { return session != null; }

    /** The current user's id. Only call on routes that require sign-in. */
    public long userId() {
        if (session == null) {
            throw new IllegalStateException("No signed-in user on " + path);
        }
        return session.userId();
    }

    void pathParams(Map<String, String> params) { this.pathParams = params; }

    /** A numeric path segment such as the {@code 12} in {@code /tasks/12}. */
    public long id(String name) {
        String raw = pathParams.get(name);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException | NullPointerException e) {
            throw new HttpException(404, "Not found");
        }
    }

    /** A query-string value, or null. */
    public String query(String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** A form field, or null. */
    public String param(String name) {
        List<String> values = form().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** A form field, trimmed, never null. */
    public String text(String name) {
        String value = param(name);
        return value == null ? "" : value.trim();
    }

    public List<String> params(String name) {
        return form().getOrDefault(name, List.of());
    }

    /** Every submitted field, first value each, for re-displaying a form after an error. */
    public Map<String, String> formValues() {
        Map<String, String> out = new LinkedHashMap<>();
        form().forEach((key, values) -> out.put(key, values.isEmpty() ? "" : values.get(0)));
        return out;
    }

    public Map<String, List<String>> form() {
        if (form == null) {
            form = readForm();
        }
        return form;
    }

    private Map<String, List<String>> readForm() {
        if (!"POST".equals(method)) {
            return Map.of();
        }
        String type = header("Content-Type");
        if (type == null || !type.startsWith("application/x-www-form-urlencoded")) {
            return Map.of();
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_FORM_BYTES + 1);
            if (body.length > MAX_FORM_BYTES) {
                throw new HttpException(413, "That form is too large.");
            }
            return parse(new String(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new HttpException(400, "Could not read the form.");
        }
    }

    /* ---------------- CSRF and flash, managed by the server ---------------- */

    public String csrf() { return csrfToken; }

    void csrf(String token, boolean issuedNow) {
        this.csrfToken = token;
        this.csrfIssued = issuedNow;
    }

    boolean csrfIssued() { return csrfIssued; }

    /** A one-off message carried over a redirect, or null. */
    public String flash() { return flash; }

    void flash(String message) { this.flash = message; }

    /* ---------------- parsing ---------------- */

    static Map<String, List<String>> parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new HashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> parseCookies(String header) {
        if (header == null || header.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return out;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new HttpException(400, "Malformed request.");
        }
    }

    public Optional<String> optional(String name) {
        String value = text(name);
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
