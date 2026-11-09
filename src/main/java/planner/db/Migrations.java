package planner.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.List;

/**
 * Applies the numbered SQL scripts in {@code src/main/resources/db} in order, once each,
 * recording what has run in {@code schema_version}. Each script runs in its own
 * transaction, so a failure leaves the database at the last good version.
 *
 * <p>To change the schema, add the next script to {@link #SCRIPTS}. Never edit one that has
 * already shipped.
 */
public final class Migrations {

    private static final List<String> SCRIPTS = List.of(
            "V1__schema.sql",
            "V2__ai_and_classroom.sql");

    private Migrations() { }

    public static int apply(Db db) {
        db.txv(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("""
                        create table if not exists schema_version (
                            version    int primary key,
                            script     text not null,
                            applied_at timestamptz not null default now()
                        )""");
            }
        });

        int applied = 0;
        for (int i = 0; i < SCRIPTS.size(); i++) {
            int version = i + 1;
            String script = SCRIPTS.get(i);

            boolean done = db.read(c -> Sql.count(c,
                    "select count(*) from schema_version where version = ?", version) > 0);
            if (done) {
                continue;
            }

            String sql = load(script);
            db.txv(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute(sql);
                }
                Sql.update(c, "insert into schema_version (version, script) values (?, ?)",
                        version, script);
            });
            applied++;
        }
        return applied;
    }

    private static String load(String script) {
        try (InputStream in = Migrations.class.getResourceAsStream("/db/" + script)) {
            if (in == null) {
                throw new IllegalStateException("Migration " + script + " is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read migration " + script, e);
        }
    }
}
