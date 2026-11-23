package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.storage.ILogStorage;
import io.github.betapetey.eventtrail.storage.WriteAdmissionSnapshot;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.EventTrailFailure;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntSupplier;

/**
 * Owns one complete EventTrail server lifecycle.
 *
 * <p>A storage candidate remains local until storage and every dependent
 * service have started successfully. The immutable READY publication is the
 * sole write-admission source. Stop first removes that publication, then
 * releases status, maintenance, query, session/compatibility, and storage
 * ownership in reverse startup order.</p>
 */
public final class EventTrailRuntimeCoordinator {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");

    private final EventTrailStorageFactory storageFactory;
    private final EventTrailRuntimeServices services;
    private final IntSupplier retentionDays;
    private final AtomicReference<RuntimeView> published = new AtomicReference<>(
            new RuntimeView(EventTrailLifecycleState.STOPPED, null, "Storage",
                    RecordingSnapshot.stopped(UUID.randomUUID())));
    private final ReentrantReadWriteLock admission = new ReentrantReadWriteLock();
    private final Object observationLock = new Object();
    private final ArrayDeque<RecordingSnapshot> recordingUpdates = new ArrayDeque<>();
    private final AtomicBoolean deliveringRecording = new AtomicBoolean();

    private OwnedRuntime active;
    private boolean unavailableStatusVisible;
    private long generation;
    /** af-1 F6: rows refused because no READY runtime was published; incremented by any producer thread. */
    private final AtomicLong notReadyRefusedRows = new AtomicLong();
    /** af-1 F6: {@link #notReadyRefusedRows} at the last stop report; guarded by this coordinator's monitor. */
    private long notReadyRefusalsReported;
    /**
     * af-2 (Owner decision R-M1/R-m6 b): capture attempts skipped at a capture gate because EventTrail was not
     * ready; counted only by {@link #admitCapture}/{@link #admitCaptureWithWriteCapacity}, from any capture thread,
     * and (af-3) by {@link #countDroppedCaptures} for what the stop's cleanup dropped.
     */
    private final LongAdder skippedCaptureAttempts = new LongAdder();
    /** af-2: {@link #skippedCaptureAttempts} at the last stop report; guarded by this coordinator's monitor. */
    private long skippedCaptureAttemptsReported;
    /**
     * Explosion split (design-02 2.3, Advisor answer 2; condition 4): every part of an explosion stored in parts
     * that was not stored, counted once by the publisher, since this coordinator was created.
     */
    private final ExplosionPartLossCounter explosionPartLosses = new ExplosionPartLossCounter();
    /** {@link #explosionPartLosses} at the last stop report; guarded by this coordinator's monitor. */
    private ExplosionPartLossCounter.Snapshot explosionPartLossesReported = ExplosionPartLossCounter.Snapshot.ZERO;

    static EventTrailRuntimeCoordinator production(IntSupplier retentionDays) {
        return new EventTrailRuntimeCoordinator(
                new ConfiguredStorageFactory(), new ProductionRuntimeServices(), retentionDays);
    }

    EventTrailRuntimeCoordinator(
            EventTrailStorageFactory storageFactory,
            EventTrailRuntimeServices services,
            IntSupplier retentionDays) {
        this.storageFactory = Objects.requireNonNull(storageFactory, "storageFactory");
        this.services = Objects.requireNonNull(services, "services");
        this.retentionDays = Objects.requireNonNull(retentionDays, "retentionDays");
        this.services.bindRecordingSource(this::recordingSnapshot);
    }

    /** Starts a new lifecycle, returning a bounded result instead of an exception message. */
    public StartResult start(Path eventTrailDirectory) {
        try {
            synchronized (this) { return startLocked(eventTrailDirectory); }
        } finally { deliverRecordingUpdates(); }
    }

