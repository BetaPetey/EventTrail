package io.github.betapetey.eventtrail.inventory.access;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Loader-neutral status boundary installed by the active platform lifecycle adapter.
 */
public final class ContainerAccessRuntimeDiagnostics {
    private static volatile Supplier<ContainerAccessMetrics> metricsProvider =
            ContainerAccessMetrics::empty;

    private ContainerAccessRuntimeDiagnostics() {}

    public static void installProvider(Supplier<ContainerAccessMetrics> provider) {
        metricsProvider = Objects.requireNonNull(provider, "provider");
    }

    public static ContainerAccessMetrics metrics() {
        try {
            ContainerAccessMetrics metrics = metricsProvider.get();
            return metrics == null ? ContainerAccessMetrics.empty() : metrics;
        } catch (RuntimeException | LinkageError ignored) {
            return ContainerAccessMetrics.empty();
        }
    }
}
