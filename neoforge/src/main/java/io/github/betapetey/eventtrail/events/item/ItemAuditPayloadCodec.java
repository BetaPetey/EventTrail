package io.github.betapetey.eventtrail.events.item;

import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Deterministic bounded schema-v1 payload codec for committed item events. */
public final class ItemAuditPayloadCodec {
    public static final int CURRENT_VERSION = 1;
    public static final String PREFIX = "EVENTTRAIL_ITEM_EFFECT_V1|";
    public static final int MAX_BINARY_BYTES = 68 * 1024;
    public static final int MAX_ENCODED_CHARS = 94 * 1024;

    private ItemAuditPayloadCodec() {}

    public static String encode(ItemAuditPayload payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(CURRENT_VERSION);
                write(output, outcomeCode(payload.outcome()));
                write(output, payload.itemId().toString());
                output.writeInt(payload.quantity());
                write(output, payload.presentationIdentity());
                write(output, actorCode(payload.actorState()));
                write(output, metadataCode(payload.metadataState()));
                writeNullable(output, payload.metadataSnbt());
                writeNullable(output, payload.unavailableReason());
            }
            byte[] binary = bytes.toByteArray();
            if (binary.length > MAX_BINARY_BYTES) {
                throw new IllegalArgumentException("Item payload exceeds its retained-size limit");
            }
            String encoded = PREFIX + payload.itemId() + '|'
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(binary);
            if (encoded.length() > MAX_ENCODED_CHARS) {
                throw new IllegalArgumentException("Item payload exceeds its encoded-size limit");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory item payload encoding failed", impossible);
        }
    }

    public static DecodeResult decode(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            return DecodeResult.failure(Status.UNSUPPORTED);
        }
        if (encoded.length() > MAX_ENCODED_CHARS) return DecodeResult.failure(Status.TOO_LARGE);
        int itemEnd = encoded.indexOf('|', PREFIX.length());
        if (itemEnd < 0) return DecodeResult.failure(Status.MALFORMED);
        ResourceLocation outer = ResourceLocation.tryParse(encoded.substring(PREFIX.length(), itemEnd));
        if (outer == null) return DecodeResult.failure(Status.MALFORMED);

        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(encoded.substring(itemEnd + 1));
        } catch (IllegalArgumentException failure) {
            return DecodeResult.failure(Status.MALFORMED);
        }
        if (binary.length > MAX_BINARY_BYTES) return DecodeResult.failure(Status.TOO_LARGE);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(binary))) {
            if (input.readInt() != CURRENT_VERSION) return DecodeResult.failure(Status.UNSUPPORTED);
            ItemAuditPayload.Outcome outcome = outcomeFromCode(read(input));
            ResourceLocation itemId = ResourceLocation.tryParse(read(input));
            int quantity = input.readInt();
            String presentation = read(input);
            ItemAuditPayload.ActorState actor = actorFromCode(read(input));
            ItemAuditPayload.MetadataState metadata = metadataFromCode(read(input));
            String snbt = readNullable(input);
            String reason = readNullable(input);
            if (input.read() != -1 || outcome == null || itemId == null || !outer.equals(itemId)
                    || actor == null || metadata == null) {
                return DecodeResult.failure(Status.MALFORMED);
            }
            try {
                ItemAuditPayload payload = new ItemAuditPayload(
                        outcome, itemId, quantity, presentation, actor, metadata, snbt, reason);
                if (!encoded.equals(encode(payload))) {
                    return DecodeResult.failure(Status.MALFORMED);
                }
                return DecodeResult.success(payload);
            } catch (IllegalArgumentException invalid) {
                return DecodeResult.failure(Status.MALFORMED);
            }
        } catch (IOException | RuntimeException failure) {
            return DecodeResult.failure(Status.MALFORMED);
        }
    }

    private static void writeNullable(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) write(output, value);
    }

    private static String readNullable(DataInputStream input) throws IOException {
        return input.readBoolean() ? read(input) : null;
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BINARY_BYTES) throw new IllegalArgumentException("Item payload field is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String read(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_BINARY_BYTES || length > input.available()) {
            throw new EOFException("Invalid item payload field length");
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static String outcomeCode(ItemAuditPayload.Outcome value) {
        return switch (value) {
            case DROP -> "drop";
            case PICKUP -> "pickup";
        };
    }

    private static ItemAuditPayload.Outcome outcomeFromCode(String value) {
        return switch (value) {
            case "drop" -> ItemAuditPayload.Outcome.DROP;
            case "pickup" -> ItemAuditPayload.Outcome.PICKUP;
            default -> null;
        };
    }

    private static String actorCode(ItemAuditPayload.ActorState value) {
        return switch (value) {
            case PLAYER -> "player";
            case SYNTHETIC_PLAYER -> "synthetic_player";
        };
    }

    private static ItemAuditPayload.ActorState actorFromCode(String value) {
        return switch (value) {
            case "player" -> ItemAuditPayload.ActorState.PLAYER;
            case "synthetic_player" -> ItemAuditPayload.ActorState.SYNTHETIC_PLAYER;
            default -> null;
        };
    }

    private static String metadataCode(ItemAuditPayload.MetadataState value) {
        return switch (value) {
            case CAPTURED -> "captured";
            case UNAVAILABLE -> "unavailable";
        };
    }

    private static ItemAuditPayload.MetadataState metadataFromCode(String value) {
        return switch (value) {
            case "captured" -> ItemAuditPayload.MetadataState.CAPTURED;
            case "unavailable" -> ItemAuditPayload.MetadataState.UNAVAILABLE;
            default -> null;
        };
    }

    public enum Status { OK, UNSUPPORTED, MALFORMED, TOO_LARGE }

    public record DecodeResult(Status status, ItemAuditPayload payload) {
        private static DecodeResult success(ItemAuditPayload payload) {
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
