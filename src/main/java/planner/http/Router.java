package planner.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a method and a path such as {@code /tasks/{id}/edit} to a handler. Routes are
 * private by default; a route that anyone may reach is registered with {@code publicGet}
 * or {@code publicPost}.
 */
public final class Router {

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    record Route(String method, Pattern pattern, List<String> names, Handler handler, boolean open) { }

    record Match(Route route, Map<String, String> params) { }

    private static final Pattern PARAM = Pattern.compile("\\{([a-zA-Z]+)}");

    private final List<Route> routes = new ArrayList<>();

    public Router get(String path, Handler handler) { return add("GET", path, handler, false); }

    public Router post(String path, Handler handler) { return add("POST", path, handler, false); }

    public Router publicGet(String path, Handler handler) { return add("GET", path, handler, true); }

    public Router publicPost(String path, Handler handler) { return add("POST", path, handler, true); }

    private Router add(String method, String path, Handler handler, boolean open) {
        List<String> names = new ArrayList<>();
        Matcher m = PARAM.matcher(path);
        StringBuilder regex = new StringBuilder("^");
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(path.substring(last, m.start()))).append("([^/]+)");
            names.add(m.group(1));
            last = m.end();
        }
        regex.append(Pattern.quote(path.substring(last))).append("/?$");
        routes.add(new Route(method, Pattern.compile(regex.toString()), names, handler, open));
        return this;
    }

    Optional<Match> match(String method, String path) {
        for (Route route : routes) {
            if (!route.method().equals(method)) {
                continue;
            }
            Matcher m = route.pattern().matcher(path);
            if (m.matches()) {
                Map<String, String> params = new HashMap<>();
                for (int i = 0; i < route.names().size(); i++) {
                    params.put(route.names().get(i), m.group(i + 1));
                }
                return Optional.of(new Match(route, params));
            }
        }
        return Optional.empty();
    }

    /** True if the path exists under some other method — a 405 rather than a 404. */
    boolean pathExists(String path) {
        return routes.stream().anyMatch(r -> r.pattern().matcher(path).matches());
    }
}