    private StartResult startLocked(Path eventTrailDirectory) {
        Objects.requireNonNull(eventTrailDirectory, "eventTrailDirectory");
        EventTrailLifecycleState currentState = published.get().state();
        if (currentState == EventTrailLifecycleState.READY
                || currentState == EventTrailLifecycleState.STARTING
                || currentState == EventTrailLifecycleState.STOPPING) {
            return StartResult.failure(
                    currentState, published.get().backendName(), StartFailure.ALREADY_ACTIVE,
                    published.get().recording());
        }

        String backendHint = EventTrailDiagnostics.stableStorageBackendLabel(
                storageFactory.configuredBackendName());
        RecordingSnapshot starting = published.get().recording().starting(
                ++generation, EventTrailFailure.Backend.fromLabel(backendHint));
        publish(new RuntimeView(EventTrailLifecycleState.STARTING, null, backendHint, starting));
        unavailableStatusVisible = false;

        EventTrailStorageFactory.StorageCandidate candidate = null;
        ILogStorage.WriteObservation writerObservation = () -> {};
        AtomicBoolean observationCurrent = new AtomicBoolean(true);
        boolean compatibilityOwned = false;
        boolean queryOwned = false;
        boolean maintenanceOwned = false;
        boolean statusOwned = false;
        StartFailure phase = StartFailure.RUNTIME_RESET_FAILED;

        try {
            services.resetBeforeStart();

            phase = StartFailure.STORAGE_CREATION_FAILED;
            candidate = storageFactory.create(eventTrailDirectory);

            phase = StartFailure.STORAGE_INITIALIZATION_FAILED;
            long expectedGeneration = starting.generation();
            ILogStorage.WriteObservation registered = candidate.storage().observeWriteAdmission(
                    snapshot -> observeWriter(expectedGeneration, observationCurrent, snapshot));
            writerObservation = () -> {
                observationCurrent.set(false);
                registered.close();
            };
            candidate.storage().init();
            if (!candidate.storage().isReady()) {
                phase = StartFailure.STORAGE_NOT_READY;
                throw new IllegalStateException("Configured storage did not become ready");
            }

            phase = StartFailure.COMPATIBILITY_START_FAILED;
            compatibilityOwned = true;
            services.startCompatibility();

            phase = StartFailure.QUERY_START_FAILED;
            queryOwned = true;
            services.startQueryWork();

            int configuredRetentionDays = Math.max(0, retentionDays.getAsInt());
            if (configuredRetentionDays > 0) {
                phase = StartFailure.MAINTENANCE_START_FAILED;
                maintenanceOwned = true;
                services.startMaintenance(candidate.storage(), configuredRetentionDays);
            }

            phase = StartFailure.STATUS_START_FAILED;
            statusOwned = true;
            services.startStatus(candidate.storage());

            OwnedRuntime owned = new OwnedRuntime(
                    candidate.storage(), EventTrailDiagnostics.stableStorageBackendLabel(candidate.backendName()), compatibilityOwned,
                    queryOwned, maintenanceOwned, statusOwned, writerObservation);
            active = owned;
            RecordingSnapshot recording = publishReady(owned);
            return recording.recordingAvailable()
                    ? StartResult.ready(owned.backendName(), recording)
                    : StartResult.failure(EventTrailLifecycleState.READY, owned.backendName(),
                            StartFailure.WRITER_NOT_ACCEPTING, recording);
        } catch (Throwable failure) {
            String backend = candidate == null ? backendHint
                    : EventTrailDiagnostics.stableStorageBackendLabel(candidate.backendName());
            // Capture the initiating reason before compensation can report a different failure.
            EventTrailFailure initiating = candidate != null
                    && phase == StartFailure.STORAGE_INITIALIZATION_FAILED
                    && candidate.storage().initializationFailure() != null
                    ? candidate.storage().initializationFailure()
                    : EventTrailFailure.from(failure, failurePhase(phase),
                            EventTrailFailure.Backend.fromLabel(backend));
            observationCurrent.set(false);
            EventTrailFailure observationCleanup = stopSafely("writer observation", writerObservation::close);
            List<EventTrailFailure> cleanup = compensatePartialStart(
                    candidate == null ? null : candidate.storage(),
                    statusOwned, maintenanceOwned, queryOwned, compatibilityOwned);
            if (observationCleanup != null && !cleanup.contains(observationCleanup) && cleanup.size() < 8) {
                List<EventTrailFailure> combined = new ArrayList<>(cleanup);
                combined.add(observationCleanup);
                cleanup = List.copyOf(combined);
            }
            active = null;
            RecordingSnapshot disabled = published.get().recording().disabled(
                    initiating, cleanup, false, System.currentTimeMillis());
            publish(new RuntimeView(EventTrailLifecycleState.FAILED, null, backend, disabled));
            showUnavailableStatus();
            LOGGER.debug(
                    "EventTrail {} startup diagnostic ({}, {}).",
                    backend, phase, EventTrailDiagnostics.safeFailureSummary(failure));
            return StartResult.failure(EventTrailLifecycleState.FAILED, backend, phase, disabled);
        }
    }

