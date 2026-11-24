package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveOwnerFacts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The owner read of graves design r6, 1.1-2: a per-mod key spec read from the grave's own saved data. Only the named
 * keys are read, and the result is one of 1.2's forms through {@link GraveOwners}.
 *
 * <p>The UUID keys are tried in the spec's order; the first present key decides. A present key that does not read as a
 * UUID (a wrong array length, an unparsable string, a wrong type) gives no owner, counted {@code grave-owner-malformed},
 * whatever name is stored: a key the mod wrote and EventTrail cannot read is never replaced by a name. With no UUID key
 * present, the mod's own "no owner set" form (Tombstone's absent {@code owner_id}) gives {@code noOwnerSet}; otherwise a
 * stored name gives the not-validated form, and no name gives no owner ({@code grave-owner-missing}). A UUID of all zero
 * bits is the mod's "no owner set" only for a spec that says so (GraveStone, if the fresh check confirms it).</p>
 */
public final class GraveOwnerReader {
    private GraveOwnerReader() {
    }

    /** How a UUID key is stored. */
    public enum UuidForm {
        /** An int array of 4 ({@code NbtUtils.createUUID}). */
        INT_ARRAY,
        /** A string in the canonical text form. */
        STRING,
        /** Two longs: {@code path} holds the most significant bits, {@code leastPath} the least. */
        MOST_LEAST
    }

    /** One UUID key: a dotted path inside the saved data ({@code "Death.PlayerUuid"}). */
    public record UuidKey(UuidForm form, String path, String leastPath) {
        public UuidKey {
            Objects.requireNonNull(form, "form");
            requirePath(path);
            if (form == UuidForm.MOST_LEAST) {
                requirePath(leastPath);
            } else if (leastPath != null) {
                throw new IllegalArgumentException("Only the two-long form has a second path");
            }
        }

        public static UuidKey intArray(String path) {
            return new UuidKey(UuidForm.INT_ARRAY, path, null);
        }

        public static UuidKey string(String path) {
            return new UuidKey(UuidForm.STRING, path, null);
        }

        public static UuidKey mostLeast(String mostPath, String leastPath) {
            return new UuidKey(UuidForm.MOST_LEAST, mostPath, leastPath);
        }
    }

    /**
     * A mod's owner keys: the UUID keys in order, the name's dotted path (null for none), whether no UUID key at all is
     * the mod's own "no owner set", and whether a zero UUID is.
     */
    public record Spec(List<UuidKey> uuidKeys, String namePath, boolean absentMeansNoOwnerSet,
            boolean zeroMeansNoOwnerSet) {
        public Spec {
            uuidKeys = List.copyOf(uuidKeys);
            if (uuidKeys.isEmpty()) throw new IllegalArgumentException("A spec names at least one UUID key");
            if (namePath != null) requirePath(namePath);
        }
    }

    /** The owner of {@code data} by {@code spec}, counted under {@code key} and {@code site}. Never throws. */
    public static GraveOwnerFacts read(AdapterKey key, String site, Spec spec, CompoundTag data) {
        return GraveOwners.guarded(key, site, () -> {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(data, "data");
            for (UuidKey uuidKey : spec.uuidKeys()) {
                if (!present(data, uuidKey)) continue;
                UUID uuid = uuid(data, uuidKey);
                if (uuid == null) return GraveOwners.none(key, site, GraveOwnerFacts.Reason.GRAVE_OWNER_MALFORMED);
                if (spec.zeroMeansNoOwnerSet() && uuid.getMostSignificantBits() == 0L
                        && uuid.getLeastSignificantBits() == 0L) {
                    return GraveOwners.noOwnerSet(key, site);
                }
                return GraveOwners.read(key, site, uuid, name(data, spec.namePath()));
            }
            if (spec.absentMeansNoOwnerSet()) return GraveOwners.noOwnerSet(key, site);
            return GraveOwners.read(key, site, null, name(data, spec.namePath()));
        });
    }

    /** A block entity's own saved data ({@code saveWithoutMetadata} with its level's registries). */
    public static CompoundTag blockEntityData(BlockEntity blockEntity) {
        Level level = Objects.requireNonNull(blockEntity, "blockEntity").getLevel();
        if (level == null) throw new IllegalStateException("The block entity has no level");
        return blockEntity.saveWithoutMetadata(level.registryAccess());
    }

    /** The tag at a dotted path, or null when a step is missing or is not a compound. */
    static Tag at(CompoundTag root, String path) {
        Tag current = root;
        for (String step : path.split("\\.", -1)) {
            if (!(current instanceof CompoundTag compound) || !compound.contains(step)) return null;
            current = compound.get(step);
        }
        return current;
    }

    private static boolean present(CompoundTag data, UuidKey key) {
        if (at(data, key.path()) != null) return true;
        return key.form() == UuidForm.MOST_LEAST && at(data, key.leastPath()) != null;
    }

    private static UUID uuid(CompoundTag data, UuidKey key) {
        Tag tag = at(data, key.path());
        switch (key.form()) {
            case INT_ARRAY -> {
                if (!(tag instanceof IntArrayTag array)) return null;
                int[] ints = array.getAsIntArray();
                if (ints.length != 4) return null;
                return new UUID((long) ints[0] << 32 | ints[1] & 0xFFFFFFFFL,
                        (long) ints[2] << 32 | ints[3] & 0xFFFFFFFFL);
            }
            case STRING -> {
                if (!(tag instanceof StringTag string)) return null;
                String text = string.getAsString();
                try {
                    UUID uuid = UUID.fromString(text);
                    // Only the canonical text: a short form that UUID.fromString accepts is not what the mod wrote.
                    return uuid.toString().equalsIgnoreCase(text) ? uuid : null;
                } catch (IllegalArgumentException malformed) {
                    return null;
                }
            }
            case MOST_LEAST -> {
                Tag least = at(data, key.leastPath());
                if (!(tag instanceof LongTag most) || !(least instanceof LongTag leastBits)) return null;
                return new UUID(most.getAsLong(), leastBits.getAsLong());
            }
            default -> {
                return null;
            }
        }
    }

    private static String name(CompoundTag data, String path) {
        if (path == null) return null;
        Tag tag = at(data, path);
        return tag instanceof StringTag string ? string.getAsString() : null;
    }

    private static void requirePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("A key path is a dotted list of names");
        }
    }
}
