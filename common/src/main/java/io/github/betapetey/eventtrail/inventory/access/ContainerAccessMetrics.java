package io.github.betapetey.eventtrail.inventory.access;

/** Loader-neutral bounded lifecycle counters exposed to administrative status. */
public record ContainerAccessMetrics(
        int activeSessions,
        long opened,
        long closed,
        long duplicateOpens,
        long unresolvedTargets,
        long unmatchedCloses,
        long abandoned) {

    public static ContainerAccessMetrics empty() {
        return new ContainerAccessMetrics(0, 0L, 0L, 0L, 0L, 0L, 0L);
    }
}
