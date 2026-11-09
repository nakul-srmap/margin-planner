package planner.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin helpers over JDBC. Every value goes through a bound parameter, never string
 * concatenation, so SQL injection is not possible through these methods.
 */
public final class Sql {

    @FunctionalInterface
    public interface Row<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private Sql() { }

    public static <T> List<T> list(Connection c, String sql, Row<T> row, Object... params)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(row.map(rs));
                }
                return out;
            }
        }
    }

    public static <T> Optional<T> one(Connection c, String sql, Row<T> row, Object... params)
            throws SQLException {
        List<T> rows = list(c, sql, row, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Runs an INSERT and returns the generated {@code id}. */
    public static long insert(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Insert returned no id");
                }
                return keys.getLong(1);
            }
        }
    }

    public static long count(Connection c, String sql, Object... params) throws SQLException {
        return one(c, sql, rs -> rs.getLong(1), params).orElse(0L);
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /* ---- null-safe readers for java.time ---- */

    public static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    public static LocalTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalTime.class);
    }

    public static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    public static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
