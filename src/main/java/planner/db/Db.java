package planner.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small JDBC connection pool with a transaction-shaped API.
 *
 * <p>Callers never hold a connection: they pass a function to {@link #tx} or
 * {@link #read}, and the pool borrows a connection, runs it, commits or rolls back, and
 * returns the connection. That makes it impossible to forget to close one, and makes every
 * write atomic by default.
 *
 * <p>The pool itself is a {@link BlockingQueue}: idle connections wait in it, and a request
 * that finds it empty either opens a new connection (up to the limit) or waits.
 */
public final class Db implements AutoCloseable {

    @FunctionalInterface
    public interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface VoidWork {
        void apply(Connection connection) throws SQLException;
    }

    private final String url;
    private final String user;
    private final String password;
    private final int maxSize;
    private final BlockingQueue<Connection> idle;
    private final AtomicInteger open = new AtomicInteger();
    private final List<Connection> all = new ArrayList<>();

    public Db(String url, String user, String password, int maxSize) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.maxSize = maxSize;
        this.idle = new ArrayBlockingQueue<>(maxSize);
    }

    /** Runs {@code work} in a transaction: committed if it returns, rolled back if it throws. */
    public <T> T tx(Work<T> work) {
        Connection connection = borrow();
        try {
            connection.setAutoCommit(false);
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw new DataException(e);
        } catch (RuntimeException e) {
            rollbackQuietly(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    public void txv(VoidWork work) {
        tx(connection -> {
            work.apply(connection);
            return null;
        });
    }

    /** Runs read-only work with auto-commit on. */
    public <T> T read(Work<T> work) {
        Connection connection = borrow();
        try {
            connection.setAutoCommit(true);
            return work.apply(connection);
        } catch (SQLException e) {
            throw new DataException(e);
        } finally {
            release(connection);
        }
    }

    private Connection borrow() {
        Connection connection = idle.poll();
        while (true) {
            try {
                if (connection == null) {
                    if (open.incrementAndGet() <= maxSize) {
                        try {
                            connection = DriverManager.getConnection(url, user, password);
                            synchronized (all) {
                                all.add(connection);
                            }
                        } catch (SQLException e) {
                            open.decrementAndGet();
                            throw new DataException(e);
                        }
                    } else {
                        open.decrementAndGet();
                        connection = idle.poll(10, TimeUnit.SECONDS);
                        if (connection == null) {
                            throw new DataException(new SQLException(
                                    "Timed out waiting for a database connection"));
                        }
                    }
                }
                if (connection.isValid(2)) {
                    return connection;
                }
                discard(connection);
                connection = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataException(new SQLException("Interrupted waiting for a connection"));
            } catch (SQLException e) {
                discard(connection);
                connection = null;
            }
        }
    }

    private void release(Connection connection) {
        try {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            if (!idle.offer(connection)) {
                discard(connection);
            }
        } catch (SQLException e) {
            discard(connection);
        }
    }

    private void discard(Connection connection) {
        if (connection == null) {
            return;
        }
        open.decrementAndGet();
        synchronized (all) {
            all.remove(connection);
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Already broken; nothing more to do.
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    @Override
    public void close() {
        synchronized (all) {
            for (Connection connection : all) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // Shutting down regardless.
                }
            }
            all.clear();
        }
        idle.clear();
    }
}
