package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.ItemPickupEvidenceNormalizer;
import io.github.betapetey.eventtrail.events.evidence.ItemPickupEvidenceVerifier;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NeoForge 1.21.1 boundary around the one player-inventory insertion in
 * {@link ItemEntity#playerTouch}. Direct backpack, block, and network inserts
 * never cross this boundary and therefore cannot acquire player attribution.
 */
public final class NeoForgeItemPickupAuditBridge {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final RateLimitedDiagnosticGate FAILURE_DIAGNOSTICS =
            new RateLimitedDiagnosticGate(60_000L);
    private static final Boundary INACTIVE = new Boundary(null);

    private NeoForgeItemPickupAuditBridge() {
    }

    /** Captures immutable post-Pre input and exact accountable possession count. */
    public static Boundary begin(
            ItemEntity itemEntity,
            Inventory inventory,
            ItemStack sourceStack) {
        try {
            if (inventory == null
                    || !(inventory.player instanceof ServerPlayer player)
                    || itemEntity == null
                    || sourceStack == null
                    || sourceStack.isEmpty()
                    || itemEntity.getItem() != sourceStack
                    || player.getInventory() != inventory
                    || !(itemEntity.level() instanceof ServerLevel level)
                    || player.level() != level
                    || !level.getServer().isSameThread()
                    || !itemEntity.isAddedToLevel()
                    || itemEntity.isRemoved()
                    || level.getEntity(itemEntity.getUUID()) != itemEntity) {
                return INACTIVE;
            }

            ItemStack original = sourceStack.copy();
            return new Boundary(new PendingInsertion(
                    System.currentTimeMillis(),
                    itemEntity,
                    inventory,
                    player,
                    sourceStack,
                    level,
                    resolveAuditActor(player),
                    itemEntity.getUUID(),
                    itemEntity.blockPosition(),
                    original,
                    exactPossessionCount(inventory, original)));
        } catch (RuntimeException | LinkageError failure) {
            logFailure("capture item-pickup input", failure);
            return INACTIVE;
        }
    }

    /**
     * Verifies the exact original-minus-remainder delta against the player's
     * component-aware inventory gain immediately after Inventory.add returns.
     */
    public static void finish(
            Boundary boundary,
            ItemEntity itemEntity,
            Inventory inventory,
            ItemStack remainder) {
        PendingInsertion pending = boundary == null ? null : boundary.claim();
        if (pending == null) return;

        try {
            if (itemEntity != pending.itemEntity
                    || inventory != pending.inventory
                    || inventory.player != pending.player
                    || remainder != pending.sourceStack
                    || itemEntity.getItem() != pending.sourceStack
                    || itemEntity.level() != pending.level
                    || pending.player.level() != pending.level
                    || !pending.level.getServer().isSameThread()
                    || !itemEntity.isAddedToLevel()
                    || itemEntity.isRemoved()
                    || pending.level.getEntity(pending.itemEntityId) != itemEntity) {
                return;
            }

            long after = exactPossessionCount(inventory, pending.originalStack);
            ItemStack remainderSnapshot = remainder == null ? null : remainder.copy();
            ItemStack accepted = ItemPickupEvidenceVerifier.acceptedStack(
                    pending.originalStack, remainderSnapshot);
            if (accepted.isEmpty()
                    || after < pending.possessionBefore
                    || after - pending.possessionBefore != accepted.getCount()) {
                return;
            }

            try {
                UniversalContainerTracker.onCommittedPlayerInventoryMutation(pending.player);
            } catch (RuntimeException | LinkageError failure) {
                logFailure("resynchronize container possession", failure);
            }
            if (pending.actor == null
                    || !EventTrailConfig.TRACK_ITEM_PICKUPS.get()
                    || pending.actor.actorKind() == ActorProvenance.ActorKind.SYNTHETIC_PLAYER
                    || !EventTrailManager.getInstance().admitCapture()) {
                return;
            }

            ItemPickupEvidenceVerifier.verifyPlayerInventoryGain(
                            pending.timestamp,
                            pending.itemEntityId,
                            pending.actor,
                            pending.level.dimension().location(),
                            pending.position,
                            pending.originalStack,
                            remainderSnapshot,
                            pending.possessionBefore,
                            after)
                    .map(evidence -> ItemPickupEvidenceNormalizer.toAuditRecord(
                            evidence,
                            EventTrailManager.getInstance().canAcceptAuditWrite()))
                    .ifPresent(entry -> EventTrailManager.getInstance().log(entry));
        } catch (RuntimeException | LinkageError failure) {
            logFailure("verify item-pickup possession", failure);
        }
    }

    static long exactPossessionCount(Inventory inventory, ItemStack identity) {
        if (inventory == null || identity == null || identity.isEmpty()) return 0L;
        long total = 0L;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, identity)) {
                total = Math.addExact(total, stack.getCount());
            }
        }
        return total;
    }

    private static ActorProvenance resolveAuditActor(ServerPlayer player) {
        try {
            return NeoForgePlayerProvenance.capture(
                            player,
                            ActorProvenance.Causality.DIRECT,
                            null)
                    .orElse(null);
        } catch (RuntimeException | LinkageError failure) {
            logFailure("resolve item-pickup actor", failure);
            return null;
        }
    }

    private static void logFailure(String phase, Throwable failure) {
        FAILURE_DIAGNOSTICS.acquire().ifPresent(emission -> {
            long suppressed = emission.suppressedSinceLastEmission();
            if (suppressed == 0L) {
                LOGGER.error("Could not {} ({})",
                        phase, EventTrailDiagnostics.safeFailureSummary(failure));
            } else {
                LOGGER.error("Could not {} ({}); suppressed {} similar pickup failure(s)",
                        phase, EventTrailDiagnostics.safeFailureSummary(failure), suppressed);
            }
        });
    }

    /** Opaque token valid only around one synchronous Inventory.add invocation. */
    public static final class Boundary {
        private PendingInsertion pending;

        private Boundary(PendingInsertion pending) {
            this.pending = pending;
        }

        private synchronized PendingInsertion claim() {
            PendingInsertion claimed = pending;
            pending = null;
            return claimed;
        }
    }

    private record PendingInsertion(
            long timestamp,
            ItemEntity itemEntity,
            Inventory inventory,
            ServerPlayer player,
            ItemStack sourceStack,
            ServerLevel level,
            ActorProvenance actor,
            java.util.UUID itemEntityId,
            net.minecraft.core.BlockPos position,
            ItemStack originalStack,
            long possessionBefore) {
        private PendingInsertion {
            position = position.immutable();
            originalStack = originalStack.copy();
        }

        @Override
        public ItemStack originalStack() {
            return originalStack.copy();
        }
    }
}
