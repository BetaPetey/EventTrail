package io.github.betapetey.eventtrail.util;

import com.mojang.serialization.DataResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class EventTrailNbtUtil {
    /**
     * The largest count {@code ItemStack.CODEC} accepts: its {@code count} field is
     * {@code ExtraCodecs.intRange(1, 99)} on 1.21.1. A stack-upgraded cell or drawer moves far more
     * than that in one row, so a larger stack is encoded and decoded through a count-1 copy and
     * its exact count is kept in the same top-level {@code count} field (KL-68).
     */
    static final int ITEM_CODEC_MAX_COUNT = 99;
    private static final String STACK_COUNT_KEY = "count";
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final long SERIALIZATION_WARNING_INTERVAL_MS = 60_000L;
    private static final AtomicLong nextSerializationWarning = new AtomicLong();
    private static final LongAdder suppressedSerializationWarnings = new LongAdder();

    private static HolderLookup.Provider registries() {
        return ServerLifecycleHooks.getCurrentServer().registryAccess();
    }

    public static CompoundTag stackToNbt(ItemStack stack) {
        return stackToNbt(stack, value -> encodeStack(value, registries()));
    }

    /**
     * Returns the bounded SNBT representation stored in audit rows. The NBT
     * estimate is checked before SNBT rendering so a large tag cannot create a
     * long-lived audit String, and the rendered form has its own final bound.
     */
    public static String stackToAuditString(ItemStack stack, boolean captureFullMetadata) {
        if (!captureFullMetadata && stack != null && !stack.isEmpty()) {
            return fallbackStackTag(stack, EventTrailTextBounds.METADATA_REASON_BUFFER_PRESSURE).toString();
        }
        return stackToAuditString(stack, value -> encodeStack(value, registries()));
    }

    public static ItemStack stackFromNbt(CompoundTag tag) {
        return decodeStackFromNbt(tag).stack();
    }

    /**
     * The only correct reader of stored item SNBT: a count above 99 is kept outside the codec's range.
     * The returned stack can exceed its max stack size; split by getMaxStackSize() before putting it
     * into a slot.
     */
    public static StackDecodeResult decodeStackFromNbt(CompoundTag tag) {
        try {
            return decodeStackFromNbt(tag, registries());
        } catch (RuntimeException exception) {
            reportSerializationFailure("item read", itemIdentity(tag), exception);
            return new StackDecodeResult(ItemStack.EMPTY, true, null);
        }
    }

    /**
     * The only correct reader of stored item SNBT: a count above 99 is kept outside the codec's range.
     * The returned stack can exceed its max stack size; split by getMaxStackSize() before putting it
     * into a slot.
     */
    static StackDecodeResult decodeStackFromNbt(CompoundTag tag, HolderLookup.Provider provider) {
        boolean metadataUnavailable = tag != null && tag.getBoolean(EventTrailTextBounds.METADATA_UNAVAILABLE_KEY);
        String metadataReason = tag != null && tag.contains(EventTrailTextBounds.METADATA_REASON_KEY)
                ? tag.getString(EventTrailTextBounds.METADATA_REASON_KEY)
                : null;
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, provider);
            // A count above the codec's range (the count-1-copy form of stackToNbt, or an older explicit
            // fallback) is decoded through a count-1 copy and given back its exact count.
            int exactCount = countAboveCodecLimit(tag);
            CompoundTag codecInput = tag;
            if (exactCount > 0) {
                codecInput = tag.copy();
                codecInput.putInt(STACK_COUNT_KEY, 1);
            }
            DataResult<ItemStack> result = ItemStack.CODEC.parse(ops, codecInput);
            Optional<ItemStack> decoded = result.result();
            if (decoded.isPresent() && !decoded.get().isEmpty()) {
                ItemStack stack = decoded.get();
                if (exactCount > 0) stack.setCount(exactCount);
                return new StackDecodeResult(stack, metadataUnavailable, metadataReason);
            }
        } catch (RuntimeException exception) {
            reportSerializationFailure("item read", itemIdentity(tag), exception);
            return new StackDecodeResult(ItemStack.EMPTY, true, metadataReason);
        }

        reportSerializationFailure("item read", itemIdentity(tag), null);
        return new StackDecodeResult(ItemStack.EMPTY, true, metadataReason);
    }

    public static CompoundTag saveBlockEntity(BlockEntity blockEntity) {
        return blockEntity.saveWithoutMetadata(registries());
    }

    public static void loadBlockEntity(BlockEntity blockEntity, CompoundTag tag) {
        blockEntity.loadWithComponents(tag, registries());
    }

    static CompoundTag stackToNbt(ItemStack stack, StackEncoder encoder) {
        if (stack == null || stack.isEmpty()) {
            return new CompoundTag();
        }

        try {
            // KL-68: ItemStack.CODEC refuses a count above 99, which would drop the components of every
            // larger row. The components are encoded from a count-1 copy (the codec's output does not
            // depend on the count otherwise) and the exact count replaces the copy's in the same field.
            int count = stack.getCount();
            boolean aboveCodecLimit = count > ITEM_CODEC_MAX_COUNT;
            Tag tag = encoder.encode(aboveCodecLimit ? stack.copyWithCount(1) : stack);
            if (tag instanceof CompoundTag compoundTag) {
                if (aboveCodecLimit) compoundTag.putInt(STACK_COUNT_KEY, count);
                if (compoundTag.sizeInBytes() > EventTrailTextBounds.MAX_ITEM_METADATA_NBT_BYTES) {
                    reportMetadataTooLarge("item write", itemIdentity(stack), compoundTag.sizeInBytes());
                    return fallbackStackTag(stack, EventTrailTextBounds.METADATA_REASON_TOO_LARGE);
                }
                return compoundTag;
            }
            throw new IllegalStateException("ItemStack codec returned non-compound NBT");
        } catch (RuntimeException exception) {
            String identity = itemIdentity(stack);
            reportSerializationFailure("item write", identity, exception);
            return fallbackStackTag(stack, EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE);
        }
    }

    static String stackToAuditString(ItemStack stack, StackEncoder encoder) {
        CompoundTag tag = stackToNbt(stack, encoder);
        String serialized = tag.toString();
        if (serialized.length() <= EventTrailTextBounds.MAX_ITEM_METADATA_CHARS) {
            return serialized;
        }

        reportMetadataTooLarge("item write", itemIdentity(stack), serialized.length());
        return fallbackStackTag(stack, EventTrailTextBounds.METADATA_REASON_TOO_LARGE).toString();
    }

    static CompoundTag stackToNbt(ItemStack stack, HolderLookup.Provider provider) {
        return stackToNbt(stack, value -> encodeStack(value, provider));
    }

    private static Tag encodeStack(ItemStack stack, HolderLookup.Provider provider) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, provider);
        return ItemStack.CODEC.encodeStart(ops, stack).result()
                .orElseThrow(() -> new IllegalStateException("ItemStack codec did not produce NBT"));
    }

    /** The stored int count when it is above the codec's range, else 0 (the tag decodes as it is). */
    private static int countAboveCodecLimit(CompoundTag tag) {
        if (tag == null || !tag.contains(STACK_COUNT_KEY, Tag.TAG_INT)) return 0;
        int count = tag.getInt(STACK_COUNT_KEY);
        return count > ITEM_CODEC_MAX_COUNT ? count : 0;
    }

    private static CompoundTag fallbackStackTag(ItemStack stack, String reason) {
        CompoundTag fallback = new CompoundTag();
        try {
            BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                    .map(key -> key.location().toString())
                    .ifPresent(id -> fallback.putString("id", id));
        } catch (RuntimeException ignored) {
            // Count and the explicit marker still truthfully preserve a partial audit record.
        }
        fallback.putInt("count", stack.getCount());
        fallback.putBoolean(EventTrailTextBounds.METADATA_UNAVAILABLE_KEY, true);
        fallback.putString(EventTrailTextBounds.METADATA_REASON_KEY, reason);
        return fallback;
    }

    private static String itemIdentity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty item stack";
        }
        try {
            return BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                    .map(key -> key.location().toString())
                    .orElseGet(() -> sanitizeIdentity(stack.getItem().getClass().getName()));
        } catch (RuntimeException ignored) {
            return sanitizeIdentity(stack.getItem().getClass().getName());
        }
    }

    private static String itemIdentity(CompoundTag tag) {
        if (tag == null) {
            return "missing item tag";
        }
        return tag.contains("id")
                ? sanitizeIdentity(tag.getString("id"))
                : "item tag without id";
    }

    public static void reportSerializationFailure(String category, String identity, Throwable cause) {
        long now = System.currentTimeMillis();
        long next = nextSerializationWarning.get();
        if (now < next || !nextSerializationWarning.compareAndSet(next, now + SERIALIZATION_WARNING_INTERVAL_MS)) {
            suppressedSerializationWarnings.increment();
            return;
        }

        long suppressed = suppressedSerializationWarnings.sumThenReset();
        String reason = cause == null
                ? "codec returned no usable value"
                : sanitizeIdentity(cause.getClass().getSimpleName());
        LOGGER.warn(
                "EventTrail {} metadata serialization failed for {} ({}); storing an explicit unavailable marker{}",
                sanitizeIdentity(category),
                sanitizeIdentity(identity),
                reason,
                suppressed == 0 ? "" : "; " + suppressed + " similar warnings suppressed");
    }

    public static void reportMetadataTooLarge(String category, String identity, long measuredSize) {
        long now = System.currentTimeMillis();
        long next = nextSerializationWarning.get();
        if (now < next || !nextSerializationWarning.compareAndSet(next, now + SERIALIZATION_WARNING_INTERVAL_MS)) {
            suppressedSerializationWarnings.increment();
            return;
        }

        long suppressed = suppressedSerializationWarnings.sumThenReset();
        LOGGER.warn(
                "EventTrail {} metadata for {} exceeded its safe retained-size limit (measured {}); " +
                        "storing an explicit too-large marker{}",
                sanitizeIdentity(category), sanitizeIdentity(identity), Math.max(0L, measuredSize),
                suppressed == 0 ? "" : "; " + suppressed + " similar warnings suppressed");
    }

    private static String sanitizeIdentity(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160);
    }

    @FunctionalInterface
    interface StackEncoder {
        Tag encode(ItemStack stack);
    }

    public record StackDecodeResult(ItemStack stack, boolean metadataUnavailable, String metadataReason) {
    }
}
