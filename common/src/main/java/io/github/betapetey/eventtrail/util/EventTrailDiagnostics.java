package io.github.betapetey.eventtrail.util;

import java.net.ConnectException;
import java.net.SocketException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Safe, message-free classification and summaries for operational failures. */
public final class EventTrailDiagnostics {
    public enum DatabaseFailureKind {
        CONNECTION_FAILED,
        TIMEOUT,
        OTHER
    }

    /** Upper bound on the throwables {@link #failureChain(Throwable)} visits. */
    public static final int MAX_FAILURE_CHAIN_LINKS = 64;

    private EventTrailDiagnostics() {}

    public static DatabaseFailureKind classifyDatabaseFailure(Throwable error) {
        if (isTimeout(error)) return DatabaseFailureKind.TIMEOUT;
        if (isConnectionOutage(error) || containsTypedConnectionFailure(error) ||
                containsAuthorizationFailure(error)) {
            return DatabaseFailureKind.CONNECTION_FAILED;
        }
        return DatabaseFailureKind.OTHER;
    }

    public static boolean isTimeout(Throwable error) {
        for (Throwable current : failureChain(error)) {
            if (current instanceof SQLTimeoutException || current instanceof TimeoutException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && (state.startsWith("HYT") || state.equals("57014"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isConnectionOutage(Throwable error) {
        for (Throwable current : failureChain(error)) {
            if (current instanceof ConnectException || current instanceof SocketException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                // 08xxx: connection exception. 57P01/57P02/57P03: PostgreSQL admin shutdown, crash shutdown and
                // "cannot connect now", which end or refuse the session like a lost connection (writer-01 2.3).
                if (state != null && (state.startsWith("08") || state.equals("57P01") ||
                        state.equals("57P02") || state.equals("57P03"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Every distinct throwable reachable from {@code error} through {@link Throwable#getCause()} and
     * {@link SQLException#getNextException()}, breadth first, {@code error} first. Each throwable appears at most
     * once (cycle-safe) and the walk stops after {@value #MAX_FAILURE_CHAIN_LINKS} throwables (bounded).
     */
    public static List<Throwable> failureChain(Throwable error) {
        if (error == null) return List.of();
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(error);
        seen.add(error);
        while (!pending.isEmpty() && chain.size() < MAX_FAILURE_CHAIN_LINKS) {
            Throwable current = pending.poll();
            chain.add(current);
            Throwable cause = current.getCause();
            if (cause != null && seen.add(cause)) pending.add(cause);
            if (current instanceof SQLException sqlException) {
                SQLException next = sqlException.getNextException();
                if (next != null && seen.add(next)) pending.add(next);
            }
        }
        return chain;
    }

    public static boolean containsSqlException(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 30) {
            if (current instanceof SQLException) return true;
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }

    /**
     * Returns safe server-log metadata without copying any exception message,
     * endpoint, SQL text, username, password, or filesystem path.
     */
    public static String safeFailureSummary(Throwable error) {
        Throwable diagnostic = diagnosticCause(error);
        String type = diagnostic == null ? "UnknownFailure" : diagnostic.getClass().getSimpleName();
        if (type == null || type.isBlank()) type = "UnknownFailure";

        String state = sqlState(error);
        return state == null
                ? "exception=" + sanitizeLogValue(type, 96)
                : "exception=" + sanitizeLogValue(type, 96) + ", SQLState=" + state;
    }

    public static String sanitizeLogValue(String value, int maxLength) {
        if (value == null || value.isBlank()) return "unknown";
        int limit = Math.max(1, maxLength);
        String sanitized = value.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ").trim();
        return sanitized.length() <= limit ? sanitized : sanitized.substring(0, limit);
    }

    public static String stableStorageBackendLabel(String backend) {
        if (backend == null) return "Storage";
        return switch (backend.trim().toLowerCase(Locale.ROOT)) {
            case "sqlite" -> "SQLite";
            case "mysql" -> "MySQL";
            case "postgresql" -> "PostgreSQL";
            default -> "Storage";
        };
    }

    private static Throwable diagnosticCause(Throwable error) {
        Throwable current = unwrapAsync(error);
        Throwable first = current;
        int depth = 0;
        while (current != null && depth++ < 30) {
            if (current instanceof SQLException || current instanceof TimeoutException ||
                    current instanceof ConnectException || current instanceof SocketException) {
                return current;
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return first;
    }

    private static boolean containsAuthorizationFailure(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 30) {
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && state.startsWith("28")) return true;
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }

    private static boolean containsTypedConnectionFailure(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 30) {
            if (current instanceof SQLNonTransientConnectionException ||
                    current instanceof SQLTransientConnectionException ||
                    current instanceof SQLRecoverableException) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }

    private static String sqlState(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 30) {
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && state.matches("[A-Za-z0-9]{5}")) {
                    return state.toUpperCase(Locale.ROOT);
                }
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return null;
    }

    private static Throwable unwrapAsync(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while ((current instanceof ExecutionException || current instanceof CompletionException) &&
                current.getCause() != null && current.getCause() != current && depth++ < 30) {
            current = current.getCause();
        }
        return current;
    }
}
