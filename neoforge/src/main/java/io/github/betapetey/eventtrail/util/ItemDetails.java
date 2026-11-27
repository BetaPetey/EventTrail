package io.github.betapetey.eventtrail.util;

import com.mojang.serialization.DataResult;
import io.github.betapetey.eventtrail.audit.codec.CompactIO;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.io.UTFDataFormatException;
import java.util.Arrays;
import java.util.Objects;

/**
 * One item's stored details (ItemDetails plan 1.3), the same for every row kind: a state, its reason, the item's
 * identity (its real name and rename, kept even when the details are not, except off the server thread, where the
 * stack is not read at all), and for CAPTURED and UNREADABLE the
 * {@link DetailsBlob}. {@link #capture} guarantees that a CAPTURED blob reads back exactly what was written and
 * rebuilds into a stack; anything less is stored as another state, never as CAPTURED.
 *
 * <p>The block form, inside a row's own payload:
 * <pre>
 *   state code, reason code                          (codes below; a reason that is not its state's is malformed)
 *   [identity]  realName short string ("" = none), renameKind code (0 none, 1 text, 2 unreadable, 3 check failed),
 *               renameText short string (text only)  (only where the row kind asks for it)
 *   [blob]                                           (CAPTURED: fully read; UNREADABLE: framing only, kept as evidence)
 * </pre>
 * Every field is canonical: a decoded block writes back to the same bytes.
 */
public final class ItemDetails {
    /** The realName bound: the display bound, at most 3 UTF-8 bytes per UTF-16 unit. */
    static final int MAX_REAL_NAME_UTF8_BYTES = EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS * 3;
    /** The renameText bound: the custom-name bound, at most 3 UTF-8 bytes per UTF-16 unit. */
    static final int MAX_RENAME_UTF8_BYTES = EventTrailTextBounds.MAX_CUSTOM_TEXT_CHARS * 3;
    private static final String WRITE_CATEGORY = "item details write";
    private static final String NO_USABLE_VALUE = "codec returned no usable value";

    private static final ItemDetails NO_DETAILS = new ItemDetails(State.NONE, Reason.NONE, Identity.EMPTY, null, null);

    public enum State {
        /** No item (null or empty stack). */
        NONE(0),
        /** Stored and proven: reads back exactly and rebuilds into a stack. */
        CAPTURED(1),
        /** Stored, but the stored bytes do not read back exactly or do not rebuild; kept as evidence. */
        UNREADABLE(2),
        /** Over one of the family's limits; not stored. */
        TOO_LARGE(3),
        /** The writer was under pressure (today's {@code write_buffer_saturated}); not stored. */
        BUSY(4),
        /** Could not be captured (codec failure, off the server thread, no server); not stored. */
        NOT_STORED(5);

        private static final CompactIO.CodeTable<State> CODES = CompactIO.CodeTable.of(State.class, State::code);
        private final int code;

        State(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        boolean hasReasons() {
            return this == UNREADABLE || this == TOO_LARGE || this == NOT_STORED;
        }

        boolean hasBlob() {
            return this == CAPTURED || this == UNREADABLE;
        }
    }

    /** One reason code table per state (codes from 1); every other state has only {@link #NONE}, code 0. */
    public enum Reason {
        NONE(null, 0, null),
        DECODE_FAILED(State.UNREADABLE, 1, EventTrailTextBounds.DETAILS_REASON_DECODE_FAILED),
        STORE_DIFFERS(State.UNREADABLE, 2, EventTrailTextBounds.DETAILS_REASON_STORE_DIFFERS),
        NBT_BYTES(State.TOO_LARGE, 1, EventTrailTextBounds.DETAILS_REASON_NBT_BYTES),
        TEXT_CHARS(State.TOO_LARGE, 2, EventTrailTextBounds.DETAILS_REASON_TEXT_CHARS),
        UTF8_BYTES(State.TOO_LARGE, 3, EventTrailTextBounds.DETAILS_REASON_UTF8_BYTES),
        BINARY_BYTES(State.TOO_LARGE, 4, EventTrailTextBounds.DETAILS_REASON_BINARY_BYTES),
        TOO_DEEP(State.TOO_LARGE, 5, EventTrailTextBounds.DETAILS_REASON_TOO_DEEP),
        CODEC_THREW(State.NOT_STORED, 1, EventTrailTextBounds.DETAILS_REASON_CODEC_THREW),
        NON_COMPOUND(State.NOT_STORED, 2, EventTrailTextBounds.DETAILS_REASON_NON_COMPOUND),
        OFF_THREAD(State.NOT_STORED, 3, EventTrailTextBounds.DETAILS_REASON_OFF_THREAD),
        NO_SERVER(State.NOT_STORED, 4, EventTrailTextBounds.DETAILS_REASON_NO_SERVER);

