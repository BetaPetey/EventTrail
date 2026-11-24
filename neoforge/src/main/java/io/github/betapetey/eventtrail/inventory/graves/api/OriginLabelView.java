package io.github.betapetey.eventtrail.inventory.graves.api;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An item entity's origin label, read or about to be written (graves design r6, 1.6.1; read by the pickup, 3.2). A
 * label is not a row: it only lets a player's later pickup row say whose the items were. Its stored form (the
 * {@code eventtrail:origin} compound, {@code v=1}) belongs to the piece that writes it; this is the shape the pieces
 * exchange.
 *
 * <p>Invariant: the bucket counts plus {@code unlabelled} plus {@code unknown} equal {@code seen}. Every bucket holds at
 * least one unit, so a vanilla-legal stack holds at most {@value #MAX_BUCKETS} buckets. Kind MIXED is not stored; see
 * {@link #mixed()}.</p>
 */
public record OriginLabelView(
        ResourceLocation itemId,
        String componentsSha256,
        int seen,
        List<Bucket> buckets,
        int unlabelled,
        int unknown) {

    /** The bucket cap (1.6.1). Past it the writer folds the smallest buckets into {@code unknown}. */
    public static final int MAX_BUCKETS = 99;

    /** Where a bucket's units came from. MIXED is a row form only, never a stored kind. */
    public enum Kind { GRAVE, DEATH_DROPS }

    /** One known origin: kind, owner uuid, owner name, grave id and death time (each nullable), and its count. */
    public record Bucket(Kind kind, UUID ownerUuid, String ownerName, ResourceLocation graveId, Long deathEpochMillis,
            int count) {
        public Bucket {
            Objects.requireNonNull(kind, "kind");
            if (count < 1) throw new IllegalArgumentException("A bucket holds at least one unit");
        }
    }

    public OriginLabelView {
        Objects.requireNonNull(itemId, "itemId");
        if (componentsSha256 == null || !componentsSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("componentsSha256 must be a lowercase SHA-256");
        }
        buckets = List.copyOf(buckets);
        if (buckets.size() > MAX_BUCKETS) throw new IllegalArgumentException("More than " + MAX_BUCKETS + " buckets");
        if (seen < 0 || unlabelled < 0 || unknown < 0) throw new IllegalArgumentException("Counts must not be negative");
        long total = (long) unlabelled + unknown;
        for (Bucket bucket : buckets) total += bucket.count();
        if (total != seen) throw new IllegalArgumentException("Buckets plus unlabelled plus unknown must equal seen");
    }

    /** A pile is mixed when it has more than one bucket, or any unlabelled or unknown units (1.6.1). */
    public boolean mixed() {
        return buckets.size() > 1 || unlabelled > 0 || unknown > 0;
    }
}
