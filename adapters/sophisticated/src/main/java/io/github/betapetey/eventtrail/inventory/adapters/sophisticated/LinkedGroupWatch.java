package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageEpoch;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Sophisticated part 4 (guard design 01, 2.3): the EventTrail-side registry of linked storage groups, keyed by Core's
 * group id and fed by the commit hook ({@code SophisticatedLinkedCommitMixin}, Core's private
 * {@code LinkedStorageGroupManager.commit}). Per group it keeps a sticky refusal, a structure epoch (contents
 * replaced or layout changed: the changes that rebuild the host's handlers) and a manual-action epoch (a second
 * tracked action on the same group nested inside one action).
 *
 * <p>What a commit does here:</p>
 * <ul>
 *   <li>not on the server thread: the group is refused for the rest of the run (sticky; an entry is made if none
 *       exists), with one warning per server start ({@value #OFF_THREAD_REASON}). The commit runs after the stack
 *       changed, so this catches an off-thread write only from its commit on (guard design 01, section 3);</li>
 *   <li>on the server thread with contents replaced or layout changed: the group's structure epoch +1, which ends
 *       every open session on the group. The hook runs at HEAD, before Core reloads the host;</li>
 *   <li>any other commit: a diagnostic counter only (the player's own writes commit too). A commit for a group with
 *       no entry does one map read and returns.</li>
 * </ul>
 *
 * <p>Producer fail-closed (as {@code EnderOpeningScope.externalMutation}): if handling a commit fails, the group, or
 * every group of the run when the group has no entry or no id (a poison flag), is refused before the failure goes
 * back to the hook guard, which reports it. A refused group stays refused until the runtime generation changes
 * (server start and stop). Every refusal the watch makes itself is warned once per server start through
 * {@link AdapterFailSafe#refusedOnce} under {@link AdapterKey#SOPHISTICATED_LINKED_GROUPS}; a later open or action
 * that the watch refuses is warned by the linked adapter that asked ({@link #refusal}).</p>
 *
 * <p>UNPROVEN until the GameTest: mixins do not apply in unit tests, so whether the hook fires, and therefore whether
 * an off-thread write is seen at all, is not shown by any unit test. Nothing may claim off-thread detection before
 * the Owner-authorised GameTest shows the hook firing (Advisor condition 2).</p>
 *
 * <p>Thread safety: {@link #commit} may run on any thread; the session calls ({@link #open}, {@link #unchanged},
 * {@link #beginManualAction}, {@link #manualActionUnchanged}) run on the server thread. Group state is updated under
 * the state's own lock; the table is replaced, never cleared, when the runtime generation changes. The group cap
 * ({@value #MAX_GROUPS} per run) is checked before an entry is made, so concurrent commits can exceed it by at most
 * the number of racing threads.</p>
 */
public final class LinkedGroupWatch {
    /** The hook's site name, as the hook guard and the warnings name it. */
    public static final String COMMIT_SITE = "hook:SophisticatedLinkedCommitMixin.commit";
    /** A group was committed off the server thread: refused until the runtime generation changes. */
    public static final String OFF_THREAD_REASON = "linked-group-off-thread-write";
    /** A commit arrived without a group id: every group of the run is refused. */
    public static final String UNATTRIBUTED_REASON = "linked-group-commit-unattributed";
    /** Handling a commit failed: that group (or every group) is refused. */
    public static final String WATCH_FAILED_REASON = "linked-group-watch-failed";
    /** More distinct groups than the cap were opened or seen this run: that group cannot be recorded. */
    public static final String TABLE_FULL_REASON = "linked-group-table-full";
    /** An epoch reached its maximum: the group is refused rather than wrapped. */
    public static final String SATURATED_REASON = "linked-group-epoch-saturated";
    /** Distinct groups kept per run. */
    public static final int MAX_GROUPS = 4096;

    /**
     * Where the watch's own refusals go (production: one warning per server start, in the fail-safe's generic refusal
     * wording; each refused group, and each refusal of every group, counted once per run).
     */
    @FunctionalInterface
    public interface Refusals {
        void refused(String reason);
    }

    /** One session's view of a group at open: the run and the structure epoch it saw. */
    public record Stamp(UUID groupId, long generation, long structureEpoch) {
        public Stamp {
            Objects.requireNonNull(groupId, "groupId");
        }
    }

    /** One tracked action's manual-action epoch on its group. */
    public record ActionStamp(UUID groupId, long generation, long manualActionEpoch) {
        public ActionStamp {
            Objects.requireNonNull(groupId, "groupId");
        }
    }

    private static final LinkedGroupWatch PRODUCTION = new LinkedGroupWatch(MovingStorageEpoch::runtimeGeneration,
            LinkedGroupWatch::onServerThread,
            reason -> AdapterFailSafe.refusedOnce(AdapterKey.SOPHISTICATED_LINKED_GROUPS, COMMIT_SITE, reason),
            MAX_GROUPS);

    private final LongSupplier generation;
    private final BooleanSupplier serverThread;
    private final Refusals refusals;
    private final int maxGroups;
    private final AtomicReference<Table> table;

    LinkedGroupWatch(LongSupplier generation, BooleanSupplier serverThread, Refusals refusals, int maxGroups) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        this.refusals = Objects.requireNonNull(refusals, "refusals");
        if (maxGroups < 1) throw new IllegalArgumentException("maxGroups must be positive");
        this.maxGroups = maxGroups;
        this.table = new AtomicReference<>(new Table(generation.getAsLong()));
    }

    /** The server's watch, the one the commit hook feeds. */
    public static LinkedGroupWatch production() {
        return PRODUCTION;
    }

    /** The commit hook's body. May throw only what handling the commit threw, after failing closed. */
    public static void observedCommit(UUID groupId, boolean contentsReplaced, boolean layout) {
        PRODUCTION.commit(groupId, contentsReplaced, layout);
    }

    /** True only on the running server's own thread. */
    static boolean onServerThread() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread();
    }

    /**
     * One commit of {@code groupId} (Core's {@code commit(groupId, contentsReplaced, carrier, layout)}; the carrier
     * flag changes no shown contents and is not used). Fails closed, then rethrows what failed.
     */
    public void commit(UUID groupId, boolean contentsReplaced, boolean layout) {
        try {
            Table current = table();
            // Only a refusal that changes the state is reported (review-01 NIT-2): one report per refused group, or one
            // per run for a refusal of every group, never one per later commit.
            if (groupId == null) {
                if (current.poison(UNATTRIBUTED_REASON)) refusals.refused(UNATTRIBUTED_REASON);
                return;
            }
            if (!serverThread.getAsBoolean()) {
                GroupState state = entry(current, groupId);
                // A full table cannot hold this group's refusal, so every group of the run is refused instead.
                boolean changed = state == null ? current.poison(OFF_THREAD_REASON) : state.refuse(OFF_THREAD_REASON);
                if (changed) refusals.refused(OFF_THREAD_REASON);
                return;
            }
            GroupState state = current.groups.get(groupId);
            if (state == null) return;
            if (contentsReplaced || layout) {
                if (state.structureChanged()) refusals.refused(SATURATED_REASON);
            } else {
                state.counted();
            }
        } catch (Throwable failure) {
            failClosed(groupId);
            throw failure;
        }
    }

    /**
     * Marks the group refused, or every group when it has no entry or no id. Allocates nothing and never throws, so
     * it also works when the failure was a stack or memory error.
     */
    private void failClosed(UUID groupId) {
        try {
            Table current = table.get();
            GroupState state = groupId == null ? null : current.groups.get(groupId);
            if (state != null) state.refuse(WATCH_FAILED_REASON);
            else current.poison(WATCH_FAILED_REASON);
        } catch (Throwable ignored) {
            // Nothing more can be marked; the hook guard still reports the original failure.
        }
    }

    /**
     * A linked session opens on {@code groupId}: its stamp, or empty when the group is refused (the reason is
     * {@link #refusal}). Makes the group's entry. Server thread.
     */
    public Optional<Stamp> open(UUID groupId) {
        if (groupId == null) return Optional.empty();
        Table current = table();
        if (current.poisoned() != null) return Optional.empty();
        GroupState state = entry(current, groupId);
        if (state == null) return Optional.empty();
        long epoch = state.openEpoch();
        return epoch < 0 ? Optional.empty() : Optional.of(new Stamp(groupId, current.generation, epoch));
    }

    /** The group of {@code stamp} has not been refused, replaced or re-laid-out since the stamp, in the same run. */
    public boolean unchanged(Stamp stamp) {
        if (stamp == null) return false;
        Table current = table();
        if (current.generation != stamp.generation() || current.poisoned() != null) return false;
        GroupState state = current.groups.get(stamp.groupId());
        return state != null && state.structureUnchanged(stamp.structureEpoch());
    }

    /**
     * A tracked action on the group begins (any player, any member): the group's manual-action epoch +1. Empty when
     * the group is refused or was never opened in this run. Server thread.
     */
    public Optional<ActionStamp> beginManualAction(UUID groupId) {
        if (groupId == null) return Optional.empty();
        Table current = table();
        if (current.poisoned() != null) return Optional.empty();
        GroupState state = current.groups.get(groupId);
        if (state == null) return Optional.empty();
        long epoch = state.beginManualAction();
        return epoch < 0 ? Optional.empty() : Optional.of(new ActionStamp(groupId, current.generation, epoch));
    }

    /** No other tracked action began on the group since {@code stamp}, and the group is not refused. */
    public boolean manualActionUnchanged(ActionStamp stamp) {
        if (stamp == null) return false;
        Table current = table();
        if (current.generation != stamp.generation() || current.poisoned() != null) return false;
        GroupState state = current.groups.get(stamp.groupId());
        return state != null && state.manualActionUnchanged(stamp.manualActionEpoch());
    }

    /** Why the group is refused in this run, or null when it is not. */
    public String refusal(UUID groupId) {
        Table current = table();
        String poisoned = current.poisoned();
        if (poisoned != null) return poisoned;
        if (groupId == null) return UNATTRIBUTED_REASON;
        GroupState state = current.groups.get(groupId);
        if (state != null) return state.refusal();
        return current.groups.size() >= maxGroups ? TABLE_FULL_REASON : null;
    }

    /** Test view: the group's structure epoch in this run, or -1 without an entry. */
    long structureEpoch(UUID groupId) {
        GroupState state = table().groups.get(groupId);
        return state == null ? -1L : state.structureEpoch();
    }

    /** Test view: the counted plain commits of the group in this run, or -1 without an entry. */
    long commits(UUID groupId) {
        GroupState state = table().groups.get(groupId);
        return state == null ? -1L : state.commits();
    }

    /** Test view: entries in this run's table. */
    int groups() {
        return table().groups.size();
    }

    private Table table() {
        long now = generation.getAsLong();
        while (true) {
            Table current = table.get();
            if (current.generation == now) return current;
            Table fresh = new Table(now);
            if (table.compareAndSet(current, fresh)) return fresh;
        }
    }

    /** The group's entry, made if absent; null when the table is full. */
    private GroupState entry(Table current, UUID groupId) {
        GroupState state = current.groups.get(groupId);
        if (state != null) return state;
        if (current.groups.size() >= maxGroups) return null;
        return current.groups.computeIfAbsent(groupId, ignored -> new GroupState());
    }

    /** One run's groups. */
    private static final class Table {
        final long generation;
        final ConcurrentHashMap<UUID, GroupState> groups = new ConcurrentHashMap<>();
        private volatile String poisoned;

        Table(long generation) {
            this.generation = generation;
        }

        /**
         * Every group of the run is refused from now on; the first reason is kept. True only for the call that set
         * it. Allocates nothing.
         */
        synchronized boolean poison(String reason) {
            if (poisoned != null) return false;
            poisoned = reason;
            return true;
        }

        String poisoned() {
            return poisoned;
        }
    }

    /** One group's state in one run. Every method holds the state's lock and allocates nothing. */
    private static final class GroupState {
        private String refusal;
        private long structureEpoch;
        private long manualActionEpoch;
        private long commits;

        /** Refuses the group; the first reason is kept. True only for the call that refused it. */
        synchronized boolean refuse(String reason) {
            if (refusal != null) return false;
            refusal = reason;
            return true;
        }

        synchronized String refusal() {
            return refusal;
        }

        /** The epoch a new session starts from, or -1 when refused. */
        synchronized long openEpoch() {
            return refusal == null ? structureEpoch : -1L;
        }

        /** Structure epoch +1. True when it was saturated and the group is now refused. */
        synchronized boolean structureChanged() {
            if (refusal != null) return false;
            if (structureEpoch == Long.MAX_VALUE) {
                refusal = SATURATED_REASON;
                return true;
            }
            structureEpoch++;
            return false;
        }

        synchronized boolean structureUnchanged(long epoch) {
            return refusal == null && structureEpoch == epoch;
        }

        synchronized long structureEpoch() {
            return structureEpoch;
        }

        /** Manual-action epoch +1 and the new value, or -1 when refused (saturation refuses the group). */
        synchronized long beginManualAction() {
            if (refusal != null) return -1L;
            if (manualActionEpoch == Long.MAX_VALUE) {
                refusal = SATURATED_REASON;
                return -1L;
            }
            return ++manualActionEpoch;
        }

        synchronized boolean manualActionUnchanged(long epoch) {
            return refusal == null && manualActionEpoch == epoch;
        }

        synchronized void counted() {
            if (commits != Long.MAX_VALUE) commits++;
        }

        synchronized long commits() {
            return commits;
        }
    }
}