    /**
     * Detaches write admission and releases all successfully acquired services.
     * Repeated calls after completion are no-ops.
     */
    public void stop() {
        try {
            synchronized (this) { stopLocked(); }
        } finally { deliverRecordingUpdates(); }
    }

    private void stopLocked() {
        RuntimeView current = published.get();
        OwnedRuntime owned = active;
        if (current.state() == EventTrailLifecycleState.STOPPED
                && owned == null && !unavailableStatusVisible) {
            // The repeated stop at the server's final stop event reports refusals and skipped capture attempts since
            // the first stop (af-1 F6; af-2 R-M1 b).
            reportNotReadyRefusals();
            reportSkippedCaptureAttempts();
            reportExplosionPartLosses();
            return;
        }

        publish(new RuntimeView(EventTrailLifecycleState.STOPPING, null, current.backendName(),
                current.recording().stopping(false)));
        active = null;
        if (owned != null) stopSafely("writer observation", owned.writerObservation()::close);

        if (owned != null && owned.statusOwned()) stopSafely("status", services::stopStatus);
        else if (unavailableStatusVisible) stopSafely("status", services::stopStatus);
        unavailableStatusVisible = false;
        if (owned != null && owned.maintenanceOwned()) {
            stopSafely("maintenance", services::stopMaintenance);
        }
        if (owned != null && owned.queryOwned()) stopSafely("query", services::stopQueryWork);
        if (owned != null && owned.compatibilityOwned()) {
            stopSafely("session/compatibility", services::stopCompatibilityAndSessions);
        }
        if (owned != null) closeSafely(owned.storage());

        publish(new RuntimeView(EventTrailLifecycleState.STOPPED, null, current.backendName(),
                published.get().recording().stopping(true)));
        // af-1 F6: after the storage's own shutdown summary.
        reportNotReadyRefusals();
        reportSkippedCaptureAttempts();
        reportExplosionPartLosses();
    }

    public boolean tryLog(AuditRecord record) {
        if (record == null) return false;
        admission.readLock().lock();
        try {
            RuntimeView view = published.get();
            if (admits(view)) return view.storage().tryLog(record);
            countRefusal(view, 1);
            return false;
        } finally {
            admission.readLock().unlock();
            deliverRecordingUpdates();
        }
    }

    /** All rows of one action or none, under the same admission read lock as {@link #tryLog}. */
    public boolean tryLogAll(List<AuditRecord> records) {
        if (records == null || records.isEmpty()) return false;
        admission.readLock().lock();
        try {
            RuntimeView view = published.get();
            if (admits(view)) return view.storage().tryLogAll(records);
            // Per row, like the storage's refusal counters (af-1 F6).
            countRefusal(view, records.size());
            return false;
        } finally {
            admission.readLock().unlock();
            deliverRecordingUpdates();
        }
    }

    /**
     * Explosion split (design-02 2.1, Advisor M-3): one part of an explosion stored in parts, under the same admission
     * read lock as {@link #tryLogAll}. Without a READY runtime whose storage is ready it answers REFUSED and counts
     * nothing here (the publisher counts every part not stored once, in {@link #explosionPartLosses}); NO_ROOM is
     * never counted anywhere.
     */
    public ILogStorage.OfferResult tryLogAllWhenRoom(
            List<AuditRecord> records, long retainedBytes, int headroomRows, long headroomBytes) {
        if (records == null || records.isEmpty()) return ILogStorage.OfferResult.REFUSED;
        admission.readLock().lock();
        try {
            RuntimeView view = published.get();
            if (!admits(view)) return ILogStorage.OfferResult.REFUSED;
            ILogStorage.OfferResult result =
                    view.storage().tryLogAllWhenRoom(records, retainedBytes, headroomRows, headroomBytes);
            return result == null ? ILogStorage.OfferResult.REFUSED : result;
        } finally {
            admission.readLock().unlock();
            deliverRecordingUpdates();
        }
    }

