package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPhase;
import net.minecraft.core.BlockPos;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable query specification for structured EventTrail lookups. */
public record AdvancedQuery(
        long fromTime,
        long throughTime,
        long highWaterId,
        String world,
        BlockPos center,
        int radius,
        String playerName,
        ActionSelection actionSelection,
        String block,
        List<String> blockNeedles,
        String item,
        List<String> itemNeedles,
        String entity,
        ContainerAccessPhase accessPhase,
        int perPage,
        int page,
        String exactName
) {
    /** A query without an exact-name filter (every caller before the {@code name:} filter). */
    public AdvancedQuery(
            long fromTime,
            long throughTime,
            long highWaterId,
            String world,
            BlockPos center,
            int radius,
            String playerName,
            ActionSelection actionSelection,
            String block,
            List<String> blockNeedles,
            String item,
            List<String> itemNeedles,
            String entity,
            ContainerAccessPhase accessPhase,
            int perPage,
            int page) {
        this(fromTime, throughTime, highWaterId, world, center, radius, playerName, actionSelection,
                block, blockNeedles, item, itemNeedles, entity, accessPhase, perPage, page, null);
    }

    public AdvancedQuery {
        highWaterId = Math.max(0L, highWaterId);
        actionSelection = Objects.requireNonNull(actionSelection, "actionSelection");
        if (accessPhase != null
                && !actionSelection.isOnly(AuditAction.CONTAINER_ACCESS)) {
            throw new IllegalArgumentException(
                    "Container access phase requires a container-access-only action selection");
        }
        // Retain the internal family-only query API, not the removed public
        // access: syntax. It cannot contradict a phase-aware public selection.
        if (accessPhase != null && !actionSelection.containerAccessPhases().isEmpty()
                && !actionSelection.containerAccessPhases().contains(accessPhase)) {
            throw new IllegalArgumentException("Conflicting container access phases");
        }
        blockNeedles = blockNeedles == null ? List.of() : List.copyOf(blockNeedles);
        itemNeedles = itemNeedles == null ? List.of() : List.copyOf(itemNeedles);
        // name: compares the logged name exactly, so the text is kept as given (no trim, no case change).
        if (exactName != null && exactName.isBlank()) {
            throw new IllegalArgumentException("An exact-name filter needs a name");
        }
    }

    public AdvancedQuery withHighWaterId(long capturedHighWaterId) {
        return new AdvancedQuery(
                fromTime, throughTime, capturedHighWaterId, world, center, radius,
                playerName, actionSelection, block, blockNeedles, item, itemNeedles,
                entity, accessPhase, perPage, page, exactName);
    }

    public AdvancedQuery withPage(int requestedPage) {
        return withPaging(perPage, requestedPage);
    }

    /**
     * This query with another page size and page; every other component unchanged (lane xn r2, review-01
     * NIT-4: copy through here, never through the 16-argument constructor, which drops {@code exactName}).
     */
    public AdvancedQuery withPaging(int requestedPerPage, int requestedPage) {
        return new AdvancedQuery(
                fromTime, throughTime, highWaterId, world, center, radius,
                playerName, actionSelection, block, blockNeedles, item, itemNeedles,
                entity, accessPhase, requestedPerPage, requestedPage, exactName);
    }

    /**
     * The actions whose rows carry the logged block or container name that {@code name:} matches (block break,
     * place and explosion; container open/close, deposit and withdrawal). A lookup restricted to other actions
     * only can never match a name.
     */
    public static final Set<AuditAction> EXACT_NAME_ACTIONS = Set.copyOf(EnumSet.of(
            AuditAction.BLOCK_BREAK, AuditAction.BLOCK_PLACE, AuditAction.EXPLOSION,
            AuditAction.CONTAINER_ACCESS, AuditAction.CONTAINER_DEPOSIT, AuditAction.CONTAINER_WITHDRAWAL));

    /** False when the action selection is restricted to actions none of which {@code name:} can match. */
    public static boolean exactNameCanMatch(ActionSelection selection) {
        return !selection.restricted() || selection.actions().stream().anyMatch(EXACT_NAME_ACTIONS::contains);
    }
}
