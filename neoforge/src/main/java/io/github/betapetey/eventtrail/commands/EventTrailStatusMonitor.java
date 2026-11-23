package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.core.RecordingSnapshot;
import io.github.betapetey.eventtrail.storage.AbstractJdbcStorage;
import io.github.betapetey.eventtrail.storage.AdvancedLogStorage;
import io.github.betapetey.eventtrail.storage.ILogStorage;
import io.github.betapetey.eventtrail.storage.StorageTransportSecurity;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.UUID;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Lifecycle-owned background health probe and immutable status snapshot. */
public final class EventTrailStatusMonitor {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    public static final long REFRESH_INTERVAL_MILLIS = 30_000L;
    public static final long STALE_AFTER_MILLIS = 90_000L;
    static final int WORKER_QUEUE_CAPACITY = 1;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2L;
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final AtomicReference<HealthSnapshot> SNAPSHOT = new AtomicReference<>(
            HealthSnapshot.stopped("Storage", null, System.currentTimeMillis()));
    private static final AtomicReference<RecordingSnapshot> RECORDING = new AtomicReference<>(
            RecordingSnapshot.stopped(UUID.randomUUID()));
    private static final Object LIFECYCLE_LOCK = new Object();

    private static ThreadPoolExecutor executor;
    private static boolean running;
    private static long generation;
    private static final Set<ExtendedLookup.StorageFailureCategory> loggedFailures =
            EnumSet.noneOf(ExtendedLookup.StorageFailureCategory.class);

    private EventTrailStatusMonitor() {}

    public static void start(ILogStorage storage) {
        BackendDescriptor descriptor = descriptor(storage);
        if (storage == null || !storage.isReady()) {
            stopWorker();
            SNAPSHOT.set(HealthSnapshot.unhealthy(
                    descriptor.backend(), descriptor.serverId(), descriptor.transportSecurity(), false, 0L,
                    ExtendedLookup.StorageFailureCategory.STORAGE_NOT_READY,
                    System.currentTimeMillis()));
            return;
        }
        start(descriptor, () -> probeStorage(storage), REFRESH_INTERVAL_MILLIS);
    }

    public static void stop() {
        stopWorker();
        HealthSnapshot previous = SNAPSHOT.get();
        SNAPSHOT.set(HealthSnapshot.stopped(
                previous.backend(), previous.serverId(), previous.transportSecurity(),
                System.currentTimeMillis()));
    }

    public static HealthSnapshot snapshot() {
        return SNAPSHOT.get();
    }

    /** Independent of the health worker: neither null storage nor SELECT success erases a failure. */
    public static RecordingSnapshot recordingSnapshot() { return RECORDING.get(); }

    public static void publishRecording(RecordingSnapshot recording) {
        if (recording == null) throw new IllegalArgumentException("Recording snapshot is required");
        RECORDING.set(recording);
    }

