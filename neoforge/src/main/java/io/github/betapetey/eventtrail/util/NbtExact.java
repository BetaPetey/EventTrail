package io.github.betapetey.eventtrail.util;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The exact structural compare of the save-time verify (ItemDetails plan 1.1). The game's own {@code Tag.equals}
 * compares floats and doubles with {@code ==}, so a NaN is unequal to itself; here they compare by their bits
 * ({@code floatToIntBits}/{@code doubleToLongBits}), so a NaN equals a NaN. (No tree holds a -0: the tags'
 * {@code valueOf} gives the shared zero tag for it.)
 * Compounds compare by key set and values in any order; lists in order (the element type of two empty lists is not
 * compared: NbtIo writes END for every empty list); arrays by type and content; other tags by type and value.
 */
public final class NbtExact {
    private NbtExact() {}

    public static boolean equals(Tag first, Tag second) {
        return firstDifference(first, second) == null;
    }

    /**
     * The path of the first difference, or null when the trees are equal: "" for the root, a compound key as
     * {@code parent.key} ({@code key} at the root), a list member as {@code parent[i]}; a missing or extra key gives
     * that key's path and a shorter list its first missing index.
     */
    public static String firstDifference(Tag first, Tag second) {
        return difference(first, second, "");
    }

    private static String difference(Tag first, Tag second, String path) {
        if (first == null || second == null) return first == second ? null : path;
        if (first.getId() != second.getId()) return path;
        if (first instanceof CompoundTag a && second instanceof CompoundTag b) return compoundDifference(a, b, path);
        if (first instanceof ListTag a && second instanceof ListTag b) return listDifference(a, b, path);
        if (first instanceof FloatTag a && second instanceof FloatTag b) {
            return Float.floatToIntBits(a.getAsFloat()) == Float.floatToIntBits(b.getAsFloat()) ? null : path;
        }
        if (first instanceof DoubleTag a && second instanceof DoubleTag b) {
            return Double.doubleToLongBits(a.getAsDouble()) == Double.doubleToLongBits(b.getAsDouble()) ? null : path;
        }
        if (first instanceof ByteArrayTag a && second instanceof ByteArrayTag b) {
            return Arrays.equals(a.getAsByteArray(), b.getAsByteArray()) ? null : path;
        }
        if (first instanceof IntArrayTag a && second instanceof IntArrayTag b) {
            return Arrays.equals(a.getAsIntArray(), b.getAsIntArray()) ? null : path;
        }
        if (first instanceof LongArrayTag a && second instanceof LongArrayTag b) {
            return Arrays.equals(a.getAsLongArray(), b.getAsLongArray()) ? null : path;
        }
        return first.equals(second) ? null : path;
    }

    private static String compoundDifference(CompoundTag first, CompoundTag second, String path) {
        Set<String> keys = new TreeSet<>(first.getAllKeys());
        keys.addAll(second.getAllKeys());
        List<String> shared = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (!first.contains(key) || !second.contains(key)) return child(path, key);
            shared.add(key);
        }
        for (String key : shared) {
            String difference = difference(first.get(key), second.get(key), child(path, key));
            if (difference != null) return difference;
        }
        return null;
    }

    private static String listDifference(ListTag first, ListTag second, String path) {
        int shared = Math.min(first.size(), second.size());
        for (int index = 0; index < shared; index++) {
            String difference = difference(first.get(index), second.get(index), path + "[" + index + "]");
            if (difference != null) return difference;
        }
        return first.size() == second.size() ? null : path + "[" + shared + "]";
    }

    private static String child(String parent, String key) {
        return parent.isEmpty() ? key : parent + "." + key;
    }
}
