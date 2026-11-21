package io.github.betapetey.eventtrail.storage;

/** Finite driver timeout relationships shared by EventTrail's external JDBC backends. */
final class JdbcTimeouts {
    private static final int QUERY_COMPLETION_MARGIN_SECONDS = 5;

    private JdbcTimeouts() {}

    static int connectTimeoutSeconds(int connectionTimeoutMillis) {
        return Math.max(1, ceilMillisToSeconds(connectionTimeoutMillis));
    }

    static int socketTimeoutMillis(int connectionTimeoutMillis, int queryTimeoutSeconds) {
        long queryWindowMillis = Math.addExact(
                Math.multiplyExact((long) queryTimeoutSeconds, 1_000L),
                QUERY_COMPLETION_MARGIN_SECONDS * 1_000L);
        long timeout = Math.max(connectionTimeoutMillis, queryWindowMillis);
        if (timeout > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("EventTrail JDBC socket timeout exceeds the driver limit");
        }
        return (int) timeout;
    }

    static int socketTimeoutSeconds(int connectionTimeoutMillis, int queryTimeoutSeconds) {
        return ceilMillisToSeconds(socketTimeoutMillis(connectionTimeoutMillis, queryTimeoutSeconds));
    }

    private static int ceilMillisToSeconds(int milliseconds) {
        return (int) Math.max(1L, (milliseconds + 999L) / 1_000L);
    }
}