    /** Explosion split (condition 4): the figure counting every part not stored, for the publisher and status. */
    public ExplosionPartLossCounter explosionPartLosses() {
        return explosionPartLosses;
    }

    /**
     * Explosion split (design-02 2.3; Advisor answer 2), under this coordinator's monitor right after
     * {@link #reportSkippedCaptureAttempts} at the end of every stop: one INFO line with the explosion parts not
     * stored since the previous such line, when there were any. The publisher's stop pass runs before the stop
     * (EventTrailMod), so the parts it dropped are in this line.
     */
    private void reportExplosionPartLosses() {
        ExplosionPartLossCounter.Snapshot total = explosionPartLosses.snapshot();
        ExplosionPartLossCounter.Snapshot sinceLastReport = total.minus(explosionPartLossesReported);
        if (sinceLastReport.isEmpty()) return;
        explosionPartLossesReported = total;
        LOGGER.info(
                "EventTrail explosions stored in parts: {}; lookups show those explosions INCOMPLETE. "
                        + "Since this process started: {}.",
                sinceLastReport.describe(), total.describe());
    }

    private static boolean admits(RuntimeView view) {
        return view.state() == EventTrailLifecycleState.READY
                && view.storage() != null
                && view.storage().isReady();
    }

    /**
     * af-2 R-M2: a READY runtime whose storage stopped accepting writes in the run (for example a failed SQLite
     * ownership check, which leaves the published state READY) refuses the rows on the storage's behalf; the
     * storage counts them as refused because its writer stopped, so its shutdown summary and the status name the
     * right cause. Every other refusal here (no READY runtime) is a not-ready refusal. The storage call is a
     * count-only add: it never offers the rows to a storage that is not ready.
     */
    private void countRefusal(RuntimeView view, int rows) {
        ILogStorage storage = view.state() == EventTrailLifecycleState.READY ? view.storage() : null;
        if (storage != null && storage.recordRefusedWhileUnavailable(rows)) return;
        notReadyRefusedRows.addAndGet(rows);
    }

    /**
     * af-1 F6: audit rows offered to {@link #tryLog}/{@link #tryLogAll} and refused because no READY runtime was
     * published (before start-up completed, after a failed start, or from the start of a stop), since this
     * coordinator was created (for the production singleton: since this process started). Refusals by a READY
     * runtime's storage, including those it can no longer accept (af-2 R-M2), are counted by the storage.
     */
    public long notReadyRefusedRows() {
        return notReadyRefusedRows.get();
    }

    /**
     * af-2, the one shared capture gate (Owner decision R-M1/R-m6 b). Every production capture point that checks
     * EventTrail's readiness before it builds or offers an action asks this instead of {@link #isReady}, so each
     * skipped attempt is counted once and every point counts the same way. Returns exactly {@link #isReady}; when
     * that is false, one skipped capture attempt is counted. The ready path adds no allocation, lock or write; the
     * counting path adds one {@link LongAdder#increment}. Neither adds anything that can throw.
     */
    public boolean admitCapture() {
        return countSkippedCapture(isReady());
    }

    /**
     * af-2: the same counted gate for capture points that also need write room before they build an action
     * (container access): returns exactly {@link #canAcceptAuditWrite}; when false, one skipped capture attempt
     * is counted.
     */
    public boolean admitCaptureWithWriteCapacity() {
        return countSkippedCapture(canAcceptAuditWrite());
    }

    private boolean countSkippedCapture(boolean admitted) {
        if (!admitted) addSkippedCaptureAttempts(1L);
        return admitted;
    }

