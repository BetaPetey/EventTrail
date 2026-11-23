package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.ItemDropAuditSession;
import io.github.betapetey.eventtrail.events.evidence.ItemDropEvidenceNormalizer;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * NeoForge 1.21.1 boundary for explicit player discards admitted to a world.
 * State exists only on the server thread around one synchronous packet/menu
 * operation; unscoped programmatic item spawns cannot create player rows.
 */
public final class NeoForgeItemDropAuditBridge {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final ThreadLocal<ActiveOperation> ACTIVE = new ThreadLocal<>();
    private static final Boundary INACTIVE = new Boundary(null);

    private NeoForgeItemDropAuditBridge() {
    }

    /** Captures the exact selected-stack quantity owned by a Q/drop-all packet. */
    public static Boundary beginKeyDiscard(ServerPlayer player, boolean dropAll) {
        if (player == null) return INACTIVE;
        ItemStack selected = player.getInventory().getSelected();
        if (selected.isEmpty()) return INACTIVE;
        int count = dropAll ? selected.getCount() : 1;
        return beginExplicitDiscard(player, selected.copyWithCount(count));
    }

    /** Captures the exact slot/cursor quantity owned by a vanilla discard click. */
    public static Boundary beginMenuDiscard(
            ServerPlayer player,
            AbstractContainerMenu menu,
            int slot,
            int button,
            ClickType clickType) {
        if (!isExplicitMenuDiscard(menu, slot, button, clickType)) {
            return INACTIVE;
        }
        ItemStack source;
        int count;
        if (clickType == ClickType.THROW) {
            source = menu.getSlot(slot).getItem();
            count = button == 0 ? 1 : source.getCount();
        } else {
            source = menu.getCarried();
            count = button == 0 ? source.getCount() : 1;
        }
        return source.isEmpty()
                ? INACTIVE
                : beginExplicitDiscard(player, source.copyWithCount(count));
    }

    /** Captures the exact client-supplied creative discard stack. */
    public static Boundary beginCreativeDiscard(ServerPlayer player, ItemStack stack) {
        return stack == null || stack.isEmpty()
                ? INACTIVE
                : beginExplicitDiscard(player, stack.copy());
    }

    private static Boundary beginExplicitDiscard(
            ServerPlayer player,
            ItemStack expectedStack) {
        ActiveOperation existing = ACTIVE.get();
        if (existing != null) {
            existing.session.rejectNestedOperation();
            return INACTIVE;
        }

        try {
            if (player == null
                    || !(player.level() instanceof ServerLevel level)
                    || !level.getServer().isSameThread()
                    || !EventTrailConfig.TRACK_ITEM_DROPS.get()
                    || !EventTrailManager.getInstance().admitCapture()) {
                return INACTIVE;
            }

            Optional<ActorProvenance> actor = NeoForgePlayerProvenance.capture(
                    player,
                    ActorProvenance.Causality.DIRECT,
                    null);
            if (actor.isEmpty()) {
                return INACTIVE;
            }

            ItemDropAuditSession session = new ItemDropAuditSession(
                    System.currentTimeMillis(),
                    actor.get(),
                    level.dimension().location(),
                    expectedStack);
            ActiveOperation operation = new ActiveOperation(player, level, session);
            // The boundary is built before ACTIVE is set, so nothing that can fail lies between setting ACTIVE and
            // handing out the only object that ends it. Otherwise a failure there would leave ACTIVE set with no
            // boundary, and every later explicit discard on this thread would be rejected as nested.
            Boundary boundary = new Boundary(operation);
            ACTIVE.set(operation);
            return boundary;
        } catch (RuntimeException | LinkageError failure) {
            ACTIVE.remove();
            logFailure("begin explicit item discard", failure);
            return INACTIVE;
        }
    }

    /** Correlates the exact pre-hook candidate before a listener can mutate it. */
    public static void observeTossCandidate(
            net.minecraft.world.entity.player.Player player,
            ItemStack candidateStack) {
        ActiveOperation operation = ACTIVE.get();
        if (operation == null) return;

        try {
            operation.session.acceptCandidate(
                    player == operation.player ? operation.session.actor() : null,
                    candidateStack);
        } catch (RuntimeException | LinkageError failure) {
            operation.session.abort();
            logFailure("correlate item-discard candidate", failure);
        }
    }

