package io.github.betapetey.eventtrail.audit;

import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.EventTrailText;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The one shared shown cause (GF-ONPLAYER design 4.1, 4.2, 4.5, 4.8): the detail that says what moved a player's items
 * when the player is not credited ("via Magnet Upgrade", "taken by Inventory Scanner (owner Alex)"), and the upgrade
 * named on a player's own container rows ("via Restock Upgrade").
 *
 * <p>The display string is resolved once, on the server thread at capture, by {@link DisplayNameResolver} (the one-name
 * rule: the game's own name first, a custom name alongside as {@code Real (renamed "X")}, a marked id when the game gives
 * no name). It is stored as-is and never rebuilt later from a client string or from the current registry: that is the
 * anti-spoofing part. {@link #text()} is the only shown form, and AuditText's 512-character bound is its last step, so
 * the text built at capture and the text rebuilt at decode are equal at every field length.</p>
 *
 * <p>{@link Form#UNIDENTIFIED} is the one marked cause, {@value #UNIDENTIFIED_TEXT}. It has no other field. Only
 * player-action container rows that meet design 4.5's three conditions may carry it; the InventoryChange codec refuses
 * it.</p>
 *
 * <p>The cause block ({@link #writeBlock}, {@link #readBlock}) starts with a form byte: 0 = NAMED, followed by the
 * fields; 1 = UNIDENTIFIED, with no more bytes. Any other byte, and any other non-canonical field, is refused and never
 * guessed. A block that reads is always re-encoded to the same bytes.</p>
 */
public record ShownCause(
        Form form,
        Verb verb,
        Subject subject,
        ResourceLocation id,
        String display,
        boolean resolved,
        Owner owner) {

    /** The exact text of the marked cause (design 4.1, 4.5). */
    public static final String UNIDENTIFIED_TEXT = "via an upgrade (not identified)";
    /** Form byte of a named cause. */
    public static final int FORM_NAMED = 0;
    /** Form byte of the marked cause; no more bytes follow it. */
    public static final int FORM_UNIDENTIFIED = 1;
    /** The bound on the stored display string: AuditText's own bound, so a display can never outgrow the shown text. */
    public static final int MAX_DISPLAY_CHARS = AuditText.MAX_PRESENTATION_CHARACTERS;
    /** A real name, and an owner name, are bounded as the resolver bounds a real name (256). */
    public static final int MAX_NAME_CHARS = EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS;
    /** A custom name is bounded as the resolver bounds one, before escaping (EventTrailTextBounds.MAX_CUSTOM_TEXT_CHARS). */
    static final int MAX_CUSTOM_CHARS = 100;
    /** The largest UTF-8 field the cause-block decoder reads; every valid field is far smaller. */
    public static final int MAX_FIELD_BYTES = 4 * 1024;

    static final String RENAMED_UNREADABLE = " (renamed, name unreadable)";
    private static final Pattern SAFE_TEXT_WHITESPACE = Pattern.compile("\\s+");
    private static final ShownCause UNIDENTIFIED_UPGRADE =
            new ShownCause(Form.UNIDENTIFIED, null, null, null, null, false, null);

    /** Which kind of cause this is (design 4.1, M4). */
    public enum Form {
        /** A named device, block or entity. */
        NAMED,
        /** The one marked cause, {@value ShownCause#UNIDENTIFIED_TEXT}; no other field. */
        UNIDENTIFIED
    }

    /** How the cause reads. */
    public enum Verb {
        VIA("via ", "via"),
        TAKEN_BY("taken by ", "taken_by");

        private final String prefix;
        private final String code;

        Verb(String prefix, String code) {
            this.prefix = prefix;
            this.code = code;
        }
    }

    /** What the registry id names. */
    public enum Subject {
        ITEM("item"),
        BLOCK("block"),
        ENTITY("entity");

        private final String code;

        Subject(String code) {
            this.code = code;
        }
    }

    /** How the owning mod holds a device's owner (design 4.1, m5). */
    public enum OwnerState {
        VALIDATED("validated"),
        NOT_VALIDATED("not_validated"),
        /** The mod's own "no owner set" (SecurityCraft's isDefaultOwner). */
        DEFAULT("default");

        private final String code;

        OwnerState(String code) {
            this.code = code;
        }
    }

    /**
     * A device's owner as the owning mod stores it (the scanner only). The name is shown; the uuid text is kept for the
     * payload. Both are safe text: no line breaks or lone surrogates, at most {@value ShownCause#MAX_NAME_CHARS} characters. A
     * VALIDATED or NOT_VALIDATED owner needs a non-blank name; a DEFAULT owner's name is not shown.
     */
    public record Owner(String name, String uuid, OwnerState state) {
        public Owner {
            Objects.requireNonNull(state, "state");
            requireSafeOrEmpty(name, MAX_NAME_CHARS, "owner name");
            requireSafeOrEmpty(uuid, MAX_NAME_CHARS, "owner uuid");
            if (state != OwnerState.DEFAULT && name.isEmpty()) {
                throw new IllegalArgumentException("A validated or not-validated owner needs a name");
            }
        }

        /**
         * Captures an owner from the mod's own values: each is made safe text and cut at {@value ShownCause#MAX_NAME_CHARS}
         * characters; a missing or blank value becomes "". Throws IllegalArgumentException for a VALIDATED or
         * NOT_VALIDATED owner without a name; the caller counts that as its own refusal.
         */
        public static Owner capture(String name, String uuid, OwnerState state) {
            return new Owner(orEmpty(safeText(name, MAX_NAME_CHARS)), orEmpty(safeText(uuid, MAX_NAME_CHARS)), state);
        }

        /** " (owner Alex)", " (owner Alex, not validated)" or " (owner not set)". */
        public String text() {
            return switch (state) {
                case VALIDATED -> " (owner " + name + ")";
                case NOT_VALIDATED -> " (owner " + name + ", not validated)";
                case DEFAULT -> " (owner not set)";
            };
        }
    }

    public ShownCause {
        Objects.requireNonNull(form, "form");
        if (form == Form.UNIDENTIFIED) {
            if (verb != null || subject != null || id != null || display != null || resolved || owner != null) {
                throw new IllegalArgumentException("The marked cause has no other field");
            }
        } else {
            Objects.requireNonNull(verb, "verb");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(id, "id");
            requireSafeOrEmpty(display, MAX_DISPLAY_CHARS, "display");
            if (display.isEmpty()) throw new IllegalArgumentException("A named cause needs a display string");
        }
    }

    // ---------------------------------------------------------------------
    // Factories. All are called on the server thread at capture time.
    // ---------------------------------------------------------------------

    /**
     * "via &lt;device&gt;": the stack's own name through {@link DisplayNameResolver#stackDisplayName(ItemStack)},
     * including its renamed, rename-check-failed and written-book forms. When the game gives no name, the resolver's
     * marked "Unresolved item (&lt;id&gt;)" is shown and {@link #resolved()} is false. Throws IllegalArgumentException
     * for a null or empty stack: with no device to name, the caller writes no row and counts it.
     */
    public static ShownCause via(ItemStack device) {
        if (device == null || device.isEmpty()) throw new IllegalArgumentException("device must be a non-empty stack");
        ResourceLocation id = registryId(() -> BuiltInRegistries.ITEM.getKey(device.getItem()), "item");
        String display = safeText(DisplayNameResolver.stackDisplayName(device), MAX_DISPLAY_CHARS);
        if (display == null) throw new IllegalArgumentException("The device stack has no display string");
        boolean resolved = !display.startsWith(DisplayNameResolver.unresolvedItemDisplay(id.toString()));
        return new ShownCause(Form.NAMED, Verb.VIA, Subject.ITEM, id, display, resolved, null);
    }

    /**
     * "taken by &lt;block&gt;": {@link DisplayNameResolver#blockDisplayName(Block)}, with the block entity's custom name
     * alongside as {@code (renamed "X")} when it is a named block entity, then the owner text. Throws
     * IllegalArgumentException for a null block.
     */
    public static ShownCause takenBy(Block block, BlockEntity blockEntity, Owner owner) {
        if (block == null) throw new IllegalArgumentException("block must not be null");
        ResourceLocation id = registryId(() -> BuiltInRegistries.BLOCK.getKey(block), "block");
        String real;
        try {
            real = DisplayNameResolver.blockDisplayName(block);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            real = null;
        }
        Supplier<Component> customName = blockEntity instanceof Nameable named ? named::getCustomName : null;
        return compose(Verb.TAKEN_BY, Subject.BLOCK, id, real, customName, owner);
    }

    /**
     * "via &lt;entity&gt;": the entity type's game name, with the entity's custom name alongside as
     * {@code (renamed "X")}. Throws IllegalArgumentException for a null entity.
     */
    public static ShownCause viaEntity(Entity entity) {
        if (entity == null) throw new IllegalArgumentException("entity must not be null");
        return viaEntity(entity.getType(), entity::getCustomName);
    }

    /** Test seam: {@link #viaEntity(Entity)} from the entity's type and its custom-name reader. */
    static ShownCause viaEntity(EntityType<?> type, Supplier<Component> customName) {
        if (type == null) throw new IllegalArgumentException("entity type must not be null");
        ResourceLocation id = registryId(() -> BuiltInRegistries.ENTITY_TYPE.getKey(type), "entity");
        String real = null;
        try {
            String key = type.getDescriptionId();
            String name = safeText(type.getDescription().getString(), MAX_NAME_CHARS);
            if (name != null && !name.equals(key)) real = name;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            real = null;
        }
        return compose(Verb.VIA, Subject.ENTITY, id, real, customName, null);
    }

    /**
     * "via &lt;grave&gt;" (graves design r6, 1.4): a grave's take rows. {@code display} is the grave's display as the
     * caller resolved it on the server thread (real name, custom name alongside); it is stored as safe text, and a null or
     * blank display stores the marked "Unresolved &lt;subject&gt; (&lt;id&gt;)" with {@link #resolved()} false, as
     * {@link #compose} does. A display that is that marked form is not resolved either. {@code owner} is the grave's owner
     * as {@code GraveOwners} built it, or null when the read found none. Throws NullPointerException for a null id or
     * subject.
     */
    public static ShownCause viaGrave(ResourceLocation id, String display, Subject subject, Owner owner) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subject, "subject");
        String unresolved = unresolvedDisplay(subject.code, id.toString());
        String safe = safeText(display, MAX_DISPLAY_CHARS);
        if (safe == null) return new ShownCause(Form.NAMED, Verb.VIA, subject, id, unresolved, false, owner);
        return new ShownCause(Form.NAMED, Verb.VIA, subject, id, safe, !safe.startsWith(unresolved), owner);
    }

    /**
     * The one marked cause, {@value #UNIDENTIFIED_TEXT} (design 4.8 item 1). Only for player-action container rows
     * that meet section 4.5's three conditions; the InventoryChange codec refuses it. Returns the same constant every
     * call.
     */
    public static ShownCause unidentifiedUpgrade() {
        return UNIDENTIFIED_UPGRADE;
    }

    /**
     * Builds a named cause from a real name (null when the game gave none: the marked "Unresolved &lt;subject&gt;
     * (&lt;id&gt;)" is shown and the cause is not resolved), an optional custom-name reader and an optional owner.
     */
    static ShownCause compose(Verb verb, Subject subject, ResourceLocation id, String realName,
            Supplier<Component> customName, Owner owner) {
        Objects.requireNonNull(verb, "verb");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(id, "id");
        String real = safeText(realName, MAX_NAME_CHARS);
        boolean resolved = real != null;
        if (!resolved) real = unresolvedDisplay(subject.code, id.toString());
        String display = safeText(real + renameSuffix(customName), MAX_DISPLAY_CHARS);
        return new ShownCause(Form.NAMED, verb, subject, id, display, resolved, owner);
    }

    // ---------------------------------------------------------------------
    // The shown text.
    // ---------------------------------------------------------------------

    /**
     * The only shown form: "via " or "taken by ", the display string, then the owner text, bounded by AuditText as the
     * last step; or exactly {@value #UNIDENTIFIED_TEXT}.
     */
    public String text() {
        if (form == Form.UNIDENTIFIED) return UNIDENTIFIED_TEXT;
        String raw = verb.prefix + display + (owner == null ? "" : owner.text());
        return AuditText.boundedRequired(raw, "shown cause");
    }

    // ---------------------------------------------------------------------
    // The cause block (design 4.2).
    // ---------------------------------------------------------------------

    /** Writes the cause block: the form byte, then, for a named cause, its fields. */
    public static void writeBlock(DataOutputStream output, ShownCause cause) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(cause, "cause");
        if (cause.form == Form.UNIDENTIFIED) {
            output.writeByte(FORM_UNIDENTIFIED);
            return;
        }
        output.writeByte(FORM_NAMED);
        writeString(output, cause.verb.code);
        writeString(output, cause.subject.code);
        writeString(output, cause.id.toString());
        writeString(output, cause.display);
        output.writeByte(cause.resolved ? 1 : 0);
        output.writeByte(cause.owner == null ? 0 : 1);
        if (cause.owner != null) {
            writeString(output, cause.owner.name);
            writeString(output, cause.owner.uuid);
            writeString(output, cause.owner.state.code);
        }
    }

    /**
     * Reads one cause block and leaves the stream after it. A form byte other than 0 or 1, an unknown code, a flag
     * byte other than 0 or 1, a non-canonical id, a field that is not valid UTF-8, too long, or not safe text, and a
     * short read are all refused with an IOException; nothing is guessed. Form byte 1 gives
     * {@link #unidentifiedUpgrade()}.
     */
    public static ShownCause readBlock(DataInputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        int form = input.readUnsignedByte();
        if (form == FORM_UNIDENTIFIED) return UNIDENTIFIED_UPGRADE;
        if (form != FORM_NAMED) throw new IOException("Shown-cause form byte " + form + " is not canonical");
        Verb verb = verbFromCode(readString(input));
        Subject subject = subjectFromCode(readString(input));
        String idText = readString(input);
        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null || !id.toString().equals(idText)) throw new IOException("Shown-cause id is not canonical");
        String display = readString(input);
        boolean resolved = readFlag(input, "resolved");
        Owner owner = null;
        if (readFlag(input, "owner")) {
            String name = readString(input);
            String uuid = readString(input);
            OwnerState state = ownerStateFromCode(readString(input));
            try {
                owner = new Owner(name, uuid, state);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Shown-cause owner is not canonical", invalid);
            }
        }
        try {
            return new ShownCause(Form.NAMED, verb, subject, id, display, resolved, owner);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Shown cause is not canonical", invalid);
        }
    }

    private static boolean readFlag(DataInputStream input, String field) throws IOException {
        int value = input.readUnsignedByte();
        if (value == 0) return false;
        if (value == 1) return true;
        throw new IOException("Shown-cause " + field + " byte " + value + " is not canonical");
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FIELD_BYTES) throw new IllegalArgumentException("Shown-cause field is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_FIELD_BYTES || length > input.available()) {
            throw new EOFException("Invalid shown-cause field length");
        }
        byte[] bytes = input.readNBytes(length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IOException("Shown-cause field is not valid UTF-8", malformedUtf8);
        }
    }

    private static Verb verbFromCode(String code) throws IOException {
        for (Verb value : Verb.values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IOException("Unknown shown-cause verb");
    }

    private static Subject subjectFromCode(String code) throws IOException {
        for (Subject value : Subject.values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IOException("Unknown shown-cause subject");
    }

    private static OwnerState ownerStateFromCode(String code) throws IOException {
        for (OwnerState value : OwnerState.values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IOException("Unknown shown-cause owner state");
    }

    // ---------------------------------------------------------------------
    // Text rules, mirrored from DisplayNameResolver so every name here reads as an item name does.
    // ---------------------------------------------------------------------

    private static ResourceLocation registryId(Supplier<ResourceLocation> lookup, String kind) {
        ResourceLocation id;
        try {
            id = lookup.get();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            throw new IllegalArgumentException("The " + kind + " has no readable registry id", failure);
        }
        if (id == null) throw new IllegalArgumentException("The " + kind + " has no registry id");
        return id;
    }

    /**
     * {@code (renamed "X")} for a custom name, as stackDisplayName writes it: the name as safe text cut at
     * {@value #MAX_CUSTOM_CHARS} characters, backslash and double quote escaped; {@value #RENAMED_UNREADABLE} when
     * reading it throws; "" with no custom name.
     */
    static String renameSuffix(Supplier<Component> customName) {
        if (customName == null) return "";
        String text;
        try {
            Component name = customName.get();
            if (name == null) return "";
            text = name.getString();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return RENAMED_UNREADABLE;
        }
        String safe = safeText(text, MAX_CUSTOM_CHARS);
        return " (renamed \"" + quoted(safe == null ? "" : safe) + "\")";
    }

    /** DisplayNameResolver's quoting: backslash and double quote escaped, nothing else. */
    static String quoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** DisplayNameResolver's marked fallback, "Unresolved &lt;kind&gt; (&lt;id&gt;)", within the real-name bound. */
    static String unresolvedDisplay(String kind, String identity) {
        String safeIdentity = safeText(identity, MAX_NAME_CHARS);
        if (safeIdentity == null) return "Unknown";
        String prefix = "Unresolved " + kind + " (";
        int identityBudget = MAX_NAME_CHARS - prefix.length() - 1;
        if (safeIdentity.length() > identityBudget) {
            safeIdentity = EventTrailText.boundWithEllipsis(safeIdentity, identityBudget);
        }
        return prefix + safeIdentity + ")";
    }

    /**
     * DisplayNameResolver's item safe-text rule (item-names design 2.6): every lone surrogate becomes "?"; CR, LF,
     * TAB, U+0085, U+2028 and U+2029 become a space; runs of Java's ASCII whitespace collapse to one space; trim; a
     * blank result gives null; a longer result is cut at {@code maximum} UTF-16 units between whole code points with
     * "...". Applying it to its own result changes nothing.
     */
    static String safeText(String value, int maximum) {
        if (value == null) return null;
        StringBuilder out = new StringBuilder(value.length());
        int length = value.length();
        for (int index = 0; index < length; index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 < length && Character.isLowSurrogate(value.charAt(index + 1))) {
                    out.append(unit).append(value.charAt(index + 1));
                    index++;
                } else {
                    out.append('?');
                }
            } else if (Character.isLowSurrogate(unit)) {
                out.append('?');
            } else if (unit == '\r' || unit == '\n' || unit == '\t'
                    || unit == '\u0085' || unit == '\u2028' || unit == '\u2029') {
                out.append(' ');
            } else {
                out.append(unit);
            }
        }
        String clean = SAFE_TEXT_WHITESPACE.matcher(out).replaceAll(" ").trim();
        if (clean.isBlank()) return null;
        return EventTrailText.boundWithEllipsis(clean, maximum);
    }

    private static void requireSafeOrEmpty(String value, int maximum, String field) {
        Objects.requireNonNull(value, field);
        if (value.isEmpty()) return;
        if (!value.equals(safeText(value, maximum))) {
            throw new IllegalArgumentException(field + " is not bounded safe text");
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
