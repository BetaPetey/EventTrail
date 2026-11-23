package io.github.betapetey.eventtrail.events.block;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

/** Defensive, deterministic copy of retained block-entity metadata. */
public final class BlockEntityTagSanitizer {
    private static final Set<String> ROOT_LOCATION_KEYS = Set.of("id", "x", "y", "z");

    private BlockEntityTagSanitizer() {
    }

    public static CompoundTag sanitize(CompoundTag source) {
        if (source == null) throw new IllegalArgumentException("Block-entity metadata is missing");
        preflightRetainedSize(source, true);
        WorkBudget budget = new WorkBudget();
        budget.charge(0);
        CompoundTag copy = new CompoundTag();
        Set<String> keys = source.getAllKeys();
        int retainedMembers = keys.size();
        for (String locationKey : ROOT_LOCATION_KEYS) {
            if (keys.contains(locationKey)) retainedMembers--;
        }
        budget.requireMembersFit(retainedMembers);
        keys.stream()
                .filter(key -> !ROOT_LOCATION_KEYS.contains(key))
                .sorted()
                .forEach(key -> {
                    Tag value = source.get(key);
                    if (value != null) copy.put(key, copyTag(value, 1, budget));
                });
        return copy;
    }

    public static String canonicalSnbt(CompoundTag tag) {
        if (tag == null) throw new IllegalArgumentException("Block-entity metadata is missing");
        preflightRetainedSize(tag, false);
        return render(tag, 0, new WorkBudget());
    }

    private static Tag copyTag(Tag source, int depth, WorkBudget budget) {
        budget.charge(depth);
        if (source instanceof CompoundTag compound) {
            CompoundTag copy = new CompoundTag();
            Set<String> keys = compound.getAllKeys();
            budget.requireMembersFit(keys.size());
            keys.stream().sorted().forEach(key -> {
                Tag value = compound.get(key);
                if (value != null) copy.put(key, copyTag(value, depth + 1, budget));
            });
            return copy;
        }
        if (source instanceof ListTag list) {
            budget.requireMembersFit(list.size());
            ListTag copy = new ListTag();
            for (Tag value : list) copy.add(copyTag(value, depth + 1, budget));
            return copy;
        }
        chargeArrayMembers(source, budget);
        return source.copy();
    }

    private static String render(Tag tag, int depth, WorkBudget budget) {
        budget.charge(depth);
        if (tag instanceof CompoundTag compound) {
            Set<String> keys = compound.getAllKeys();
            budget.requireMembersFit(keys.size());
            StringBuilder text = new StringBuilder("{");
            boolean first = true;
            for (String key : new java.util.TreeSet<>(keys)) {
                Tag value = compound.get(key);
                if (value == null) continue;
                if (!first) text.append(',');
                text.append(StringTag.valueOf(key)).append(':')
                        .append(render(value, depth + 1, budget));
                first = false;
            }
            return text.append('}').toString();
        }
        if (tag instanceof ListTag list) {
            budget.requireMembersFit(list.size());
            StringBuilder text = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) text.append(',');
                text.append(render(list.get(index), depth + 1, budget));
            }
            return text.append(']').toString();
        }
        chargeArrayMembers(tag, budget);
        return tag.toString();
    }

    private static void preflightRetainedSize(CompoundTag source, boolean omitRootLocationKeys) {
        long retainedBytes = measureCompound(
                source, 0, new WorkBudget(), omitRootLocationKeys);
        if (retainedBytes > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_NBT_BYTES) {
            throw new LimitExceededException(
                    "Block-entity metadata is too large", retainedBytes);
        }
    }

    private static long measureTag(Tag source, int depth, WorkBudget budget) {
        budget.charge(depth);
        if (source instanceof CompoundTag compound) {
            return measureCompoundContents(compound, depth, budget, false);
        }
        if (source instanceof ListTag list) {
            budget.requireMembersFit(list.size());
            long bytes = checkedBytes(37L + 4L * list.size());
            for (Tag value : list) {
                bytes = checkedBytes(bytes + measureTag(value, depth + 1, budget));
            }
            return bytes;
        }
        if (source instanceof ByteArrayTag bytes) {
            budget.chargeMembers(bytes.size());
            return checkedBytes(24L + bytes.size());
        }
        if (source instanceof IntArrayTag integers) {
            budget.chargeMembers(integers.size());
            return checkedBytes(24L + 4L * integers.size());
        }
        if (source instanceof LongArrayTag longs) {
            budget.chargeMembers(longs.size());
            return checkedBytes(24L + 8L * longs.size());
        }
        return checkedBytes(source.sizeInBytes());
    }

    private static long measureCompound(
            CompoundTag source,
            int depth,
            WorkBudget budget,
            boolean omitRootLocationKeys) {
        budget.charge(depth);
        return measureCompoundContents(source, depth, budget, omitRootLocationKeys);
    }

    private static long measureCompoundContents(
            CompoundTag source,
            int depth,
            WorkBudget budget,
            boolean omitRootLocationKeys) {
        Set<String> keys = source.getAllKeys();
        int retainedMembers = keys.size();
        if (omitRootLocationKeys) {
            for (String locationKey : ROOT_LOCATION_KEYS) {
                if (keys.contains(locationKey)) retainedMembers--;
            }
        }
        budget.requireMembersFit(retainedMembers);
        long bytes = 48L;
        for (String key : keys) {
            if (omitRootLocationKeys && ROOT_LOCATION_KEYS.contains(key)) continue;
            Tag value = source.get(key);
            if (value == null) continue;
            bytes = checkedBytes(bytes + 64L + 2L * key.length());
            bytes = checkedBytes(bytes + measureTag(value, depth + 1, budget));
        }
        return bytes;
    }

    private static long checkedBytes(long bytes) {
        if (bytes < 0L || bytes > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_NBT_BYTES) {
            throw new LimitExceededException(
                    "Block-entity metadata is too large",
                    bytes < 0L ? Long.MAX_VALUE : bytes);
        }
        return bytes;
    }

    private static void chargeArrayMembers(Tag source, WorkBudget budget) {
        if (source instanceof ByteArrayTag bytes) {
            budget.chargeMembers(bytes.size());
        } else if (source instanceof IntArrayTag integers) {
            budget.chargeMembers(integers.size());
        } else if (source instanceof LongArrayTag longs) {
            budget.chargeMembers(longs.size());
        }
    }

    static final class LimitExceededException extends IllegalArgumentException {
        private final long observedAmount;

        private LimitExceededException(String message, long observedAmount) {
            super(message);
            this.observedAmount = observedAmount;
        }

        long observedAmount() {
            return observedAmount;
        }
    }

    private static final class WorkBudget {
        private int nodes;

        private void charge(int depth) {
            if (depth > BlockAuditLimits.MAX_NBT_DEPTH) {
                throw new LimitExceededException(
                        "Block-entity metadata nesting is too deep", depth);
            }
            if (++nodes > BlockAuditLimits.MAX_NBT_NODES) {
                throw new LimitExceededException(
                        "Block-entity metadata has too many members", nodes);
            }
        }

        private void requireMembersFit(int members) {
            if (members < 0 || members > BlockAuditLimits.MAX_NBT_NODES - nodes) {
                throw new LimitExceededException(
                        "Block-entity metadata has too many members", (long) nodes + members);
            }
        }

        private void chargeMembers(int members) {
            requireMembersFit(members);
            nodes += members;
        }
    }
}
