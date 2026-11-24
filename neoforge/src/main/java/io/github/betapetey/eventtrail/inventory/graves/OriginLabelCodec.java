package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.inventory.graves.api.OriginLabelView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The stored form of an item entity's origin label (graves design r6, 1.6.1): a compound under
 * {@value #KEY} in the entity's NeoForge persistent data, {@code v=1}. It sits on the entity, never on the stack.
 *
 * <pre>
 * eventtrail:origin {
 *   v: 1, item: "minecraft:cobblestone", components: "&lt;sha256 hex&gt;", seen: 24,
 *   buckets: [ { kind: "GRAVE" | "DEATH_DROPS", owner: [I; 4 ints] (optional), name: "Bob" (optional),
 *                grave: "simpletomb:grave_simple" (optional), death: 1763900000000L (optional), count: 24 } ],
 *   unlabelled: 0, unknown: 0 }
 * </pre>
 *
 * <p>Reading: a label whose owner does not read as a UUID gives an owner-unknown bucket (owner and name both
 * dropped); a label whose version, kind or structure does not read (a missing or ill-typed field, a bucket count below
 * 1, a broken invariant, more than {@value OriginLabelView#MAX_BUCKETS} buckets) is ignored and counted
 * {@code origin-unreadable} (1.6.1; 3.2 item 7).</p>
 */
public final class OriginLabelCodec {
    /** The persistent-data key. */
    public static final String KEY = "eventtrail:origin";
    /** The stored form's version. */
    public static final int VERSION = 1;

    private static final String V = "v";
    private static final String ITEM = "item";
    private static final String COMPONENTS = "components";
    private static final String SEEN = "seen";
    private static final String BUCKETS = "buckets";
    private static final String UNLABELLED = "unlabelled";
    private static final String UNKNOWN = "unknown";
    private static final String KIND = "kind";
    private static final String OWNER = "owner";
    private static final String NAME = "name";
    private static final String GRAVE = "grave";
    private static final String DEATH = "death";
    private static final String COUNT = "count";

    private OriginLabelCodec() {
    }

    /** What a read found. */
    public enum Status {
        /** No label: an ordinary entity. */
        ABSENT,
        /** A readable label. */
        LABEL,
        /** A label that did not read: ignored, counted {@code origin-unreadable}. */
        UNREADABLE
    }

    /** A read's result; {@code label} is set only for {@link Status#LABEL}. */
    public record Decoded(Status status, OriginLabelView label) {
        public Decoded {
            Objects.requireNonNull(status, "status");
            if ((status == Status.LABEL) != (label != null)) {
                throw new IllegalArgumentException("Only a readable label carries one");
            }
        }
    }

    /** The stored compound of {@code label}. */
    public static CompoundTag encode(OriginLabelView label) {
        Objects.requireNonNull(label, "label");
        CompoundTag tag = new CompoundTag();
        tag.putInt(V, VERSION);
        tag.putString(ITEM, label.itemId().toString());
        tag.putString(COMPONENTS, label.componentsSha256());
        tag.putInt(SEEN, label.seen());
        ListTag buckets = new ListTag();
        for (OriginLabelView.Bucket bucket : label.buckets()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(KIND, bucket.kind().name());
            if (bucket.ownerUuid() != null) entry.put(OWNER, uuidTag(bucket.ownerUuid()));
            if (bucket.ownerName() != null) entry.putString(NAME, bucket.ownerName());
            if (bucket.graveId() != null) entry.putString(GRAVE, bucket.graveId().toString());
            if (bucket.deathEpochMillis() != null) entry.putLong(DEATH, bucket.deathEpochMillis());
            entry.putInt(COUNT, bucket.count());
            buckets.add(entry);
        }
        tag.put(BUCKETS, buckets);
        tag.putInt(UNLABELLED, label.unlabelled());
        tag.putInt(UNKNOWN, label.unknown());
        return tag;
    }

    /** Reads the label of an entity's persistent data (null reads as no label). */
    public static Decoded read(CompoundTag persistentData) {
        if (persistentData == null || !persistentData.contains(KEY)) return new Decoded(Status.ABSENT, null);
        Tag stored = persistentData.get(KEY);
        if (!(stored instanceof CompoundTag tag)) return unreadable();
        return decode(tag);
    }

    /** Reads one stored compound. */
    public static Decoded decode(CompoundTag tag) {
        try {
            if (!tag.contains(V, Tag.TAG_INT) || tag.getInt(V) != VERSION) return unreadable();
            if (!tag.contains(ITEM, Tag.TAG_STRING) || !tag.contains(COMPONENTS, Tag.TAG_STRING)
                    || !tag.contains(SEEN, Tag.TAG_INT) || !tag.contains(BUCKETS, Tag.TAG_LIST)
                    || !tag.contains(UNLABELLED, Tag.TAG_INT) || !tag.contains(UNKNOWN, Tag.TAG_INT)) {
                return unreadable();
            }
            ResourceLocation item = ResourceLocation.tryParse(tag.getString(ITEM));
            if (item == null) return unreadable();
            if (!(tag.get(BUCKETS) instanceof ListTag list)) return unreadable();
            if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) return unreadable();
            List<OriginLabelView.Bucket> buckets = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                OriginLabelView.Bucket bucket = bucket(list.getCompound(i));
                if (bucket == null) return unreadable();
                buckets.add(bucket);
            }
            return new Decoded(Status.LABEL, new OriginLabelView(item, tag.getString(COMPONENTS), tag.getInt(SEEN),
                    buckets, tag.getInt(UNLABELLED), tag.getInt(UNKNOWN)));
        } catch (RuntimeException structure) {
            return unreadable();
        }
    }

    /** Stores {@code label} in an entity's persistent data, replacing any earlier one. */
    public static void write(CompoundTag persistentData, OriginLabelView label) {
        Objects.requireNonNull(persistentData, "persistentData").put(KEY, encode(label));
    }

    /** Removes the label from an entity's persistent data. */
    public static void remove(CompoundTag persistentData) {
        if (persistentData != null) persistentData.remove(KEY);
    }

    private static OriginLabelView.Bucket bucket(CompoundTag entry) {
        if (!entry.contains(KIND, Tag.TAG_STRING) || !entry.contains(COUNT, Tag.TAG_INT)) return null;
        OriginLabelView.Kind kind;
        try {
            kind = OriginLabelView.Kind.valueOf(entry.getString(KIND));
        } catch (IllegalArgumentException unknownKind) {
            return null;
        }
        int count = entry.getInt(COUNT);
        if (count < 1) return null;
        ResourceLocation grave = null;
        if (entry.contains(GRAVE)) {
            if (!entry.contains(GRAVE, Tag.TAG_STRING)) return null;
            grave = ResourceLocation.tryParse(entry.getString(GRAVE));
            if (grave == null) return null;
        }
        Long death = null;
        if (entry.contains(DEATH)) {
            if (!entry.contains(DEATH, Tag.TAG_LONG)) return null;
            death = entry.getLong(DEATH);
        }
        UUID owner = null;
        String name = null;
        boolean ownerReadable = true;
        if (entry.contains(OWNER)) {
            owner = uuid(entry.get(OWNER));
            ownerReadable = owner != null;
        }
        if (entry.contains(NAME)) {
            if (entry.contains(NAME, Tag.TAG_STRING)) {
                name = entry.getString(NAME);
            } else {
                ownerReadable = false;
            }
        }
        if (!ownerReadable) {
            // 1.6.1 / 3.2 item 7: an owner that does not read gives an owner-unknown bucket.
            owner = null;
            name = null;
        }
        return new OriginLabelView.Bucket(kind, owner, name, grave, death, count);
    }

    private static Decoded unreadable() {
        GraveCounts.count(GraveCounts.Reason.ORIGIN_UNREADABLE);
        return new Decoded(Status.UNREADABLE, null);
    }

    private static IntArrayTag uuidTag(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new IntArrayTag(new int[] {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least});
    }

    private static UUID uuid(Tag tag) {
        if (!(tag instanceof IntArrayTag array)) return null;
        int[] ints = array.getAsIntArray();
        if (ints.length != 4) return null;
        return new UUID((long) ints[0] << 32 | ints[1] & 0xFFFFFFFFL, (long) ints[2] << 32 | ints[3] & 0xFFFFFFFFL);
    }
}
