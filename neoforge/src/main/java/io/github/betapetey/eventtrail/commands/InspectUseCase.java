package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.commands.investigation.PlayerInvestigationRegistry;
import io.github.betapetey.eventtrail.commands.investigation.PlayerInvestigationRegistry.RequestTransition;
import io.github.betapetey.eventtrail.commands.presentation.AuditEntryPresenter;
import io.github.betapetey.eventtrail.commands.presentation.ExplosionVerdicts;
import io.github.betapetey.eventtrail.commands.presentation.InspectResultsView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-thread inspect workflow. Minecraft events and chat messages remain in
 * the adapter; this service owns state transitions, query facts, and paging.
 */
final class InspectUseCase {
    private final PlayerInvestigationRegistry<InspectSnapshot, ?> sessions;
    private final AuditEntryPresenter presenter;

    InspectUseCase(
            PlayerInvestigationRegistry<InspectSnapshot, ?> sessions,
            AuditEntryPresenter presenter) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
    }

    ToggleResult toggle(UUID playerId, boolean ready) {
        if (!ready) return ToggleResult.NOT_READY;
        return sessions.toggleInspect(playerId).inspecting()
                ? ToggleResult.ENABLED
                : ToggleResult.DISABLED;
    }

    StartResult plan(
            UUID playerId,
            ResourceLocation dimensionId,
            BlockPos position,
            boolean permitted,
            boolean ready) {
        Objects.requireNonNull(playerId, "playerId");
        if (!permitted) {
            sessions.revokeInspect(playerId);
            return StartResult.failure(StartStatus.PERMISSION_DENIED);
        }
        if (!ready) return StartResult.failure(StartStatus.NOT_READY);
        Optional<RequestTransition> transition = sessions.planInspect(playerId);
        if (transition.isEmpty()) return StartResult.failure(StartStatus.NOT_INSPECTING);
        return StartResult.ready(new QueryRequest(
                transition.orElseThrow(),
                Objects.requireNonNull(dimensionId, "dimensionId"),
                Objects.requireNonNull(position, "position").immutable()));
    }

    boolean admit(QueryRequest request) {
        return sessions.admit(requireRequest(request).transition());
    }

    List<AuditRecord> execute(QueryRequest request, PositionHistoryQuery query) {
        QueryRequest current = requireRequest(request);
        return List.copyOf(Objects.requireNonNull(query, "query").query(
                current.position(), current.dimensionId(), 100));
    }

    /**
     * Explosion split (design-02 3.2; Advisor M-6 d, e): the inspect query plus the verdicts of the explosions
     * stored in parts among its rows, read here on the query thread, so later pages render the stored snapshot on
     * the server thread with no storage read. The id mark is taken before the rows, so a verdict counts only part
     * rows that existed when the inspect began (it can only err towards INCOMPLETE). A failed mark or verdict read
     * leaves those rows "completeness not checked"; it never fails the inspect.
     *
     * @param reader null when the storage cannot read part rows
     */
    InspectRows executeWithVerdicts(
            QueryRequest request,
            PositionHistoryQuery query,
            ExplosionVerdicts.PartReader reader,
            IdMark mark) {
        // "Query: N ms" (Owner 8:15 PM 11-22): the id mark, the rows and the verdicts, as lookup times its query.
        long started = System.nanoTime();
        Long idMark = null;
        if (reader != null && mark != null) {
            try {
                idMark = mark.capture();
            } catch (Exception | LinkageError failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                idMark = null;
            }
        }
        List<AuditRecord> records = execute(request, query);
        ExplosionVerdicts verdicts = idMark == null
                ? ExplosionVerdicts.none()
                : ExplosionVerdicts.read(records, reader, idMark);
        long queryMillis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return new InspectRows(records, verdicts, queryMillis);
    }

    Completion complete(
            QueryRequest request,
            InspectRows rows,
            boolean permitted,
            int perPage) {
        Objects.requireNonNull(rows, "rows");
        return complete(request, rows.records(), rows.verdicts(), rows.queryMillis(), permitted, perPage);
    }

    Completion complete(
            QueryRequest request,
            List<AuditRecord> records,
            boolean permitted,
            int perPage) {
        return complete(request, records, ExplosionVerdicts.none(), 0L, permitted, perPage);
    }

    private Completion complete(
            QueryRequest request,
            List<AuditRecord> records,
            ExplosionVerdicts verdicts,
            long queryMillis,
            boolean permitted,
            int perPage) {
        QueryRequest current = requireRequest(request);
        if (!sessions.isPending(current.transition())) return Completion.stale();
        if (!permitted) {
            sessions.revokeInspect(current.transition().playerId());
            return Completion.failure(CompletionStatus.PERMISSION_DENIED);
        }
        List<AuditRecord> snapshotRows = List.copyOf(records);
        if (snapshotRows.isEmpty()) {
            if (!sessions.fail(current.transition())) return Completion.stale();
            return Completion.failure(CompletionStatus.EMPTY);
        }
        InspectSnapshot snapshot = new InspectSnapshot(
                current.dimensionId(), current.position(), snapshotRows, verdicts, queryMillis);
        if (!sessions.publishInspect(current.transition(), snapshot)) return Completion.stale();
        return Completion.page(presenter.inspect(
                snapshot.dimensionId(), snapshot.position(), snapshot.records(), 1, perPage, snapshot.verdicts(),
                snapshot.queryMillis()));
    }

    Completion page(UUID playerId, int requestedPage, boolean permitted, int perPage) {
        Objects.requireNonNull(playerId, "playerId");
        if (!permitted) {
            sessions.revokeInspect(playerId);
            return Completion.failure(CompletionStatus.PERMISSION_DENIED);
        }
        InspectSnapshot snapshot = sessions.inspectSnapshot(playerId).orElse(null);
        if (snapshot == null || snapshot.records().isEmpty()) {
            return Completion.failure(CompletionStatus.EMPTY);
        }
        try {
            return Completion.page(presenter.inspect(
                    snapshot.dimensionId(), snapshot.position(), snapshot.records(),
                    requestedPage, perPage, snapshot.verdicts(), snapshot.queryMillis()));
        } catch (IllegalArgumentException invalidPage) {
            return Completion.failure(CompletionStatus.INVALID_PAGE);
        }
    }

    boolean fail(QueryRequest request) {
        return sessions.fail(requireRequest(request).transition());
    }

    boolean revalidate(UUID playerId, boolean permitted) {
        if (permitted) return sessions.isInspecting(playerId);
        sessions.revokeInspect(playerId);
        return false;
    }

    private static QueryRequest requireRequest(QueryRequest request) {
        return Objects.requireNonNull(request, "request");
    }

    @FunctionalInterface
    interface PositionHistoryQuery {
        List<AuditRecord> query(BlockPos position, ResourceLocation dimensionId, int limit);
    }

    /** The storage's id high-water mark (AdvancedLogStorage.captureHighWaterMark). */
    @FunctionalInterface
    interface IdMark {
        long capture() throws Exception;
    }

    /** The inspect rows, the verdicts read with them, and the query's time in ms. */
    record InspectRows(List<AuditRecord> records, ExplosionVerdicts verdicts, long queryMillis) {
        InspectRows {
            records = List.copyOf(records);
            verdicts = Objects.requireNonNull(verdicts, "verdicts");
            if (queryMillis < 0L) throw new IllegalArgumentException("queryMillis cannot be negative");
        }

        InspectRows(List<AuditRecord> records, ExplosionVerdicts verdicts) {
            this(records, verdicts, 0L);
        }
    }

    record QueryRequest(
            RequestTransition transition,
            ResourceLocation dimensionId,
            BlockPos position) {
        QueryRequest {
            transition = Objects.requireNonNull(transition, "transition");
            dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
            position = Objects.requireNonNull(position, "position").immutable();
        }
    }

    /** The published inspect history; {@code queryMillis} is the first query's time, shown on every page. */
    record InspectSnapshot(
            ResourceLocation dimensionId,
            BlockPos position,
            List<AuditRecord> records,
            ExplosionVerdicts verdicts,
            long queryMillis) {
        InspectSnapshot {
            dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
            position = Objects.requireNonNull(position, "position").immutable();
            records = List.copyOf(records);
            verdicts = Objects.requireNonNull(verdicts, "verdicts");
            if (queryMillis < 0L) throw new IllegalArgumentException("queryMillis cannot be negative");
        }

        InspectSnapshot(
                ResourceLocation dimensionId,
                BlockPos position,
                List<AuditRecord> records,
                ExplosionVerdicts verdicts) {
            this(dimensionId, position, records, verdicts, 0L);
        }

        /** A snapshot without verdicts: every row of an explosion stored in parts reads "not checked". */
        InspectSnapshot(ResourceLocation dimensionId, BlockPos position, List<AuditRecord> records) {
            this(dimensionId, position, records, ExplosionVerdicts.none());
        }
    }

    record StartResult(StartStatus status, QueryRequest request) {
        static StartResult ready(QueryRequest request) {
            return new StartResult(StartStatus.READY, Objects.requireNonNull(request));
        }

        static StartResult failure(StartStatus status) {
            if (status == StartStatus.READY) throw new IllegalArgumentException("READY needs a request");
            return new StartResult(Objects.requireNonNull(status), null);
        }

        boolean ready() {
            return status == StartStatus.READY && request != null;
        }
    }

    record Completion(CompletionStatus status, InspectResultsView view) {
        static Completion page(InspectResultsView view) {
            return new Completion(CompletionStatus.PAGE, Objects.requireNonNull(view));
        }

        static Completion stale() {
            return failure(CompletionStatus.STALE);
        }

        static Completion failure(CompletionStatus status) {
            if (status == CompletionStatus.PAGE) throw new IllegalArgumentException("PAGE needs a view");
            return new Completion(Objects.requireNonNull(status), null);
        }

        boolean hasPage() {
            return status == CompletionStatus.PAGE && view != null;
        }
    }

    enum ToggleResult { ENABLED, DISABLED, NOT_READY }

    enum StartStatus { READY, NOT_INSPECTING, PERMISSION_DENIED, NOT_READY }

    enum CompletionStatus { PAGE, EMPTY, INVALID_PAGE, PERMISSION_DENIED, STALE }
}