    /**
     * af-3 (Owner decision R-M1/R-m6 b, re-check m4): the stop's cleanup drops open container and container-access
     * sessions and in-flight actions without writing their rows (no CLOSE or completion is fabricated). Each
     * cleanup counts every item it dropped here, once, into the same skipped-capture-attempt figure as the capture
     * gate, so these are counted rather than lost silently. A count of zero or less adds nothing. Like the gate it
     * adds one {@link LongAdder#add}, no lock and nothing that can throw.
     */
    public void countDroppedCaptures(int dropped) {
        if (dropped > 0) addSkippedCaptureAttempts(dropped);
    }

    /** The one place that adds to the skipped-capture-attempt figure (af-2 gate and af-3 stop cleanup). */
    private void addSkippedCaptureAttempts(long attempts) {
        skippedCaptureAttempts.add(attempts);
    }

    /**
     * af-2: capture attempts skipped by {@link #admitCapture}/{@link #admitCaptureWithWriteCapacity} since this
     * coordinator was created (for the production singleton: since this process started), plus the sessions and
     * in-flight actions the stop's cleanup dropped ({@link #countDroppedCaptures}, af-3). It counts attempts, not
     * rows, and a gate can run before it is known whether any row would result, so it is an upper bound.
     */
    public long skippedCaptureAttempts() {
        return skippedCaptureAttempts.sum();
    }

    /**
     * af-1 F6, under this coordinator's monitor at the end of every stop (including the repeated stop at the
     * server's final stop event, which comes after players were disconnected and the worlds saved): one WARN line
     * with the not-ready refusals since the previous such line, when there were any.
     */
    private void reportNotReadyRefusals() {
        long total = notReadyRefusedRows.get();
        long sinceLastReport = total - notReadyRefusalsReported;
        if (sinceLastReport <= 0L) return;
        notReadyRefusalsReported = total;
        LOGGER.warn(
                "EventTrail refused {} audit entr{} because the runtime was not READY (before start-up completed, " +
                        "after a failed start, or after the server began to stop); they were not recorded. " +
                        "Refused while not READY since this process started: {}.",
                sinceLastReport, sinceLastReport == 1L ? "y" : "ies", total);
    }

    /**
     * af-2 (Owner decision R-M1/R-m6 b), under this coordinator's monitor right after
     * {@link #reportNotReadyRefusals} at the end of every stop: one INFO line with the skipped capture attempts
     * since the previous such line, when there were any. INFO, not WARN: the figure is an upper bound on actions
     * (a gate can run before it is known whether any row would result), and start-up and stop skips are normal.
     */
    private void reportSkippedCaptureAttempts() {
        long total = skippedCaptureAttempts.sum();
        long sinceLastReport = total - skippedCaptureAttemptsReported;
        if (sinceLastReport <= 0L) return;
        skippedCaptureAttemptsReported = total;
        LOGGER.info(
                "EventTrail skipped {} capture attempt{} because it was not ready (before start-up completed, after " +
                        "a failed start, after a failed storage-ownership check, or after the server began to stop; " +
                        "container access also while its database writer was stopped or its write buffer was nearly " +
                        "full; including open container sessions and in-flight actions dropped at the stop); those " +
                        "actions were not recorded. Skipped capture attempts are an upper bound, not rows. Skipped " +
                        "capture attempts since this process started: {}.",
                sinceLastReport, sinceLastReport == 1L ? "" : "s", total);
    }

    public boolean canAcceptAuditWrite() {
        admission.readLock().lock();
        try {
            RuntimeView view = published.get();
            return view.state() == EventTrailLifecycleState.READY
                    && view.storage() != null
                    && view.storage().isReady()
                    && view.storage().hasWriteCapacityHint();
        } finally {
            admission.readLock().unlock();
        }
    }

    public boolean isReady() {
        RuntimeView view = published.get();
        return view.state() == EventTrailLifecycleState.READY
                && view.storage() != null
                && view.storage().isReady();
    }

    public EventTrailLifecycleState state() {
        return published.get().state();
    }

    public ILogStorage storage() {
        RuntimeView view = published.get();
        return view.state() == EventTrailLifecycleState.READY ? view.storage() : null;
    }

    public String backendName() {
        return published.get().backendName();
    }

