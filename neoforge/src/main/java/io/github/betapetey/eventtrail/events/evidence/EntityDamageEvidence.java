package io.github.betapetey.eventtrail.events.evidence;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Immutable DamageSource facts captured before the live entities can change. */
public record EntityDamageEvidence(
        ResourceLocation damageTypeId,
        String damageMessageId,
        EntityIdentity directEntity,
        EntityIdentity causingEntity,
        ResourceLocation mechanismId,
        ItemStack suppliedWeapon,
        boolean suppliedWeaponUnavailable,
        boolean directEntityUnavailable,
        boolean causingEntityUnavailable) {

    public EntityDamageEvidence {
        damageTypeId = Objects.requireNonNull(damageTypeId, "damageTypeId");
        damageMessageId = io.github.betapetey.eventtrail.util.EventTrailTextBounds.literalDisplayText(damageMessageId);
        suppliedWeapon = suppliedWeapon == null ? ItemStack.EMPTY : suppliedWeapon.copy();
        if (suppliedWeaponUnavailable && !suppliedWeapon.isEmpty()) {
            throw new IllegalArgumentException(
                    "An unavailable source weapon cannot also contain a supplied stack");
        }
        if (directEntityUnavailable && directEntity != null) {
            throw new IllegalArgumentException(
                    "Unavailable direct-entity evidence cannot contain an identity");
        }
        if (causingEntityUnavailable && causingEntity != null) {
            throw new IllegalArgumentException(
                    "Unavailable causing-entity evidence cannot contain an identity");
        }
    }

    public EntityDamageEvidence(
            ResourceLocation damageTypeId,
            EntityIdentity directEntity,
            EntityIdentity causingEntity,
            ResourceLocation mechanismId,
            ItemStack suppliedWeapon) {
        this(
                damageTypeId,
                null,
                directEntity,
                causingEntity,
                mechanismId,
                suppliedWeapon,
                false,
                false,
                false);
    }

    public EntityDamageEvidence(
            ResourceLocation damageTypeId,
            EntityIdentity directEntity,
            EntityIdentity causingEntity,
            ResourceLocation mechanismId,
            ItemStack suppliedWeapon,
            boolean suppliedWeaponUnavailable) {
        this(damageTypeId, null, directEntity, causingEntity, mechanismId, suppliedWeapon,
                suppliedWeaponUnavailable, false, false);
    }

    public EntityDamageEvidence(
            ResourceLocation damageTypeId,
            String damageMessageId,
            EntityIdentity directEntity,
            EntityIdentity causingEntity,
            ResourceLocation mechanismId,
            ItemStack suppliedWeapon,
            boolean suppliedWeaponUnavailable) {
        this(damageTypeId, damageMessageId, directEntity, causingEntity, mechanismId,
                suppliedWeapon, suppliedWeaponUnavailable, false, false);
    }

    /** Never exposes the record's retained mutable stack instance. */
    @Override
    public ItemStack suppliedWeapon() {
        return suppliedWeapon.copy();
    }

    public boolean hasSuppliedWeapon() {
        return !suppliedWeapon.isEmpty();
    }

    public EntityDamageEvidence redactExcludedSyntheticPlayers() {
        return new EntityDamageEvidence(
                damageTypeId,
                damageMessageId,
                redact(directEntity),
                redact(causingEntity),
                mechanismId,
                suppliedWeapon,
                suppliedWeaponUnavailable,
                directEntityUnavailable,
                causingEntityUnavailable);
    }

    private static EntityIdentity redact(EntityIdentity identity) {
        return identity == null ? null : identity.redactSyntheticPlayer();
    }
}
