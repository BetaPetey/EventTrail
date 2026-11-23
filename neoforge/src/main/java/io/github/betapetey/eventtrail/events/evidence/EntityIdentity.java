package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable technical and presentation identity for one entity observed by an
 * audit boundary. The record retains no live Minecraft entity.
 */
public record EntityIdentity(
        ResourceLocation typeId,
        UUID entityId,
        String canonicalName,
        String translatedTypeName,
        String displayName,
        String customName,
        EntityKind kind) {

    public EntityIdentity {
        typeId = Objects.requireNonNull(typeId, "typeId");
        entityId = Objects.requireNonNull(entityId, "entityId");
        canonicalName = boundedOptional(canonicalName);
        translatedTypeName = boundedOptional(translatedTypeName);
        displayName = boundedOptional(displayName);
        customName = boundedOptional(customName);
        kind = Objects.requireNonNull(kind, "kind");
    }

    public EntityIdentity(
            ResourceLocation typeId,
            UUID entityId,
            String translatedTypeName,
            String displayName,
            String customName,
            EntityKind kind) {
        this(typeId, entityId, null, translatedTypeName, displayName, customName, kind);
    }

    /** Presentation never replaces the canonical registry identity. */
    public String preferredDisplayName() {
        if (customName != null) return customName;
        if (displayName != null) return displayName;
        if (canonicalName != null) return canonicalName;
        if (translatedTypeName != null) return translatedTypeName;
        return typeId.toString();
    }

    /**
     * Removes a synthetic player's identifying fields while retaining the
     * truthful fact that the cause was an automated player.
     */
    public EntityIdentity redactSyntheticPlayer() {
        if (kind != EntityKind.SYNTHETIC_PLAYER) return this;
        return new EntityIdentity(
                typeId,
                new UUID(0L, 0L),
                null,
                "Automated player",
                "Automated player",
                null,
                EntityKind.REDACTED_SYNTHETIC_PLAYER);
    }

    private static String boundedOptional(String value) {
        return EventTrailTextBounds.literalDisplayText(value);
    }

    public enum EntityKind {
        PLAYER,
        SYNTHETIC_PLAYER,
        REDACTED_SYNTHETIC_PLAYER,
        PROJECTILE,
        TAMED_ANIMAL,
        LIVING_ENTITY,
        DEVICE_OR_AUTOMATION,
        OTHER
    }
}
