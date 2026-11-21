package io.github.betapetey.eventtrail.events.block;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Deterministic, bounded, versioned schema-v1 payload codec for block effects.
 *
 * <p>Explosion split (Owner decision B; Advisor answer 3): a payload without an explosion tag is version 1, byte for
 * byte as before; a payload with one is version 2 = the version-1 fields followed by the tag as one non-null string.
 * Nothing else decodes: any other version is UNSUPPORTED, a version 2 without its tag or with a byte after it is
 * MALFORMED. The prefix stays {@link #PREFIX} (it names the row-schema family). {@link #CURRENT_VERSION} stays 1, the
 * evidence version of every untagged row.</p>
 */
public final class BlockEffectPayloadCodec {
    public static final int CURRENT_VERSION = 1;
    /** The payload version of a row that carries an explosion tag (an explosion stored in parts). */
    public static final int TAGGED_VERSION = 2;
    public static final String PREFIX = "EVENTTRAIL_BLOCK_EFFECT_V1:";
    public static final int MAX_BINARY_BYTES = 70 * 1024;
    public static final int MAX_ENCODED_UTF8_BYTES = 96 * 1024;

    private BlockEffectPayloadCodec() {
    }

    public static String encode(BlockEffectPayload payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(versionOf(payload));
                write(output, payload.subjectKind().storageCode());
                write(output, payload.technicalIdentity());
                write(output, payload.presentationIdentity());
                write(output, payload.actorKind().storageCode());
                write(output, payload.causality().storageCode());
                writeNullable(output, payload.causeDetail());
                write(output, payload.blockEntityEvidence().status().storageCode());
                write(output, payload.truncation().storageCode());
                writeNullable(output,
                        payload.blockEntityEvidence().canonicalSnbt().orElse(null));
                if (payload.explosionTag() != null) write(output, payload.explosionTag());
            }
            byte[] binary = bytes.toByteArray();
            if (binary.length > MAX_BINARY_BYTES) {
                throw new IllegalArgumentException(
                        "Block-effect payload exceeds its retained-size limit");
            }
            String encoded = PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(binary);
            if (utf8Length(encoded) > MAX_ENCODED_UTF8_BYTES) {
                throw new IllegalArgumentException(
                        "Block-effect payload exceeds its encoded-size limit");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory block-effect encoding failed", impossible);
        }
    }

    public static DecodeResult decode(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return DecodeResult.failure(DecodeStatus.UNSUPPORTED);
        }
        if (stored.length() > MAX_ENCODED_UTF8_BYTES) {
            return DecodeResult.failure(DecodeStatus.TOO_LARGE);
        }
        if (utf8Length(stored) > MAX_ENCODED_UTF8_BYTES) {
            return DecodeResult.failure(DecodeStatus.TOO_LARGE);
        }

        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException invalidBase64) {
            return DecodeResult.failure(DecodeStatus.MALFORMED);
        }
        if (binary.length > MAX_BINARY_BYTES) {
            return DecodeResult.failure(DecodeStatus.TOO_LARGE);
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(binary))) {
            int version = input.readInt();
            if (version != CURRENT_VERSION && version != TAGGED_VERSION) {
                return DecodeResult.failure(DecodeStatus.UNSUPPORTED);
            }
            BlockEffectPayload.SubjectKind subjectKind = BlockEffectPayload.SubjectKind
                    .fromStorageCode(read(input)).orElse(null);
            String technicalIdentity = read(input);
            String presentation = read(input);
            BlockEffectPayload.ActorKind actorKind = BlockEffectPayload.ActorKind
                    .fromStorageCode(read(input)).orElse(null);
            BlockEffectPayload.Causality causality = BlockEffectPayload.Causality
                    .fromStorageCode(read(input)).orElse(null);
            String cause = readNullable(input);
            BlockEntityEvidence.Status metadataStatus = BlockEntityEvidence.Status
                    .fromStorageCode(read(input)).orElse(null);
            BlockEffectTruncation truncation = BlockEffectTruncation
                    .fromStorageCode(read(input)).orElse(null);
            String canonicalMetadata = readNullable(input);
            // Version 2 only: the explosion tag, one non-null string; a missing tag is MALFORMED (EOF).
            String explosionTag = version == TAGGED_VERSION ? read(input) : null;
            if (input.read() != -1 || subjectKind == null || actorKind == null
                    || causality == null || metadataStatus == null || truncation == null) {
                return DecodeResult.failure(DecodeStatus.MALFORMED);
            }

            BlockEntityEvidence evidence;
            if (metadataStatus == BlockEntityEvidence.Status.CAPTURED) {
                if (canonicalMetadata == null || canonicalMetadata.isBlank()) {
                    return DecodeResult.failure(DecodeStatus.MALFORMED);
                }
                if (canonicalMetadata.length() > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_CHARS
                        || utf8Length(canonicalMetadata)
                            > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_UTF8_BYTES) {
                    return DecodeResult.failure(DecodeStatus.TOO_LARGE);
                }
                CompoundTag parsed = TagParser.parseTag(canonicalMetadata);
                CompoundTag sanitized = BlockEntityTagSanitizer.sanitize(parsed);
                String normalized = BlockEntityTagSanitizer.canonicalSnbt(sanitized);
                if (!canonicalMetadata.equals(normalized)
                        || sanitized.sizeInBytes()
                            > BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_NBT_BYTES) {
                    return DecodeResult.failure(DecodeStatus.MALFORMED);
                }
                evidence = BlockEntityEvidence.captured(sanitized, normalized);
            } else {
                if (canonicalMetadata != null) {
                    return DecodeResult.failure(DecodeStatus.MALFORMED);
                }
                evidence = BlockEntityEvidence.outcome(metadataStatus);
            }

            try {
                BlockEffectPayload payload = new BlockEffectPayload(
                        subjectKind,
                        technicalIdentity,
                        presentation,
                        actorKind,
                        causality,
                        cause,
                        evidence,
                        truncation,
                        explosionTag);
                if (!stored.equals(encode(payload))) {
                    return DecodeResult.failure(DecodeStatus.MALFORMED);
                }
                return DecodeResult.success(payload);
            } catch (IllegalArgumentException invalidPayload) {
                return DecodeResult.failure(DecodeStatus.MALFORMED);
            }
        } catch (EOFException | CharacterCodingException failure) {
            return DecodeResult.failure(DecodeStatus.MALFORMED);
        } catch (CommandSyntaxException failure) {
            return DecodeResult.failure(DecodeStatus.MALFORMED);
        } catch (IOException | RuntimeException failure) {
            return DecodeResult.failure(DecodeStatus.MALFORMED);
        } catch (StackOverflowError failure) {
            return DecodeResult.failure(DecodeStatus.TOO_LARGE);
        }
    }

    /** The payload version {@link #encode} writes for {@code payload}: 2 with an explosion tag, otherwise 1. */
    public static int versionOf(BlockEffectPayload payload) {
        return Objects.requireNonNull(payload, "payload").explosionTag() == null ? CURRENT_VERSION : TAGGED_VERSION;
    }

    /**
     * Advisor M-1: the encoded length (UTF-8 bytes, all ASCII) of the payload {@code untaggedEncoded} once a tag of
     * {@code tagUtf8Bytes} bytes is added (version 1 to version 2: the same fields, then a 4-byte length and the tag),
     * or -1 when that payload would pass {@link #MAX_BINARY_BYTES} or {@link #MAX_ENCODED_UTF8_BYTES}, so
     * {@link #encode} would throw. Computed from lengths only: no decode, no allocation.
     *
     * @param untaggedEncoded a version-1 payload exactly as {@link #encode} wrote it
     */
    public static int taggedEncodedLength(String untaggedEncoded, int tagUtf8Bytes) {
        Objects.requireNonNull(untaggedEncoded, "untaggedEncoded");
        if (!untaggedEncoded.startsWith(PREFIX)) throw new IllegalArgumentException("Not a block-effect payload");
        if (tagUtf8Bytes < 0) throw new IllegalArgumentException("tagUtf8Bytes must not be negative");
        long base64Chars = untaggedEncoded.length() - PREFIX.length();
        // Unpadded base64: every 4 characters are 3 bytes, a trailing 2 or 3 characters 1 or 2 bytes.
        long untaggedBinary = base64Chars * 3L / 4L;
        long taggedBinary = untaggedBinary + Integer.BYTES + tagUtf8Bytes;
        if (taggedBinary > MAX_BINARY_BYTES) return -1;
        long taggedEncoded = PREFIX.length() + (taggedBinary * 4L + 2L) / 3L;
        if (taggedEncoded > MAX_ENCODED_UTF8_BYTES) return -1;
        return (int) taggedEncoded;
    }

    private static void writeNullable(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) write(output, value);
    }

    private static String readNullable(DataInputStream input)
            throws IOException, CharacterCodingException {
        return input.readBoolean() ? read(input) : null;
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BINARY_BYTES) {
            throw new IllegalArgumentException("Block-effect payload field is too large");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String read(DataInputStream input)
            throws IOException, CharacterCodingException {
        int length = input.readInt();
        if (length < 0 || length > MAX_BINARY_BYTES || length > input.available()) {
            throw new EOFException("Invalid block-effect payload field length");
        }
        byte[] bytes = input.readNBytes(length);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public enum DecodeStatus { OK, MALFORMED, TOO_LARGE, UNSUPPORTED }

    public record DecodeResult(DecodeStatus status, BlockEffectPayload payload) {
        public DecodeResult {
            status = Objects.requireNonNull(status, "status");
            if ((status == DecodeStatus.OK) != (payload != null)) {
                throw new IllegalArgumentException(
                        "Only successful decoding may contain a payload");
            }
        }

        private static DecodeResult success(BlockEffectPayload payload) {
            return new DecodeResult(DecodeStatus.OK, payload);
        }

        private static DecodeResult failure(DecodeStatus status) {
            return new DecodeResult(status, null);
        }

        public boolean successful() {
            return status == DecodeStatus.OK;
        }
    }
}
