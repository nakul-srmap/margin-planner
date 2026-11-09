package planner;

import planner.auth.Accounts;
import planner.db.Db;
import planner.db.Migrations;
import planner.domain.Planner;
import planner.domain.Store;
import planner.http.Server;
import planner.web.Web;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneId;
import java.util.TimeZone;
import java.util.List;

/** Starts Margin: connects to PostgreSQL, brings the schema up to date, and serves the site. */
public final class App {

    private App() { }

    public static void main(String[] args) throws Exception {
        Config config = Config.load();

        // Plan in the student's own time zone, but talk to PostgreSQL in UTC. The JDBC driver
        // sends the JVM's zone name to the server, and some servers reject older names such as
        // Asia/Calcutta. The schema stores plain local dates and times, so nothing else changes.
        Clock clock = Clock.system(ZoneId.systemDefault());
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        try {
            ensureDatabase(config);
        } catch (SQLException e) {
            System.err.println();
            System.err.println("Could not connect to PostgreSQL at " + config.dbUrl() + " as \"" + config.dbUser() + "\".");
            System.err.println("  " + e.getMessage());
            System.err.println();
            System.err.println("Check that PostgreSQL is running and that the user name and password are right.");
            System.err.println("Set them in " + Config.USER_FILE + " (kept across updates),");
            System.err.println("or in planner.properties next to Build.java, or with DB_URL, DB_USER and DB_PASSWORD.");
            System.exit(1);
        }

        Db db = new Db(config.dbUrl(), config.dbUser(), config.dbPassword(), 10);
        int applied = Migrations.apply(db);
        if (applied > 0) {
            System.out.println("Database schema updated (" + applied + " step" + (applied == 1 ? "" : "s") + ").");
        }

        Store store = new Store(db);
        Accounts accounts = new Accounts(db);
        Planner planner = new Planner(store);
        Server server = new Server(Web.routes(store, planner, accounts, clock), accounts, Web::errorPage)
                .assets(List.of("app.css", "app.js", "favicon.svg", "recursive.woff2", "recursive-OFL.txt", "theme.js"));
        server.start(config.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            db.close();
        }));
        System.out.println();
        System.out.println("Margin is running at http://localhost:" + config.port());
        System.out.println("Press Ctrl+C to stop.");
    }

    /** Creates the database on first run if the server is reachable but the database doesn't exist yet. */
    static void ensureDatabase(Config config) throws SQLException {
        try (Connection c = DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword())) {
            c.isValid(2);
            return;
        } catch (SQLException e) {
            if (!"3D000".equals(e.getSQLState())) {
                throw e;
            }
        }
        String url = config.dbUrl();
        int slash = url.lastIndexOf('/');
        int q = url.indexOf('?', slash);
        String name = q < 0 ? url.substring(slash + 1) : url.substring(slash + 1, q);
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new SQLException("Database \"" + name + "\" doesn't exist. Create it, then start again.");
        }
        String admin = url.substring(0, slash + 1) + "postgres" + (q < 0 ? "" : url.substring(q));
        try (Connection c = DriverManager.getConnection(admin, config.dbUser(), config.dbPassword());
             Statement st = c.createStatement()) {
            st.execute("create database " + name);
            System.out.println("Created database \"" + name + "\".");
        }
    }

}