        private final State state;
        private final int code;
        private final String text;

        Reason(State state, int code, String text) {
            this.state = state;
            this.code = code;
            this.text = text;
        }

        /** The state this reason belongs to; null for {@link #NONE}. */
        public State state() {
            return state;
        }

        public int code() {
            return code;
        }

        /** The reason's name (for WARNs); null for {@link #NONE}. */
        public String text() {
            return text;
        }

        /** The reason with this code in this state's table, or null when the table has none. */
        public static Reason of(State state, int code) {
            if (code == 0) return state.hasReasons() ? null : NONE;
            for (Reason reason : values()) {
                if (reason.state == state && reason.code == code) return reason;
            }
            return null;
        }
    }

    /**
     * The item's identity: its real name (the written-book label, else the game's name with any rename removed,
     * cleaned by the item rule; null when none) and its rename state. Kept in every state but NONE.
     */
    public record Identity(String realName, DisplayNameResolver.RenameState rename) {
        public static final Identity EMPTY = new Identity(null, DisplayNameResolver.RenameState.NONE);

        public Identity {
            realName = DisplayNameResolver.itemText(realName, EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS);
            rename = rename == null ? DisplayNameResolver.RenameState.NONE : rename;
        }

        /** The stack's identity by the item rule's own reads, as the kill-weapon fix reads it; never throws. */
        public static Identity of(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return EMPTY;
            String realName;
            try {
                String book = DisplayNameResolver.writtenBookLabel(stack);
                realName = book != null ? book : DisplayNameResolver.gameName(stack);
            } catch (Throwable failure) {
                realName = null;
            }
            DisplayNameResolver.RenameState rename;
            try {
                rename = DisplayNameResolver.RenameState.ofStack(stack);
            } catch (Throwable failure) {
                rename = DisplayNameResolver.RenameState.CHECK_FAILED;
            }
            return new Identity(realName, rename);
        }
    }

    @FunctionalInterface
    interface Encoder {
        Tag encode(ItemStack stack, HolderLookup.Provider provider);
    }

    @FunctionalInterface
    interface Decoder {
        EventTrailNbtUtil.StackRebuild rebuild(CompoundTag tag, HolderLookup.Provider provider);
    }

    @FunctionalInterface
    interface ServerAccess {
        /** The running server's registries and whether this is its thread, or null when no server runs. */
        Server current();
    }

    @FunctionalInterface
    interface IdentityReader {
        Identity read(ItemStack stack);
    }

    /** The read-back's blob reader ({@link DetailsBlob#read(byte[], DetailsBlob.Limits)} in production). */
    @FunctionalInterface
    interface BlobReader {
        DetailsBlob.Decoded read(byte[] blob, DetailsBlob.Limits limits) throws CompactIO.MalformedException;
    }

    record Server(HolderLookup.Provider provider, boolean onServerThread) {}

    /** The capture's seams: production reads the running server, the item codec, the item rule and the blob reader. */
    record Seams(ServerAccess server, Encoder encoder, Decoder decoder, IdentityReader identity, BlobReader reader) {
        static final Seams PRODUCTION = new Seams(ItemDetails::currentServer, EventTrailNbtUtil::encodeStack,
                EventTrailNbtUtil::rebuildStack, Identity::of, DetailsBlob::read);

        Seams {
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(encoder, "encoder");
            Objects.requireNonNull(decoder, "decoder");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(reader, "reader");
        }

        /** Production seams with a fixed server: these registries, on (or off) the server thread. */
        static Seams of(HolderLookup.Provider provider, boolean onServerThread) {
            Server fixed = new Server(provider, onServerThread);
            return new Seams(() -> fixed, PRODUCTION.encoder, PRODUCTION.decoder, PRODUCTION.identity,
                    PRODUCTION.reader);
        }

