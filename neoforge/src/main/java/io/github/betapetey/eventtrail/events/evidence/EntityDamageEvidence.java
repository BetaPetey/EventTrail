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
        boolean causingEntityUnavailable,
        WeaponSource suppliedWeaponSource) {

    /**
     * Where a supplied weapon came from: the damage source, or the killer's held item (kill-weapon fix, Owner Q204 A
     * and Q326 B: only when the damage's weapon read threw and its direct hitter is a player who hit for themselves;
     * never for a mob's or a pet's hit, nor on damage with no direct entity).
     */
    public enum WeaponSource { DAMAGE, HELD }

    public EntityDamageEvidence {
        damageTypeId = Objects.requireNonNull(damageTypeId, "damageTypeId");
        damageMessageId = io.github.betapetey.eventtrail.util.EventTrailTextBounds.literalDisplayText(damageMessageId);
        suppliedWeapon = suppliedWeapon == null ? ItemStack.EMPTY : suppliedWeapon.copy();
        if (suppliedWeaponUnavailable && !suppliedWeapon.isEmpty()) {
            throw new IllegalArgumentException(
                    "An unavailable source weapon cannot also contain a supplied stack");
        }
        if (suppliedWeaponSource == null || suppliedWeapon.isEmpty()) suppliedWeaponSource = WeaponSource.DAMAGE;
        if (directEntityUnavailable && directEntity != null) {
            throw new IllegalArgumentException(
                    "Unavailable direct-entity evidence cannot contain an identity");
        }
        if (causingEntityUnavailable && causingEntity != null) {
            throw new IllegalArgumentException(
                    "Unavailable causing-entity evidence cannot contain an identity");
        }
    }

    /** The shape before the kill-weapon fix: a weapon from the damage source. */
    public EntityDamageEvidence(
            ResourceLocation damageTypeId,
            String damageMessageId,
            EntityIdentity directEntity,
            EntityIdentity causingEntity,
            ResourceLocation mechanismId,
            ItemStack suppliedWeapon,
            boolean suppliedWeaponUnavailable,
            boolean directEntityUnavailable,
            boolean causingEntityUnavailable) {
        this(damageTypeId, damageMessageId, directEntity, causingEntity, mechanismId, suppliedWeapon,
                suppliedWeaponUnavailable, directEntityUnavailable, causingEntityUnavailable, WeaponSource.DAMAGE);
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

    /** Whether the supplied weapon is the killer's held item rather than the damage source's weapon. */
    public boolean suppliedWeaponHeld() {
        return suppliedWeaponSource == WeaponSource.HELD;
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
                causingEntityUnavailable,
                suppliedWeaponSource);
    }

    private static EntityIdentity redact(EntityIdentity identity) {
        return identity == null ? null : identity.redactSyntheticPlayer();
    }
}
