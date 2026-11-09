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
        String port = pick("PORT", file.getProperty("port"), "8080");

        try {
            return new Config(url, user, password, Integer.parseInt(port.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("port must be a number, got '" + port + "'");
        }
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
