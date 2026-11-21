package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.util.EventTrailFailure;

/** Fixed presentation text from enum-only facts; never accepts exception text or paths. */
public final class RecordingAnnouncements {
    private RecordingAnnouncements() {}

    public static String disabled(EventTrailFailure failure) {
        return "AUDIT LOGGING DISABLED - [" + failure.code() + "] (backend="
                + failure.backend().displayName() + ", phase=" + failure.phase()
                + ", role=" + failure.role() + "). " + failure.explanation() + " " + failure.action()
                + " Events occurring while disabled are not recorded by EventTrail and will not be backfilled."
                + " Check /eventtrail status to confirm recovery.";
    }

    public static String recovered() {
        return "EventTrail audit logging resumed. New events can now be recorded."
                + " Events missed while logging was disabled have not been backfilled."
                + " Confirm current recording and persistence state with /eventtrail status.";
    }

    public static String diagnostic(EventTrailFailure failure) {
        return "EventTrail recording diagnostic [" + failure.code() + "] (phase="
                + failure.phase() + ", role=" + failure.role() + "). "
                + failure.explanation() + " " + failure.action();
    }
}
