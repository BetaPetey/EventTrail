package io.github.betapetey.eventtrail.storage;

import java.util.Objects;

/** Immutable, path-free observations. Capability is not permission to run physical reclamation. */
public record StorageMaintenanceSnapshot(
        Backend backend, Scope scope, SQLiteMode sqliteMode, Capability capability,
        State reclamationState, long observedAtMillis, Observation observation,
        Allocation allocation, ReclamationResult lastReclamation, PolicyStatus policyStatus) {

    public StorageMaintenanceSnapshot(Backend backend, Scope scope, SQLiteMode sqliteMode, Capability capability,
                                      State state, long at, Observation observation, Allocation allocation,
                                      ReclamationResult result) {
        this(backend, scope, sqliteMode, capability, state, at, observation, allocation, result, null);
    }

    public enum Backend { SQLITE, MYSQL, POSTGRESQL, UNKNOWN }
    public enum Scope { LOCAL_DATABASE_FILE, WHOLE_EXTERNAL_TABLE, WHOLE_EXTERNAL_RELATION, UNKNOWN }
    public enum SQLiteMode { NONE, FULL, INCREMENTAL, UNKNOWN, NOT_APPLICABLE }
    public enum Capability {
        INCREMENTAL_CAPABLE, MIGRATION_REQUIRED, FULL_MODE_UNCHANGED,
        EXTERNAL_DBA_MANAGED, UNKNOWN, UNSUPPORTED_RUNTIME, UNRECOGNIZED_OBJECTS, UNQUALIFIED_LAYOUT
    }
    public enum State {
        DISABLED, UNSUPPORTED_RUNTIME, WAITING_FOR_IDLE, ELIGIBLE, RUNNING_INCREMENTAL,
        YIELDED, REUSE_RESERVE_RETAINED, CHECKPOINT_DEFERRED, MIGRATION_REQUIRED,
        EXTERNAL_DBA_MANAGED, DEFERRED_LOW_HEADROOM, DEFERRED_SLOW_STORAGE, WAITING_FOR_MATERIAL_GROWTH,
        UNQUALIFIED_LAYOUT, FULL_MODE_UNCHANGED, FAILED
    }
    public enum Observation { NOT_OBSERVED, AVAILABLE, UNAVAILABLE }
    public enum Quality { EXACT, ENGINE_ESTIMATED, UNAVAILABLE }

    /** Bounded cached policy facts. Reservations/forfeiture are NOT successful physical reclamation. */
    public record PolicyStatus(String jdbcVersion, String nativeVersion, String phase,
                               Long initialFreePages, Long reservePages, Long remainingPages, Long baselinePages,
                               Long reservedPages, Long forfeitedPages, int unitCap,
                               Long lastNativeNanos, Long lastSettledUnitNanos, Long lastSliceNanos,
                               Long lastCheckpointNanos, String reason) {}

    public StorageMaintenanceSnapshot {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(sqliteMode, "sqliteMode");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(reclamationState, "reclamationState");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(allocation, "allocation");
        if (observedAtMillis < 0L) throw new IllegalArgumentException("Negative observation timestamp");
    }

    /** Unknown is represented explicitly, never as a measured zero. */
    public record Metric(Long value, Quality quality) {
        public Metric {
            Objects.requireNonNull(quality, "quality");
            if ((value == null) != (quality == Quality.UNAVAILABLE) || value != null && value < 0L) {
                throw new IllegalArgumentException("Metric value and quality disagree");
            }
        }
        public static Metric exact(long value) { return new Metric(value, Quality.EXACT); }
        public static Metric estimated(long value) { return new Metric(value, Quality.ENGINE_ESTIMATED); }
        public static Metric unavailable() { return new Metric(null, Quality.UNAVAILABLE); }
        static Metric optionalExact(Long value) { return value == null ? unavailable() : exact(value); }
    }

    /** Page counts share the backend's short observation; apparent file lengths are not filesystem allocation. */
    public record Allocation(
            Metric pageSizeBytes, Metric pageCount, Metric freelistPages, Metric reusableWholePageBytes,
            Metric mainFileLengthBytes, Metric walLengthBytes, Metric shmLengthBytes,
            Metric filesystemAllocatedBytes) {
        public Allocation {
            Objects.requireNonNull(pageSizeBytes); Objects.requireNonNull(pageCount);
            Objects.requireNonNull(freelistPages); Objects.requireNonNull(reusableWholePageBytes);
            Objects.requireNonNull(mainFileLengthBytes); Objects.requireNonNull(walLengthBytes);
            Objects.requireNonNull(shmLengthBytes); Objects.requireNonNull(filesystemAllocatedBytes);
        }
        public static Allocation unavailable() {
            Metric missing = Metric.unavailable();
            return new Allocation(missing, missing, missing, missing, missing, missing, missing, missing);
        }
    }

    /** Separate operation facts; null lastReclamation means no operation was observed, not zero-byte success. */
    public record ReclamationResult(
            State outcome, long completedAtMillis, Metric pagesReleased, Metric logicalPageBytesReduced,
            Metric apparentMainFileBytesReduced, Metric filesystemAllocatedBytesReduced) {
        public ReclamationResult {
            Objects.requireNonNull(outcome); Objects.requireNonNull(pagesReleased);
            Objects.requireNonNull(logicalPageBytesReduced); Objects.requireNonNull(apparentMainFileBytesReduced);
            Objects.requireNonNull(filesystemAllocatedBytesReduced);
            if (completedAtMillis < 0L) throw new IllegalArgumentException("Negative reclamation timestamp");
        }
    }

    /** Mode/allocation observation alone never authorizes a reclamation operation. */
    public static StorageMaintenanceSnapshot sqlite(
            SQLiteMode mode, long observedAtMillis, Long pageSizeBytes, Long pageCount, Long freelistPages,
            Long mainFileLengthBytes, Long walLengthBytes, Long shmLengthBytes) {
        Objects.requireNonNull(mode, "mode");
        if (mode == SQLiteMode.NOT_APPLICABLE) throw new IllegalArgumentException("SQLite mode required");
        if (pageSizeBytes != null && pageSizeBytes <= 0L || pageCount != null && pageCount < 0L
                || freelistPages != null && freelistPages < 0L
                || pageCount != null && freelistPages != null && freelistPages > pageCount) {
            throw new IllegalArgumentException("Invalid SQLite page observation");
        }
        Long freeBytes = pageSizeBytes == null || freelistPages == null ? null
                : Math.multiplyExact(pageSizeBytes, freelistPages);
        Capability capability = switch (mode) {
            case NONE -> Capability.MIGRATION_REQUIRED;
            case FULL -> Capability.FULL_MODE_UNCHANGED;
            case INCREMENTAL -> Capability.INCREMENTAL_CAPABLE;
            case UNKNOWN -> Capability.UNKNOWN;
            case NOT_APPLICABLE -> throw new IllegalArgumentException("SQLite mode required");
        };
        return new StorageMaintenanceSnapshot(Backend.SQLITE, Scope.LOCAL_DATABASE_FILE, mode, capability,
                State.DISABLED, observedAtMillis, Observation.AVAILABLE,
                new Allocation(Metric.optionalExact(pageSizeBytes), Metric.optionalExact(pageCount),
                        Metric.optionalExact(freelistPages), Metric.optionalExact(freeBytes),
                        Metric.optionalExact(mainFileLengthBytes), Metric.optionalExact(walLengthBytes),
                        Metric.optionalExact(shmLengthBytes), Metric.unavailable()), null);
    }

    /** Policy knowledge is not an allocation observation and needs no external database queries or privileges. */
    public static StorageMaintenanceSnapshot external(Backend backend) {
        if (backend != Backend.MYSQL && backend != Backend.POSTGRESQL) {
            throw new IllegalArgumentException("External backend required");
        }
        return new StorageMaintenanceSnapshot(backend,
                backend == Backend.MYSQL ? Scope.WHOLE_EXTERNAL_TABLE : Scope.WHOLE_EXTERNAL_RELATION,
                SQLiteMode.NOT_APPLICABLE, Capability.EXTERNAL_DBA_MANAGED, State.DISABLED,
                0L, Observation.NOT_OBSERVED, Allocation.unavailable(), null);
    }

    public static StorageMaintenanceSnapshot unavailable(Backend backend, long observedAtMillis) {
        if (backend == Backend.MYSQL || backend == Backend.POSTGRESQL) return external(backend);
        return new StorageMaintenanceSnapshot(backend,
                backend == Backend.SQLITE ? Scope.LOCAL_DATABASE_FILE : Scope.UNKNOWN,
                SQLiteMode.UNKNOWN, Capability.UNKNOWN, State.DISABLED, observedAtMillis,
                observedAtMillis == 0L ? Observation.NOT_OBSERVED : Observation.UNAVAILABLE,
                Allocation.unavailable(), null);
    }
}
