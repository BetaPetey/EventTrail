package io.github.betapetey.eventtrail.inventory.moving;

/** Server-thread-only, constant-size CLOSE invalidation; not a pose or movement history. */
public final class MovingStorageEpoch {
    private static volatile long runtimeGeneration;
    private long epoch;
    private boolean armed;
    private boolean invalid;

    public record Stamp(long generation, long epoch) {}

    public Stamp snapshot() {
        armed = true;
        return new Stamp(runtimeGeneration, epoch);
    }

    public boolean unchanged(Stamp stamp) {
        return stamp != null && armed && !invalid && stamp.generation() == runtimeGeneration
                && stamp.epoch() == epoch;
    }

    /** Call only after an actual native pose/context change, even if it later returns to A. */
    public void changed() {
        if (!armed || invalid) return;
        if (epoch == Long.MAX_VALUE) invalid = true;
        else epoch++;
    }

    /** Removed/disassembled targets cannot regain an old access witness. */
    public void invalidate() { invalid = true; }

    public boolean valid() { return !invalid; }

    public static long runtimeGeneration() { return runtimeGeneration; }
    public static void resetRuntime() { runtimeGeneration = Math.incrementExact(runtimeGeneration); }
}
