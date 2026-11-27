package io.github.betapetey.eventtrail.inventory.core;

import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.util.AuditSnbtParser;
import io.github.betapetey.eventtrail.util.DetailsBlob;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import io.github.betapetey.eventtrail.util.ItemDetails;
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
 *
 * <p>{@code details} (ItemDetails plan 1.4, B1) is the item's {@link ItemDetails} block, null on every version 1 and 2
 * payload. A payload with details is version 3: the block decides the row, so {@code metadataState} and
 * {@code metadataUnavailableReason} are derived from it (a value passed in must be null or equal to what its accessor
 * gives, else it is refused), the stored {@code metadataSnbt} field is null and {@link #metadataSnbt()} gives the
 * stored tree's text.</p>
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
        ShownCause shownCause,
        ItemDetails details) {

    public static final int MAX_PROVIDER_CHARS = 96;
    public static final int MAX_REASON_CHARS = 96;

    /** A version 1 or 2 payload (no ItemDetails block): the thirteen fields every row had before B1. */
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
            String backingReference,
            ShownCause shownCause) {
        this(direction, itemId, quantity, metadataState, metadataSnbt, metadataUnavailableReason, actorState,
                targetDisplay, providerId, accessKind, backingContextKind, backingReference, shownCause, null);
    }

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
        if (details == null) metadataState = Objects.requireNonNull(metadataState, "metadataState");
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

        if (details != null) {
            // Version 3 (ItemDetails plan 2.2): the block decides; the three text fields are derived from it.
            if (details.state() == ItemDetails.State.NONE) {
                throw new IllegalArgumentException("a transfer row always moves an item");
            }
            if (details.identity() == null) throw new IllegalArgumentException("transfer details carry the identity");
            if (details.state() == ItemDetails.State.CAPTURED) {
                // Today's constructor checks, applied to the stored tree in place of the text: the declared item and
                // count, then the 64 Ki-character text limit and the UTF-8 limit (the Owner's Q480 OK).
                if (!details.describes(itemId.toString(), quantity)) {
                    throw new IllegalArgumentException("details conflict with the declared item");
                }
                if (!details.withinTextLimits(DetailsBlob.Limits.ITEM)) {
                    throw new IllegalArgumentException("details render past the item text limit");
                }
            }
            MetadataState derivedState = details.state() == ItemDetails.State.CAPTURED
                    ? MetadataState.CAPTURED : MetadataState.UNAVAILABLE;
            String derivedReason = derivedState == MetadataState.CAPTURED ? null : unavailableReason(details);
            // A value passed in must be null or what the matching accessor gives; a conflicting one is refused.
            if (metadataState != null && metadataState != derivedState) {
                throw new IllegalArgumentException("metadataState conflicts with the details");
            }
            if (metadataUnavailableReason != null && !metadataUnavailableReason.equals(derivedReason)) {
                throw new IllegalArgumentException("metadataUnavailableReason conflicts with the details");
            }
            if (metadataSnbt != null && !metadataSnbt.equals(details.detailsText())) {
                throw new IllegalArgumentException("metadataSnbt conflicts with the details");
            }
            metadataState = derivedState;
            metadataUnavailableReason = derivedReason;
            metadataSnbt = null;
        } else if (metadataState == MetadataState.CAPTURED) {
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
        return from(evidence, syntheticActor, providerId, capturedMetadataSnbt, null);
    }

    /**
     * As {@link #from(InventoryTransactionEvidence, boolean, String, String)}, with the row's shown cause (graves
     * design r6, 1.4). A null cause gives the same payload as the four-argument form, which the codec writes as
     * version 1 byte for byte; a cause makes it version 2.
     */
    public static ContainerTransferPayload from(
            InventoryTransactionEvidence evidence,
            boolean syntheticActor,
            String providerId,
            String capturedMetadataSnbt,
            ShownCause cause) {
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
                opaqueReference(evidence.target().stableContextKey()),
                cause);
    }

    /**
     * The captured SNBT text: for version 1 and 2 the stored text; for version 3 the text of the stored tree
     * ({@link ItemDetails#detailsText()}) when CAPTURED, else null. Readers that parse today's text (the presenter and
     * the grouper, until B5) read a version 3 row the same way.
     */
    @Override
    public String metadataSnbt() {
        return details != null ? details.detailsText() : metadataSnbt;
    }

    /**
     * A version 3 payload (ItemDetails plan 1.4): the chunk's fields with its {@link ItemDetails} block. CAPTURED
     * details whose tree names another item or count than the chunk are stored as UNREADABLE / store_differs, the
     * bytes kept (where version 1 stored codec_failure).
     */
    public static ContainerTransferPayload fromDetails(
            InventoryTransactionEvidence evidence,
            boolean syntheticActor,
            String providerId,
            ItemDetails details,
            ShownCause cause) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(details, "details");
        ResourceLocation itemId = ResourceLocation.parse(evidence.item().registryId());
        int quantity = Math.toIntExact(evidence.quantity());
        if (details.state() == ItemDetails.State.CAPTURED && !details.describes(itemId.toString(), quantity)) {
            details = details.asStoreDiffers(itemId.toString());
        }
        return new ContainerTransferPayload(
                evidence.direction(),
                itemId,
                quantity,
                null,
                null,
                null,
                syntheticActor ? ActorState.SYNTHETIC_PLAYER : ActorState.PLAYER,
                evidence.target().label(),
                providerId,
                evidence.target().accessKind(),
                evidence.target().backingContextKind(),
                opaqueReference(evidence.target().stableContextKey()),
                cause,
                details);
    }

    /** The reason today's readers show for a version 3 row that is not CAPTURED (the wording stays as today's). */
    private static String unavailableReason(ItemDetails details) {
        return switch (details.state()) {
            case BUSY -> EventTrailTextBounds.METADATA_REASON_BUFFER_PRESSURE;
            case TOO_LARGE -> EventTrailTextBounds.METADATA_REASON_TOO_LARGE;
            case NOT_STORED -> EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE;
            case UNREADABLE -> details.reason().text();
            case NONE, CAPTURED -> throw new IllegalStateException(details.state() + " has no unavailable reason");
        };
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
