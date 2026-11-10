package planner;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Settings, read from {@code planner.properties} in the working directory and then
 * overridden by environment variables. Nothing secret is hard-coded: the defaults match a
 * fresh local PostgreSQL install and are meant only for your own machine.
 */
public record Config(String dbUrl, String dbUser, String dbPassword, int port) {

    /** Settings that survive updates: ~/.margin/planner.properties (C:\\Users\\you\\.margin on Windows). */
    public static final Path USER_FILE = Path.of(System.getProperty("user.home"), ".margin", "planner.properties");

    /** The user-folder file first, then planner.properties next to Build.java, which wins if both set a value. */
    static Properties files() {
        Properties merged = new Properties();
        for (Path path : new Path[] {USER_FILE, Path.of("planner.properties")}) {
            if (Files.isRegularFile(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    Properties p = new Properties();
                    p.load(reader);
                    p.forEach((k, v) -> {
                        if (!String.valueOf(v).isBlank()) {
                            merged.put(String.valueOf(k).replace("\uFEFF", "").trim(), String.valueOf(v).trim());
                        }
                    });
                } catch (IOException e) {
                    throw new IllegalStateException("Could not read " + path.toAbsolutePath(), e);
                }
            }
        }
        return merged;
    }

    public static Config load() {
        Properties file = files();

        String url = pick("DB_URL", file.getProperty("db.url"),
                "jdbc:postgresql://localhost:5432/planner");
        String user = pick("DB_USER", file.getProperty("db.user"), "postgres");
        String password = pick("DB_PASSWORD", file.getProperty("db.password"), "postgres");
        String hosted = System.getenv("DATABASE_URL");
        if (hosted != null && !hosted.isBlank() && System.getenv("DB_URL") == null) {
            String[] parsed = fromDatabaseUrl(hosted.trim());
            url = parsed[0];
            user = parsed[1];
            password = parsed[2];
        }
        String port = pick("PORT", file.getProperty("port"), "8080");

        try {
            return new Config(url, user, password, Integer.parseInt(port.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("port must be a number, got '" + port + "'");
        }
    }

    /**
     * Turns a hosting provider's postgresql://user:pass@host:port/db?sslmode=require into the
     * JDBC URL, user and password the driver needs.
     */
    public static String[] fromDatabaseUrl(String value) {
        java.net.URI u = java.net.URI.create(value.replaceFirst("^postgres(ql)?://", "http://"));
        String info = u.getRawUserInfo() == null ? "" : u.getRawUserInfo();
        int colon = info.indexOf(':');
        String user = java.net.URLDecoder.decode(colon < 0 ? info : info.substring(0, colon), StandardCharsets.UTF_8);
        String pass = colon < 0 ? "" : java.net.URLDecoder.decode(info.substring(colon + 1), StandardCharsets.UTF_8);
        String ssl = u.getQuery() != null && u.getQuery().contains("sslmode=disable") ? "" : "?sslmode=require";
        String jdbc = "jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getPath() + ssl;
        return new String[] {jdbc, user, pass};
    }

    /** An optional setting, from the environment or planner.properties; null when not set. */
    public static String setting(String env, String key) {
        Properties file;
        try {
            file = files();
        } catch (IllegalStateException e) {
            return null;
        }
        String v = pick(env, file.getProperty(key), "");
        return v.isBlank() ? null : v.trim();
    }

    private static String pick(String env, String fromFile, String fallback) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fromFile != null && !fromFile.isBlank() ? fromFile : fallback;
    }
}
