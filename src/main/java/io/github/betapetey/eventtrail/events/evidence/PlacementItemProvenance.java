package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.util.EventTrailNbtUtil;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Immutable pre-use identity for the BlockItem that initiated one placement
 * operation. The live ItemStack is copied and serialized once, then discarded.
 *
 * <p>GF-BLOCKIDENTITY: {@code heldBlock} is the held BlockItem's block, captured at begin. It is never written to a
 * row; the placement session uses it only to keep a clicked snapshot whose Block did not change (slab on slab, a
 * second candle). {@code null} means no held block is known, and such a snapshot is then never kept.</p>
 */
public record PlacementItemProvenance(
        ResourceLocation canonicalItemId,
        String auditStack,
        Block heldBlock) {

    public PlacementItemProvenance {
        canonicalItemId = Objects.requireNonNull(canonicalItemId, "canonicalItemId");
        if (auditStack == null || auditStack.isBlank()) {
            throw new IllegalArgumentException("auditStack must not be blank");
        }
        if (auditStack.length() > EventTrailTextBounds.MAX_ITEM_METADATA_CHARS) {
            throw new IllegalArgumentException("auditStack exceeds the retained-size bound");
        }
    }

    /** Provenance with no known held block: a same-Block clicked snapshot is never kept as a placement. */
    public PlacementItemProvenance(ResourceLocation canonicalItemId, String auditStack) {
        this(canonicalItemId, auditStack, null);
    }

    /**
     * Captures a registered BlockItem before Minecraft mutates the held stack.
     * Non-block or unregistered items have no authoritative placement
     * provenance and therefore fail closed.
     */
    public static Optional<PlacementItemProvenance> capture(
            ItemStack stack,
            boolean captureFullMetadata) {
        return capture(
                stack,
                value -> EventTrailNbtUtil.stackToAuditString(value, captureFullMetadata));
    }

    static Optional<PlacementItemProvenance> capture(
            ItemStack stack,
            Function<ItemStack, String> serializer) {
        Objects.requireNonNull(serializer, "serializer");
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return Optional.empty();
        }

        ItemStack snapshot = stack.copy();
        Block heldBlock = blockItem.getBlock();
        return BuiltInRegistries.ITEM.getResourceKey(snapshot.getItem()).flatMap(key -> {
            try {
                return Optional.of(new PlacementItemProvenance(
                        key.location(), serializer.apply(snapshot), heldBlock));
            } catch (RuntimeException failure) {
                return Optional.empty();
            }
        });
    }
}