        Seams withEncoder(Encoder replacement) {
            return new Seams(server, replacement, decoder, identity, reader);
        }

        Seams withDecoder(Decoder replacement) {
            return new Seams(server, encoder, replacement, identity, reader);
        }

        Seams withReader(BlobReader replacement) {
            return new Seams(server, encoder, decoder, identity, replacement);
        }
    }

    private final State state;
    private final Reason reason;
    private final Identity identity;
    private final byte[] blob;
    private final CompoundTag details;
    /** {@link #detailsText()}'s cache; not part of equality. */
    private String text;

    private ItemDetails(State state, Reason reason, Identity identity, byte[] blob, CompoundTag details) {
        this.state = state;
        this.reason = reason;
        this.identity = identity;
        this.blob = blob;
        this.details = details;
    }

    /**
     * Captures a stack's details on the server thread (plan 1.3). {@code captureFullMetadata} false is writer
     * pressure (BUSY). {@code limits} is the row family's ({@link DetailsBlob.Limits#ITEM} for items).
     */
    public static ItemDetails capture(ItemStack stack, boolean captureFullMetadata, DetailsBlob.Limits limits) {
        return capture(stack, captureFullMetadata, limits, Seams.PRODUCTION);
    }

    static ItemDetails capture(ItemStack stack, boolean captureFullMetadata, DetailsBlob.Limits limits, Seams seams) {
        Objects.requireNonNull(limits, "limits");
        if (stack == null || stack.isEmpty()) return NO_DETAILS;
        if (!captureFullMetadata) {
            return new ItemDetails(State.BUSY, Reason.NONE, readIdentity(seams, stack), null, null);
        }
        String item = EventTrailNbtUtil.itemIdentity(stack);

        Server server;
        try {
            server = seams.server().current();
        } catch (RuntimeException | LinkageError failure) {
            server = null;
        }
        if (server == null) return notStored(item, readIdentity(seams, stack), Reason.NO_SERVER, null);
        // Review n6: off the server thread the stack's components are not read at all, the identity included.
        if (!server.onServerThread()) return notStored(item, Identity.EMPTY, Reason.OFF_THREAD, null);
        Identity identity = readIdentity(seams, stack);
        HolderLookup.Provider provider = server.provider();
        Encoder encoder = seams.encoder();

        // Step 2: the shared KL-68 encode step.
        Tag encoded;
        try {
            encoded = EventTrailNbtUtil.encodeExact(stack, value -> encoder.encode(value, provider));
        } catch (RuntimeException failure) {
            return notStored(item, identity, Reason.CODEC_THREW, failure);
        }
        if (!(encoded instanceof CompoundTag written)) return notStored(item, identity, Reason.NON_COMPOUND, null);

        // Step 3: the gates, each giving today's decision for the family's limits; binary_bytes is new.
        int nbtBytes = written.sizeInBytes();
        if (nbtBytes > limits.maxNbtBytes()) return tooLarge(item, identity, Reason.NBT_BYTES, nbtBytes);
        DetailsBlob.WalkFailure walk = DetailsBlob.walk(written, limits);
        if (walk == DetailsBlob.WalkFailure.TOO_DEEP) return tooLarge(item, identity, Reason.TOO_DEEP, -1);
        // Under the item caps the node bound cannot bind below the NBT size cap: a tag counts at least 8 bytes in
        // sizeInBytes and an array member at least 1, so more than 131072 nodes is over 64 KiB (review n2).
        // It is reported as nbt_bytes.
        if (walk == DetailsBlob.WalkFailure.TOO_MANY_NODES) return tooLarge(item, identity, Reason.NBT_BYTES, nbtBytes);
        String text = written.toString();   // rendered only to measure, as today's check does
        if (text.length() > limits.maxTextChars()) return tooLarge(item, identity, Reason.TEXT_CHARS, text.length());
        if (limits.checksUtf8()) {
            long utf8 = utf8Length(text);
            if (utf8 > limits.maxUtf8Bytes()) return tooLarge(item, identity, Reason.UTF8_BYTES, utf8);
        }

        // Step 4: the NbtIo bytes, framed with P7.
        byte[] raw;
        try {
            raw = DetailsBlob.nbtBytes(written);
        } catch (UTFDataFormatException overlong) {
            // A string over 65535 modified-UTF-8 bytes (decision D6): too large for the binary form.
            return tooLarge(item, identity, Reason.BINARY_BYTES, -1);
        } catch (IOException | RuntimeException failure) {
            return notStored(item, identity, Reason.CODEC_THREW, failure);
        }
        if (raw.length > limits.maxBinaryBytes()) return tooLarge(item, identity, Reason.BINARY_BYTES, raw.length);
        byte[] blob = DetailsBlob.frame(raw, 0);

        // Step 5: the stored bytes themselves read back exactly (decision D3).
        CompoundTag readBack = readBack(written, blob, limits, seams.reader());
        if (readBack == null) return unreadable(item, identity, Reason.STORE_DIFFERS, blob, "read-back differs");

        // Step 6: they rebuild into a stack through the shared step.
        EventTrailNbtUtil.StackRebuild rebuilt;
        try {
            rebuilt = seams.decoder().rebuild(readBack, provider);
        } catch (RuntimeException failure) {
            return unreadable(item, identity, Reason.DECODE_FAILED, blob, failure.getClass().getSimpleName());
        }
        if (rebuilt == null || !rebuilt.rebuilt()) {
            return unreadable(item, identity, Reason.DECODE_FAILED, blob, failureMessage(rebuilt));
        }

        // Step 7: the rebuilt stack encodes back the same; a difference stays CAPTURED with the bit (D1, D5).
        String difference;
        try {
            Tag again = EventTrailNbtUtil.encodeExact(rebuilt.stack(), value -> encoder.encode(value, provider));
            difference = NbtExact.firstDifference(written, again);
            if (difference != null && difference.isEmpty()) difference = "(root)";
        } catch (RuntimeException failure) {
            difference = "(re-encode threw " + failure.getClass().getSimpleName() + ")";
        }
        if (difference != null) {
            EventTrailNbtUtil.reportDetailsFailure(WRITE_CATEGORY, item, "re-encode differs at " + difference
                    + "; stored with the diagnostic bit");
            blob = DetailsBlob.withForm(blob, DetailsBlob.FORM_REENCODE_DIFFERED);
            readBack = readBack(written, blob, limits, seams.reader());
            if (readBack == null) return unreadable(item, identity, Reason.STORE_DIFFERS, blob, "read-back differs");
        }
        // Step 8.
        return new ItemDetails(State.CAPTURED, Reason.NONE, identity, blob, readBack);
    }

