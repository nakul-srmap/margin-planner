package planner.ai;

import planner.Config;
import planner.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Calls Google's Gemini API. The key comes from planner.properties (gemini.api.key) or GEMINI_API_KEY. */
public final class Gemini {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15)).build();

    private Gemini() { }

    public static boolean configured() {
        return Config.setting("GEMINI_API_KEY", "gemini.api.key") != null;
    }

    /** Sends a prompt and returns the model's reply, which is asked to be JSON. */
    public static String json(String prompt) throws IOException, InterruptedException {
        String key = Config.setting("GEMINI_API_KEY", "gemini.api.key");
        if (key == null) {
            throw new AiException("Add your Gemini API key to planner.properties as gemini.api.key, then restart Margin.");
        }
        String chosen = Config.setting("GEMINI_MODEL", "gemini.model");
        String model = chosen != null ? chosen : found != null ? found : "gemini-flash-latest";
        String body = "{\"contents\":[{\"parts\":[{\"text\":" + Json.quote(prompt) + "}]}],"
                + "\"generationConfig\":{\"responseMimeType\":\"application/json\",\"temperature\":0.2}}";
        HttpResponse<String> res = call(key, model, body);
        if (chosen == null && (res.statusCode() == 404 || res.statusCode() == 401 || res.statusCode() == 400)) {
            // Models are retired often; ask Google which ones this key can use and retry once.
            String latest = newestFlash(key);
            if (latest != null && !latest.equals(model)) {
                found = latest;
                model = latest;
                res = call(key, model, body);
            }
        }
        // Busy or briefly failing: wait and retry, then try other current models.
        for (int attempt = 0; busy(res) && attempt < 2; attempt++) {
            Thread.sleep(1500L * (attempt + 1));
            res = call(key, model, body);
        }
        if (busy(res)) {
            for (String other : alternatives(key, model)) {
                HttpResponse<String> r = call(key, other, body);
                if (r.statusCode() == 200) {
                    res = r;
                    model = other;
                    break;
                }
            }
        }
        if (res.statusCode() != 200) {
            String msg = Json.str(Json.at(safe(res.body()), "error", "message"));
            String detail = msg == null ? "" : " Google said: " + msg;
            throw new AiException(switch (res.statusCode()) {
                case 400, 401, 403 -> "Gemini refused the request (" + res.statusCode() + ", model " + model + ")." + detail;
                case 404 -> "The Gemini model " + model + " isn't available. Set gemini.model in planner.properties." + detail;
                case 429 -> "Gemini's free limit was reached. Wait a minute and try again.";
                case 500, 502, 503, 504 -> "Gemini is very busy right now, on every model Margin tried. Try again in a minute.";
                default -> "Gemini didn't answer (" + res.statusCode() + ")." + detail;
            });
        }
        String text = Json.str(Json.at(safe(res.body()), "candidates", 0, "content", "parts", 0, "text"));
        if (text == null) {
            throw new AiException("Gemini sent an empty answer. Try rewording.");
        }
        return text.replaceAll("(?s)^\\s*```(?:json)?\\s*|\\s*```\\s*$", "");
    }

    private static volatile String found;

    /** Asks the model to think briefly: reading dates from a note doesn't need deep reasoning, and it's much faster. */
    private static HttpResponse<String> call(String key, String model, String body) throws IOException, InterruptedException {
        String thinking = model.startsWith("gemini-2") ? "\"thinkingConfig\":{\"thinkingBudget\":0}," : "\"thinkingConfig\":{\"thinkingLevel\":\"low\"},";
        HttpResponse<String> res = send(key, model, body.replace("\"generationConfig\":{", "\"generationConfig\":{" + thinking));
        if (res.statusCode() == 400 && res.body().toLowerCase().contains("think")) {
            res = send(key, model, body);   // this model doesn't take that setting; ask without it
        }
        return res;
    }

    private static HttpResponse<String> send(String key, String model, String body) throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        HttpResponse<String> r = sendOnce(key, model, body);
        System.out.printf("Gemini %s -> %d in %d ms%n", model, r.statusCode(), (System.nanoTime() - t0) / 1_000_000);
        return r;
    }

    private static HttpResponse<String> sendOnce(String key, String model, String body) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(
                        URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean busy(HttpResponse<String> res) {
        int c = res.statusCode();
        return c == 429 || c == 500 || c == 502 || c == 503 || c == 504;
    }

    /** Other flash models to fall back on when one is overloaded, newest first, at most three. */
    private static java.util.List<String> alternatives(String key, String skip) throws IOException, InterruptedException {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(
                        URI.create("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"))
                .timeout(Duration.ofSeconds(20)).header("x-goog-api-key", key).GET().build(), HttpResponse.BodyHandlers.ofString());
        java.util.List<String> out = res.statusCode() == 200 ? flashModels(res.body()) : new java.util.ArrayList<>();
        out.remove(skip);
        return out.subList(0, Math.min(3, out.size()));
    }

    /** Plain and lite flash models that can generate content, newest version first. */
    public static java.util.List<String> flashModels(String listJson) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (Object m : Json.arr(Json.at(safe(listJson), "models"))) {
            String name = Json.str(Json.at(m, "name"));
            if (name != null && Json.arr(Json.at(m, "supportedGenerationMethods")).contains("generateContent")) {
                String id = name.replaceFirst("^models/", "");
                if (id.matches("gemini-\\d+(\\.\\d+)?-flash(-lite)?")) {
                    ids.add(id);
                }
            }
        }
        ids.sort(java.util.Comparator.comparingDouble((String id) -> -Double.parseDouble(id.replaceAll("^gemini-([\\d.]+)-.*$", "$1")))
                .thenComparing(id -> id.endsWith("-lite")));
        return ids;
    }

    /** The newest general "flash" model this key may call, from Google's own model list. */
    static String newestFlash(String key) throws IOException, InterruptedException {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(
                        URI.create("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"))
                .timeout(Duration.ofSeconds(20)).header("x-goog-api-key", key).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            return null;
        }
        return pickFlash(res.body());
    }

    public static String pickFlash(String listJson) {
        String best = null;
        double bestVersion = -1;
        for (Object m : Json.arr(Json.at(safe(listJson), "models"))) {
            String name = Json.str(Json.at(m, "name"));
            if (name == null || !Json.arr(Json.at(m, "supportedGenerationMethods")).contains("generateContent")) {
                continue;
            }
            String id = name.replaceFirst("^models/", "");
            if (!id.matches("gemini-\\d+(\\.\\d+)?-flash") ) {
                continue;   // skip lite, image, tts, live, preview and dated variants
            }
            double v = Double.parseDouble(id.replaceAll("^gemini-([\\d.]+)-flash$", "$1"));
            if (v > bestVersion) {
                bestVersion = v;
                best = id;
            }
        }
        return best;
    }

    private static Object safe(String body) {
        try {
            return Json.parse(body);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A problem the student can act on. */
    public static final class AiException extends RuntimeException {
        public AiException(String message) {
            super(message);
        }
    }
}
