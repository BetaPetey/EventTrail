package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.storage.SQLiteReclamationPolicy.Episode;
import io.github.betapetey.eventtrail.storage.SQLiteReclamationPolicy.Phase;
import io.github.betapetey.eventtrail.storage.StorageMaintenanceSnapshot.Metric;
import io.github.betapetey.eventtrail.storage.StorageMaintenanceSnapshot.State;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static io.github.betapetey.eventtrail.storage.SQLiteReclamationPolicy.*;

/** One storage generation, invoked only on its existing maintenance worker; no executor or retry loop. */
final class SQLiteReclamationController {
    interface Operations {
        SQLiteReclamationOperations.Observation observe(BooleanSupplier admission) throws SQLException;
        SQLiteReclamationOperations.UnitResult vacuum(long reserve, long remaining, long group, int cap,
                                                       BooleanSupplier admission) throws SQLException;
        SQLiteReclamationOperations.CheckpointResult passive(BooleanSupplier admission) throws SQLException;
    }

    private final Operations operations;
    private final SQLiteReclamationStateFile stateFile;
    private final String binding;
    private final LongSupplier nanoClock;
    private final AtomicBoolean running = new AtomicBoolean();
    private Episode episode;
    private SQLiteReclamationOperations.Observation observation;
    private volatile StorageMaintenanceSnapshot cached;
    private boolean failed;
    private int consecutiveSlowMinimum;
    private boolean slowLatched;
    private long totalCompletedUnits;
    private long totalCompletedCheckpoints;
    private Long lastNativeNanos, lastSettledUnitNanos, lastSliceNanos, lastCheckpointNanos;
    private volatile SQLiteReclamationOperations.CheckpointResult lastCheckpoint;
    private volatile SQLiteReclamationOperations.UnitResult lastUnit;
    private StorageMaintenanceSnapshot.ReclamationResult lastResult;
    private long checkpointMainReduction;

    SQLiteReclamationController(SQLiteStorage storage, SQLiteReclamationStateFile stateFile, String binding) {
        this(storage.reclamationOperations(), stateFile, binding);
    }

    /** Deterministic policy tests supply fixed operations; production binds only its real storage-internal adapter. */
    SQLiteReclamationController(Operations operations, SQLiteReclamationStateFile stateFile, String binding) {
        this(operations, stateFile, binding, System::nanoTime);
    }

