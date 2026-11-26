package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.audit.SchemaV1Row;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * The disk spool's frame payload, format version 1 (writer-spool design-02 3.1, 3.2; design-01 3 "payload" and
 * "String"). One frame is one admitted action: 1 to {@link #MAX_ROWS} schema-v1 rows in capture order. A row is
 * i64 timestamp, i32 x, i32 y, i32 z, then six strings in column order (action code, indexed uuid, indexed name,
 * dimension id, subject identity, evidence payload). A string is u8 tag (0 null, 1 strict UTF-8, 2 UTF-16BE) + u32
 * byte length + bytes; tag 2 only when strict UTF-8 refuses the string (an unpaired surrogate), so every Java string
 * round-trips exactly. All integers are big-endian.
 *
 * <p>The limits are format-v1 constants (n-2), never the writer's overridable limits. The writer's retained estimate
 * of a row is at least 512 + 2 per char (SchemaV1RowCodec) and the encoding is at most 50 + 3 per char, so a row
 * encodes to at most 1.5 times its estimate (n-1): a row the writer admits (at most 256 KiB) encodes to at most
 * {@link #MAX_ROW_BYTES}, an action (at most 32 MiB) to at most {@link #MAX_PAYLOAD_BYTES}.</p>
 *
 * <p>The decoder is strict: an unknown tag, a bad length, malformed UTF-8, a non-canonical tag 2, a row above the
 * row limit, or a payload the row count does not consume exactly is refused ({@link UnreadableFrame}).</p>
 */
final class SpoolFrameCodec {
    static final int FORMAT_VERSION = 1;
    static final int MAX_ROWS = 1024;
    static final int MAX_ROW_BYTES = 384 * 1024;
    static final int MAX_PAYLOAD_BYTES = 48 * 1024 * 1024;
    /** Fixed bytes of one encoded row: 8 + 3 x 4 + 6 x (1 + 4). */
    static final int ROW_FIXED_BYTES = 50;

    private static final byte TAG_NULL = 0;
    private static final byte TAG_UTF8 = 1;
    private static final byte TAG_UTF16BE = 2;

    private SpoolFrameCodec() {}

    /** A stored frame whose bytes fail the strict decoder or the CRC; never inserted (S-6). */
    static final class UnreadableFrame extends Exception {
        UnreadableFrame(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * Encodes one action. Throws IllegalArgumentException when the action is outside the format's limits; that can
     * only be a codec fault of this one action (m-3), never a failure of the file.
     */
    static byte[] encode(List<SchemaV1Row> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("A spool frame holds 1 to " + MAX_ROWS + " rows");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(rows.size() * 256);
        CharsetEncoder encoder = strictUtf8Encoder();
        long total = 0L;
        for (SchemaV1Row row : rows) {
            if (row == null) throw new IllegalArgumentException("A spool frame row must not be null");
            byte[] encoded = encodeRow(row, encoder);
            if (encoded.length > MAX_ROW_BYTES) {
                throw new IllegalArgumentException("A spool row encodes above the format-v1 row limit");
            }
            total += encoded.length;
            if (total > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("A spool frame encodes above the format-v1 payload limit");
            }
            out.writeBytes(encoded);
        }
        return out.toByteArray();
    }

    private static byte[] encodeRow(SchemaV1Row row, CharsetEncoder encoder) {
        byte[][] strings = {
                encodeString(row.actionCode(), encoder), encodeString(row.indexedUuid(), encoder),
                encodeString(row.indexedName(), encoder), encodeString(row.dimensionId(), encoder),
                encodeString(row.subjectIdentity(), encoder), encodeString(row.evidencePayload(), encoder)};
        long size = 20L;
        for (byte[] value : strings) size += value.length;
        if (size > MAX_ROW_BYTES) {
            throw new IllegalArgumentException("A spool row encodes above the format-v1 row limit");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        buffer.putLong(row.timestamp()).putInt(row.x()).putInt(row.y()).putInt(row.z());
        for (byte[] value : strings) buffer.put(value);
        return buffer.array();
    }

    private static byte[] encodeString(String value, CharsetEncoder encoder) {
        if (value == null) return new byte[]{TAG_NULL, 0, 0, 0, 0};
        byte tag;
        byte[] bytes;
        try {
            ByteBuffer encoded = encoder.reset().encode(CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            tag = TAG_UTF8;
        } catch (CharacterCodingException unpaired) {
            bytes = new byte[value.length() * 2];
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                bytes[2 * i] = (byte) (c >>> 8);
                bytes[2 * i + 1] = (byte) c;
            }
            tag = TAG_UTF16BE;
        }
        ByteBuffer out = ByteBuffer.allocate(5 + bytes.length);
        out.put(tag).putInt(bytes.length).put(bytes);
        return out.array();
    }

    /**
     * Owner Q397 A: the exact byte length {@link #encode} gives these rows (the payload the spool counts as waiting on
     * disk), worked out without encoding: one pass over each string's chars, no allocation. Used for the cap test
     * ("full"), so a staged action counts exactly the disk bytes it will take once committed. The format limits are
     * not checked here; an action outside them fails at encode on the spool thread as before.
     */
    static long encodedLength(List<SchemaV1Row> rows) {
        long total = 0L;
        for (SchemaV1Row row : rows) total += encodedLength(row);
        return total;
    }

    /** Q397 A: the exact byte length of one encoded row ({@link #ROW_FIXED_BYTES} plus the six strings' bytes). */
    static long encodedLength(SchemaV1Row row) {
        return ROW_FIXED_BYTES + stringBytes(row.actionCode()) + stringBytes(row.indexedUuid())
                + stringBytes(row.indexedName()) + stringBytes(row.dimensionId())
                + stringBytes(row.subjectIdentity()) + stringBytes(row.evidencePayload());
    }

    /**
     * Q397 A: the bytes {@link #encodeString} writes after its 5-byte header: 0 for null; the strict UTF-8 length
     * (1, 2 or 3 bytes per char, 4 per surrogate pair); or, when the string has an unpaired surrogate (strict UTF-8
     * refuses it), 2 per char (UTF-16BE).
     */
    static long stringBytes(String value) {
        if (value == null) return 0L;
        int n = value.length();
        long utf8 = 0L;
        for (int i = 0; i < n; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                utf8 += 1L;
            } else if (c < 0x800) {
                utf8 += 2L;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < n && Character.isLowSurrogate(value.charAt(i + 1))) {
                    utf8 += 4L;
                    i++;
                } else {
                    return 2L * n;
                }
            } else if (Character.isLowSurrogate(c)) {
                return 2L * n;
            } else {
                utf8 += 3L;
            }
        }
        return utf8;
    }

    /** Strict decode of a payload that must hold exactly {@code rowCount} rows. */
    static List<SchemaV1Row> decode(byte[] payload, int rowCount) throws UnreadableFrame {
        if (payload == null) throw new UnreadableFrame("payload absent");
        if (rowCount < 1 || rowCount > MAX_ROWS) throw new UnreadableFrame("row count outside the format");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new UnreadableFrame("payload above the format limit");
        ByteBuffer in = ByteBuffer.wrap(payload);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        CharsetEncoder encoder = strictUtf8Encoder();
        List<SchemaV1Row> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            int start = in.position();
            if (in.remaining() < 20) throw new UnreadableFrame("row header cut off");
            long timestamp = in.getLong();
            int x = in.getInt();
            int y = in.getInt();
            int z = in.getInt();
            String actionCode = decodeString(in, decoder, encoder);
            String indexedUuid = decodeString(in, decoder, encoder);
            String indexedName = decodeString(in, decoder, encoder);
            String dimensionId = decodeString(in, decoder, encoder);
            String subjectIdentity = decodeString(in, decoder, encoder);
            String evidencePayload = decodeString(in, decoder, encoder);
            if (in.position() - start > MAX_ROW_BYTES) throw new UnreadableFrame("row above the format limit");
            rows.add(new SchemaV1Row(timestamp, actionCode, indexedUuid, indexedName, dimensionId, x, y, z,
                    subjectIdentity, evidencePayload));
        }
        if (in.hasRemaining()) throw new UnreadableFrame("trailing bytes after the last row");
        return List.copyOf(rows);
    }

    private static String decodeString(ByteBuffer in, CharsetDecoder decoder, CharsetEncoder encoder)
            throws UnreadableFrame {
        if (in.remaining() < 5) throw new UnreadableFrame("string header cut off");
        byte tag = in.get();
        int length = in.getInt();
        if (length < 0 || length > in.remaining() || length > MAX_ROW_BYTES) {
            throw new UnreadableFrame("string length outside the payload");
        }
        switch (tag) {
            case TAG_NULL -> {
                if (length != 0) throw new UnreadableFrame("null string with a length");
                return null;
            }
            case TAG_UTF8 -> {
                ByteBuffer slice = in.slice(in.position(), length);
                in.position(in.position() + length);
                try {
                    return decoder.reset().decode(slice).toString();
                } catch (CharacterCodingException malformed) {
                    throw new UnreadableFrame("malformed UTF-8");
                }
            }
            case TAG_UTF16BE -> {
                if ((length & 1) != 0) throw new UnreadableFrame("odd UTF-16BE length");
                char[] chars = new char[length / 2];
                for (int i = 0; i < chars.length; i++) chars[i] = in.getChar();
                String value = new String(chars);
                // Canonical form only: tag 2 is written only for strings strict UTF-8 refuses.
                if (encoder.reset().canEncode(value)) throw new UnreadableFrame("non-canonical UTF-16BE string");
                return value;
            }
            default -> throw new UnreadableFrame("unknown string tag");
        }
    }

    /** CRC32C over seq (8 B BE), row count (4 B BE) and the payload (design-02 3.1). */
    static long crc(long seq, int rowCount, byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(ByteBuffer.allocate(12).putLong(seq).putInt(rowCount).flip());
        crc.update(payload, 0, payload.length);
        return crc.getValue();
    }

    /** The smallest row timestamp of an action (the age test, design-02 6.6). */
    static long minTimestamp(List<SchemaV1Row> rows) {
        long min = Long.MAX_VALUE;
        for (SchemaV1Row row : rows) min = Math.min(min, row.timestamp());
        return min;
    }

    private static CharsetEncoder strictUtf8Encoder() {
        return StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }
}
