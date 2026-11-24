package io.github.betapetey.eventtrail.inventory.graves.api;

import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Which grave a take, spill or label names (graves design r6, 1.1-1 and 1.4). One of three forms: a block grave
 * (WORLD_BLOCK, key {@code "<dim>|x,y,z"}), an entity grave (ENTITY_BOUND at the entity's block position, key
 * {@code <modid>:grave-entity:<entity uuid>}) or an item grave (ITEM_BOUND, key
 * {@code <modid>:grave-item:<unique id>}).
 *
 * <p>{@code graveId} is the block, entity-type or item registry id, shown as the cause's id. {@code display} is the
 * grave's display from DisplayNameResolver (the game's own name first, a custom name alongside, never instead); the
 * adapter resolves it, this record only requires it. Every form carries a block position, because every current row
 * needs an access position.</p>
 */
public record GraveIdentity(
        Kind kind,
        String dimensionId,
        BlockPos position,
        ResourceLocation graveId,
        String display,
        String stableKey) {

    /** The three grave forms, with the backing kind and the cause subject each one uses. */
    public enum Kind {
        BLOCK(BackingContextKind.WORLD_BLOCK, ShownCause.Subject.BLOCK),
        ENTITY(BackingContextKind.ENTITY_BOUND, ShownCause.Subject.ENTITY),
        ITEM(BackingContextKind.ITEM_BOUND, ShownCause.Subject.ITEM);

        private final BackingContextKind backing;
        private final ShownCause.Subject subject;

        Kind(BackingContextKind backing, ShownCause.Subject subject) {
            this.backing = backing;
            this.subject = subject;
        }
    }

    public GraveIdentity {
        Objects.requireNonNull(kind, "kind");
        dimensionId = requireText(dimensionId, "dimensionId");
        position = Objects.requireNonNull(position, "position").immutable();
        Objects.requireNonNull(graveId, "graveId");
        display = requireText(display, "display");
        stableKey = requireText(stableKey, "stableKey");
    }

    /** A block grave: key {@code "<dim>|x,y,z"}, as FunctionalStorage's and PlayerActionScopes' world-block keys. */
    public static GraveIdentity block(String dimensionId, BlockPos position, ResourceLocation blockId, String display) {
        Objects.requireNonNull(position, "position");
        return new GraveIdentity(Kind.BLOCK, dimensionId, position, blockId, display,
                dimensionId + "|" + position.getX() + "," + position.getY() + "," + position.getZ());
    }

    /** An entity grave (Corpse): key {@code <modid>:grave-entity:<entity uuid>}. */
    public static GraveIdentity entity(String modId, String dimensionId, BlockPos position, ResourceLocation entityTypeId,
            String display, UUID entityUuid) {
        return new GraveIdentity(Kind.ENTITY, dimensionId, position, entityTypeId, display,
                requireModId(modId) + ":grave-entity:" + Objects.requireNonNull(entityUuid, "entityUuid"));
    }

    /** An item grave (no mod uses it yet): key {@code <modid>:grave-item:<the item's own unique id>}. */
    public static GraveIdentity item(String modId, String dimensionId, BlockPos position, ResourceLocation itemId,
            String display, String uniqueId) {
        return new GraveIdentity(Kind.ITEM, dimensionId, position, itemId, display,
                requireModId(modId) + ":grave-item:" + requireText(uniqueId, "uniqueId"));
    }

    public BackingContextKind backingContextKind() {
        return kind.backing;
    }

    public ShownCause.Subject subject() {
        return kind.subject;
    }

    /** The row's target: the grave's position, its display as the label, its backing kind and stable key. */
    public ContainerTarget target() {
        return ContainerTarget.block(dimensionId, position, display, kind.backing, stableKey);
    }

    private static String requireModId(String modId) {
        if (modId == null || !modId.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("modId must be a registry namespace");
        }
        return modId;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