    /**
     * Observes CommonHooks only after its toss hook and addFreshEntity call
     * return. A non-null entity is still rejected unless the exact instance is
     * registered in the operation's ServerLevel.
     */
    public static void observeTossResult(net.minecraft.world.entity.player.Player player, ItemEntity entity) {
        ActiveOperation operation = ACTIVE.get();
        if (operation == null) return;

        try {
            boolean actorMatches = player == operation.player;
            boolean admitted = actorMatches
                    && entity != null
                    && entity.level() == operation.level
                    && entity.isAddedToLevel()
                    && !entity.isRemoved()
                    && operation.level.getEntity(entity.getUUID()) == entity;
            operation.session.observe(
                    actorMatches ? operation.session.actor() : null,
                    entity == null ? null : entity.getUUID(),
                    entity == null ? null : entity.level().dimension().location(),
                    entity == null ? null : entity.blockPosition(),
                    entity == null ? ItemStack.EMPTY : entity.getItem(),
                    admitted);
        } catch (RuntimeException | LinkageError failure) {
            operation.session.abort();
            logFailure("observe admitted item discard", failure);
        }
    }

    /** Finishes only after the enclosing packet/menu operation returns normally. */
    public static void finish(Boundary boundary) {
        ActiveOperation operation = boundary == null ? null : boundary.operation;
        if (operation == null) return;

        try {
            if (ACTIVE.get() != operation) {
                operation.session.abort();
                return;
            }
            boolean succeeded = operation.player.level() == operation.level
                    && operation.level.getServer().isSameThread()
                    && EventTrailManager.getInstance().admitCapture();
            ItemDropAuditSession.Completion completion = operation.session.complete(
                    succeeded,
                    operation.session.actor(),
                    operation.level.dimension().location());

            ACTIVE.remove();
            completion.evidence().ifPresent(evidence -> EventTrailManager.getInstance().log(
                    ItemDropEvidenceNormalizer.toAuditRecord(
                            evidence,
                            EventTrailManager.getInstance().canAcceptAuditWrite())));
        } catch (RuntimeException | LinkageError failure) {
            operation.session.abort();
            logFailure("finalize admitted item discard", failure);
        } finally {
            if (ACTIVE.get() == operation) {
                ACTIVE.remove();
            }
        }
    }

    /** Expires an input operation whose enclosing call did not return normally. */
    public static void abort(Boundary boundary) {
        ActiveOperation operation = boundary == null ? null : boundary.operation;
        if (operation == null) return;
        try {
            operation.session.abort();
        } catch (RuntimeException | LinkageError failure) {
            logFailure("abort item discard", failure);
        } finally {
            if (ACTIVE.get() == operation) {
                ACTIVE.remove();
            }
        }
    }

    /** Mirrors the two vanilla menu branches that explicitly discard items. */
    public static boolean isExplicitMenuDiscard(
            AbstractContainerMenu menu,
            int slot,
            int button,
            ClickType clickType) {
        if (menu == null || clickType == null || (button != 0 && button != 1)) {
            return false;
        }
        if (clickType == ClickType.THROW) {
            return slot >= 0
                    && slot < menu.slots.size()
                    && menu.getCarried().isEmpty();
        }
        return slot == -999
                && (clickType == ClickType.PICKUP || clickType == ClickType.QUICK_MOVE)
                && !menu.getCarried().isEmpty();
    }

    private static void logFailure(String phase, Throwable failure) {
        LOGGER.error("Could not {} ({})", phase, EventTrailDiagnostics.safeFailureSummary(failure));
    }

    /** Opaque token bound to one exact synchronous player-input operation. */
    public static final class Boundary {
        private final ActiveOperation operation;

        private Boundary(ActiveOperation operation) {
            this.operation = operation;
        }
    }

    private record ActiveOperation(
            ServerPlayer player,
            ServerLevel level,
            ItemDropAuditSession session) {
    }
}
