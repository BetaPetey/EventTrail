package io.github.betapetey.eventtrail.events.evidence;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared accountable-possession contract. Loader adapters supply immutable
 * before/after facts; this verifier never receives live players or inventories.
 */
public final class ItemPickupEvidenceVerifier {
    private ItemPickupEvidenceVerifier() {
    }

    public static Optional<CommittedItemPickupEvidence> verifyPlayerInventoryGain(
            long timestamp,
            UUID itemEntityId,
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos position,
            ItemStack original,
            ItemStack remainder,
            long possessionBefore,
            long possessionAfter) {
        if (timestamp < 0L
                || itemEntityId == null
                || actor == null
                || actor.playerId() == null
                || actor.causality() != ActorProvenance.Causality.DIRECT
                || dimensionId == null
                || position == null
                || possessionBefore < 0L
                || possessionAfter < possessionBefore) {
            return Optional.empty();
        }

        ItemStack accepted = acceptedStack(original, remainder);
        if (accepted.isEmpty()
                || possessionAfter - possessionBefore != accepted.getCount()) {
            return Optional.empty();
        }

        return Optional.of(new CommittedItemPickupEvidence(
                timestamp,
                itemEntityId,
                actor,
                dimensionId,
                position,
                accepted,
                CommittedItemPickupEvidence.ProofKind.PLAYER_INVENTORY_NET_GAIN));
    }

    /** Returns an immutable accepted copy or empty when count/identity is unverifiable. */
    public static ItemStack acceptedStack(ItemStack original, ItemStack remainder) {
        if (original == null || original.isEmpty() || remainder == null) {
            return ItemStack.EMPTY;
        }

        int acceptedCount;
        if (remainder.isEmpty()) {
            acceptedCount = original.getCount();
        } else {
            if (!ItemStack.isSameItemSameComponents(original, remainder)) {
                return ItemStack.EMPTY;
            }
            acceptedCount = original.getCount() - remainder.getCount();
        }
        return acceptedCount <= 0
                ? ItemStack.EMPTY
                : original.copyWithCount(acceptedCount);
    }
}
