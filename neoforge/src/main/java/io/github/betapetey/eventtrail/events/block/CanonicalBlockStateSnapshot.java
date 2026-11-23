package io.github.betapetey.eventtrail.events.block;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Immutable registry identity and deterministically ordered properties for one block state. */
public record CanonicalBlockStateSnapshot(
        ResourceLocation blockId,
        SortedMap<String, String> properties) {

    public CanonicalBlockStateSnapshot {
        blockId = Objects.requireNonNull(blockId, "blockId");
        TreeMap<String, String> ordered = new TreeMap<>();
        Objects.requireNonNull(properties, "properties").forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("Block-state properties require nonblank names and values");
            }
            if (ordered.put(name, value) != null) {
                throw new IllegalArgumentException("Duplicate block-state property " + name);
            }
        });
        properties = Collections.unmodifiableSortedMap(ordered);
    }

    public static CanonicalBlockStateSnapshot capture(BlockState state) {
        Objects.requireNonNull(state, "state");
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getResourceKey(state.getBlock())
                .map(key -> key.location())
                .orElseThrow(() -> new IllegalArgumentException("Block is not registered"));

        TreeMap<String, String> properties = new TreeMap<>();
        state.getBlock().getStateDefinition().getProperties().stream()
                .sorted(java.util.Comparator.comparing(Property::getName))
                .forEach(property -> properties.put(
                        property.getName(), propertyValueName(state, property)));
        return new CanonicalBlockStateSnapshot(blockId, properties);
    }

    /**
     * Validates the exact registry-id/property representation without consulting
     * a live registry. This keeps schema restoration deterministic while still
     * rejecting partial, duplicate, or non-canonically ordered state text.
     */
    public static boolean isCanonicalStorageText(String value) {
        if (value == null || value.isBlank()) return false;
        int propertyStart = value.indexOf('[');
        if (propertyStart < 0) {
            ResourceLocation blockId = ResourceLocation.tryParse(value);
            return blockId != null && blockId.toString().equals(value)
                    && value.indexOf(']') < 0;
        }
        if (propertyStart == 0 || !value.endsWith("]")
                || value.indexOf('[', propertyStart + 1) >= 0
                || value.indexOf(']') != value.length() - 1) {
            return false;
        }

        ResourceLocation blockId = ResourceLocation.tryParse(
                value.substring(0, propertyStart));
        if (blockId == null || !blockId.toString().equals(
                value.substring(0, propertyStart))) {
            return false;
        }
        String encodedProperties = value.substring(propertyStart + 1, value.length() - 1);
        if (encodedProperties.isEmpty()) return false;

        TreeMap<String, String> properties = new TreeMap<>();
        for (String encodedProperty : encodedProperties.split(",", -1)) {
            int separator = encodedProperty.indexOf('=');
            if (separator <= 0 || separator == encodedProperty.length() - 1
                    || encodedProperty.indexOf('=', separator + 1) >= 0) {
                return false;
            }
            String name = encodedProperty.substring(0, separator);
            String propertyValue = encodedProperty.substring(separator + 1);
            if (name.isBlank() || propertyValue.isBlank()
                    || !name.equals(name.trim())
                    || !propertyValue.equals(propertyValue.trim())
                    || properties.put(name, propertyValue) != null) {
                return false;
            }
        }
        return new CanonicalBlockStateSnapshot(blockId, properties)
                .storageText().equals(value);
    }

    public String storageText() {
        if (properties.isEmpty()) return blockId.toString();
        StringBuilder text = new StringBuilder(blockId.toString()).append('[');
        boolean first = true;
        for (var entry : properties.entrySet()) {
            if (!first) text.append(',');
            text.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return text.append(']').toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String propertyValueName(
            BlockState state, Property<?> untypedProperty) {
        Property<T> property = (Property<T>) untypedProperty;
        return property.getName(state.getValue(property));
    }

    @Override
    public String toString() {
        return storageText();
    }
}
