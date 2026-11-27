package io.github.betapetey.eventtrail.util;

import io.github.betapetey.eventtrail.audit.codec.CompactIO;
import io.github.betapetey.eventtrail.events.block.BlockAuditLimits;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The one shared binary store for item and block-entity details (ItemDetails plan 1.2): the tree in NbtIo's binary
 * form, which keeps what the text form loses ({@code ""} keys, NaN and infinite numbers, exact types), framed as
 * <pre>
 *   form byte     bit0 DEFLATED, bit1 ID_COUNT_LEFT_OUT (held for H3, refused), bit2 REENCODE_DIFFERED, bits 3-7 zero
 *   rawLength     varint, 1 .. the family's binary cap
 *   storedLength  varint, DEFLATED only, 1 .. rawLength - 1
 *   data          rawLength raw bytes, or storedLength zlib bytes
 * </pre>
 * P7: zlib level 1 only when the raw form is over {@value #DEFLATE_ABOVE_BYTES} bytes, kept only when smaller.
 *
 * <p>The reader is bounded and needs no registries: an exact inflate into rawLength bytes (fewer, more, unconsumed
 * input or a bad Adler-32 is malformed), {@code NbtIo.read} under a {@link NbtAccounter} quota, every raw byte
 * consumed, then EventTrail's own iterative {@link #walk} (depth and nodes) and the {@code sizeInBytes} cap.
 */
public final class DetailsBlob {
    public static final int FORM_DEFLATED = 0x01;
    /** Held for H3 (not built in B0): never written, refused by every B0 reader. */
    public static final int FORM_ID_COUNT_LEFT_OUT = 0x02;
    public static final int FORM_REENCODE_DIFFERED = 0x04;
    /** The bits a B0 reader accepts. */
    static final int FORM_READABLE_BITS = FORM_DEFLATED | FORM_REENCODE_DIFFERED;
    /** P7's threshold: compress only a raw form over this many bytes. */
    public static final int DEFLATE_ABOVE_BYTES = 512;
    /** P7's zlib level. */
    public static final int DEFLATE_LEVEL = 1;
    /** NbtIo's read quota, as a multiple of the family's cap (its own estimate counts more than the bytes). */
    private static final long READ_QUOTA_PER_CAP_BYTE = 4L;
    private static final int TAG_COMPOUND = Tag.TAG_COMPOUND;

    private DetailsBlob() {}

    /**
     * How a family counts depth today; {@code maxDepth} is read under this rule.
     * <ul>
     *   <li>{@link #TEXT_BRACKETS}: {@code AuditSnbtParser.validateStructure}'s count of the text's brackets. The
     *       root compound is 1, a compound, a list and an array each open one level, other tags open none.</li>
     *   <li>{@link #EVERY_TAG}: {@code BlockEntityTagSanitizer}'s rule. The root is 0 and every tag, leaves
     *       included, is one level below its parent; a tag deeper than {@code maxDepth} is refused.</li>
     * </ul>
     */
    public enum DepthRule { TEXT_BRACKETS, EVERY_TAG }

    /**
     * One family's limits, from today's constants. {@code maxUtf8Bytes} {@link #UNCHECKED} means the family does not
     * check the UTF-8 length today (kills and deaths). {@code depthRule} is the family's own way of counting depth.
     */
    public record Limits(int maxBinaryBytes, int maxNbtBytes, int maxTextChars, int maxUtf8Bytes, int maxDepth,
                         int maxNodes, DepthRule depthRule) {
        public static final int UNCHECKED = Integer.MAX_VALUE;
        /** Items: the NBT, text and UTF-8 caps of the item and transfer checks, the text reader's depth rule. */
        public static final Limits ITEM = new Limits(EventTrailTextBounds.MAX_ITEM_METADATA_NBT_BYTES,
                EventTrailTextBounds.MAX_ITEM_METADATA_NBT_BYTES, EventTrailTextBounds.MAX_ITEM_METADATA_CHARS,
                EventTrailTextBounds.MAX_ITEM_METADATA_NBT_BYTES, AuditSnbtParser.MAX_NESTING_DEPTH,
                BlockAuditLimits.MAX_NBT_NODES, DepthRule.TEXT_BRACKETS);
        /** Block entities: the block evidence caps, with the sanitizer's own depth rule (review m3). */
        public static final Limits BLOCK_ENTITY = new Limits(BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_NBT_BYTES,
                BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_NBT_BYTES, BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_CHARS,
                BlockAuditLimits.MAX_BLOCK_ENTITY_METADATA_UTF8_BYTES, BlockAuditLimits.MAX_NBT_DEPTH,
                BlockAuditLimits.MAX_NBT_NODES, DepthRule.EVERY_TAG);

        public Limits {
            if (maxBinaryBytes < 1 || maxNbtBytes < 1 || maxTextChars < 1 || maxUtf8Bytes < 1 || maxDepth < 1
                    || maxNodes < 1) {
                throw new IllegalArgumentException("every limit must be positive");
            }
            Objects.requireNonNull(depthRule, "depthRule");
        }

        /** Limits with the item family's depth rule ({@link DepthRule#TEXT_BRACKETS}). */
        public Limits(int maxBinaryBytes, int maxNbtBytes, int maxTextChars, int maxUtf8Bytes, int maxDepth,
                      int maxNodes) {
            this(maxBinaryBytes, maxNbtBytes, maxTextChars, maxUtf8Bytes, maxDepth, maxNodes, DepthRule.TEXT_BRACKETS);
        }

        public boolean checksUtf8() {
            return maxUtf8Bytes != UNCHECKED;
        }
    }

    public enum WalkFailure { TOO_DEEP, TOO_MANY_NODES }

    /** A fully read blob: its tree, its form byte and its exact stored bytes. */
    public record Decoded(CompoundTag tag, int form, byte[] blob) {}

    /** A blob checked for framing only (form bits, lengths, cap), its bytes kept unparsed. */
    public record Framed(int form, int rawLength, byte[] blob) {}

    /**
     * The tree's NbtIo bytes: the unnamed root form {@code NbtIo.write} writes (type byte, empty name, payload), but
     * without its fallback that silently writes "" for a string over 65535 modified-UTF-8 bytes; such a string throws
     * {@link java.io.UTFDataFormatException} here instead.
     */
    public static byte[] nbtBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeByte(tag.getId());
            output.writeUTF("");
            tag.write(output);
        }
        return buffer.toByteArray();
    }

    /** Frames raw NbtIo bytes; {@code flags} is 0 or {@link #FORM_REENCODE_DIFFERED}, DEFLATED is chosen here. */
    public static byte[] frame(byte[] raw, int flags) {
        if (raw == null || raw.length == 0) throw new IllegalArgumentException("no raw bytes");
        if ((flags & ~FORM_REENCODE_DIFFERED) != 0) throw new IllegalArgumentException("form flags " + flags);
        if (raw.length > DEFLATE_ABOVE_BYTES) {
            byte[] deflated = deflate(raw);
            if (deflated.length < raw.length) {
                return new CompactIO.Writer().code(flags | FORM_DEFLATED).varint(raw.length).varint(deflated.length)
                        .bytes(deflated).toByteArray();
            }
        }
        return new CompactIO.Writer().code(flags).varint(raw.length).bytes(raw).toByteArray();
    }

    /** A copy of a framed blob with {@code addFlags} ({@link #FORM_REENCODE_DIFFERED} only) set in its form byte. */
    public static byte[] withForm(byte[] blob, int addFlags) {
        if (blob == null || blob.length == 0) throw new IllegalArgumentException("no blob");
        if ((addFlags & ~FORM_REENCODE_DIFFERED) != 0) throw new IllegalArgumentException("form flags " + addFlags);
        byte[] copy = blob.clone();
        copy[0] = (byte) (copy[0] | addFlags);
        return copy;
    }

    /** Reads one whole blob; every byte of {@code blob} must belong to it. */
    public static Decoded read(byte[] blob, Limits limits) throws CompactIO.MalformedException {
        CompactIO.Reader reader = new CompactIO.Reader(blob);
        Decoded decoded = read(reader, limits);
        reader.requireEnd();
        return decoded;
    }

    /** Reads one blob from {@code reader}, which stops at the blob's own end. */
    public static Decoded read(CompactIO.Reader reader, Limits limits) throws CompactIO.MalformedException {
        Parts parts = parts(reader, limits);
        byte[] raw = (parts.form & FORM_DEFLATED) != 0 ? inflate(parts.data, parts.rawLength) : parts.data;
        CompoundTag tag = parse(raw, limits);
        return new Decoded(tag, parts.form, parts.blob);
    }

    /** Checks one blob's framing only and keeps its bytes (an UNREADABLE block's evidence, decision D4). */
    public static Framed frameOf(CompactIO.Reader reader, Limits limits) throws CompactIO.MalformedException {
        Parts parts = parts(reader, limits);
        return new Framed(parts.form, parts.rawLength, parts.blob);
    }

    /**
     * EventTrail's own bounded walk, iterative: depth by the family's {@link DepthRule} (the text's brackets for
     * items, the sanitizer's every-tag rule for block entities), nodes as {@code BlockEntityTagSanitizer} counts them
     * (one per tag and one per array member). Null when within limits.
     */
    public static WalkFailure walk(Tag root, Limits limits) {
        boolean everyTag = limits.depthRule() == DepthRule.EVERY_TAG;
        ArrayDeque<Frame> pending = new ArrayDeque<>();
        pending.push(new Frame(root, everyTag ? 0 : 1));
        long nodes = 0;
        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            Tag tag = frame.tag;
            nodes++;
            boolean counted = everyTag || tag instanceof CompoundTag || tag instanceof CollectionTag<?>;
            if (counted && frame.depth > limits.maxDepth()) return WalkFailure.TOO_DEEP;
            if (tag instanceof CompoundTag compound) {
                for (String key : compound.getAllKeys()) pending.push(new Frame(compound.get(key), frame.depth + 1));
            } else if (tag instanceof ListTag list) {
                for (Tag member : list) pending.push(new Frame(member, frame.depth + 1));
            } else if (tag instanceof CollectionTag<?> array) {
                nodes += array.size();
            }
            if (nodes > limits.maxNodes()) return WalkFailure.TOO_MANY_NODES;
        }
        return null;
    }

    private record Frame(Tag tag, int depth) {}

    private record Parts(int form, int rawLength, byte[] data, byte[] blob) {}

    private static Parts parts(CompactIO.Reader reader, Limits limits) throws CompactIO.MalformedException {
        int start = reader.position();
        int form = reader.flags(FORM_READABLE_BITS);
        int rawLength = reader.varint(limits.maxBinaryBytes());
        if (rawLength < 1) throw new CompactIO.MalformedException("empty details");
        byte[] data;
        if ((form & FORM_DEFLATED) != 0) {
            int storedLength = reader.varint(rawLength - 1);
            if (storedLength < 1) throw new CompactIO.MalformedException("empty deflated details");
            data = reader.bytes(storedLength);
        } else {
            data = reader.bytes(rawLength);
        }
        return new Parts(form, rawLength, data, reader.slice(start, reader.position()));
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(DEFLATE_LEVEL, false);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 16);
            byte[] chunk = new byte[4_096];
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] stored, int rawLength) throws CompactIO.MalformedException {
        Inflater inflater = new Inflater(false);
        try {
            inflater.setInput(stored);
            byte[] raw = new byte[rawLength];
            int filled = 0;
            int stalls = 0;
            while (filled < rawLength) {
                int produced = inflater.inflate(raw, filled, rawLength - filled);
                if (produced == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary() || ++stalls > 2) {
                        break;
                    }
                }
                filled += produced;
            }
            if (filled != rawLength) throw new CompactIO.MalformedException("inflated short of the declared length");
            if (!inflater.finished()) {
                // The declared length is reached: the stream must end here (this also checks its Adler-32).
                byte[] probe = new byte[1];
                if (inflater.inflate(probe) != 0 || !inflater.finished()) {
                    throw new CompactIO.MalformedException("inflates past the declared length");
                }
            }
            if (inflater.getRemaining() != 0) throw new CompactIO.MalformedException("unconsumed deflated bytes");
            return raw;
        } catch (DataFormatException invalid) {
            throw new CompactIO.MalformedException("invalid deflated details", invalid);
        } finally {
            inflater.end();
        }
    }

    private static CompoundTag parse(byte[] raw, Limits limits) throws CompactIO.MalformedException {
        // The writer's own root form: a compound under the empty name.
        if (raw.length < 3 || raw[0] != TAG_COMPOUND || raw[1] != 0 || raw[2] != 0) {
            throw new CompactIO.MalformedException("not an unnamed compound root");
        }
        preScan(raw, limits);
        ByteArrayInputStream bytes = new ByteArrayInputStream(raw);
        CompoundTag tag;
        try (DataInputStream input = new DataInputStream(bytes)) {
            long quota = READ_QUOTA_PER_CAP_BYTE * Math.max(limits.maxBinaryBytes(), limits.maxNbtBytes());
            tag = NbtIo.read(input, NbtAccounter.create(quota));
        } catch (IOException | RuntimeException invalid) {
            throw new CompactIO.MalformedException("unreadable NbtIo bytes", invalid);
        }
        if (bytes.available() != 0) throw new CompactIO.MalformedException("trailing NbtIo bytes");
        WalkFailure walk = walk(tag, limits);
        if (walk != null) throw new CompactIO.MalformedException("details " + walk);
        if (tag.sizeInBytes() > limits.maxNbtBytes()) throw new CompactIO.MalformedException("details over the cap");
        return tag;
    }

    /**
     * Review m2: EventTrail's own cheap, iterative check of the NbtIo byte structure before {@code NbtIo.read}, which
     * builds a full crash report (with a system report) for every IOException inside the body. Measured in
     * DetailsBlobTest with the game version set: about 28 to 34 ms for each such refusal (and about 400 ms for the
     * first), against about 0.02 ms for a good read and about 0.01 ms for the game's own RuntimeException refusals;
     * with this check the same bodies are refused in about 0.01 ms. It refuses only what {@code NbtIo.read} refuses:
     * a tag id outside 1..12 where a tag is read, a list declaring members of End or of an invalid id, a negative list
     * or array count, a string that is not modified UTF-8 as {@code DataInputStream.readUTF} reads it, and any field
     * running past the end. A list with no members keeps
     * whatever element id it declares, as NbtIo does. Trailing bytes are refused, as {@link #parse} refuses them.
     * It also refuses, by the family's {@link DepthRule}, every tree the {@link #walk} after {@code NbtIo.read} would
     * refuse as too deep, so the game's reader never meets its own depth limit either. (A compound with a repeated
     * key, which the writer never writes, is judged on every copy of the key here, where NbtIo keeps the last.)
     * Everything it passes still goes through {@code NbtIo.read} and every check after it.
     */
    static void preScan(byte[] raw, Limits limits) throws CompactIO.MalformedException {
        if (raw.length < 3 || raw[0] != TAG_COMPOUND || raw[1] != 0 || raw[2] != 0) {
            throw new CompactIO.MalformedException("not an unnamed compound root");
        }
        boolean everyTag = limits.depthRule() == DepthRule.EVERY_TAG;
        Scan scan = new Scan(raw, 3);
        // Open containers: {COMPOUND_OPEN, 0, depth} for a compound, {element id, members left, depth} for a list.
        ArrayDeque<int[]> open = new ArrayDeque<>();
        open.push(new int[] {COMPOUND_OPEN, 0, everyTag ? 0 : 1});
        while (!open.isEmpty()) {
            int[] top = open.peek();
            int type;
            if (top[0] == COMPOUND_OPEN) {
                type = scan.unsignedByte();
                if (type == Tag.TAG_END) {
                    open.pop();
                    continue;
                }
                if (type > Tag.TAG_LONG_ARRAY) throw new CompactIO.MalformedException("invalid tag id " + type);
                scan.modifiedUtf8();
            } else {
                if (top[1] == 0) {
                    open.pop();
                    continue;
                }
                top[1]--;
                type = top[0];
            }
            int depth = top[2] + 1;
            boolean counted = everyTag || type == Tag.TAG_COMPOUND || type == Tag.TAG_LIST
                    || type == Tag.TAG_BYTE_ARRAY || type == Tag.TAG_INT_ARRAY || type == Tag.TAG_LONG_ARRAY;
            if (counted && depth > limits.maxDepth()) {
                throw new CompactIO.MalformedException("details " + WalkFailure.TOO_DEEP);
            }
            switch (type) {
                case Tag.TAG_BYTE -> scan.skip(1);
                case Tag.TAG_SHORT -> scan.skip(2);
                case Tag.TAG_INT, Tag.TAG_FLOAT -> scan.skip(4);
                case Tag.TAG_LONG, Tag.TAG_DOUBLE -> scan.skip(8);
                case Tag.TAG_BYTE_ARRAY -> scan.skip(scan.count(1));
                case Tag.TAG_INT_ARRAY -> scan.skip(scan.count(4));
                case Tag.TAG_LONG_ARRAY -> scan.skip(scan.count(8));
                case Tag.TAG_STRING -> scan.modifiedUtf8();
                case Tag.TAG_COMPOUND -> open.push(new int[] {COMPOUND_OPEN, 0, depth});
                case Tag.TAG_LIST -> {
                    int element = scan.unsignedByte();
                    int members = scan.int32();
                    if (members < 0) throw new CompactIO.MalformedException("negative list count");
                    if (members > 0) {
                        if (element == Tag.TAG_END || element > Tag.TAG_LONG_ARRAY) {
                            throw new CompactIO.MalformedException("list of " + element + " with members");
                        }
                        // Every member takes at least one byte.
                        if (members > scan.remaining()) throw new CompactIO.MalformedException("truncated list");
                        open.push(new int[] {element, members, depth});
                    }
                }
                default -> throw new CompactIO.MalformedException("invalid tag id " + type);
            }
        }
        if (scan.remaining() != 0) throw new CompactIO.MalformedException("trailing NbtIo bytes");
    }

    private static final int COMPOUND_OPEN = -1;

    /** A bounds-checked cursor over raw NbtIo bytes (big-endian, as DataInput reads them). */
    private static final class Scan {
        private final byte[] raw;
        private int position;

        Scan(byte[] raw, int position) {
            this.raw = raw;
            this.position = position;
        }

        int remaining() {
            return raw.length - position;
        }

        void skip(long bytes) throws CompactIO.MalformedException {
            if (bytes > remaining()) throw new CompactIO.MalformedException("truncated NbtIo body");
            position += (int) bytes;
        }

        int unsignedByte() throws CompactIO.MalformedException {
            if (remaining() < 1) throw new CompactIO.MalformedException("truncated NbtIo body");
            return raw[position++] & 0xFF;
        }

        int int32() throws CompactIO.MalformedException {
            if (remaining() < 4) throw new CompactIO.MalformedException("truncated NbtIo body");
            int value = ((raw[position] & 0xFF) << 24) | ((raw[position + 1] & 0xFF) << 16)
                    | ((raw[position + 2] & 0xFF) << 8) | (raw[position + 3] & 0xFF);
            position += 4;
            return value;
        }

        /** An array's byte length: its count times {@code width}; a negative count is malformed. */
        long count(int width) throws CompactIO.MalformedException {
            int members = int32();
            if (members < 0) throw new CompactIO.MalformedException("negative array count");
            return (long) members * width;
        }

        /** One string as {@code DataInputStream.readUTF} reads it: a u16 length, then modified UTF-8. */
        void modifiedUtf8() throws CompactIO.MalformedException {
            if (remaining() < 2) throw new CompactIO.MalformedException("truncated NbtIo body");
            int length = ((raw[position] & 0xFF) << 8) | (raw[position + 1] & 0xFF);
            position += 2;
            if (length > remaining()) throw new CompactIO.MalformedException("truncated NbtIo string");
            int end = position + length;
            int at = position;
            while (at < end) {
                int c = raw[at] & 0xFF;
                switch (c >> 4) {
                    case 0, 1, 2, 3, 4, 5, 6, 7 -> at++;
                    case 12, 13 -> {
                        if (at + 2 > end || (raw[at + 1] & 0xC0) != 0x80) throw badUtf8();
                        at += 2;
                    }
                    case 14 -> {
                        if (at + 3 > end || (raw[at + 1] & 0xC0) != 0x80 || (raw[at + 2] & 0xC0) != 0x80) {
                            throw badUtf8();
                        }
                        at += 3;
                    }
                    default -> throw badUtf8();
                }
            }
            position = end;
        }

        private static CompactIO.MalformedException badUtf8() {
            return new CompactIO.MalformedException("NbtIo string is not modified UTF-8");
        }
    }
}
