package io.github.betapetey.eventtrail.audit.codec;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * The P4 field forms every new evidence version shares (ItemDetails plan 2.1): a version byte that is never 0x00 (an
 * old binary payload starts with an int32 version, so with 0x00), minimal unsigned LEB128 varints, short strings
 * (varint byte length and strict UTF-8), name strings (today's int32 length and UTF-8), code bytes from a fixed table
 * per field and flag bytes. Every reader refuses what the writer never writes: a non-minimal or over-maximum varint,
 * invalid UTF-8, an unknown code or flag bit, a truncated field.
 */
public final class CompactIO {
    /** The two forms a name string could take (ItemDetails plan Q3). */
    public enum NameForm { INT32, VARINT }

    /** The name-string length form: today's 4-byte length (plan Q3, answer A). One named constant. */
    public static final NameForm NAME_STRING_FORM = NameForm.INT32;

    private static final int MAX_VARINT_BYTES = 5;

    private CompactIO() {}

    /** The number of bytes {@link Writer#varint(int)} writes for {@code value}. */
    public static int varintLength(int value) {
        if (value < 0) throw new IllegalArgumentException("negative varint " + value);
        int length = 1;
        while ((value >>>= 7) != 0) length++;
        return length;
    }

    /** A stored field that is not in the form the writer writes. */
    public static final class MalformedException extends Exception {
        private static final long serialVersionUID = 1L;

        public MalformedException(String message) {
            super(message);
        }

        public MalformedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One fixed code per value of an enum field; a code is 0 to 255 and belongs to one value only. */
    public static final class CodeTable<E extends Enum<E>> {
        private final int[] codeByOrdinal;
        private final E[] valueByCode;

        private CodeTable(int[] codeByOrdinal, E[] valueByCode) {
            this.codeByOrdinal = codeByOrdinal;
            this.valueByCode = valueByCode;
        }

        public static <E extends Enum<E>> CodeTable<E> of(Class<E> type, ToIntFunction<E> code) {
            E[] values = type.getEnumConstants();
            int[] codeByOrdinal = new int[values.length];
            @SuppressWarnings("unchecked")
            E[] valueByCode = (E[]) java.lang.reflect.Array.newInstance(type, 256);
            for (E value : values) {
                int assigned = code.applyAsInt(value);
                if (assigned < 0 || assigned > 0xFF) {
                    throw new IllegalArgumentException("code " + assigned + " for " + value + " is not one byte");
                }
                if (valueByCode[assigned] != null) {
                    throw new IllegalArgumentException("code " + assigned + " is given to " + valueByCode[assigned]
                            + " and " + value);
                }
                valueByCode[assigned] = value;
                codeByOrdinal[value.ordinal()] = assigned;
            }
            return new CodeTable<>(codeByOrdinal, valueByCode);
        }

        public int codeOf(E value) {
            return codeByOrdinal[Objects.requireNonNull(value, "value").ordinal()];
        }

        /** The value with this code, or null when the table has none. */
        public E valueOf(int code) {
            return code < 0 || code > 0xFF ? null : valueByCode[code];
        }
    }

    /** Writes the field forms; a value the reader would refuse is an {@link IllegalArgumentException}. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Writer varint(int value) {
            if (value < 0) throw new IllegalArgumentException("negative varint " + value);
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
            return this;
        }

        public Writer shortString(String value, int maxBytes) {
            byte[] utf8 = strictUtf8(value, maxBytes);
            varint(utf8.length);
            out.writeBytes(utf8);
            return this;
        }

        public Writer nameString(String value, int maxBytes) {
            byte[] utf8 = strictUtf8(value, maxBytes);
            if (NAME_STRING_FORM == NameForm.INT32) {
                out.write(utf8.length >>> 24);
                out.write(utf8.length >>> 16);
                out.write(utf8.length >>> 8);
                out.write(utf8.length);
            } else {
                varint(utf8.length);
            }
            out.writeBytes(utf8);
            return this;
        }

        public Writer version(int version) {
            if (version < 1 || version > 0xFF) throw new IllegalArgumentException("version " + version);
            out.write(version);
            return this;
        }

        public Writer code(int code) {
            if (code < 0 || code > 0xFF) throw new IllegalArgumentException("code " + code);
            out.write(code);
            return this;
        }

        public <E extends Enum<E>> Writer code(CodeTable<E> table, E value) {
            return code(table.codeOf(value));
        }

        public Writer flags(int flags, int mask) {
            if (mask < 0 || mask > 0xFF || (flags & ~mask) != 0) {
                throw new IllegalArgumentException("flags " + flags + " outside mask " + mask);
            }
            out.write(flags);
            return this;
        }

        public Writer bytes(byte[] bytes) {
            out.writeBytes(Objects.requireNonNull(bytes, "bytes"));
            return this;
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }

        private static byte[] strictUtf8(String value, int maxBytes) {
            Objects.requireNonNull(value, "value");
            try {
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(value));
                byte[] utf8 = Arrays.copyOfRange(encoded.array(), encoded.arrayOffset() + encoded.position(),
                        encoded.arrayOffset() + encoded.limit());
                if (utf8.length > maxBytes) {
                    throw new IllegalArgumentException(utf8.length + " UTF-8 bytes over the field's " + maxBytes);
                }
                return utf8;
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("text is not valid UTF-16 (a lone surrogate)", invalid);
            }
        }
    }

    /** Reads the field forms from a window of a byte array; positions are counted from the window's start. */
    public static final class Reader {
        private final byte[] data;
        private final int start;
        private final int end;
        private int position;

        public Reader(byte[] data) {
            this(data, 0, Objects.requireNonNull(data, "data").length);
        }

        public Reader(byte[] data, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, Objects.requireNonNull(data, "data").length);
            this.data = data;
            this.start = offset;
            this.end = offset + length;
            this.position = offset;
        }

        public int position() {
            return position - start;
        }

        public int remaining() {
            return end - position;
        }

        public void requireEnd() throws MalformedException {
            if (position != end) throw new MalformedException(remaining() + " trailing bytes");
        }

        public int varint(int max) throws MalformedException {
            int value = 0;
            for (int index = 0; index < MAX_VARINT_BYTES; index++) {
                int current = nextByte("varint");
                if (index == MAX_VARINT_BYTES - 1 && current > 0x07) {
                    throw new MalformedException("varint over 31 bits");
                }
                value |= (current & 0x7F) << (7 * index);
                if ((current & 0x80) == 0) {
                    if (index > 0 && current == 0) throw new MalformedException("non-minimal varint");
                    if (value > max) throw new MalformedException("varint " + value + " over the field's " + max);
                    return value;
                }
            }
            throw new MalformedException("varint longer than five bytes");
        }

        public String shortString(int maxBytes) throws MalformedException {
            return strictText(bytes(varint(maxBytes)));
        }

        public String nameString(int maxBytes) throws MalformedException {
            int length;
            if (NAME_STRING_FORM == NameForm.INT32) {
                if (remaining() < 4) throw new MalformedException("truncated name length");
                length = ((data[position] & 0xFF) << 24) | ((data[position + 1] & 0xFF) << 16)
                        | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
                position += 4;
                if (length < 0 || length > maxBytes) {
                    throw new MalformedException("name length " + length + " outside 0.." + maxBytes);
                }
            } else {
                length = varint(maxBytes);
            }
            return strictText(bytes(length));
        }

        public int version() throws MalformedException {
            int version = nextByte("version");
            if (version == 0) throw new MalformedException("version byte 0x00 (an old int32 version)");
            return version;
        }

        public int code() throws MalformedException {
            return nextByte("code");
        }

        public <E extends Enum<E>> E code(CodeTable<E> table) throws MalformedException {
            int code = code();
            E value = table.valueOf(code);
            if (value == null) throw new MalformedException("unknown code " + code);
            return value;
        }

        public int flags(int mask) throws MalformedException {
            int flags = nextByte("flags");
            if ((flags & ~mask) != 0) throw new MalformedException("unknown flag bits " + (flags & ~mask));
            return flags;
        }

        public byte[] bytes(int length) throws MalformedException {
            if (length < 0 || length > remaining()) {
                throw new MalformedException("field of " + length + " bytes, " + remaining() + " left");
            }
            byte[] copy = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return copy;
        }

        /** A copy of the window's bytes from {@code from} to {@code to}, counted from the window's start. */
        public byte[] slice(int from, int to) {
            Objects.checkFromToIndex(from, to, end - start);
            return Arrays.copyOfRange(data, start + from, start + to);
        }

        private int nextByte(String field) throws MalformedException {
            if (position >= end) throw new MalformedException("truncated " + field);
            return data[position++] & 0xFF;
        }

        private static String strictText(byte[] utf8) throws MalformedException {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(utf8))
                        .toString();
            } catch (CharacterCodingException invalid) {
                throw new MalformedException("invalid UTF-8", invalid);
            }
        }
    }
}
