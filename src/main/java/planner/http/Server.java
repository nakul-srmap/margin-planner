package planner.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import planner.auth.Accounts;
import planner.auth.Passwords;
import planner.auth.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps the JDK's built-in {@link HttpServer} and applies the rules every request goes
 * through, in order:
 *
 * <ol>
 *   <li>static files are served directly from memory;</li>
 *   <li>the session cookie is resolved to a signed-in user, if any;</li>
 *   <li>private routes redirect anonymous visitors to sign-in;</li>
 *   <li>every POST must carry the CSRF token, or it is refused;</li>
 *   <li>the handler runs, and any failure becomes a readable error page;</li>
 *   <li>security headers are added to every response.</li>
 * </ol>
 */
public final class Server {

    private static final Logger LOG = Logger.getLogger(Server.class.getName());

    public static final String SESSION_COOKIE = "sid";
    private static final String ANON_CSRF_COOKIE = "pre";

    private final Router router;
    private final Accounts accounts;
    private final BiFunction<Request, HttpException, String> errorPage;
    private final Map<String, Asset> assets = new HashMap<>();
    private HttpServer server;
    private ExecutorService pool;

    record Asset(byte[] body, String type) { }

    public Server(Router router, Accounts accounts, BiFunction<Request, HttpException, String> errorPage) {
        this.router = router;
        this.accounts = accounts;
        this.errorPage = errorPage;
    }

    /** Loads files from {@code /static} on the classpath into memory once, at startup. */
    public Server assets(List<String> names) {
        for (String name : names) {
            try (InputStream in = Server.class.getResourceAsStream("/static/" + name)) {
                if (in == null) {
                    throw new IllegalStateException("Missing static file: " + name);
                }
                assets.put("/" + name, new Asset(in.readAllBytes(), contentType(name)));
            } catch (IOException e) {
                throw new IllegalStateException("Could not load static file " + name, e);
            }
        }
        return this;
    }

    public void start(int port) throws IOException {
        // Listen on IPv4 0.0.0.0 by default: hosting proxies such as Render's connect over IPv4,
        // and inside some containers Java's default socket would only accept IPv6.
        String host = planner.Config.setting("HOST", "host");
        server = HttpServer.create(new InetSocketAddress(host == null ? "0.0.0.0" : host, port), 64);
        pool = Executors.newFixedThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors() * 2));
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /* ------------------------------------------------------------------------- */

    private void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        Response response;
        Request request = null;
        try {
            try {
                request = new Request(exchange);
                response = dispatch(request);
            } catch (HttpException e) {
                response = Response.html(e.status(), errorPage.apply(request, e));
            } catch (Throwable e) {
                LOG.log(Level.SEVERE, "Unhandled error on " + exchange.getRequestMethod() + " "
                        + exchange.getRequestURI(), e);
                response = Response.html(500, "<!doctype html><title>Error</title><p>Something went wrong on our side. The error has been logged.</p>");
                try {
                    response = Response.html(500, errorPage.apply(request,
                            new HttpException(500, "Something went wrong on our side. The error has been logged.")));
                } catch (Throwable ignored) {
                    // keep the plain page
                }
            }
            write(exchange, request, response);
            if (LOG_REQUESTS) {
                System.out.printf("%s %s -> %d in %d ms%n", exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        response.status(), (System.nanoTime() - started) / 1_000_000);
            }
        } catch (Throwable e) {
            LOG.log(Level.SEVERE, "Could not answer " + exchange.getRequestURI(), e);
            exchange.close();   // never leave a browser waiting
        }
    }

    /** Request lines in the log help when hosted; set LOG_REQUESTS=false to silence them. */
    private static final boolean LOG_REQUESTS = !"false".equalsIgnoreCase(String.valueOf(planner.Config.setting("LOG_REQUESTS", "log.requests")));

    private Response dispatch(Request request) throws Exception {
        Asset asset = assets.get(request.path());
        if (asset != null && "GET".equals(request.method())) {
            return Response.bytes(200, asset.body(), asset.type())
                    .header("Cache-Control", "public, max-age=300");
        }

        // Who is this?
        Optional<Session> session = accounts.find(request.cookie(SESSION_COOKIE));
        session.ifPresent(request::session);

        // CSRF token: the session's when signed in, otherwise a pre-session cookie, so
        // the sign-in and registration forms are protected too.
        if (session.isPresent()) {
            request.csrf(session.get().csrf(), false);
        } else {
            String pre = request.cookie(ANON_CSRF_COOKIE);
            boolean fresh = pre == null || pre.length() < 32;
            request.csrf(fresh ? Passwords.token() : pre, fresh);
        }

        String flash = request.cookie("flash");
        if (flash != null) {
            request.flash(URLDecoder.decode(flash, StandardCharsets.UTF_8));
        }

        Optional<Router.Match> match = router.match(request.method(), request.path());
        if (match.isEmpty()) {
            throw router.pathExists(request.path())
                    ? new HttpException(405, "That action isn't available here.")
                    : HttpException.notFound();
        }

        Router.Route route = match.get().route();
        request.pathParams(match.get().params());

        if (!route.open() && !request.signedIn()) {
            String next = "GET".equals(request.method()) ? request.path() : "/";
            return Response.redirect("/signin?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8));
        }

        if ("POST".equals(request.method())) {
            String sent = request.param("_csrf");
            if (sent == null || !MessageDigest.isEqual(
                    sent.getBytes(StandardCharsets.UTF_8),
                    request.csrf().getBytes(StandardCharsets.UTF_8))) {
                throw new HttpException(403,
                        "This form expired. Go back, reload the page and try again.");
            }
        }

        return route.handler().handle(request);
    }

    private void write(HttpExchange exchange, Request request, Response response) throws IOException {
        var headers = exchange.getResponseHeaders();
        response.headers().forEach(headers::set);

        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "same-origin");
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; "
                        + "form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        if (!headers.containsKey("Cache-Control")) {
            headers.set("Cache-Control", "no-store");
        }

        for (String cookie : response.cookies()) {
            headers.add("Set-Cookie", cookie);
        }
        if (request != null && request.csrfIssued()) {
            headers.add("Set-Cookie", ANON_CSRF_COOKIE + "=" + request.csrf()
                    + "; Path=/; HttpOnly; SameSite=Lax" + SECURE + "; Max-Age=86400");
        }
        String flashCookie = response.flashCookie();
        if (flashCookie != null) {
            headers.add("Set-Cookie", flashCookie);
        } else if (request != null && request.flash() != null) {
            headers.add("Set-Cookie", "flash=; Path=/; Max-Age=0");
        }

        byte[] body = response.body();
        boolean empty = body.length == 0 || "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(response.status(), empty ? -1 : body.length);
        if (!empty) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    /** "; Secure" when the site is served over https (PUBLIC_URL starts with https://). */
    public static final String SECURE = String.valueOf(planner.Config.setting("PUBLIC_URL", "public.url")).startsWith("https://")
            ? "; Secure" : "";

    public static String sessionCookie(String id) {
        return SESSION_COOKIE + "=" + id + "; Path=/; HttpOnly; SameSite=Lax" + Server.SECURE + "; Max-Age="
                + (Accounts.SESSION_DAYS * 24 * 3600);
    }

    public static String clearSessionCookie() {
        return SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0";
    }

    private static String contentType(String name) {
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