    /** The read-back verify of step 5: null when the blob reads back exactly what was written, else store_differs. */
    static Reason readBackReason(CompoundTag written, byte[] blob, DetailsBlob.Limits limits) {
        return readBack(written, blob, limits, DetailsBlob::read) == null ? Reason.STORE_DIFFERS : null;
    }

    private static CompoundTag readBack(CompoundTag written, byte[] blob, DetailsBlob.Limits limits,
                                        BlobReader reader) {
        try {
            DetailsBlob.Decoded decoded = reader.read(blob, limits);
            CompoundTag tag = decoded == null ? null : decoded.tag();
            return tag != null && NbtExact.equals(written, tag) ? tag : null;
        } catch (CompactIO.MalformedException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The codec's message for the WARN, built here only (review m1): the shared rebuild step keeps the result
     * unevaluated, so today's reader does no new work. Building it can fail or recurse deeply; either gives a fixed
     * text, never a failed capture.
     */
    private static String failureMessage(EventTrailNbtUtil.StackRebuild rebuilt) {
        if (rebuilt == null || rebuilt.failure() == null) return NO_USABLE_VALUE;
        try {
            String message = rebuilt.failure().error().map(DataResult.Error::message).orElse(null);
            return message == null || message.isBlank() ? NO_USABLE_VALUE : message;
        } catch (RuntimeException | StackOverflowError unreadable) {
            return "codec message unreadable (" + unreadable.getClass().getSimpleName() + ")";
        }
    }

    /** Reads one block (see the class comment); {@code limits} is the row family's. */
    public static ItemDetails readFrom(CompactIO.Reader reader, boolean withIdentity, DetailsBlob.Limits limits)
            throws CompactIO.MalformedException {
        State state = reader.code(State.CODES);
        int reasonCode = reader.code();
        Reason reason = Reason.of(state, reasonCode);
        if (reason == null) throw new CompactIO.MalformedException("reason " + reasonCode + " is not one of " + state);
        Identity identity = withIdentity ? readIdentity(reader) : null;
        byte[] blob = null;
        CompoundTag details = null;
        if (state == State.CAPTURED) {
            DetailsBlob.Decoded decoded = DetailsBlob.read(reader, limits);
            blob = decoded.blob();
            details = decoded.tag();
        } else if (state == State.UNREADABLE) {
            blob = DetailsBlob.frameOf(reader, limits).blob();
        }
        return new ItemDetails(state, reason, identity, blob, details);
    }

    /** Writes this block; {@code withIdentity} must match what the reader will ask for. */
    public void writeTo(CompactIO.Writer writer, boolean withIdentity) {
        writer.code(state.code()).code(reason.code());
        if (withIdentity) {
            if (identity == null) throw new IllegalStateException("details read without an identity");
            writer.shortString(identity.realName() == null ? "" : identity.realName(), MAX_REAL_NAME_UTF8_BYTES);
            DisplayNameResolver.RenameState rename = identity.rename();
            writer.code(renameCode(rename.kind()));
            if (rename.kind() == DisplayNameResolver.RenameState.Kind.TEXT) {
                writer.shortString(rename.text(), MAX_RENAME_UTF8_BYTES);
            }
        }
        if (state.hasBlob()) writer.bytes(blob);
    }

    public State state() {
        return state;
    }

    public Reason reason() {
        return reason;
    }

    /** The identity; null only for a block read without one. */
    public Identity identity() {
        return identity;
    }

    /** A copy of the stored blob (CAPTURED and UNREADABLE), else null. */
    public byte[] blob() {
        return blob == null ? null : blob.clone();
    }

    /** A copy of the proven tree (CAPTURED only), else null. */
    public CompoundTag details() {
        return details == null ? null : details.copy();
    }

    /** CAPTURED with the diagnostic bit: the rebuilt stack encoded back differently (decision D1). */
    public boolean reencodeDiffered() {
        return state == State.CAPTURED && (blob[0] & DetailsBlob.FORM_REENCODE_DIFFERED) != 0;
    }

    /**
     * CAPTURED and the proven tree names this item and count: the declared-item match of today's transfer and item
     * checks (an "id" string equal to {@code itemId}; no "count" means 1, else an int count equal to {@code count}).
     * False for every other state.
     */
    public boolean describes(String itemId, int count) {
        if (state != State.CAPTURED) return false;
        if (!details.contains("id", Tag.TAG_STRING) || !details.getString("id").equals(itemId)) return false;
        if (!details.contains("count")) return count == 1;   // ItemStack.CODEC omits its default count of one
        return details.contains("count", Tag.TAG_INT) && details.getInt("count") == count;
    }

    /**
     * The proven tree's text ({@link CompoundTag#toString()}, today's SNBT form), rendered once on first use; null
     * unless CAPTURED. For readers that parse today's text until they read the tree (plan 1.5, B5).
     */
    public String detailsText() {
        if (state != State.CAPTURED) return null;
        String rendered = text;
        if (rendered == null) {
            rendered = details.toString();
            text = rendered;   // an immutable value; a race only renders it twice
        }
        return rendered;
    }

    /**
     * The text gates of step 3 applied to the stored tree: its text is at most {@code limits.maxTextChars()} chars
     * and, where the family checks it, at most {@code limits.maxUtf8Bytes()} UTF-8 bytes. A reader calls this so a
     * row whose tree renders past the bound is refused as today's over-long text is (the Owner's Q480 OK). True for
     * every state but CAPTURED: only CAPTURED has text.
     */
    public boolean withinTextLimits(DetailsBlob.Limits limits) {
        Objects.requireNonNull(limits, "limits");
        if (state != State.CAPTURED) return true;
        String rendered = detailsText();
        if (rendered.length() > limits.maxTextChars()) return false;
        return !limits.checksUtf8() || utf8Length(rendered) <= limits.maxUtf8Bytes();
    }

    /**
     * These CAPTURED details as UNREADABLE / store_differs, the bytes and identity kept, with a WARN: for a writer
     * whose row declares another item or count than the stored tree (the old check's mismatch, which today stores
     * codec_failure). Only for CAPTURED.
     */
    public ItemDetails asStoreDiffers(String item) {
        if (state != State.CAPTURED) throw new IllegalStateException("only CAPTURED details can differ from a row");
        return unreadable(item, identity, Reason.STORE_DIFFERS, blob, "the stored details name another item or count");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemDetails that && state == that.state && reason == that.reason
                && Objects.equals(identity, that.identity) && Arrays.equals(blob, that.blob);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, reason, identity, Arrays.hashCode(blob));
    }

    @Override
    public String toString() {
        return "ItemDetails[" + state + (reason == Reason.NONE ? "" : "/" + reason.text())
                + (blob == null ? "" : ", " + blob.length + " bytes") + "]";
    }

    private static Identity readIdentity(Seams seams, ItemStack stack) {
        try {
            Identity identity = seams.identity().read(stack);
            return identity == null ? Identity.EMPTY : identity;
        } catch (Throwable failure) {
            return new Identity(null, DisplayNameResolver.RenameState.CHECK_FAILED);
        }
    }

    private static Identity readIdentity(CompactIO.Reader reader) throws CompactIO.MalformedException {
        String realName = reader.shortString(MAX_REAL_NAME_UTF8_BYTES);
        if (!realName.isEmpty()
                && !realName.equals(DisplayNameResolver.itemText(realName, EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS))) {
            throw new CompactIO.MalformedException("realName is not in its cleaned form");
        }
        int kindCode = reader.code();
        DisplayNameResolver.RenameState rename = switch (kindCode) {
            case 0 -> DisplayNameResolver.RenameState.NONE;
            case 1 -> {
                String text = reader.shortString(MAX_RENAME_UTF8_BYTES);
                if (!text.isEmpty()
                        && !text.equals(DisplayNameResolver.itemText(text, EventTrailTextBounds.MAX_CUSTOM_TEXT_CHARS))) {
                    throw new CompactIO.MalformedException("renameText is not in its cleaned form");
                }
                yield DisplayNameResolver.RenameState.text(text);
            }
            case 2 -> DisplayNameResolver.RenameState.UNREADABLE;
            case 3 -> DisplayNameResolver.RenameState.CHECK_FAILED;
            default -> throw new CompactIO.MalformedException("unknown rename kind " + kindCode);
        };
        return new Identity(realName.isEmpty() ? null : realName, rename);
    }

    private static int renameCode(DisplayNameResolver.RenameState.Kind kind) {
        return switch (kind) {
            case NONE -> 0;
            case TEXT -> 1;
            case UNREADABLE -> 2;
            case CHECK_FAILED -> 3;
        };
    }

    private static ItemDetails notStored(String item, Identity identity, Reason reason, Throwable cause) {
        if (cause != null) {
            EventTrailNbtUtil.reportSerializationFailure("item write", item, cause);
        } else {
            EventTrailNbtUtil.reportDetailsFailure(WRITE_CATEGORY, item, "not stored: " + reason.text());
        }
        return new ItemDetails(State.NOT_STORED, reason, identity, null, null);
    }

    private static ItemDetails tooLarge(String item, Identity identity, Reason reason, long measured) {
        if (measured >= 0) {
            EventTrailNbtUtil.reportMetadataTooLarge("item write", item, measured);
        } else {
            EventTrailNbtUtil.reportDetailsFailure(WRITE_CATEGORY, item, "too large: " + reason.text());
        }
        return new ItemDetails(State.TOO_LARGE, reason, identity, null, null);
    }

    private static ItemDetails unreadable(String item, Identity identity, Reason reason, byte[] blob, String detail) {
        EventTrailNbtUtil.reportDetailsFailure(WRITE_CATEGORY, item, reason.text() + ": " + detail);
        return new ItemDetails(State.UNREADABLE, reason, identity, blob, null);
    }

    /** The UTF-8 length {@code String.getBytes(UTF_8)} gives (a lone surrogate is written as one '?'). */
    private static long utf8Length(String text) {
        long bytes = 0;
        int length = text.length();
        for (int index = 0; index < length; index++) {
            char unit = text.charAt(index);
            if (unit < 0x80) {
                bytes += 1;
            } else if (unit < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(unit) && index + 1 < length
                    && Character.isLowSurrogate(text.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else if (Character.isSurrogate(unit)) {
                bytes += 1;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static Server currentServer() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return new Server(server.registryAccess(), server.isSameThread());
    }
}
