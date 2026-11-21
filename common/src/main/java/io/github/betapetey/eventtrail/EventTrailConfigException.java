package io.github.betapetey.eventtrail;

/** Raised when EventTrail cannot safely load or preserve its server configuration. */
public final class EventTrailConfigException extends RuntimeException {
    public EventTrailConfigException(String message) {
        super(message);
    }

    public EventTrailConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
