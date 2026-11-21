package io.github.betapetey.eventtrail.inventory.access;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

/**
 * Loader-neutral exact-menu lifecycle tracker.
 *
 * <p>All state is guarded by this tracker's lock so it remains correct if a
 * future platform adapter changes event delivery. Production NeoForge calls
 * remain O(1) on the server thread and retain at most one session per
 * player. The only code run outside the lock is a session's authority-lost
 * callback (see {@link #close(UUID, Object, long)}).</p>
 */
public final class ContainerAccessSessionTracker {
    private static final Runnable NO_CALLBACK = () -> { };

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Supplier<UUID> sessionIds;

    private long opened;
    private long closed;
    private long duplicateOpens;
    private long unresolvedTargets;
    private long unmatchedCloses;
    private long abandoned;

    public ContainerAccessSessionTracker() {
        this(UUID::randomUUID);
    }

    public ContainerAccessSessionTracker(Supplier<UUID> sessionIds) {
        this.sessionIds = Objects.requireNonNull(sessionIds, "sessionIds");
    }

    public synchronized boolean isDuplicate(UUID playerId, Object exactMenuIdentity) {
        if (playerId == null || exactMenuIdentity == null) return false;
        Session current = sessions.get(playerId);
        return current != null && current.menuIdentity == exactMenuIdentity;
    }

    /**
     * Abandons an older menu when the server has already installed a different
     * menu for the same player, without manufacturing CLOSE evidence.
     */
    public synchronized boolean abandonIfDifferent(
            UUID playerId, Object exactMenuIdentity) {
        if (playerId == null || exactMenuIdentity == null) return false;
        Session current = sessions.get(playerId);
        if (current == null || current.menuIdentity == exactMenuIdentity) return false;
        sessions.remove(playerId);
        abandoned++;
        return true;
    }

    public synchronized OpenResult open(
            ContainerAccessActor actor,
            Object exactMenuIdentity,
            ContainerAccessTargetSnapshot target,
            long timestamp) {
        return open(actor, exactMenuIdentity, target, timestamp, () -> true);
    }

    /** Optional owner guard for private backings that can be invalidated while a menu stays open. */
    public synchronized OpenResult open(
            ContainerAccessActor actor, Object exactMenuIdentity,
            ContainerAccessTargetSnapshot target, long timestamp, BooleanSupplier currentAuthority) {
        return open(actor, exactMenuIdentity, target, timestamp, currentAuthority, NO_CALLBACK);
    }

    /**
     * As {@link #open(ContainerAccessActor, Object, ContainerAccessTargetSnapshot, long, BooleanSupplier)}, with a
     * callback run once, after this tracker's lock is released, when the close of this session finds
     * {@code currentAuthority} false or throwing (the CLOSE row is then not written). No other path runs it: not an
     * unmatched close, an abandon, a logout, a replaced or cleared session.
     */
    public synchronized OpenResult open(
            ContainerAccessActor actor, Object exactMenuIdentity,
            ContainerAccessTargetSnapshot target, long timestamp, BooleanSupplier currentAuthority,
            Runnable onAuthorityLost) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(exactMenuIdentity, "exactMenuIdentity");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(currentAuthority, "currentAuthority");
        Objects.requireNonNull(onAuthorityLost, "onAuthorityLost");

        Session current = sessions.get(actor.playerId());
        if (current != null && current.menuIdentity == exactMenuIdentity) {
            duplicateOpens++;
            return new OpenResult(OpenOutcome.DUPLICATE, Optional.empty());
        }

        OpenOutcome outcome = OpenOutcome.OPENED;
        if (current != null) {
            sessions.remove(actor.playerId());
            abandoned++;
            outcome = OpenOutcome.REPLACED_UNMATCHED;
        }