    /** Tests may also supply the slice clock; production always uses {@link System#nanoTime()}. */
    SQLiteReclamationController(Operations operations, SQLiteReclamationStateFile stateFile, String binding,
                                LongSupplier nanoClock) {
        this.operations = operations;
        this.stateFile = stateFile;
        this.binding = binding;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** Runs before startup retention: invalid existing state baselines the OLD pool, never a fresh episode. */
    void initialize(SQLiteReclamationOperations.Observation baseline) throws IOException {
        observation = baseline;
        if (baseline.capability() != SQLiteReclamationOperations.State.ELIGIBLE) {
            publish(map(baseline.capability()), baseline.capability().name());
            return;
        }
        byte[] bytes = stateFile.read();
        Episode loaded;
        boolean baselineRequired = false;
        try { loaded = decode(bytes, binding); }
        catch (IOException malformed) { loaded = baseline(binding, baseline.freelistPages()); baselineRequired = true; }
        Episode recovered = loaded.recoverUncertainGroup();
        if (baselineRequired || recovered != loaded) stateFile.write(encode(recovered));
        episode = recovered;
        publish(configured() ? State.WAITING_FOR_IDLE : State.DISABLED,
                baselineRequired ? "FREE_POOL_BASELINED_BEFORE_RETENTION" : "WAITING_FOR_IDLE_RETENTION");
    }

    StorageMaintenanceSnapshot snapshot() { return cached; }

    void recordSettledSliceNanos(long elapsed) {
        lastSliceNanos = Math.max(0, elapsed);
        if (cached != null) publish(cached.reclamationState(), cached.policyStatus().reason());
    }

    void updateObservation(SQLiteReclamationOperations.Observation fresh) {
        if (fresh == null || !running.compareAndSet(false, true)) return;
        try {
            observation = fresh;
            publish(fresh.capability() == SQLiteReclamationOperations.State.ELIGIBLE
                            ? cached.reclamationState() : map(fresh.capability()),
                    fresh.capability() == SQLiteReclamationOperations.State.ELIGIBLE
                            ? cached.policyStatus().reason() : fresh.capability().name());
        } finally { running.set(false); }
    }

    void afterRetention(long cutoff, RetentionResult retention, BooleanSupplier idleAdmission) {
        if (!running.compareAndSet(false, true)) return;
        long started = nanoClock.getAsLong();
        long released = 0, reduced = 0, mainReduced = 0;
        long checkpointReductionBefore = checkpointMainReduction;
        State outcome = State.WAITING_FOR_IDLE;
        String reason = "WAITING_FOR_IDLE_RETENTION";
        try {
            if (failed) { outcome = State.FAILED; reason = "POLICY_STATE_OR_NATIVE_FAILURE"; return; }
            if (episode == null) { outcome = map(observation.capability()); reason = observation.capability().name(); return; }
            // A completed positive startup opportunity may authorize a later idle EXHAUSTED zero-row check.
            Episode credited = episode.retention(retention);
            if (credited != episode) persist(credited);
            if (!configured()) { outcome = State.DISABLED; reason = "AUTOMATIC_RECLAMATION_DISABLED"; return; }
            if (slowLatched) { outcome = State.DEFERRED_SLOW_STORAGE; reason = "THREE_SLOW_MINIMUM_UNITS"; return; }
            if (retention.outcome() != RetentionResult.Outcome.EXHAUSTED) {
                reason = "RETENTION_NOT_EXHAUSTED"; return;
            }
            BooleanSupplier admission = () -> configured() && idleAdmission.getAsBoolean();
            if (!admission.getAsBoolean()) return;
            SQLiteReclamationOperations.Observation current = operations.observe(admission);
            if (current == null) { outcome = State.YIELDED; reason = "HUMAN_OR_HIGHER_PRIORITY_WORK"; return; }
            observation = current;
            if (current.capability() != SQLiteReclamationOperations.State.ELIGIBLE) {
                outcome = map(current.capability()); reason = current.capability().name(); return;
            }
            if (episode.checkpointPending()) {
                var checkpoint = checkpoint(admission);
                outcome = map(checkpoint.state()); reason = checkpoint.reason();
                if (checkpoint.state() != SQLiteReclamationOperations.State.COMPLETED) return;
                if (checkpoint.settledNanos() > SLOW_NANOS || checkpoint.nativeNanos() > SLOW_NANOS) {
                    outcome = State.DEFERRED_SLOW_STORAGE; reason = "SLOW_PASSIVE_CHECKPOINT"; return;
                }
            }
            Episode begun = episode.begin(observation.pageCount(), observation.freelistPages());
            if (begun != episode) persist(begun);
            if (episode.phase() != Phase.ACTIVE) {
                outcome = State.WAITING_FOR_MATERIAL_GROWTH; reason = "POSITIVE_RETENTION_AND_64_MIB_NET_GROWTH_REQUIRED"; return;
            }
            int units = 0;
            boolean end = false;
            while (!end && units < MAX_SLICE_UNITS && withinSlice(started) && admission.getAsBoolean()) {
                if (episode.remaining() == 0 || observation.freelistPages() <= episode.reserve()) {
                    persist(episode.complete(observation.freelistPages()));
                    outcome = State.REUSE_RESERVE_RETAINED; reason = "FIXED_RESERVE_OR_EPISODE_ALLOWANCE_REACHED"; break;
                }
                // Publication is outside native mutation, but still inside the storage's auxiliary ownership.
                persist(episode.reserveGroup());
                int groupUsed = 0, groupUnits = 0, nextCap = episode.unitCap();
                boolean slow = false;
                while (groupUnits < MAX_GROUP_UNITS && units < MAX_SLICE_UNITS
                        && groupUsed < episode.reserved() && withinSlice(started) && admission.getAsBoolean()) {
                    var unit = operations.vacuum(episode.reserve(), episode.remaining() + episode.reserved() - groupUsed,
                            episode.reserved() - groupUsed, episode.unitCap(), admission);
                    if (unit.after() != null) observation = unit.after();
                    outcome = map(unit.state()); reason = unit.reason();
                    if (unit.state() != SQLiteReclamationOperations.State.COMPLETED) { end = true; break; }
                    lastNativeNanos = unit.nativeNanos(); lastSettledUnitNanos = unit.settledNanos();
                    lastUnit = unit;
                    totalCompletedUnits++; units++; groupUnits++;
                    groupUsed = Math.addExact(groupUsed, Math.toIntExact(unit.releasedPages()));
                    released += unit.releasedPages(); reduced += unit.pageCountReduction(); mainReduced += unit.mainFileReduction();
                    if (unit.releasedPages() <= 0) { end = true; reason = "NO_PROGRESS"; outcome = State.YIELDED; break; }
                    slow = unit.nativeNanos() > SLOW_NANOS || unit.settledNanos() > SLOW_NANOS;
                    if (slow) {
                        nextCap = downshift(episode.unitCap());
                        if (episode.unitCap() == 1 && ++consecutiveSlowMinimum >= 3) slowLatched = true;
                        outcome = State.DEFERRED_SLOW_STORAGE;
                        reason = slowLatched ? "THREE_SLOW_MINIMUM_UNITS" : "SLOW_UNIT_DOWNSHIFT";
                        end = true; break;
                    }
                    consecutiveSlowMinimum = 0;
                }
                // Only this normal path refunds known unused work. Any throw keeps the durable reservation spent.
                Episode settled = episode.settleGroup(groupUsed, nextCap);
                // No native unit entered in this fully settled group: there is no new backfill to request.
                persist(groupUsed == 0 ? settled.checkpointCompleted() : settled);
                if (groupUsed > 0 && !slow && withinSlice(started) && admission.getAsBoolean()) {
                    var checkpoint = checkpoint(admission);
                    if (checkpoint.state() != SQLiteReclamationOperations.State.COMPLETED) {
                        outcome = map(checkpoint.state()); reason = checkpoint.reason(); end = true;
                    } else if (checkpoint.settledNanos() > SLOW_NANOS || checkpoint.nativeNanos() > SLOW_NANOS) {
                        outcome = State.DEFERRED_SLOW_STORAGE; reason = "SLOW_PASSIVE_CHECKPOINT"; end = true;
                    }
                }
                if (episode.checkpointPending()) end = true; // Checkpoint always precedes another group.
                if (groupUsed == 0) end = true;
            }
            if (outcome == State.RUNNING_INCREMENTAL || outcome == State.ELIGIBLE) {
                outcome = State.YIELDED; reason = "SLICE_COMPLETE_NEXT_NORMAL_IDLE_OPPORTUNITY";
            }
        } catch (IOException | SQLException | RuntimeException failure) {
            // Do not keep retrying an ambiguous state publication/native outcome in this generation.
            failed = true; outcome = State.FAILED; reason = "POLICY_STATE_OR_NATIVE_FAILURE";
            LogManager.getLogger("eventtrail").warn("Optional SQLite reclamation stopped; logical retention remains independent ({}).",
                    EventTrailDiagnostics.safeFailureSummary(failure));
        } finally {
            lastSliceNanos = Math.max(0, nanoClock.getAsLong() - started);
            mainReduced = Math.addExact(mainReduced, checkpointMainReduction - checkpointReductionBefore);
            if (released > 0 || mainReduced > 0) lastResult = new StorageMaintenanceSnapshot.ReclamationResult(outcome,
                    System.currentTimeMillis(), Metric.exact(released), Metric.exact(Math.multiplyExact(reduced, PAGE_SIZE)),
                    Metric.exact(mainReduced), Metric.unavailable());
            publish(outcome, reason);
            running.set(false);
        }
    }

    private SQLiteReclamationOperations.CheckpointResult checkpoint(BooleanSupplier admission) throws SQLException, IOException {
        var result = operations.passive(admission);
        lastCheckpoint = result;
        if (result.before() != null) lastCheckpointNanos = result.settledNanos();
        if (result.before() != null && result.after() != null)
            checkpointMainReduction = Math.addExact(checkpointMainReduction,
                    Math.max(0, result.before().mainBytes() - result.after().mainBytes()));
        if (result.after() != null) observation = result.after();
        if (result.state() == SQLiteReclamationOperations.State.COMPLETED) {
            totalCompletedCheckpoints++;
            persist(episode.checkpointCompleted());
        }
        return result;
    }

    private void persist(Episode next) throws IOException {
        stateFile.write(encode(next));
        episode = next; // No in-memory credit precedes its durable publication.
    }

    private void publish(State state, String reason) {
        if (observation == null) return;
        var base = observation.snapshot();
        var facts = new StorageMaintenanceSnapshot.PolicyStatus(observation.jdbcVersion(), observation.nativeVersion(),
                episode == null ? "UNAVAILABLE" : episode.phase().name(),
                episode == null ? null : episode.initialFree(), episode == null ? null : episode.reserve(),
                episode == null ? null : episode.remaining() + episode.reserved(), episode == null ? null : episode.baseline(),
                episode == null ? null : (long) episode.reserved(), episode == null ? null : episode.forfeited(),
                episode == null ? MAX_UNIT_PAGES : episode.unitCap(), lastNativeNanos, lastSettledUnitNanos,
                lastSliceNanos, lastCheckpointNanos, reason);
        cached = new StorageMaintenanceSnapshot(base.backend(), base.scope(), base.sqliteMode(), base.capability(), state,
                base.observedAtMillis(), base.observation(), base.allocation(), lastResult, facts);
    }

    private static boolean configured() { return EventTrailConfig.reclaimEnabled() && EventTrailConfig.LOG_RETENTION_DAYS.get() > 0; }
    private boolean withinSlice(long start) { return nanoClock.getAsLong() - start < SLICE_NANOS; }
    private static State map(SQLiteReclamationOperations.State state) {
        return switch (state) {
            case ELIGIBLE -> State.ELIGIBLE;
            case COMPLETED -> State.RUNNING_INCREMENTAL;
            case YIELDED -> State.YIELDED;
            case REUSE_RESERVE_RETAINED -> State.REUSE_RESERVE_RETAINED;
            case CHECKPOINT_DEFERRED -> State.CHECKPOINT_DEFERRED;
            case DEFERRED_LOW_HEADROOM -> State.DEFERRED_LOW_HEADROOM;
            case UNSUPPORTED_RUNTIME -> State.UNSUPPORTED_RUNTIME;
            case UNQUALIFIED_LAYOUT -> State.UNQUALIFIED_LAYOUT;
            case MIGRATION_REQUIRED -> State.MIGRATION_REQUIRED;
            case FULL_MODE_UNCHANGED -> State.FULL_MODE_UNCHANGED;
            case UNRECOGNIZED_OBJECTS -> State.FAILED;
        };
    }
}