    /** Fully cached; no JDBC, writer polling, or background executor is required. */
    public RecordingSnapshot recordingSnapshot() {
        refreshRecordingFacts();
        return published.get().recording();
    }

    private void refreshRecordingFacts() {
        RuntimeView current = published.get();
        if (current.state() == EventTrailLifecycleState.READY && current.storage() != null
                && current.storage().terminalBackendAccessFailure() != null) {
            // Cached policy only. This also reconciles a later backend revocation
            // after a writer-only failure has already stopped the idle poller.
            observeWriter(current.recording().generation(), null,
                    current.storage().writeAdmissionSnapshot(), false);
        }
    }

    /** Recheck a queued startup-success effect against current-generation recording authority. */
    public boolean isCurrentRecordingSuccess(StartResult result) {
        RecordingSnapshot now = recordingSnapshot();
        return result != null && result.ready() && result.recording() != null
                && now.serverRunId().equals(result.recording().serverRunId())
                && now.generation() == result.recording().generation()
                && now.recordingAvailable();
    }

    public void clearPlayerState(UUID playerId) {
        if (playerId != null) services.clearPlayerState(playerId);
    }

    public void bindServer(MinecraftServer server) { services.bindServer(server); }
    public void playerLoggedIn(MinecraftServer server, UUID playerId) { services.playerLoggedIn(server, playerId); }
    public void retentionPlayersChanged(MinecraftServer server, int humanPlayers) {
        services.retentionPlayersChanged(server, humanPlayers);
    }
    public void serverStarted(MinecraftServer server, int humanPlayers) { services.serverStarted(server, humanPlayers); }
    public void serverTick(MinecraftServer server) {
        refreshRecordingFacts();
        deliverRecordingUpdates();
        services.serverTick(server);
    }

    private List<EventTrailFailure> compensatePartialStart(
            ILogStorage candidate,
            boolean statusOwned,
            boolean maintenanceOwned,
            boolean queryOwned,
            boolean compatibilityOwned) {
        List<EventTrailFailure> failures = new ArrayList<>();
        if (statusOwned) addCleanup(failures, stopSafely("status", services::stopStatus));
        if (maintenanceOwned) addCleanup(failures, stopSafely("maintenance", services::stopMaintenance));
        if (queryOwned) addCleanup(failures, stopSafely("query", services::stopQueryWork));
        if (compatibilityOwned) {
            addCleanup(failures, stopSafely("session/compatibility", services::stopCompatibilityAndSessions));
        }
        if (candidate != null) {
            addCleanup(failures, closeSafely(candidate));
            for (EventTrailFailure failure : candidate.cleanupFailures()) addCleanup(failures, failure);
        }
        return List.copyOf(failures);
    }

    private void showUnavailableStatus() {
        try {
            services.showUnavailableStatus();
            unavailableStatusVisible = true;
        } catch (Throwable statusFailure) {
            LOGGER.warn("EventTrail could not publish unavailable storage status ({}).",
                    EventTrailDiagnostics.safeFailureSummary(statusFailure));
        }
    }

    private void publish(RuntimeView view) {
        admission.writeLock().lock();
        try {
            synchronized (observationLock) {
                published.set(view);
                enqueueRecordingLocked(view.recording());
            }
        } finally {
            admission.writeLock().unlock();
        }
        deliverRecordingUpdates();
    }

    private RecordingSnapshot publishReady(OwnedRuntime owned) {
        admission.writeLock().lock();
        try {
            // Read without observationLock: a storage callback never waits for
            // this coordinator's lifecycle monitor or its admission lock.
            WriteAdmissionSnapshot observed = owned.storage().writeAdmissionSnapshot();
            boolean readable = owned.storage().isReady();
            // isReady may have observed a terminal backend restriction after the
            // first snapshot. Reconcile that cached version before READY publication.
            WriteAdmissionSnapshot afterReadiness = owned.storage().writeAdmissionSnapshot();
            if (afterReadiness.version() > observed.version()) observed = afterReadiness;
            synchronized (observationLock) {
                RuntimeView current = published.get();
                WriteAdmissionSnapshot newest = current.writer().version() > observed.version()
                        ? current.writer() : observed;
                RecordingSnapshot recording = current.recording().admitted(
                        newest, readable, System.currentTimeMillis());
                published.set(new RuntimeView(EventTrailLifecycleState.READY, owned.storage(),
                        owned.backendName(), recording, newest));
                enqueueRecordingLocked(recording);
            }
        } finally {
            admission.writeLock().unlock();
        }
        deliverRecordingUpdates();
        return published.get().recording();
    }