        UUID sessionId = Objects.requireNonNull(sessionIds.get(), "sessionIds supplied null");
        Session accepted = new Session(
                actor, exactMenuIdentity, sessionId, target, Math.max(0L, timestamp), currentAuthority,
                onAuthorityLost);
        sessions.put(actor.playerId(), accepted);
        opened++;
        return new OpenResult(
                outcome,
                Optional.of(accepted.evidence(ContainerAccessPhase.OPEN, accepted.openedAt)));
    }

    /**
     * Ends the exact session and returns its CLOSE evidence while its authority still holds. When the authority is
     * false or throws, the session is counted abandoned, no CLOSE evidence is returned, and the session's
     * authority-lost callback runs once after the lock is released; anything it throws is contained.
     */
    public Optional<ContainerAccessEvidence> close(
            UUID playerId, Object exactMenuIdentity, long timestamp) {
        Runnable authorityLost;
        synchronized (this) {
            if (playerId == null || exactMenuIdentity == null) {
                unmatchedCloses++;
                return Optional.empty();
            }
            Session current = sessions.get(playerId);
            if (current == null || current.menuIdentity != exactMenuIdentity) {
                unmatchedCloses++;
                return Optional.empty();
            }
            sessions.remove(playerId);
            boolean authority;
            try {
                authority = current.currentAuthority.getAsBoolean();
            } catch (RuntimeException | LinkageError invalidAuthority) {
                authority = false;
            }
            if (authority) {
                closed++;
                return Optional.of(current.evidence(
                        ContainerAccessPhase.CLOSE, Math.max(current.openedAt, timestamp)));
            }
            abandoned++;
            authorityLost = current.onAuthorityLost;
        }
        try {
            authorityLost.run();
        } catch (RuntimeException | LinkageError ignored) {
            // The callback only reports; a failure in it never reaches the close (a VirtualMachineError does).
        }
        return Optional.empty();
    }

    /**
     * Clears a disconnected player's session without manufacturing a CLOSE fact.
     *
     * <p>NeoForge does not guarantee a container-close event during disconnect,
     * so the disconnect itself is not evidence that the player closed the menu.
     * A graceful client close is recorded through {@link #close(UUID, Object, long)}
     * before this cleanup runs.</p>
     */
    public synchronized boolean abandonForLogout(UUID playerId) {
        return abandon(playerId);
    }

    /** Removes one session without fabricating a CLOSE fact. */
    public synchronized boolean abandon(UUID playerId) {
        if (playerId == null || sessions.remove(playerId) == null) return false;
        abandoned++;
        return true;
    }

    /** Records one provider/target resolution that deliberately failed closed. */
    public synchronized void unresolvedTarget() {
        unresolvedTargets++;
    }

    /**
     * Server stop/crash cleanup never manufactures CLOSE evidence. Returns the number of sessions dropped, so the
     * platform owner can count them (af-3, re-check m4).
     */
    public synchronized int clear() {
        int dropped = sessions.size();
        abandoned += dropped;
        sessions.clear();
        return dropped;
    }

    public synchronized void reset() {
        sessions.clear();
        opened = 0L;
        closed = 0L;
        duplicateOpens = 0L;
        unresolvedTargets = 0L;
        unmatchedCloses = 0L;
        abandoned = 0L;
    }

    public synchronized Metrics metrics() {
        return new Metrics(
                sessions.size(), opened, closed, duplicateOpens,
                unresolvedTargets, unmatchedCloses, abandoned);
    }

    public enum OpenOutcome { OPENED, DUPLICATE, REPLACED_UNMATCHED }

    public record OpenResult(
            OpenOutcome outcome, Optional<ContainerAccessEvidence> evidence) {
        public OpenResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            evidence = evidence == null ? Optional.empty() : evidence;
        }
    }

    public record Metrics(
            int activeSessions,
            long opened,
            long closed,
            long duplicateOpens,
            long unresolvedTargets,
            long unmatchedCloses,
            long abandoned) {}

    private record Session(
            ContainerAccessActor actor,
            Object menuIdentity,
            UUID sessionId,
            ContainerAccessTargetSnapshot target,
            long openedAt,
            BooleanSupplier currentAuthority,
            Runnable onAuthorityLost) {
        private ContainerAccessEvidence evidence(ContainerAccessPhase phase, long timestamp) {
            return new ContainerAccessEvidence(
                    timestamp, openedAt, sessionId, phase, actor, target);
        }
    }
}
