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
            StackRebuild rebuilt = rebuildStack(tag, provider);
            if (rebuilt.rebuilt()) {
                return new StackDecodeResult(rebuilt.stack(), metadataUnavailable, metadataReason);
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
            // larger row; encodeExact encodes a count-1 copy and puts the exact count back.
            Tag tag = encodeExact(stack, encoder);
            if (tag instanceof CompoundTag compoundTag) {
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

    /**
     * The shared KL-68 encode step (ItemDetails plan 1.3 step 2): a count above the codec's range is encoded from a
     * count-1 copy (the codec's output does not depend on the count otherwise) and the exact count replaces the
     * copy's in the same field. Throws what the encoder throws; a non-compound result is returned as it is.
     */
    static Tag encodeExact(ItemStack stack, StackEncoder encoder) {
        int count = stack.getCount();
        boolean aboveCodecLimit = count > ITEM_CODEC_MAX_COUNT;
        Tag tag = encoder.encode(aboveCodecLimit ? stack.copyWithCount(1) : stack);
        if (aboveCodecLimit && tag instanceof CompoundTag compoundTag) compoundTag.putInt(STACK_COUNT_KEY, count);
        return tag;
    }

    /**
     * A rebuilt stack, or EMPTY with the codec's failed result (null when it gave an empty stack). The result is kept
     * unevaluated: its message is built only by the one caller that reports it (ItemDetails.capture, review m1).
     */
    record StackRebuild(ItemStack stack, DataResult<ItemStack> failure) {
        boolean rebuilt() {
            return stack != null && !stack.isEmpty();
        }
    }

    /**
     * The shared rebuild step (ItemDetails plan 1.3 step 6) of the one correct reader: the codec's full result (never
     * a partial one), through a count-1 copy for a count above 99, given back its exact count. Reports nothing,
     * builds no error message; throws what the codec throws.
     */
    static StackRebuild rebuildStack(CompoundTag tag, HolderLookup.Provider provider) {
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
            return new StackRebuild(stack, null);
        }
        // No message is built here: today's reader drops it, so the old path does no new work.
        return new StackRebuild(ItemStack.EMPTY, decoded.isPresent() ? null : result);
    }

    static Tag encodeStack(ItemStack stack, HolderLookup.Provider provider) {
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

    /** Kill-weapon fix: the bounded {id, count, marker, reason} stub for a stack whose text cannot be stored. */
    static CompoundTag unavailableStackTag(ItemStack stack, String reason) {
        return fallbackStackTag(stack, reason);
    }

    private static CompoundTag fallbackStackTag(ItemStack stack, String reason) {
        CompoundTag fallback = new CompoundTag();
        try {
            BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                    .map(key -> key.location().toString())
                    .ifPresent(id -> fallback.putString("id", id));
        } catch (RuntimeException | LinkageError ignored) {
            // Count and the explicit marker still truthfully preserve a partial audit record.
        }
        fallback.putInt("count", stack.getCount());
        fallback.putBoolean(EventTrailTextBounds.METADATA_UNAVAILABLE_KEY, true);
        fallback.putString(EventTrailTextBounds.METADATA_REASON_KEY, reason);
        return fallback;
    }

    static String itemIdentity(ItemStack stack) {
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

    /**
     * The item-details WARN (ItemDetails plan 1.3), on the same rate-limit gate as the two above: what the capture
     * found for an item, as one short reason code and detail. An overload of {@link #reportSerializationFailure}
     * would be ambiguous for its callers that pass a {@code null} cause.
     */
    static void reportDetailsFailure(String category, String identity, String detail) {
        long now = System.currentTimeMillis();
        long next = nextSerializationWarning.get();
        if (now < next || !nextSerializationWarning.compareAndSet(next, now + SERIALIZATION_WARNING_INTERVAL_MS)) {
            suppressedSerializationWarnings.increment();
            return;
        }

        long suppressed = suppressedSerializationWarnings.sumThenReset();
        LOGGER.warn(
                "EventTrail {} item details for {}: {}{}",
                sanitizeIdentity(category), sanitizeIdentity(withoutControls(identity)),
                sanitizeIdentity(withoutControls(detail)),
                suppressed == 0 ? "" : "; " + suppressed + " similar warnings suppressed");
    }

    /**
     * Review n5: a details WARN can carry codec message text, which can hold player-authored text. Every ISO control
     * character (ESC and the rest, not only CR, LF and TAB) becomes a space before the shared trim and cut.
     */
    static String withoutControls(String value) {
        if (value == null) return null;
        StringBuilder cleaned = null;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isISOControl(c)) {
                if (cleaned == null) cleaned = new StringBuilder(value.length()).append(value, 0, index);
                cleaned.append(' ');
            } else if (cleaned != null) {
                cleaned.append(c);
            }
        }
        return cleaned == null ? value : cleaned.toString();
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
