package planner.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a handler returns. Built with the static factories, then optionally decorated. */
public final class Response {

    private final int status;
    private final byte[] body;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final List<String> cookies = new ArrayList<>();
    private String flash;

    private Response(int status, byte[] body, String contentType) {
        this.status = status;
        this.body = body;
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
    }

    public static Response html(String html) {
        return html(200, html);
    }

    public static Response html(int status, String html) {
        return new Response(status, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    public static Response bytes(int status, byte[] body, String contentType) {
        return new Response(status, body, contentType);
    }

    /**
     * 303 See Other: after a form POST the browser fetches the target with GET, so a
     * refresh never resubmits the form.
     */
    public static Response redirect(String location) {
        Response response = new Response(303, new byte[0], null);
        response.headers.put("Location", location);
        return response;
    }

    public Response header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public Response cookie(String setCookie) {
        cookies.add(setCookie);
        return this;
    }

    /** Attaches a one-line message the next page shows once. */
    public Response flash(String message) {
        this.flash = message;
        return this;
    }

    int status() { return status; }

    byte[] body() { return body; }

    Map<String, String> headers() { return headers; }

    List<String> cookies() { return cookies; }

    String flashCookie() {
        return flash == null ? null
                : "flash=" + URLEncoder.encode(flash, StandardCharsets.UTF_8)
                        + "; Path=/; HttpOnly; SameSite=Lax" + Server.SECURE + "; Max-Age=60";
    }
}
