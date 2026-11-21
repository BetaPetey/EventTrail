package io.github.betapetey.eventtrail.core;

/** Explicit server-lifecycle state for EventTrail-owned services. */
public enum EventTrailLifecycleState {
    STOPPED,
    STARTING,
    READY,
    STOPPING,
    FAILED
}
