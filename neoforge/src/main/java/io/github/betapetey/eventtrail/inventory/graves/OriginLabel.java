package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.inventory.graves.api.OriginLabelView;
import io.github.betapetey.eventtrail.inventory.graves.api.OwnerPortion;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The origin label's rules (graves design r6, 1.6.1 to 1.6.3), on {@link OriginLabelView} values: the identity of a
 * stack, building a label (equal buckets summed, past {@value OriginLabelView#MAX_BUCKETS} buckets the smallest folded
 * into {@code unknown}), reconciling a label with its entity's real stack, and a take by the pile rule.
 */
public final class OriginLabel {
    private OriginLabel() {
    }

    /** The stack's item registry id. */
    public static ResourceLocation itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(Objects.requireNonNull(stack, "stack").getItem());
    }

    /**
     * The lowercase SHA-256 of the stack's canonical component data (1.6.1 {@code identity}): its component patch
     * encoded with the registries, as sorted-key SNBT text in UTF-8. Two stacks of one item and equal components give
     * equal values; the count is not part of it.
     */
    public static String componentsSha256(ItemStack stack, HolderLookup.Provider registries) {
        Objects.requireNonNull(stack, "stack");
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, Objects.requireNonNull(registries, "registries"));
        Tag patch = DataComponentPatch.CODEC.encodeStart(ops, stack.getComponentsPatch()).getOrThrow();
        return sha256(patch.toString());
    }

    /**
     * A label of {@code buckets} (each of count at least 1; equal kind, owner, name, grave and death summed, in
     * first-seen order), {@code unlabelled} and {@code unknown}; {@code seen} is their sum. Past
     * {@value OriginLabelView#MAX_BUCKETS} buckets the smallest (the later of equal ones first) are folded into
     * {@code unknown}, counted {@code origin-buckets-folded} once.
     */
    public static OriginLabelView build(ResourceLocation itemId, String componentsSha256,
            List<OriginLabelView.Bucket> buckets, int unlabelled, int unknown) {
        Map<BucketKey, Integer> merged = new LinkedHashMap<>();
        for (OriginLabelView.Bucket bucket : buckets) {
            merged.merge(BucketKey.of(bucket), bucket.count(), Math::addExact);
        }
        List<OriginLabelView.Bucket> list = new ArrayList<>(merged.size());
        merged.forEach((key, count) -> list.add(key.bucket(count)));
        int folded = 0;
        if (list.size() > OriginLabelView.MAX_BUCKETS) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) order.add(i);
            order.sort(Comparator.<Integer>comparingInt(i -> list.get(i).count()).thenComparing(i -> -i));
            List<Integer> drop = order.subList(0, list.size() - OriginLabelView.MAX_BUCKETS);
            List<OriginLabelView.Bucket> kept = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (drop.contains(i)) {
                    folded = Math.addExact(folded, list.get(i).count());
                } else {
                    kept.add(list.get(i));
                }
            }
            list.clear();
            list.addAll(kept);
            GraveCounts.count(GraveCounts.Reason.ORIGIN_BUCKETS_FOLDED);
        }
        int unknownTotal = Math.addExact(unknown, folded);
        long seen = (long) unlabelled + unknownTotal;
        for (OriginLabelView.Bucket bucket : list) seen += bucket.count();
        return new OriginLabelView(itemId, componentsSha256, Math.toIntExact(seen), list, unlabelled, unknownTotal);
    }

    /** One death's drop: a DEATH_DROPS bucket of the dead player, with the death time (3.1). */
    public static OriginLabelView.Bucket deathBucket(UUID owner, String name, long deathEpochMillis, int count) {
        return new OriginLabelView.Bucket(OriginLabelView.Kind.DEATH_DROPS, owner, name, null, deathEpochMillis, count);
    }

    /**
     * The label of a spilled entity from a pile take's parts (1.6.4): a grave owner's or another owner's part is a
     * GRAVE bucket of that owner and {@code graveId} (the owner's UUID only when it is validated); an UNLABELLED part
     * goes to {@code unlabelled}, an UNKNOWN part to {@code unknown}. {@code ownUnits} are the taker's own units in the
     * entity, which carry no origin ({@code unlabelled}).
     */
    public static OriginLabelView fromParts(ResourceLocation itemId, String componentsSha256, ResourceLocation graveId,
            List<OwnerPortion> parts, int ownUnits) {
        List<OriginLabelView.Bucket> buckets = new ArrayList<>();
        int unlabelled = ownUnits;
        int unknown = 0;
        for (OwnerPortion part : parts) {
            int count = Math.toIntExact(part.count());
            switch (part.share()) {
                case GRAVE_OWNER, OTHER_OWNER -> buckets.add(graveBucket(part.owner(), graveId, count));
                case UNLABELLED -> unlabelled = Math.addExact(unlabelled, count);
                case UNKNOWN -> unknown = Math.addExact(unknown, count);
            }
        }
        return build(itemId, componentsSha256, buckets, unlabelled, unknown);
    }

    /** A label whose every unit is {@code unknown} (a copied spill, 1.3-3). */
    public static OriginLabelView allUnknown(ResourceLocation itemId, String componentsSha256, int count) {
        return new OriginLabelView(itemId, componentsSha256, count, List.of(), 0, count);
    }

    /** A reconcile's result: the label to keep (null when it was removed) and whether the identity changed. */
    public record Reconciled(OriginLabelView label, boolean identityChanged) {
    }

    /**
     * Reconciles {@code label} with its entity's current stack (1.6.2). A different item or component hash removes the
     * label ({@code origin-identity-changed}, counted). Else {@code count > seen} adds the difference to
     * {@code unlabelled}; {@code count < seen} takes {@code seen - count} by the pile rule and discards it; then
     * {@code seen = count}.
     */
    public static Reconciled reconcile(OriginLabelView label, ResourceLocation itemId, String componentsSha256,
            int count) {
        Objects.requireNonNull(label, "label");
        if (count < 0) throw new IllegalArgumentException("count must not be negative");
        if (!label.itemId().equals(itemId) || !label.componentsSha256().equals(componentsSha256)) {
            GraveCounts.count(GraveCounts.Reason.ORIGIN_IDENTITY_CHANGED);
            return new Reconciled(null, true);
        }
        if (count == label.seen()) return new Reconciled(label, false);
        if (count > label.seen()) {
            return new Reconciled(new OriginLabelView(label.itemId(), label.componentsSha256(), count, label.buckets(),
                    Math.addExact(label.unlabelled(), count - label.seen()), label.unknown()), false);
        }
        return new Reconciled(take(label, label.seen() - count).remaining(), false);
    }

    /**
     * A take's parts by the pile rule: each bucket's certain part (in the label's order), the unlabelled bucket's
     * certain part, the uncertain part, and the label that stays.
     */
    public record Take(List<OriginLabelView.Bucket> certain, int unlabelledCertain, int uncertain,
            OriginLabelView remaining) {
        public Take {
            certain = List.copyOf(certain);
            Objects.requireNonNull(remaining, "remaining");
        }
    }

    /** A take of {@code p} units ({@code 0..seen}) from {@code label} by the pile rule (1.6.3). */
    public static Take take(OriginLabelView label, int p) {
        Objects.requireNonNull(label, "label");
        List<Long> named = new ArrayList<>(label.buckets().size() + 1);
        for (OriginLabelView.Bucket bucket : label.buckets()) named.add((long) bucket.count());
        named.add((long) label.unlabelled());
        PileRule.Split split = PileRule.take(named, label.unknown(), p);
        List<OriginLabelView.Bucket> certain = new ArrayList<>();
        List<OriginLabelView.Bucket> kept = new ArrayList<>();
        for (int i = 0; i < label.buckets().size(); i++) {
            OriginLabelView.Bucket bucket = label.buckets().get(i);
            int part = Math.toIntExact(split.certain().get(i));
            if (part > 0) certain.add(withCount(bucket, part));
            int stays = Math.toIntExact(split.keptNamed().get(i));
            if (stays > 0) kept.add(withCount(bucket, stays));
        }
        int last = label.buckets().size();
        int keptUnlabelled = Math.toIntExact(split.keptNamed().get(last));
        int keptUnknown = Math.toIntExact(split.keptUnknown());
        OriginLabelView remaining = new OriginLabelView(label.itemId(), label.componentsSha256(), label.seen() - p,
                kept, keptUnlabelled, keptUnknown);
        return new Take(certain, Math.toIntExact(split.certain().get(last)), Math.toIntExact(split.uncertain()),
                remaining);
    }

    private static OriginLabelView.Bucket graveBucket(ShownCause.Owner owner, ResourceLocation graveId, int count) {
        UUID uuid = null;
        String name = null;
        if (owner != null && owner.state() != ShownCause.OwnerState.DEFAULT) {
            name = owner.name().isEmpty() ? null : owner.name();
            if (owner.state() == ShownCause.OwnerState.VALIDATED) {
                try {
                    uuid = UUID.fromString(owner.uuid());
                } catch (IllegalArgumentException malformed) {
                    uuid = null;
                    name = null;
                }
            }
        }
        return new OriginLabelView.Bucket(OriginLabelView.Kind.GRAVE, uuid, name, graveId, null, count);
    }

    private static OriginLabelView.Bucket withCount(OriginLabelView.Bucket bucket, int count) {
        return new OriginLabelView.Bucket(bucket.kind(), bucket.ownerUuid(), bucket.ownerName(), bucket.graveId(),
                bucket.deathEpochMillis(), count);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is not available", missing);
        }
    }

    private record BucketKey(OriginLabelView.Kind kind, UUID owner, String name, ResourceLocation grave, Long death) {
        static BucketKey of(OriginLabelView.Bucket bucket) {
            return new BucketKey(bucket.kind(), bucket.ownerUuid(), bucket.ownerName(), bucket.graveId(),
                    bucket.deathEpochMillis());
        }

        OriginLabelView.Bucket bucket(int count) {
            return new OriginLabelView.Bucket(kind, owner, name, grave, death, count);
        }
    }
}
