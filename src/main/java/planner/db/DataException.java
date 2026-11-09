package planner.db;

import java.sql.SQLException;

/** An unchecked wrapper for {@link SQLException}, carrying the SQL state for callers that care. */
public class DataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataException(SQLException cause) {
        super(cause.getMessage(), cause);
    }

    public String sqlState() {
        return getCause() instanceof SQLException sql ? sql.getSQLState() : null;
    }

    /** PostgreSQL's code for a unique-constraint violation. */
    public boolean isUniqueViolation() {
        return "23505".equals(sqlState());
    }

    /** PostgreSQL's code for a CHECK-constraint violation. */
    public boolean isCheckViolation() {
        return "23514".equals(sqlState());
    }
}
