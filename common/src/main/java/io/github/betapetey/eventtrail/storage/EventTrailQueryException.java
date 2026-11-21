package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Explicit failure from an EventTrail investigation read. This prevents database
 * errors from being indistinguishable from successful zero-row searches.
 */
public final class EventTrailQueryException extends RuntimeException {
    public enum Category {
        STORAGE_UNAVAILABLE,
        TIMEOUT,
        DATA_READ_FAILURE,
        QUERY_FAILURE
    }

    private final Category category;

    private EventTrailQueryException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }

    public String getSafeUserMessage() {
        return switch (category) {
            case STORAGE_UNAVAILABLE ->
                    "EventTrail could not complete the lookup because the database is unavailable. " +
                            "Try again shortly or check /eventtrail status.";
            case TIMEOUT -> "EventTrail lookup timed out. Try a narrower search.";
            case DATA_READ_FAILURE ->
                    "EventTrail could not read the requested log data. Check the server logs.";
            case QUERY_FAILURE -> "EventTrail could not complete the lookup. Check the server logs.";
        };
    }

    static EventTrailQueryException storageUnavailable(Throwable cause) {
        return new EventTrailQueryException(
                Category.STORAGE_UNAVAILABLE, "EventTrail storage is unavailable for queries", cause);
    }

    static EventTrailQueryException dataReadFailure(Throwable cause) {
        return new EventTrailQueryException(
                Category.DATA_READ_FAILURE, "EventTrail could not decode a stored log row", cause);
    }

    static EventTrailQueryException classify(Throwable error, boolean storageReady) {
        EventTrailQueryException existing = find(error);
        if (existing != null) return existing;

        Throwable cause = unwrapAsync(error);
        if (EventTrailDiagnostics.isTimeout(cause)) {
            return new EventTrailQueryException(Category.TIMEOUT, "EventTrail database query timed out", cause);
        }
        if (EventTrailDiagnostics.isConnectionOutage(cause) ||
                cause instanceof RejectedExecutionException || !storageReady) {
            return storageUnavailable(cause);
        }
        return new EventTrailQueryException(Category.QUERY_FAILURE, "EventTrail database query failed", cause);
    }

    public static EventTrailQueryException find(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 30) {
            if (current instanceof EventTrailQueryException queryException) return queryException;
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return null;
    }

    public static String safeUserMessage(Throwable error) {
        EventTrailQueryException queryException = find(error);
        return queryException == null
                ? "EventTrail could not complete the lookup. Check the server logs."
                : queryException.getSafeUserMessage();
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
