package planner.auth;

import planner.db.DataException;
import planner.db.Db;
import planner.db.Sql;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Accounts and sessions. Sessions live in the database rather than in memory, so
 * restarting the server does not sign everyone out.
 */
public final class Accounts {

    public static final int SESSION_DAYS = 30;

    /** Raised when registration finds the address already in use. */
    public static final class EmailTaken extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Db db;

    public Accounts(Db db) {
        this.db = db;
    }

    /** Creates the account and its default preferences together, or neither. */
    public long register(String name, String email, String password) {
        String hash = Passwords.hash(password);
        try {
            return db.tx(c -> {
                long id = Sql.insert(c,
                        "insert into users (name, email, password_hash) values (?, ?, ?)",
                        name, email.toLowerCase(), hash);
                Sql.update(c, "insert into preferences (user_id) values (?)", id);
                return id;
            });
        } catch (DataException e) {
            if (e.isUniqueViolation()) {
                throw new EmailTaken();
            }
            throw e;
        }
    }

    /**
     * Returns the user id when the password matches. When the address is unknown a dummy
     * hash is still checked, so a failed sign-in takes the same time either way and does
     * not reveal which addresses have accounts.
     */
    public Optional<Long> authenticate(String email, String password) {
        record Row(long id, String hash) { }
        Optional<Row> row = db.read(c -> Sql.one(c,
                "select id, password_hash from users where lower(email) = lower(?)",
                rs -> new Row(rs.getLong("id"), rs.getString("password_hash")), email));

        if (row.isEmpty()) {
            Passwords.matches(password, DUMMY_HASH);
            return Optional.empty();
        }
        return Passwords.matches(password, row.get().hash()) ? Optional.of(row.get().id()) : Optional.empty();
    }

    private static final String DUMMY_HASH = Passwords.hash("not-a-real-password");

    public Session startSession(long userId) {
        String id = Passwords.token();
        String csrf = Passwords.token();
        db.txv(c -> {
            Sql.update(c, "delete from sessions where expires_at < now()");
            Sql.update(c, "insert into sessions (id, user_id, csrf, expires_at) values (?, ?, ?, ?)",
                    id, userId, csrf, OffsetDateTime.now().plusDays(SESSION_DAYS));
        });
        return find(id).orElseThrow();
    }

    public Optional<Session> find(String sessionId) {
        if (sessionId == null || sessionId.length() > 64) {
            return Optional.empty();
        }
        return db.read(c -> Sql.one(c, """
                select s.id, s.user_id, s.csrf, u.name, u.email
                from sessions s join users u on u.id = s.user_id
                where s.id = ? and s.expires_at > now()
                """,
                rs -> new Session(rs.getString("id"), rs.getLong("user_id"), rs.getString("csrf"),
                        rs.getString("name"), rs.getString("email")),
                sessionId));
    }

    public void endSession(String sessionId) {
        db.txv(c -> Sql.update(c, "delete from sessions where id = ?", sessionId));
    }
}
