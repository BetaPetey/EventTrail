package io.github.betapetey.eventtrail.inventory.core;

import io.github.betapetey.eventtrail.audit.ShownCause;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Deterministic bounded versioned codec for schema-v1 container-transfer evidence.
 *
 * <p>Shown cause (GF-ONPLAYER design 4.2, decision 20): a payload without a {@link ContainerTransferPayload#shownCause()}
 * is binary version 1, byte for byte as before; a payload with one is version 2 = the version-1 fields followed by the
 * cause block ({@link ShownCause#writeBlock}). Nothing else decodes: any other version is UNSUPPORTED; a version 2
 * without its cause block, with a non-canonical block, or with a byte after it is MALFORMED; a version 1 with a byte
 * after backingReference is MALFORMED as before. The prefix stays {@link #PREFIX} (it names the payload family).
 * {@link #CURRENT_VERSION} stays 1, the evidence version of every row without a cause; {@link #versionOf} gives the
 * version of a given payload.</p>
 */
public final class ContainerTransferPayloadCodec {
    public static final int CURRENT_VERSION = 1;
    /** The payload version of a row that carries a shown cause. */
    public static final int SHOWN_CAUSE_VERSION = 2;
    public static final String PREFIX = "EVENTTRAIL_CONTAINER_TRANSFER_V1|";
    public static final int MAX_BINARY_BYTES = 72 * 1024;
    public static final int MAX_ENCODED_CHARS = 100 * 1024;

    private ContainerTransferPayloadCodec() {}

    /**
     * Keeps the exact item registry identity outside the binary envelope so
     * existing parameterized SQL item filters remain literal and index-row
     * inspection remains understandable without decoding presentation data.
     */
    public static String encode(ContainerTransferPayload payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(versionOf(payload));
                writeString(output, directionCode(payload.direction()));
                writeString(output, payload.itemId().toString());
                output.writeInt(payload.quantity());
                writeString(output, metadataCode(payload.metadataState()));
                writeNullableString(output, payload.metadataSnbt());
                writeNullableString(output, payload.metadataUnavailableReason());
                writeString(output, actorCode(payload.actorState()));
                writeString(output, payload.targetDisplay());
                writeString(output, payload.providerId());
                writeString(output, accessCode(payload.accessKind()));
                writeString(output, backingCode(payload.backingContextKind()));
                writeString(output, payload.backingReference());
                if (payload.shownCause() != null) ShownCause.writeBlock(output, payload.shownCause());
            }
            byte[] binary = bytes.toByteArray();
            if (binary.length > MAX_BINARY_BYTES) {
                throw new IllegalArgumentException("Container-transfer payload exceeds its retained-size limit");
            }
            String encoded = PREFIX + payload.itemId() + '|'
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(binary);
            if (encoded.length() > MAX_ENCODED_CHARS) {
                throw new IllegalArgumentException("Container-transfer payload exceeds its encoded-size limit");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory container-transfer encoding failed", impossible);
        }
    }

    public static DecodeResult decode(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            return DecodeResult.failure(Status.UNSUPPORTED);
        }
        if (encoded.length() > MAX_ENCODED_CHARS) return DecodeResult.failure(Status.TOO_LARGE);
        int itemEnd = encoded.indexOf('|', PREFIX.length());
        if (itemEnd < 0) return DecodeResult.failure(Status.MALFORMED);
        ResourceLocation outerItem = ResourceLocation.tryParse(
                encoded.substring(PREFIX.length(), itemEnd));
        if (outerItem == null) return DecodeResult.failure(Status.MALFORMED);

        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(encoded.substring(itemEnd + 1));
        } catch (IllegalArgumentException invalidBase64) {
            return DecodeResult.failure(Status.MALFORMED);
        }
        if (binary.length > MAX_BINARY_BYTES) return DecodeResult.failure(Status.TOO_LARGE);

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(binary))) {
            int version = input.readInt();
            if (version != CURRENT_VERSION && version != SHOWN_CAUSE_VERSION) {
                return DecodeResult.failure(Status.UNSUPPORTED);
            }
            TransactionDirection direction = directionFromCode(readString(input));
            ResourceLocation itemId = ResourceLocation.tryParse(readString(input));
            int quantity = input.readInt();
            ContainerTransferPayload.MetadataState metadataState =
                    metadataFromCode(readString(input));
            String metadata = readNullableString(input);
            String metadataReason = readNullableString(input);
            ContainerTransferPayload.ActorState actor = actorFromCode(readString(input));
            String targetDisplay = readString(input);
            String providerId = readString(input);
            AccessKind accessKind = accessFromCode(readString(input));
            BackingContextKind backing = backingFromCode(readString(input));
            String backingReference = readString(input);
            // Version 2 only: the cause block. A missing block is MALFORMED (EOF), a non-canonical one too (IOException).
            ShownCause shownCause = version == SHOWN_CAUSE_VERSION ? ShownCause.readBlock(input) : null;
            if (input.read() != -1 || direction == null || itemId == null
                    || !outerItem.equals(itemId) || metadataState == null || actor == null
                    || accessKind == null || backing == null) {
                return DecodeResult.failure(Status.MALFORMED);
            }
            try {
                ContainerTransferPayload payload = new ContainerTransferPayload(
                        direction, itemId, quantity, metadataState, metadata, metadataReason,
                        actor, targetDisplay, providerId, accessKind, backing, backingReference, shownCause);
                if (!encoded.equals(encode(payload))) {
                    return DecodeResult.failure(Status.MALFORMED);
                }
                return DecodeResult.success(payload);
            } catch (IllegalArgumentException invalidPayload) {
                return DecodeResult.failure(Status.MALFORMED);
            }
        } catch (IOException | RuntimeException failure) {
            return DecodeResult.failure(Status.MALFORMED);
        }
    }

    /** The payload version {@link #encode} writes for {@code payload}: 2 with a shown cause, otherwise 1. */
    public static int versionOf(ContainerTransferPayload payload) {
        return Objects.requireNonNull(payload, "payload").shownCause() == null ? CURRENT_VERSION : SHOWN_CAUSE_VERSION;
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) writeString(output, value);
    }

    private static String readNullableString(DataInputStream input) throws IOException {
        return input.readBoolean() ? readString(input) : null;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BINARY_BYTES) throw new IllegalArgumentException("Payload field is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_BINARY_BYTES || length > input.available()) {
            throw new EOFException("Invalid container-transfer field length");
        }
        byte[] bytes = input.readNBytes(length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IOException("Container-transfer field is not valid UTF-8", malformedUtf8);
        }
    }

    private static String directionCode(TransactionDirection value) {
        return switch (value) {
            case DEPOSIT -> "deposit";
            case WITHDRAWAL -> "withdrawal";
        };
    }

    private static TransactionDirection directionFromCode(String value) {
        return switch (value) {
            case "deposit" -> TransactionDirection.DEPOSIT;
            case "withdrawal" -> TransactionDirection.WITHDRAWAL;
            default -> null;
        };
    }

    private static String metadataCode(ContainerTransferPayload.MetadataState value) {
        return switch (value) {
            case CAPTURED -> "captured";
            case UNAVAILABLE -> "unavailable";
        };
    }

    private static ContainerTransferPayload.MetadataState metadataFromCode(String value) {
        return switch (value) {
            case "captured" -> ContainerTransferPayload.MetadataState.CAPTURED;
            case "unavailable" -> ContainerTransferPayload.MetadataState.UNAVAILABLE;
            default -> null;
        };
    }

    private static String actorCode(ContainerTransferPayload.ActorState value) {
        return switch (value) {
            case PLAYER -> "player";
            case SYNTHETIC_PLAYER -> "synthetic_player";
        };
    }

    private static ContainerTransferPayload.ActorState actorFromCode(String value) {
        return switch (value) {
            case "player" -> ContainerTransferPayload.ActorState.PLAYER;
            case "synthetic_player" -> ContainerTransferPayload.ActorState.SYNTHETIC_PLAYER;
            default -> null;
        };
    }

    private static String accessCode(AccessKind value) {
        return switch (value) {
            case PHYSICAL -> "physical";
            case WIRED_NETWORK -> "wired_network";
            case WIRELESS_NETWORK -> "wireless_network";
            case PORTABLE_MENU -> "portable_menu";
            case DIRECT_INTERACTION -> "direct_interaction";
        };
    }

    private static AccessKind accessFromCode(String value) {
        return switch (value) {
            case "physical" -> AccessKind.PHYSICAL;
            case "wired_network" -> AccessKind.WIRED_NETWORK;
            case "wireless_network" -> AccessKind.WIRELESS_NETWORK;
            case "portable_menu" -> AccessKind.PORTABLE_MENU;
            case "direct_interaction" -> AccessKind.DIRECT_INTERACTION;
            default -> null;
        };
    }

    private static String backingCode(BackingContextKind value) {
        return switch (value) {
            case WORLD_BLOCK -> "world_block";
            case COMPOUND_BLOCK -> "compound_block";
            case ENTITY_BOUND -> "entity_bound";
            case ITEM_BOUND -> "item_bound";
            case NESTED_ITEM -> "nested_item";
            case PLACED_BLOCK -> "placed_block";
            case PLAYER_BOUND -> "player_bound";
            case OTHER_PLAYER_BOUND -> "other_player_bound";
            case NETWORK -> "network";
            case SHARED_CHANNEL -> "shared_channel";
            case PROXY -> "proxy";
            case MOVING_STORE -> "moving_store";
            case TRASH -> "trash";
            case UNKNOWN -> "unknown";
        };
    }

    private static BackingContextKind backingFromCode(String value) {
        return switch (value) {
            case "world_block" -> BackingContextKind.WORLD_BLOCK;
            case "compound_block" -> BackingContextKind.COMPOUND_BLOCK;
            case "entity_bound" -> BackingContextKind.ENTITY_BOUND;
            case "item_bound" -> BackingContextKind.ITEM_BOUND;
            case "nested_item" -> BackingContextKind.NESTED_ITEM;
            case "placed_block" -> BackingContextKind.PLACED_BLOCK;
            case "player_bound" -> BackingContextKind.PLAYER_BOUND;
            case "other_player_bound" -> BackingContextKind.OTHER_PLAYER_BOUND;
            case "network" -> BackingContextKind.NETWORK;
            case "shared_channel" -> BackingContextKind.SHARED_CHANNEL;
            case "proxy" -> BackingContextKind.PROXY;
            case "moving_store" -> BackingContextKind.MOVING_STORE;
            case "trash" -> BackingContextKind.TRASH;
            case "unknown" -> BackingContextKind.UNKNOWN;
            default -> null;
        };
    }

    public enum Status { OK, UNSUPPORTED, MALFORMED, TOO_LARGE }

    public record DecodeResult(Status status, ContainerTransferPayload payload) {
        private static DecodeResult success(ContainerTransferPayload payload) {
            return new DecodeResult(Status.OK, payload);
        }

        private static DecodeResult failure(Status status) {
            return new DecodeResult(status, null);
        }

        public boolean successful() {
            return status == Status.OK && payload != null;
        }
    }
}
