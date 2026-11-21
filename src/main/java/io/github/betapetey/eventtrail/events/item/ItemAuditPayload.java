package io.github.betapetey.eventtrail.events.item;

import io.github.betapetey.eventtrail.util.AuditSnbtParser;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Typed item facts stored for a committed drop or pickup. */
public record ItemAuditPayload(
        Outcome outcome,
        ResourceLocation itemId,
        int quantity,
        String presentationIdentity,
        ActorState actorState,
        MetadataState metadataState,
        String metadataSnbt,
        String unavailableReason) {

    public ItemAuditPayload {
        outcome = Objects.requireNonNull(outcome, "outcome");
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (presentationIdentity == null || presentationIdentity.isBlank()) {
            throw new IllegalArgumentException("presentationIdentity must not be blank");
        }
        presentationIdentity = EventTrailTextBounds.boundAuditDescription(presentationIdentity);
        actorState = Objects.requireNonNull(actorState, "actorState");
        metadataState = Objects.requireNonNull(metadataState, "metadataState");
        if (metadataState == MetadataState.CAPTURED) {
            if (!validMetadata(metadataSnbt, itemId, quantity)) {
                throw new IllegalArgumentException(
                        "metadataSnbt is invalid, too large, or conflicts with the declared item");
            }
            unavailableReason = null;
        } else {
            metadataSnbt = null;
            if (unavailableReason == null || unavailableReason.isBlank()
                    || unavailableReason.length() > 96) {
                throw new IllegalArgumentException("unavailableReason must be bounded and nonblank");
            }
        }
    }

    public static ItemAuditPayload captured(
            Outcome outcome,
            ResourceLocation itemId,
            int quantity,
            String presentationIdentity,
            boolean synthetic,
            String stackSnbt) {
        String reason = unavailableReason(stackSnbt);
        boolean usable = reason == null && validMetadata(stackSnbt, itemId, quantity);
        return new ItemAuditPayload(
                outcome,
                itemId,
                quantity,
                presentationIdentity,
                synthetic ? ActorState.SYNTHETIC_PLAYER : ActorState.PLAYER,
                usable ? MetadataState.CAPTURED : MetadataState.UNAVAILABLE,
                usable ? stackSnbt : null,
                usable ? null : reason == null
                        ? EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE
                        : reason);
    }

    private static boolean validMetadata(
            String value,
            ResourceLocation expectedItemId,
            int expectedQuantity) {
        if (value == null || value.isBlank()
                || value.length() > EventTrailTextBounds.MAX_ITEM_METADATA_CHARS
                || value.getBytes(StandardCharsets.UTF_8).length
                    > EventTrailTextBounds.MAX_ITEM_METADATA_NBT_BYTES) {
            return false;
        }
        AuditSnbtParser.ParseResult parsed = AuditSnbtParser.parsePayload(value, 0);
        if (!parsed.successful()) return false;
        CompoundTag tag = parsed.tag();
        if (!tag.contains("id", Tag.TAG_STRING)
                || !expectedItemId.toString().equals(tag.getString("id"))) {
            return false;
        }
        return tag.contains("count")
                ? tag.contains("count", Tag.TAG_INT) && tag.getInt("count") == expectedQuantity
                : expectedQuantity == 1;
    }

    private static String unavailableReason(String value) {
        if (value == null || value.isBlank()) return "missing";
        if (value.length() > EventTrailTextBounds.MAX_ITEM_METADATA_CHARS
                || value.getBytes(StandardCharsets.UTF_8).length
                    > EventTrailTextBounds.MAX_ITEM_METADATA_NBT_BYTES) {
            return EventTrailTextBounds.METADATA_REASON_TOO_LARGE;
        }
        AuditSnbtParser.ParseResult parsed = AuditSnbtParser.parsePayload(value, 0);
        if (!parsed.successful()) return EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE;
        CompoundTag tag = parsed.tag();
        if (!tag.getBoolean(EventTrailTextBounds.METADATA_UNAVAILABLE_KEY)) return null;
        String reason = tag.getString(EventTrailTextBounds.METADATA_REASON_KEY);
        return reason == null || reason.isBlank() ? "unavailable" : reason;
    }

    public enum Outcome { DROP, PICKUP }

    public enum ActorState { PLAYER, SYNTHETIC_PLAYER }

    public enum MetadataState { CAPTURED, UNAVAILABLE }
}
