package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.util.EventTrailFailure;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

public interface ILogStorage {
    void init() throws Exception;

    void log(AuditRecord record);

    /** Attempts one final atomic admission into the storage writer. */
    boolean tryLog(AuditRecord record);

    /**
     * Admits every row of one action, or none, and returns true only if all were admitted; the database
     * storages commit them in one transaction. This default serves storages that cannot keep a group whole: one
     * row is delegated to {@link #tryLog}; a larger group, an empty or null list, or a null row is refused
     * (fails closed). Every production storage overrides it.
     */
    default boolean tryLogAll(List<AuditRecord> records) {
        if (records == null || records.size() != 1) return false;
        AuditRecord record = records.get(0);
        return record != null && tryLog(record);
    }

    /** The answer of {@link #tryLogAllWhenRoom}. */
    enum OfferResult {
        /** Every row was admitted (one queue element, written whole or not at all, as {@link #tryLogAll}). */
        ADMITTED,
        /** Not now: the writer has no room for the rows plus the headroom. Nothing changed; not a refusal. */
        NO_ROOM,
        /** Refused for good; nothing was admitted. This call counted nothing: the caller counts it, once. */
        REFUSED
    }

    /**
     * Explosion split (design-02 2.1, Advisor M-3/M-5 and answer 2): admits every row of one action like
     * {@link #tryLogAll}, but only while admitting it still leaves {@code headroomRows} row permits and
     * {@code headroomBytes} pending-payload bytes free, and without counting or warning anything: NO_ROOM when there
     * is no such room now (the caller keeps the rows and asks again later), REFUSED when it can never be admitted
     * (the caller counts it once in its own figure). {@code retainedBytes} is the caller's exact writer estimate of
     * the rows, so a NO_ROOM answer does no per-row work. This default is for test storages only: it maps
     * {@link #tryLogAll} true/false to ADMITTED/REFUSED and never answers NO_ROOM (fails closed). Such a storage's own
     * {@code tryLogAll} may count its refusal, which the publisher then counts again (review N-3). No production
     * storage uses it: all three extend AbstractJdbcStorage, whose override is final.
     */
    default OfferResult tryLogAllWhenRoom(
            List<AuditRecord> records, long retainedBytes, int headroomRows, long headroomBytes) {
        return tryLogAll(records) ? OfferResult.ADMITTED : OfferResult.REFUSED;
    }

    /**
     * Count-only (af-2 R-M2): the runtime refused {@code rows} rows of one action on this storage's behalf because
     * this storage reported not ready while the runtime was still READY. Never offers or stores anything. Returns
     * true when this storage counted them (in its refused-because-the-writer-stopped figure); the default counts
     * nothing and returns false, so the caller counts them itself.
     */
    default boolean recordRefusedWhileUnavailable(int rows) {
        return false;
    }

    /** Advisory only; final write admission remains atomic inside the storage implementation. */
    default boolean hasWriteCapacityHint() {
        return isReady();
    }

    /** Pure cached policy observation, including history readiness; no I/O or listener callbacks. */
    default WriteAdmissionSnapshot writeAdmissionSnapshot() {
        return WriteAdmissionSnapshot.unknown();
    }

    /** Register before init; delivery may be out of order and versions must be reconciled. */
    default WriteObservation observeWriteAdmission(Consumer<WriteAdmissionSnapshot> observer) {
        Objects.requireNonNull(observer, "observer").accept(writeAdmissionSnapshot());
        return () -> {};
    }

    @FunctionalInterface
    interface WriteObservation extends AutoCloseable {
        @Override void close();
    }

    default EventTrailFailure initializationFailure() { return null; }

    /** Pure cached terminal backend restriction; no writer/health polling or storage I/O. */
    default EventTrailFailure terminalBackendAccessFailure() { return null; }

    default List<EventTrailFailure> cleanupFailures() { return List.of(); }

    /** Pure cached reporting only; command rendering must never borrow a connection or inspect files. */
    default StorageMaintenanceSnapshot maintenanceSnapshot() {
        return StorageMaintenanceSnapshot.unavailable(StorageMaintenanceSnapshot.Backend.UNKNOWN, 0L);
    }

    /** Separate, optional physical-maintenance boundary on the same owned retention worker. */
    default void afterRetentionMaintenance(long cutoff, RetentionResult result,
                                           java.util.function.BooleanSupplier idleAdmission) {
        refreshMaintenanceSnapshot(idleAdmission);
    }

    /** Background-only read-only observation boundary, separate from retention success. */
    default void refreshMaintenanceSnapshot(BooleanSupplier idleAdmission) {
        Objects.requireNonNull(idleAdmission, "idleAdmission");
    }

    List<AuditRecord> queryPosition(BlockPos pos, String world, int limit);

    List<AuditRecord> queryRadius(BlockPos pos, String world, int radius, int limit);

    List<AuditRecord> queryPlayer(String playerUuid, int limit);

    List<AuditRecord> queryTimeRange(long startTime, long endTime, int limit);

    long deleteOlderThan(long cutoffTimestamp);

    /**
     * Runs one bounded retention pass with an explicit stopping condition.
     * Legacy count-only implementations cannot establish exhaustion from a count,
     * so the default performs no deletion and reports unsupported result handling.
     */
    default RetentionResult deleteOlderThanWithResult(long cutoffTimestamp) {
        throw new UnsupportedOperationException("Storage does not report retention outcomes");
    }

    /** Runs a pass whose next finite batch requires fresh, nonblocking caller admission. */
    default RetentionResult deleteOlderThanWithResult(long cutoffTimestamp, BooleanSupplier mayStartBatch) {
        Objects.requireNonNull(mayStartBatch, "mayStartBatch");
        throw new UnsupportedOperationException("Storage does not support cooperative retention admission");
    }

    void close();

    boolean isReady();
}
