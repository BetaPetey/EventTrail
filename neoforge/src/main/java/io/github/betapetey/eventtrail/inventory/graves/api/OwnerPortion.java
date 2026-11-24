package io.github.betapetey.eventtrail.inventory.graves.api;

import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;

import java.util.Objects;

/**
 * Who owns how many units of one item identity in a grave (graves design r6, 1.1-4, Q194 B). The default is one
 * portion: the grave's owner, every unit. Simple Tomb supplies its ledger's portions (2.3-6.6): its owner buckets, and
 * its {@code unlabelled} and {@code unknown} units as owner-unknown portions. A take of an identity is split over its
 * portions with the pile rule (1.3-8, 1.6.3), where every share but {@link Share#UNKNOWN} is a named bucket.
 *
 * <p>{@code owner} is the value stored in the row's cause: the grave owner's for {@link Share#GRAVE_OWNER} (null when
 * the owner read found none), the bucket's owner for {@link Share#OTHER_OWNER}, and null for the two owner-unknown
 * shares.</p>
 */
public record OwnerPortion(InventoryItemIdentity item, Share share, ShownCause.Owner owner, long count) {

    /** Whose units these are. */
    public enum Share {
        /** The grave owner's own units: providerId {@code eventtrail.grave.<modid>}. */
        GRAVE_OWNER,
        /** Another known owner's units (Simple Tomb's ground pull): providerId {@code eventtrail.grave.<modid>.pulled}. */
        OTHER_OWNER,
        /** Units known to come from no labelled origin: a named bucket with no owner, {@code .pulled}. */
        UNLABELLED,
        /** Units whose bucket can no longer be told: no certain part in the pile rule, no owner, {@code .pulled}. */
        UNKNOWN
    }

    public OwnerPortion {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(share, "share");
        if (count <= 0L) throw new IllegalArgumentException("count must be positive");
        if (share == Share.OTHER_OWNER && owner == null) {
            throw new IllegalArgumentException("Another owner's portion names that owner");
        }
        if ((share == Share.UNLABELLED || share == Share.UNKNOWN) && owner != null) {
            throw new IllegalArgumentException("An owner-unknown portion has no owner");
        }
    }

    /** The default single portion: the grave's owner, every unit (1.1-4). */
    public static OwnerPortion whole(InventoryItemIdentity item, GraveOwnerFacts graveOwner, long count) {
        return new OwnerPortion(item, Share.GRAVE_OWNER, Objects.requireNonNull(graveOwner, "graveOwner").owner(), count);
    }

    /** True when its rows use the {@code .pulled} provider (2.3-6.6): every share but the grave owner's. */
    public boolean pulled() {
        return share != Share.GRAVE_OWNER;
    }

    /** True when the pile rule gives this portion a certain part (1.6.3's named buckets). */
    public boolean named() {
        return share != Share.UNKNOWN;
    }
}
