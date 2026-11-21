package io.github.betapetey.eventtrail.commands.investigation;

import io.github.betapetey.eventtrail.commands.investigation.PageState.RequestKind;
import io.github.betapetey.eventtrail.commands.investigation.PlayerInvestigationState.InspectMode;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import java.util.UUID;

/**
 * Server-thread-owned investigation state with one value per player.
 *
 * <p>Planning an asynchronous request is side-effect free. Only an accepted
 * dispatcher admission applies its transition and clears the previous page
 * owner. A rejected admission therefore leaves the old owner intact.</p>
 */
public final class PlayerInvestigationRegistry<I, L> {
    private final Map<UUID, PlayerInvestigationState<I, L>> players = new HashMap<>();
    private long generationSequence;

    public Optional<PlayerInvestigationState<I, L>> state(UUID playerId) {
        return Optional.ofNullable(players.get(requirePlayer(playerId)));
    }

    public boolean isInspecting(UUID playerId) {
        PlayerInvestigationState<I, L> state = players.get(requirePlayer(playerId));
        return state != null && state.inspecting();
    }

    public PageState.Owner pageOwner(UUID playerId) {
        PlayerInvestigationState<I, L> state = players.get(requirePlayer(playerId));
        return state == null ? PageState.Owner.NONE : state.pageState().owner();
    }

    @SuppressWarnings("unchecked")
    public Optional<I> inspectSnapshot(UUID playerId) {
        PlayerInvestigationState<I, L> state = players.get(requirePlayer(playerId));
        if (state == null
                || !(state.pageState() instanceof PageState.InspectResults<?, ?> inspect)) {
            return Optional.empty();
        }
        return Optional.of((I) inspect.snapshot());
    }

    @SuppressWarnings("unchecked")
    public Optional<L> lookupContext(UUID playerId) {
        PlayerInvestigationState<I, L> state = players.get(requirePlayer(playerId));
        if (state == null
                || !(state.pageState() instanceof PageState.LookupResults<?, ?> lookup)) {
            return Optional.empty();
        }
        return Optional.of((L) lookup.context());
    }

    public int playerCount() {
        return players.size();
    }

    /** A successful inspect toggle invalidates every prior page owner/request. */
    public PlayerInvestigationState<I, L> toggleInspect(UUID playerId) {
        UUID id = requirePlayer(playerId);
        PlayerInvestigationState<I, L> current = players.get(id);
        InspectMode nextMode = current == null
                ? InspectMode.ENABLED
                : current.inspectMode().toggled();
        PlayerInvestigationState<I, L> updated = new PlayerInvestigationState<>(
                nextGeneration(), nextMode, PageState.empty());
        players.put(id, updated);
        return updated;
    }

    /** Plans a position inspection only while inspect mode is currently enabled. */
    public Optional<RequestTransition> planInspect(UUID playerId) {
        UUID id = requirePlayer(playerId);
        PlayerInvestigationState<I, L> current = players.get(id);
        if (current == null || !current.inspecting()) return Optional.empty();
        return Optional.of(plan(id, RequestKind.INSPECT_POSITION, current.generation()));
    }

    /** Plans a new structured lookup without changing the existing page owner. */
    public RequestTransition planInitialLookup(UUID playerId) {
        UUID id = requirePlayer(playerId);
        PlayerInvestigationState<I, L> current = players.get(id);
        return plan(id, RequestKind.LOOKUP_INITIAL, generationOf(current));
    }

    /** Captures the current lookup context before an admitted page request clears it. */
    @SuppressWarnings("unchecked")
    public Optional<PlannedLookupPage<L>> planLookupPage(UUID playerId) {
        UUID id = requirePlayer(playerId);
        PlayerInvestigationState<I, L> current = players.get(id);
        if (current == null
                || !(current.pageState() instanceof PageState.LookupResults<?, ?> lookup)) {
            return Optional.empty();
        }
        RequestTransition transition = plan(id, RequestKind.LOOKUP_PAGE, current.generation());
        return Optional.of(new PlannedLookupPage<>(transition, (L) lookup.context()));
    }

    /**
     * Applies a previously planned request after query admission succeeds.
     * The exact expected generation and request kind must still be current.
     */
    public boolean admit(RequestTransition transition) {
        Objects.requireNonNull(transition, "transition");
        PlayerInvestigationState<I, L> current = players.get(transition.playerId());
        if (generationOf(current) != transition.expectedGeneration()) return false;
        if (transition.kind() == RequestKind.INSPECT_POSITION
                && (current == null || !current.inspecting())) return false;
        if (transition.kind() == RequestKind.LOOKUP_PAGE
                && (current == null
                    || !(current.pageState() instanceof PageState.LookupResults<?, ?>))) {
            return false;
        }

        InspectMode mode = current == null ? InspectMode.DISABLED : current.inspectMode();
        players.put(transition.playerId(), new PlayerInvestigationState<>(
                transition.requestId(), mode,
                new PageState.Pending<>(transition.kind(), transition.requestId())));
        return true;
    }

    public boolean publishInspect(RequestTransition transition, I snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        PlayerInvestigationState<I, L> current = matchingPending(
                transition, RequestKind.INSPECT_POSITION);
        if (current == null || !current.inspecting()) return false;
        players.put(transition.playerId(), new PlayerInvestigationState<>(
                nextGeneration(), current.inspectMode(), new PageState.InspectResults<>(snapshot)));
        return true;
    }