    private void observeWriter(
            long expectedGeneration, AtomicBoolean currentBinding, WriteAdmissionSnapshot writer) {
        observeWriter(expectedGeneration, currentBinding, writer, true);
    }

    private void observeWriter(
            long expectedGeneration, AtomicBoolean currentBinding, WriteAdmissionSnapshot writer, boolean deliver) {
        if (writer == null) return;
        synchronized (observationLock) {
            RuntimeView current = published.get();
            if ((currentBinding != null && !currentBinding.get()) || current.recording().generation() != expectedGeneration
                    || writer.version() <= current.writer().version()
                    || current.state() == EventTrailLifecycleState.STOPPING
                    || current.state() == EventTrailLifecycleState.STOPPED
                    || current.state() == EventTrailLifecycleState.FAILED) return;
            RecordingSnapshot recording = current.recording();
            if (current.state() == EventTrailLifecycleState.READY) {
                recording = recording.admitted(writer, recording.historyReady(), System.currentTimeMillis());
            } else if (writer.state() == WriteAdmissionSnapshot.State.DISABLED) {
                EventTrailFailure cause = writer.failure() != null ? writer.failure()
                        : new EventTrailFailure(EventTrailFailure.Code.AUDIT_WRITER_STOPPED,
                                EventTrailFailure.Phase.WRITER, recording.backend(),
                                EventTrailFailure.FileRole.UNKNOWN);
                recording = recording.disabled(cause, recording.cleanupFailures(), false, System.currentTimeMillis());
            }
            published.set(new RuntimeView(current.state(), current.storage(),
                    current.backendName(), recording, writer));
            if (recording != current.recording()) enqueueRecordingLocked(recording);
        }
        if (deliver) deliverRecordingUpdates();
    }

    private void deliverRecordingUpdates() {
        // tryLog may receive a synchronous storage observation while it owns a
        // coordinator admission read lock. Facts are recorded immediately, but
        // all presentation effects wait until the outer caller releases it.
        if (Thread.holdsLock(this) || admission.getReadHoldCount() > 0 || admission.isWriteLockedByCurrentThread()) return;
        if (!deliveringRecording.compareAndSet(false, true)) return;
        do {
            try {
                RecordingSnapshot snapshot;
                while ((snapshot = pollRecordingUpdate()) != null) {
                    try { services.recordingChanged(snapshot); }
                    catch (Throwable failure) {
                        LOGGER.warn("EventTrail recording-state presentation failed ({}).",
                                EventTrailDiagnostics.safeFailureSummary(failure));
                    }
                }
            } finally {
                deliveringRecording.set(false);
            }
        } while (hasRecordingUpdates() && deliveringRecording.compareAndSet(false, true));
    }

    private void enqueueRecordingLocked(RecordingSnapshot snapshot) {
        RecordingSnapshot last = recordingUpdates.peekLast();
        if (last != null && last.serverRunId().equals(snapshot.serverRunId())
                && last.generation() == snapshot.generation()
                && ((last.recordingAvailable() && snapshot.recordingAvailable())
                    || (last.state() == snapshot.state()
                        && Objects.equals(last.currentFailure(), snapshot.currentFailure())
                        && Objects.equals(last.originalFailure(), snapshot.originalFailure())
                        && last.cleanupFailures().equals(snapshot.cleanupFailures())))) {
            // Pressure/retry flapping has no episode edge. Keep the latest
            // availability observation, but never discard a disabled/recovery boundary.
            recordingUpdates.removeLast();
        }
        recordingUpdates.addLast(snapshot);
    }

    private RecordingSnapshot pollRecordingUpdate() {
        synchronized (observationLock) { return recordingUpdates.pollFirst(); }
    }