    static void start(BackendDescriptor descriptor, HealthProbe probe, long refreshIntervalMillis) {
        if (descriptor == null || probe == null) throw new IllegalArgumentException("Status probe is required");
        stopWorker();

        ThreadPoolExecutor created;
        long currentGeneration;
        synchronized (LIFECYCLE_LOCK) {
            generation++;
            currentGeneration = generation;
            running = true;
            loggedFailures.clear();
            SNAPSHOT.set(HealthSnapshot.pending(
                    descriptor.backend(), descriptor.serverId(), descriptor.transportSecurity(),
                    System.currentTimeMillis()));
            created = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(WORKER_QUEUE_CAPACITY),
                    threadFactory(), new ThreadPoolExecutor.AbortPolicy());
            executor = created;
            created.execute(() -> refreshLoop(
                    currentGeneration, descriptor, probe, Math.max(1L, refreshIntervalMillis)));
        }
    }

    static boolean isRunning() {
        synchronized (LIFECYCLE_LOCK) {
            return running && executor != null && !executor.isShutdown();
        }
    }

    static int queuedTaskCount() {
        synchronized (LIFECYCLE_LOCK) {
            return executor == null ? 0 : executor.getQueue().size();
        }
    }

    private static void refreshLoop(
            long expectedGeneration,
            BackendDescriptor descriptor,
            HealthProbe probe,
            long refreshIntervalMillis) {
        while (isCurrent(expectedGeneration)) {
            ProbeResult result;
            long started = System.currentTimeMillis();
            try {
                result = probe.check();
                if (result == null) {
                    result = ProbeResult.failure(ExtendedLookup.StorageFailureCategory.INTERNAL_ERROR);
                }
            } catch (Throwable error) {
                ExtendedLookup.StorageFailureCategory category = ExtendedLookup.classifyStorageFailure(error);
                maybeLogFailure(expectedGeneration, descriptor.backend(), category, error);
                result = ProbeResult.failure(category);
            }

            long observedAt = System.currentTimeMillis();
            if (!publishIfCurrent(expectedGeneration, result.toSnapshot(
                    descriptor, Math.max(0L, observedAt - started), observedAt))) return;

            try {
                Thread.sleep(refreshIntervalMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ProbeResult probeStorage(ILogStorage storage) throws Exception {
        if (!storage.isReady()) {
            return ProbeResult.failure(ExtendedLookup.StorageFailureCategory.STORAGE_NOT_READY);
        }
        if (!(storage instanceof AbstractJdbcStorage jdbcStorage)) {
            return ProbeResult.failure(ExtendedLookup.StorageFailureCategory.HEALTH_CHECK_UNAVAILABLE);
        }
        jdbcStorage.verifyConnection(Math.max(1,
                Math.min(5, EventTrailConfig.DATABASE_QUERY_TIMEOUT_SECONDS.get())));
        return ProbeResult.success();
    }

    private static BackendDescriptor descriptor(ILogStorage storage) {
        String configuredBackend = EventTrailConfig.normalizeBackend(
                EventTrailConfig.DATABASE_BACKEND.get());
        String backend = storage instanceof AdvancedLogStorage advanced
                ? EventTrailDiagnostics.stableStorageBackendLabel(advanced.getBackendName())
                : EventTrailDiagnostics.stableStorageBackendLabel(configuredBackend);
        String serverId = storage instanceof AdvancedLogStorage advanced
                ? advanced.getServerId()
                : switch (configuredBackend) {
                    case "mysql", "postgresql" -> EventTrailConfig.DATABASE_SERVER_ID.get();
                    default -> null;
                };
        StorageTransportSecurity transportSecurity = storage instanceof AdvancedLogStorage advanced
                ? advanced.getTransportSecurity()
                : configuredTransportSecurity(configuredBackend);
        return new BackendDescriptor(backend, serverId, transportSecurity);
    }

    private static StorageTransportSecurity configuredTransportSecurity(String backend) {
        return switch (backend) {
            case "sqlite" -> StorageTransportSecurity.local();
            case "mysql" -> EventTrailConfig.MYSQL_SSL.get()
                    ? StorageTransportSecurity.verifiedTls(
                            EventTrailConfig.mysqlSslCaFileForConnection() != null)
                    : StorageTransportSecurity.disabled();
            case "postgresql" -> EventTrailConfig.POSTGRESQL_SSL.get()
                    ? StorageTransportSecurity.verifiedTls(
                            EventTrailConfig.postgresqlSslCaFileForConnection() != null)
                    : StorageTransportSecurity.disabled();
            default -> StorageTransportSecurity.unknown();
        };
    }

    private static boolean publishIfCurrent(long expectedGeneration, HealthSnapshot snapshot) {
        synchronized (LIFECYCLE_LOCK) {
            if (!running || generation != expectedGeneration) return false;
            SNAPSHOT.set(snapshot);
            return true;
        }
    }

    private static boolean isCurrent(long expectedGeneration) {
        synchronized (LIFECYCLE_LOCK) {
            return running && generation == expectedGeneration;
        }
    }

    private static void stopWorker() {
        ThreadPoolExecutor previous;
        synchronized (LIFECYCLE_LOCK) {
            generation++;
            running = false;
            previous = executor;
            executor = null;
        }
        if (previous == null) return;
        previous.shutdownNow();
        try {
            if (!previous.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("EventTrail status health worker did not stop within {} seconds; late observations will be discarded",
                        SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for the EventTrail status health worker to stop");
        }
    }

    private static void maybeLogFailure(
            long expectedGeneration, String backend, ExtendedLookup.StorageFailureCategory category, Throwable error) {
        synchronized (LIFECYCLE_LOCK) {
            if (!running || generation != expectedGeneration || !loggedFailures.add(category)) return;
        }
        LOGGER.warn("EventTrail {} background health check failed (category={}, {}).",
                EventTrailDiagnostics.stableStorageBackendLabel(backend), category,
                EventTrailDiagnostics.safeFailureSummary(error));
    }

    private static ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "EventTrail-Status-Health-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    enum HealthState { PENDING, HEALTHY, UNHEALTHY, STOPPED }

    public record HealthSnapshot(
            HealthState state,
            boolean coreReady,
            String backend,
            String serverId,
            long latencyMs,
            ExtendedLookup.StorageFailureCategory failure,
            long observedAt,
            long stateSince,
            StorageTransportSecurity transportSecurity) {
        static HealthSnapshot pending(String backend, String serverId, long now) {
            return pending(backend, serverId, StorageTransportSecurity.unknown(), now);
        }

        static HealthSnapshot pending(
                String backend, String serverId, StorageTransportSecurity transportSecurity, long now) {
            return new HealthSnapshot(HealthState.PENDING, true, backend, serverId,
                    0L, null, 0L, now, safeTransportSecurity(transportSecurity));
        }

        static HealthSnapshot healthy(String backend, String serverId, long latencyMs, long now) {
            return healthy(backend, serverId, StorageTransportSecurity.unknown(), latencyMs, now);
        }

        static HealthSnapshot healthy(
                String backend, String serverId, StorageTransportSecurity transportSecurity,
                long latencyMs, long now) {
            return new HealthSnapshot(HealthState.HEALTHY, true, backend, serverId,
                    latencyMs, null, now, now, safeTransportSecurity(transportSecurity));
        }

        static HealthSnapshot unhealthy(
                String backend, String serverId, boolean coreReady, long latencyMs,
                ExtendedLookup.StorageFailureCategory failure, long now) {
            return unhealthy(backend, serverId, StorageTransportSecurity.unknown(), coreReady,
                    latencyMs, failure, now);
        }

        static HealthSnapshot unhealthy(
                String backend, String serverId, StorageTransportSecurity transportSecurity,
                boolean coreReady, long latencyMs,
                ExtendedLookup.StorageFailureCategory failure, long now) {
            return new HealthSnapshot(HealthState.UNHEALTHY, coreReady, backend, serverId,
                    latencyMs, failure, now, now, safeTransportSecurity(transportSecurity));
        }

        static HealthSnapshot stopped(String backend, String serverId, long now) {
            return stopped(backend, serverId, StorageTransportSecurity.unknown(), now);
        }

        static HealthSnapshot stopped(
                String backend, String serverId, StorageTransportSecurity transportSecurity, long now) {
            return new HealthSnapshot(HealthState.STOPPED, false, backend, serverId,
                    0L, ExtendedLookup.StorageFailureCategory.STORAGE_NOT_READY, now, now,
                    safeTransportSecurity(transportSecurity));
        }

        private static StorageTransportSecurity safeTransportSecurity(
                StorageTransportSecurity transportSecurity) {
            return transportSecurity == null ? StorageTransportSecurity.unknown() : transportSecurity;
        }

        boolean isStale(long now) {
            return observedAt > 0L && now - observedAt > STALE_AFTER_MILLIS;
        }

        long ageMillis(long now) {
            long basis = observedAt > 0L ? observedAt : stateSince;
            return Math.max(0L, now - basis);
        }
    }

    record BackendDescriptor(
            String backend, String serverId, StorageTransportSecurity transportSecurity) {
        BackendDescriptor(String backend, String serverId) {
            this(backend, serverId, StorageTransportSecurity.unknown());
        }

        BackendDescriptor {
            if (transportSecurity == null) transportSecurity = StorageTransportSecurity.unknown();
        }
    }

    @FunctionalInterface
    interface HealthProbe {
        ProbeResult check() throws Exception;
    }

    record ProbeResult(boolean healthy, ExtendedLookup.StorageFailureCategory failure) {
        static ProbeResult success() { return new ProbeResult(true, null); }
        static ProbeResult failure(ExtendedLookup.StorageFailureCategory failure) {
            return new ProbeResult(false, failure);
        }

        HealthSnapshot toSnapshot(BackendDescriptor descriptor, long latencyMs, long observedAt) {
            return healthy
                    ? HealthSnapshot.healthy(
                            descriptor.backend(), descriptor.serverId(), descriptor.transportSecurity(),
                            latencyMs, observedAt)
                    : HealthSnapshot.unhealthy(
                            descriptor.backend(), descriptor.serverId(), descriptor.transportSecurity(),
                            true, latencyMs,
                            failure == null ? ExtendedLookup.StorageFailureCategory.INTERNAL_ERROR : failure,
                            observedAt);
        }
    }
}
