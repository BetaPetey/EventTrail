package io.github.betapetey.eventtrail.inventory.core;

import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.util.AuditSnbtParser;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable semantic content of one persisted container-transfer chunk.
 *
 * <p>{@code shownCause} (GF-ONPLAYER design 4.2, decision 20) is the optional detail that names what moved the items
 * on a player-action row ("via Restock Upgrade"). It is null on every row written before it existed and on every row
 * without a cause; {@link ContainerTransferPayloadCodec} writes such a payload as binary version 1, byte for byte as
 * before, and a payload with a cause as version 2.</p>
 */
public record ContainerTransferPayload(
        TransactionDirection direction,
        ResourceLocation itemId,
        int quantity,
        MetadataState metadataState,
        String metadataSnbt,
        String metadataUnavailableReason,
        ActorState actorState,
        String targetDisplay,
        String providerId,
        AccessKind accessKind,
        BackingContextKind backingContextKind,
        String backingReference,
        ShownCause shownCause) {

    public static final int MAX_PROVIDER_CHARS = 96;
    public static final int MAX_REASON_CHARS = 96;

    /** A payload without a shown cause: the twelve fields every row had before decision 20's cause detail. */
    public ContainerTransferPayload(
            TransactionDirection direction,
            ResourceLocation itemId,
            int quantity,
            MetadataState metadataState,
            String metadataSnbt,
            String metadataUnavailableReason,
            ActorState actorState,
            String targetDisplay,
            String providerId,
            AccessKind accessKind,
            BackingContextKind backingContextKind,
            String backingReference) {
        this(direction, itemId, quantity, metadataState, metadataSnbt, metadataUnavailableReason, actorState,
                targetDisplay, providerId, accessKind, backingContextKind, backingReference, null);
    }

    public ContainerTransferPayload {
        direction = Objects.requireNonNull(direction, "direction");
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        metadataState = Objects.requireNonNull(metadataState, "metadataState");
        actorState = Objects.requireNonNull(actorState, "actorState");
        targetDisplay = EventTrailTextBounds.boundAuditDescription(
                requireText(targetDisplay, "targetDisplay"));
        providerId = requireText(providerId, "providerId").toLowerCase(Locale.ROOT);
        if (providerId.length() > MAX_PROVIDER_CHARS || !providerId.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("providerId contains unsupported characters");
        }
        accessKind = Objects.requireNonNull(accessKind, "accessKind");
        backingContextKind = Objects.requireNonNull(backingContextKind, "backingContextKind");
        if (backingContextKind == BackingContextKind.UNKNOWN) {
            throw new IllegalArgumentException("Unknown backing identity cannot be persisted");
        }
        backingReference = requireText(backingReference, "backingReference").toLowerCase(Locale.ROOT);
        if (!backingReference.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backingReference must be an opaque SHA-256 identity");
        }

        if (metadataState == MetadataState.CAPTURED) {
            metadataSnbt = requireMetadata(metadataSnbt, itemId, quantity);
            metadataUnavailableReason = null;
        } else {
            metadataSnbt = null;
            metadataUnavailableReason = requireText(
                    metadataUnavailableReason, "metadataUnavailableReason");
            if (metadataUnavailableReason.length() > MAX_REASON_CHARS) {
                throw new IllegalArgumentException("metadataUnavailableReason is too long");
            }
        }
    }

    public static ContainerTransferPayload from(
            InventoryTransactionEvidence evidence,
            boolean syntheticActor,
            String providerId,
            String capturedMetadataSnbt) {
        Objects.requireNonNull(evidence, "evidence");
        String reason = metadataUnavailableReason(capturedMetadataSnbt);
        ResourceLocation itemId = ResourceLocation.parse(evidence.item().registryId());
        int quantity = Math.toIntExact(evidence.quantity());
        boolean usable = reason == null
                && validMetadata(capturedMetadataSnbt, itemId, quantity);
        return new ContainerTransferPayload(
                evidence.direction(),
                itemId,
                quantity,
                usable ? MetadataState.CAPTURED : MetadataState.UNAVAILABLE,
                usable ? capturedMetadataSnbt : null,
                usable ? null : reason == null ? "codec_failure" : reason,
                syntheticActor ? ActorState.SYNTHETIC_PLAYER : ActorState.PLAYER,
                evidence.target().label(),
                providerId,
                evidence.target().accessKind(),
                evidence.target().backingContextKind(),
                opaqueReference(evidence.target().stableContextKey()));
    }

    static String opaqueReference(String stableContextKey) {
        String value = requireText(stableContextKey, "stableContextKey");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
        return parsed.successful()
                && matchesDeclaredItem(parsed.tag(), expectedItemId, expectedQuantity);
    }

    private static String requireMetadata(
            String value,
            ResourceLocation expectedItemId,
            int expectedQuantity) {
        if (!validMetadata(value, expectedItemId, expectedQuantity)) {
            throw new IllegalArgumentException(
                    "metadataSnbt is invalid, too large, or conflicts with the declared item");
        }
        return value;
    }

    private static boolean matchesDeclaredItem(
            CompoundTag tag,
            ResourceLocation expectedItemId,
            int expectedQuantity) {
        if (!tag.contains("id", Tag.TAG_STRING)) return false;
        if (!expectedItemId.toString().equals(tag.getString("id"))) return false;

        if (!tag.contains("count")) {
            // ItemStack.CODEC omits its default count of one.
            return expectedQuantity == 1;
        }
        return tag.contains("count", Tag.TAG_INT)
                && tag.getInt("count") == expectedQuantity;
    }

    private static String metadataUnavailableReason(String value) {
        if (value == null || value.isBlank()) return "missing";
        if (value.length() > EventTrailTextBounds.MAX_ITEM_METADATA_CHARS
                || value.getBytes(StandardCharsets.UTF_8).length
                    > EventTrailTextBounds.MAX_ITEM_METADATA_NBT_BYTES) {
            return EventTrailTextBounds.METADATA_REASON_TOO_LARGE;
        }
        AuditSnbtParser.ParseResult parsed = AuditSnbtParser.parsePayload(value, 0);
        if (!parsed.successful()) return "codec_failure";
        CompoundTag tag = parsed.tag();
        if (!tag.getBoolean(EventTrailTextBounds.METADATA_UNAVAILABLE_KEY)) return null;
        String reason = tag.getString(EventTrailTextBounds.METADATA_REASON_KEY);
        return reason == null || reason.isBlank() ? "unavailable" : reason;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public enum MetadataState { CAPTURED, UNAVAILABLE }

    public enum ActorState { PLAYER, SYNTHETIC_PLAYER }
}