    public boolean publishLookup(RequestTransition transition, L context) {
        Objects.requireNonNull(context, "context");
        if (transition == null
                || (transition.kind() != RequestKind.LOOKUP_INITIAL
                    && transition.kind() != RequestKind.LOOKUP_PAGE)) return false;
        PlayerInvestigationState<I, L> current = matchingPending(transition, transition.kind());
        if (current == null) return false;
        players.put(transition.playerId(), new PlayerInvestigationState<>(
                nextGeneration(), current.inspectMode(), new PageState.LookupResults<>(context)));
        return true;
    }

    /** True only while this exact admitted request still owns the tagged pending state. */
    public boolean isPending(RequestTransition transition) {
        if (transition == null) return false;
        return matchingPending(transition, transition.kind()) != null;
    }

    /** An admitted request failure leaves no resurrected prior page owner. */
    public boolean fail(RequestTransition transition) {
        if (transition == null) return false;
        PlayerInvestigationState<I, L> current = matchingPending(transition, transition.kind());
        if (current == null) return false;
        players.put(transition.playerId(), new PlayerInvestigationState<>(
                nextGeneration(), current.inspectMode(), PageState.empty()));
        return true;
    }

    /** Disables inspect and invalidates only inspect-owned or inspect-pending state. */
    public boolean revokeInspect(UUID playerId) {
        UUID id = requirePlayer(playerId);
        PlayerInvestigationState<I, L> current = players.get(id);
        if (current == null) return false;
        boolean inspectPage = current.pageState() instanceof PageState.InspectResults<?, ?>;
        boolean inspectPending = pendingKind(current.pageState(), RequestKind.INSPECT_POSITION);
        if (!current.inspecting() && !inspectPage && !inspectPending) return false;
        PageState<I, L> retained = inspectPage || inspectPending
                ? PageState.empty()
                : current.pageState();
        players.put(id, new PlayerInvestigationState<>(
                nextGeneration(), InspectMode.DISABLED, retained));
        return true;
    }

    /** Invalidates only lookup-owned or lookup-pending state. */
    public boolean revokeLookup(UUID playerId) {
        UUID id = requirePlayer(playerId);
        PlayerInvestigationState<I, L> current = players.get(id);
        if (current == null) return false;
        boolean lookupPage = current.pageState() instanceof PageState.LookupResults<?, ?>;
        boolean lookupPending = pendingKind(current.pageState(), RequestKind.LOOKUP_INITIAL)
                || pendingKind(current.pageState(), RequestKind.LOOKUP_PAGE);
        if (!lookupPage && !lookupPending) return false;
        players.put(id, new PlayerInvestigationState<>(
                nextGeneration(), current.inspectMode(), PageState.empty()));
        return true;
    }

    /**
     * Invalidates pageable results and a page-continuation request, while
     * leaving an admitted initial lookup/inspect free to render its first page.
     */
    public boolean revokePage(UUID playerId) {
        UUID id = requirePlayer(playerId);
        PlayerInvestigationState<I, L> current = players.get(id);
        if (current == null) return false;
        boolean results = current.pageState().owner() != PageState.Owner.NONE;
        boolean pagePending = pendingKind(current.pageState(), RequestKind.LOOKUP_PAGE);
        if (!results && !pagePending) return false;
        players.put(id, new PlayerInvestigationState<>(
                nextGeneration(), current.inspectMode(), PageState.empty()));
        return true;
    }

    /** Logout is a hard boundary; a reconnect starts with a fresh generation. */
    public void logout(UUID playerId) {
        players.remove(requirePlayer(playerId));
    }

    /** Server stop clears players but intentionally does not reuse generation IDs. */
    public void clearAll() {
        players.clear();
    }

    private RequestTransition plan(UUID playerId, RequestKind kind, long expectedGeneration) {
        return new RequestTransition(
                playerId, kind, expectedGeneration, nextGeneration());
    }

    private PlayerInvestigationState<I, L> matchingPending(
            RequestTransition transition, RequestKind kind) {
        if (transition == null || transition.kind() != kind) return null;
        PlayerInvestigationState<I, L> current = players.get(transition.playerId());
        if (current == null
                || !(current.pageState() instanceof PageState.Pending<?, ?> pending)
                || pending.kind() != kind
                || pending.requestId() != transition.requestId()) return null;
        return current;
    }

    private static boolean pendingKind(PageState<?, ?> state, RequestKind kind) {
        return state instanceof PageState.Pending<?, ?> pending && pending.kind() == kind;
    }

    private long nextGeneration() {
        if (generationSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Investigation generation space exhausted");
        }
        return ++generationSequence;
    }

    private static long generationOf(PlayerInvestigationState<?, ?> state) {
        return state == null ? 0L : state.generation();
    }

    private static UUID requirePlayer(UUID playerId) {
        return Objects.requireNonNull(playerId, "playerId");
    }

    public record RequestTransition(
            UUID playerId,
            RequestKind kind,
            long expectedGeneration,
            long requestId) {
        public RequestTransition {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(kind, "kind");
            if (expectedGeneration < 0L) {
                throw new IllegalArgumentException("Expected generation cannot be negative");
            }
            if (requestId <= 0L) {
                throw new IllegalArgumentException("Request IDs must be positive");
            }
        }
    }

    public record PlannedLookupPage<L>(RequestTransition transition, L context) {
        public PlannedLookupPage {
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(context, "context");
            if (transition.kind() != RequestKind.LOOKUP_PAGE) {
                throw new IllegalArgumentException("Lookup page plans require a page request");
            }
        }
    }
}