    private boolean hasRecordingUpdates() {
        synchronized (observationLock) { return !recordingUpdates.isEmpty(); }
    }

    private EventTrailFailure stopSafely(String service, Runnable stop) {
        try {
            stop.run();
            return null;
        } catch (Throwable failure) {
            LOGGER.error("EventTrail {} service did not stop cleanly ({}).",
                    service, EventTrailDiagnostics.safeFailureSummary(failure));
            return EventTrailFailure.from(failure, EventTrailFailure.Phase.CLEANUP,
                    EventTrailFailure.Backend.fromLabel(backendName()));
        }
    }

    private EventTrailFailure closeSafely(ILogStorage storage) {
        try {
            storage.close();
            return null;
        } catch (Throwable failure) {
            LOGGER.error("EventTrail storage did not close cleanly ({}).",
                    EventTrailDiagnostics.safeFailureSummary(failure));
            return EventTrailFailure.from(failure, EventTrailFailure.Phase.CLEANUP,
                    EventTrailFailure.Backend.fromLabel(backendName()));
        }
    }

    private static void addCleanup(List<EventTrailFailure> failures, EventTrailFailure failure) {
        if (failure != null && !failures.contains(failure) && failures.size() < 8) failures.add(failure);
    }

    private static EventTrailFailure.Phase failurePhase(StartFailure failure) {
        return switch (failure) {
            case RUNTIME_RESET_FAILED -> EventTrailFailure.Phase.RUNTIME_RESET;
            case STORAGE_CREATION_FAILED -> EventTrailFailure.Phase.STORAGE_CREATION;
            case STORAGE_INITIALIZATION_FAILED, STORAGE_NOT_READY -> EventTrailFailure.Phase.STORAGE_INITIALIZATION;
            case COMPATIBILITY_START_FAILED -> EventTrailFailure.Phase.COMPATIBILITY;
            case QUERY_START_FAILED -> EventTrailFailure.Phase.QUERY;
            case MAINTENANCE_START_FAILED -> EventTrailFailure.Phase.MAINTENANCE;
            case STATUS_START_FAILED -> EventTrailFailure.Phase.STATUS;
            case WRITER_NOT_ACCEPTING -> EventTrailFailure.Phase.WRITER;
            default -> EventTrailFailure.Phase.UNKNOWN;
        };
    }

    public enum StartFailure {
        ALREADY_ACTIVE,
        RUNTIME_RESET_FAILED,
        STORAGE_CREATION_FAILED,
        STORAGE_INITIALIZATION_FAILED,
        STORAGE_NOT_READY,
        COMPATIBILITY_START_FAILED,
        QUERY_START_FAILED,
        MAINTENANCE_START_FAILED,
        STATUS_START_FAILED,
        WRITER_NOT_ACCEPTING
    }

    public record StartResult(
            boolean ready,
            EventTrailLifecycleState state,
            String backendName,
            StartFailure failure,
            EventTrailFailure reason,
            RecordingSnapshot recording) {
        static StartResult ready(String backendName, RecordingSnapshot recording) {
            return new StartResult(true, EventTrailLifecycleState.READY, backendName, null, null, recording);
        }

        static StartResult failure(
                EventTrailLifecycleState state, String backendName, StartFailure failure,
                RecordingSnapshot recording) {
            return new StartResult(false, state, backendName, failure, recording.currentFailure(), recording);
        }
    }

    private record RuntimeView(
            EventTrailLifecycleState state, ILogStorage storage, String backendName,
            RecordingSnapshot recording, WriteAdmissionSnapshot writer) {
        RuntimeView(EventTrailLifecycleState state, ILogStorage storage, String backendName,
                    RecordingSnapshot recording) {
            this(state, storage, backendName, recording, WriteAdmissionSnapshot.unknown());
        }
    }

    private record OwnedRuntime(
            ILogStorage storage,
            String backendName,
            boolean compatibilityOwned,
            boolean queryOwned,
            boolean maintenanceOwned,
            boolean statusOwned,
            ILogStorage.WriteObservation writerObservation) {}
}
