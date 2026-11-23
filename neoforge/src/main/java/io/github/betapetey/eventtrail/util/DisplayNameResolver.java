package io.github.betapetey.eventtrail.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringUtil;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Resolves the names EventTrail logs and shows.
 *
 * Item names are the game's own names, exactly as the server's game shows them
 * in the item's tooltip (item-names design 2.1-2.8): no guessed keys, no direct
 * mod-file reads and no invented names. A renamed stack is shown as
 * {@code <real> (renamed "<custom>")}. Every game call on the item path sits
 * inside {@code catch (Throwable)}, so no item method here throws: the capture
 * paths that call it (drop, pickup, transfer identity) rely on that.
 *
 * Block names keep the older lookup unchanged for now (item-names design 2.8):
 * dedicated servers do not necessarily have client language tables loaded into
 * Component.translatable(...).getString(), so the block path also reads each
 * mod's en_us.json directly.
 */
public final class DisplayNameResolver {
    static final String RENAME_CHECK_FAILED = " (rename check failed)";
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final Map<String, Map<String, String>> LANG_CACHE = new ConcurrentHashMap<>();
    private static final Pattern UNRESOLVED_FORMAT_ARGUMENT =
            Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");
    /** Java's ASCII {@code \s} class: [ \t\n\x0B\f\r]. */
    private static final Pattern ITEM_TEXT_WHITESPACE = Pattern.compile("\\s+");

    private DisplayNameResolver() {}

    /**
     * Resolves one canonical block-state label for every EventTrail renderer.
     * A captured block-entity custom name is authoritative presentation data;
     * the canonical registry id remains unchanged in the stored block state.
     */
    public static String blockStateDisplayName(String serializedState, String extraData) {
        BlockEffectPayloadCodec.DecodeResult decoded = BlockEffectPayloadCodec.decode(extraData);
        BlockEntityEvidence evidence = decoded.successful()
                ? decoded.payload().blockEntityEvidence()
                : BlockEntityEvidence.absent();
        return capturedBlockStateDisplayName(serializedState, evidence);
    }

    /** Resolves capture-time presentation without serializing an intermediate payload. */
    public static String capturedBlockStateDisplayName(
            String serializedState,
            BlockEntityEvidence evidence) {
        String customName = blockEntityCustomName(evidence);
        if (customName != null) return customName;

        ResourceLocation id = blockRegistryId(serializedState);
        if (id != null) {
            try {
                Block block = BuiltInRegistries.BLOCK.get(id);
                ResourceLocation actual = BuiltInRegistries.BLOCK.getKey(block);
                if (id.equals(actual)) {
                    String resolved = blockDisplayName(block);
                    if (resolved != null && !resolved.isBlank()) return resolved;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Historical or unavailable registry entries use the explicit
                // technical fallback below; they are not presented as resolved.
            }
            return unresolvedDisplay("block", id.toString());
        }

        String fallback = normalizeDisplayText(serializedState);
        return fallback == null ? "Unknown" : unresolvedDisplay("block", fallback);
    }

    /** Removes unresolved translation format slots without changing identity. */
    public static String normalizeDisplayText(String value) {
        if (value == null) return null;
        String normalized = value.replace("%%", "%");
        normalized = UNRESOLVED_FORMAT_ARGUMENT.matcher(normalized).replaceAll("");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? null : EventTrailTextBounds.boundText(normalized, EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS);
    }

    public static String blockDisplayName(Block block) {
        if (block == null) return null;

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id != null) {
            String canonicalKey = "block." + id.getNamespace() + "." + id.getPath();
            String translated = translation(id.getNamespace(), canonicalKey);
            if (usable(translated, canonicalKey)) return translated;
        }

        try {
            String descriptionId = block.getDescriptionId();
            if (descriptionId != null && !descriptionId.isBlank()) {
                String namespace = namespaceFromTranslationKey(descriptionId);
                String translated = translation(namespace, descriptionId);
                if (usable(translated, descriptionId)) return translated;

                String component = normalizeDisplayText(
                        Component.translatable(descriptionId).getString());
                if (usable(component, descriptionId)) return component;
            }
        } catch (Throwable ignored) {}

        try {
            Item item = block.asItem();
            if (item != null) {
                String itemName = legacyItemNameForBlockLabel(item);
                if (itemName != null && !itemName.isBlank()) return itemName;
            }
        } catch (Throwable ignored) {}

        try {
            String fallback = normalizeDisplayText(block.getName().getString());
            if (fallback != null && !fallback.isBlank()) return fallback;
        } catch (Throwable ignored) {}

        return id == null ? null : prettify(id.getPath());
    }

    /**
     * The former {@code itemDisplayName(Item)} body, unchanged, used ONLY by the
     * block-name fallback in {@link #blockDisplayName(Block)} so block rows read
     * exactly as before (item-names design 2.8, Q8-A). Item names never use it.
     */
    private static String legacyItemNameForBlockLabel(Item item) {
        if (item == null) return null;

        // Block items should use the registered block's canonical name. This is
        // especially important for mods that expose a generic/custom server-side
        // ItemStack hover name.
        if (item instanceof BlockItem blockItem) {
            String blockName = blockDisplayNameWithoutItemFallback(blockItem.getBlock());
            if (blockName != null && !blockName.isBlank()) return blockName;
        }

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id != null) {
            String itemKey = "item." + id.getNamespace() + "." + id.getPath();
            String translated = translation(id.getNamespace(), itemKey);
            if (usable(translated, itemKey)) return translated;
        }

        try {
            String descriptionId = item.getDescriptionId();
            if (descriptionId != null && !descriptionId.isBlank()) {
                String namespace = namespaceFromTranslationKey(descriptionId);
                String translated = translation(namespace, descriptionId);
                if (usable(translated, descriptionId)) return translated;

                String component = normalizeDisplayText(
                        Component.translatable(descriptionId).getString());
                if (usable(component, descriptionId)) return component;
            }
        } catch (Throwable ignored) {}

        try {
            ItemStack stack = new ItemStack(item);
            if (!stack.isEmpty()) {
                String fallback = normalizeDisplayText(stack.getHoverName().getString());
                if (fallback != null && !fallback.isBlank()) return fallback;
            }
        } catch (Throwable ignored) {}

        return id == null ? null : prettify(id.getPath());
    }

    // ---------------------------------------------------------------------
    // Item names (item-names design 2.2-2.7). No method below throws.
    // ---------------------------------------------------------------------

    /**
     * True when the stack carries a custom name its item does not carry by
     * default (a prototype custom name is not a rename). May throw; callers catch.
     */
    static boolean isRenamed(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name != null && !Objects.equals(name, stack.getPrototype().get(DataComponents.CUSTOM_NAME));
    }

    /**
     * The game's own name for this stack with any rename removed: what its
     * tooltip would show had it never been renamed. Null only for an empty
     * stack; "Unresolved item (&lt;id&gt;)" when the game gives no usable name.
     */
    public static String gameName(ItemStack stack) {
        return gameName(stack, DisplayNameResolver::isRenamed);
    }

    /** Test seam: {@link #gameName(ItemStack)} with a supplied rename test. */
    static String gameName(ItemStack stack, Predicate<ItemStack> renameTest) {
        if (stack == null || stack.isEmpty()) return null;
        try {
            ItemStack subject = stack;
            if (renameTest.test(stack)) {
                // copy(), not copyWithCount: a count-dependent game name keeps its count.
                subject = stack.copy();
                // Setting the prototype's own value removes the patch entry; null is allowed.
                subject.set(DataComponents.CUSTOM_NAME, stack.getPrototype().get(DataComponents.CUSTOM_NAME));
            }
            String safe = itemText(subject.getHoverName().getString(), EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS);
            if (safe != null) return safe;
        } catch (Throwable failure) {
            // Falls through to the marked fallback; the capture row is still written.
        }
        return unresolvedDisplay("item", registryIdOf(stack));
    }

    /**
     * Custom-name text: item safe text cut to 100 UTF-16 units between whole
     * code points; "" when blank (or absent); null only if reading it throws.
     */
    static String customName(ItemStack stack) {
        try {
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            if (name == null) return "";
            String safe = itemText(name.getString(), EventTrailTextBounds.MAX_CUSTOM_TEXT_CHARS);
            return safe == null ? "" : safe;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** Escapes backslash and double quote for the quoted part of a name; nothing else. */
    static String quoted(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * What EventTrail logs and shows for a stack: {@code <real>}, or
     * {@code <real> (renamed "<custom>")} for a renamed stack. The real name
     * always comes first; a possible rename is never dropped silently.
     */
    public static String stackDisplayName(ItemStack stack) {
        return stackDisplayName(stack, DisplayNameResolver::isRenamed);
    }

    /** Test seam: {@link #stackDisplayName(ItemStack)} with a supplied rename test. */
    static String stackDisplayName(ItemStack stack, Predicate<ItemStack> renameTest) {
        if (stack == null || stack.isEmpty()) return null;
        String real = writtenBookLabel(stack);              // Q3-B; null unless a titled vanilla written book
        if (real == null) real = gameName(stack, renameTest);
        boolean renamed;
        try {
            renamed = renameTest.test(stack);
        } catch (Throwable failure) {
            return real + RENAME_CHECK_FAILED;
        }
        if (!renamed) return real;
        String custom = customName(stack);
        return custom == null
                ? real + " (renamed, name unreadable)"
                : real + " (renamed \"" + quoted(custom) + "\")";
    }

    // ---------------------------------------------------------------------
    // Named containers (SOPH-LABELS; block-name fix BN 2.4 and 2.5, built
    // early for the Sophisticated Storage names). No method below throws.
    // ---------------------------------------------------------------------

    /** The formatter's budget (BN 2.4): the access target's own label bound, in UTF-8 bytes. */
    public static final int MAX_NAME_UTF8_BYTES = 256;

    /**
     * The custom-name state of a named container (BN 2.5, live path): none; its text (cleaned by the item rule and
     * cut at 100 UTF-16 units, "" for a blank name); present but unreadable; or the "is it renamed" check itself
     * failed. {@code text} is set only for {@link Kind#TEXT}.
     */
    public record RenameState(Kind kind, String text) {
        public static final RenameState NONE = new RenameState(Kind.NONE, null);
        public static final RenameState UNREADABLE = new RenameState(Kind.UNREADABLE, null);
        public static final RenameState CHECK_FAILED = new RenameState(Kind.CHECK_FAILED, null);

        public enum Kind { NONE, TEXT, UNREADABLE, CHECK_FAILED }

        public RenameState {
            kind = Objects.requireNonNull(kind, "kind");
            if (kind == Kind.TEXT) {
                String safe = itemText(text, EventTrailTextBounds.MAX_CUSTOM_TEXT_CHARS);
                text = safe == null ? "" : safe;
            } else {
                text = null;
            }
        }

        /** A readable custom name (blank gives ""). */
        public static RenameState text(String raw) {
            return new RenameState(Kind.TEXT, raw);
        }

        /**
         * A stack's state, by the item rule's own reads: the rename check throws -> CHECK_FAILED; not renamed (or no
         * stack) -> NONE; the custom name unreadable -> UNREADABLE; else its text.
         */
        public static RenameState ofStack(ItemStack stack) {
            if (stack == null) return NONE;
            boolean renamed;
            try {
                renamed = isRenamed(stack);
            } catch (Throwable failure) {
                return CHECK_FAILED;
            }
            if (!renamed) return NONE;
            String custom = customName(stack);
            return custom == null ? UNREADABLE : text(custom);
        }

        /**
         * A block entity's state from its custom name ({@code Nameable#getCustomName()}): the read throws ->
         * CHECK_FAILED; null (or no reader) -> NONE; its text throws -> UNREADABLE; else its text.
         */
        public static RenameState ofComponent(Supplier<Component> customName) {
            Component name;
            try {
                name = customName == null ? null : customName.get();
            } catch (Throwable failure) {
                return CHECK_FAILED;
            }
            if (name == null) return NONE;
            String raw;
            try {
                raw = name.getString();
            } catch (Throwable failure) {
                return UNREADABLE;
            }
            return text(raw);
        }
    }

    /** True when {@code value} has text left after the item safe-text rule (not null, not blank). */
    public static boolean usableName(String value) {
        return itemText(value, EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS) != null;
    }

    /**
     * The one formatter for a named container (BN 2.4): {@code L}, {@code L (X)}, {@code L (M)} or {@code L (X, M)},
     * where L is the look name, X a second in-game name (left out when blank or equal to L) and M the rename marker
     * ({@code renamed "<c>"}, {@code renamed, name unreadable} or {@code rename check failed}). L and X are cleaned by
     * the item safe-text rule; a blank L gives "Container", so the result is never null or blank and never starts
     * with " (". At most {@value #MAX_NAME_UTF8_BYTES} UTF-8 bytes: when longer, c is cut first (quoted after the cut),
     * then X (left out when not even one code point fits), then the end of L, each cut between whole code points with
     * "..."; M is never dropped. The result is unchanged by {@link EventTrailTextBounds#literalDisplayText(String)}.
     */
    public static String formatName(String look, String second, RenameState rename) {
        String l = itemText(look, EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS);
        if (l == null) l = "Container";
        String x = itemText(second, EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS);
        if (x != null && x.equals(l)) x = null;
        RenameState state = rename == null ? RenameState.NONE : rename;
        String custom = state.kind() == RenameState.Kind.TEXT ? state.text() : null;
        String marker = switch (state.kind()) {
            case TEXT -> renamedMarker(custom);
            case UNREADABLE -> "renamed, name unreadable";
            case CHECK_FAILED -> "rename check failed";
            case NONE -> null;
        };
        String name = composeName(l, x, marker);
        if (utf8Length(name) <= MAX_NAME_UTF8_BYTES) return name;

        // a. the custom text: its longest start (k whole code points, k >= 0) followed by "...", quoted after the cut.
        if (custom != null && !custom.isEmpty()) {
            int[] ends = codePointEnds(custom);
            for (int kept = ends.length - 2; kept >= 0; kept--) {
                String cut = "renamed \"" + quoted(custom.substring(0, ends[kept])) + EventTrailText.ELLIPSIS + "\"";
                name = composeName(l, x, cut);
                if (utf8Length(name) <= MAX_NAME_UTF8_BYTES) return name;
                if (kept == 0 && utf8Length(cut) < utf8Length(marker)) marker = cut;
            }
        }
        // b. the second name: its longest start of at least one code point followed by "...", else left out.
        if (x != null) {
            int[] ends = codePointEnds(x);
            for (int kept = ends.length - 2; kept >= 1; kept--) {
                name = composeName(l, x.substring(0, ends[kept]) + EventTrailText.ELLIPSIS, marker);
                if (utf8Length(name) <= MAX_NAME_UTF8_BYTES) return name;
            }
            name = composeName(l, null, marker);
            if (utf8Length(name) <= MAX_NAME_UTF8_BYTES) return name;
        }
        // c. the end of the look name, so that it and the rest fit exactly within the budget.
        int rest = marker == null ? 0 : utf8Length(composeName("", null, marker));
        int budget = MAX_NAME_UTF8_BYTES - rest - EventTrailText.ELLIPSIS.length();
        return composeName(prefixOfUtf8Bytes(l, budget) + EventTrailText.ELLIPSIS, null, marker);
    }

    private static String renamedMarker(String custom) {
        return "renamed \"" + quoted(custom) + "\"";
    }

    private static String composeName(String look, String second, String marker) {
        if (second == null && marker == null) return look;
        StringBuilder name = new StringBuilder(look).append(" (");
        if (second != null) name.append(second);
        if (second != null && marker != null) name.append(", ");
        if (marker != null) name.append(marker);
        return name.append(')').toString();
    }

    /** {@code ends[k]} is the UTF-16 end of the first k code points (ends[0] = 0, last = length). */
    private static int[] codePointEnds(String value) {
        int[] ends = new int[value.codePointCount(0, value.length()) + 1];
        int index = 0;
        for (int k = 1; k < ends.length; k++) {
            index = value.offsetByCodePoints(index, 1);
            ends[k] = index;
        }
        return ends;
    }

    /** The longest start of {@code value}, between whole code points, of at most {@code maxBytes} UTF-8 bytes. */
    private static String prefixOfUtf8Bytes(String value, int maxBytes) {
        int bytes = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int size = utf8Length(codePoint);
            if (bytes + size > maxBytes) break;
            bytes += size;
            index += Character.charCount(codePoint);
        }
        return value.substring(0, index);
    }

    /** UTF-8 length of {@code value}; a lone surrogate counts 3 bytes (an over-count; cleaned text holds none). */
    static int utf8Length(String value) {
        if (value == null) return 0;
        int bytes = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            bytes += utf8Length(codePoint);
            index += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }

    /**
     * Q3-B: a vanilla written book with a non-blank title and no ITEM_NAME is
     * named {@code Written Book (titled "<title>")}, cut as a whole at 256.
     * Null for every other stack (and for a blank title: the game then says
     * "Written Book", as {@link #gameName(ItemStack)} does).
     */
    static String writtenBookLabel(ItemStack stack) {
        return writtenBookLabel(stack, DisplayNameResolver::writtenBookRawTitle);
    }

    /** Test seam: {@link #writtenBookLabel(ItemStack)} with a supplied title reader. */
    static String writtenBookLabel(ItemStack stack, Function<ItemStack, String> titleReader) {
        try {
            if (stack == null || stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return null;
            // With ITEM_NAME set the game's name is that text, and gameName gives it.
            if (stack.get(DataComponents.ITEM_NAME) != null) return null;
        } catch (Throwable failure) {
            return null;
        }
        String title;
        try {
            title = titleReader.apply(stack);
        } catch (Throwable failure) {
            return itemText(itemTypeName(Items.WRITTEN_BOOK) + " (title unreadable)", EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS);
        }
        if (StringUtil.isBlank(title)) return null;           // the game's own blank test (U+00A0 counts)
        String safeTitle = itemText(title, EventTrailTextBounds.MAX_CUSTOM_TEXT_CHARS);
        return itemText(itemTypeName(Items.WRITTEN_BOOK)
                + " (titled \"" + quoted(safeTitle == null ? "" : safeTitle) + "\")", EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS);
    }

    private static String writtenBookRawTitle(ItemStack stack) {
        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return content == null ? null : content.title().raw();
    }

    /**
     * The game's name for an item type when no stack details exist (degraded
     * transfer and weapon rows, search), item-names design 2.4: the translated
     * description; else a plain stack's hover name; else the raw key or
     * template; else "Unresolved item (&lt;id&gt;)". Never throws.
     */
    public static String itemTypeName(Item item) {
        if (item == null) return null;
        String id;
        try {
            id = BuiltInRegistries.ITEM.getResourceKey(item)
                    .map(key -> key.location().toString())
                    .orElse(null);
        } catch (Throwable failure) {
            id = null;
        }
        if (id == null) return unresolvedDisplay("item", "unknown");

        String key = null;
        try {
            key = item.getDescriptionId();
        } catch (Throwable ignored) {}
        String description = null;
        try {
            description = itemText(item.getDescription().getString(), EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS);
        } catch (Throwable ignored) {}
        if (description != null && !description.equals(key)
                && !UNRESOLVED_FORMAT_ARGUMENT.matcher(description).find()) {
            return description;
        }

        String hover = null;
        try {
            hover = itemText(new ItemStack(item).getHoverName().getString(), EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS);
        } catch (Throwable ignored) {}
        if (hover != null) return hover;
        if (description != null) return description;          // the raw key or raw template, as the game says
        return unresolvedDisplay("item", id);
    }

    /** Registered id -> {@link #itemTypeName(Item)}; otherwise "Unresolved item (&lt;id&gt;)". */
    public static String itemDisplayName(String registryId) {
        if (registryId == null || registryId.isBlank()) return null;
        ResourceLocation id = ResourceLocation.tryParse(registryId);
        if (id == null) return unresolvedDisplay("item", registryId);

        try {
            Item item = BuiltInRegistries.ITEM.get(id);
            ResourceLocation actual = BuiltInRegistries.ITEM.getKey(item);
            if (actual != null && actual.equals(id)) return itemTypeName(item);
        } catch (Throwable ignored) {}

        return unresolvedDisplay("item", id.toString());
    }

    /** The marked fallback "Unresolved item (&lt;id&gt;)" for a capture path with no usable name. */
    public static String unresolvedItemDisplay(String registryId) {
        return unresolvedDisplay("item", registryId);
    }

    /**
     * Container label taken from an item stack (Storage in Motion carts and
     * boats, Sophisticated backpacks). Q7 = B: a renamed item keeps its
     * non-blank custom name exactly as before; any other stack shows the game's
     * own name. The block-name fix later makes this {@code stackDisplayName(stack)}.
     */
    public static String itemContainerLabel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String custom;
        try {
            custom = componentText(stack.get(DataComponents.CUSTOM_NAME), null);
        } catch (Throwable failure) {
            custom = null;
        }
        return custom != null ? custom : gameName(stack);  // a blank custom name gives the real name, as before
    }

    /**
     * The item safe-text rule (item-names design 2.6), in order: every lone
     * surrogate becomes "?" (what the payload encoders write for it); CR, LF,
     * TAB, U+0085, U+2028 and U+2029 become a space; runs of Java's ASCII
     * {@code \s} collapse to one space; trim; blank by {@link String#isBlank()}
     * gives null; cut at {@code maximum} UTF-16 units between whole code points
     * with "...".
     */
    static String itemText(String value, int maximum) {
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
        String clean = ITEM_TEXT_WHITESPACE.matcher(out).replaceAll(" ").trim();
        if (clean.isBlank()) return null;
        if (clean.length() <= maximum) return clean;
        if (maximum <= EventTrailText.ELLIPSIS.length()) {
            return EventTrailText.prefixOfWholeCharacters(clean, Math.max(0, maximum));
        }
        return EventTrailText.boundWithEllipsis(clean, maximum);
    }

    private static String registryIdOf(ItemStack stack) {
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } catch (Throwable failure) {
            return "unknown";
        }
    }

    private static String blockDisplayNameWithoutItemFallback(Block block) {
        if (block == null) return null;

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id != null) {
            String canonicalKey = "block." + id.getNamespace() + "." + id.getPath();
            String translated = translation(id.getNamespace(), canonicalKey);
            if (usable(translated, canonicalKey)) return translated;
        }

        try {
            String descriptionId = block.getDescriptionId();
            if (descriptionId != null && !descriptionId.isBlank()) {
                String namespace = namespaceFromTranslationKey(descriptionId);
                String translated = translation(namespace, descriptionId);
                if (usable(translated, descriptionId)) return translated;

                String component = normalizeDisplayText(
                        Component.translatable(descriptionId).getString());
                if (usable(component, descriptionId)) return component;
            }
        } catch (Throwable ignored) {}

        return id == null ? null : prettify(id.getPath());
    }

    private static String translation(String namespace, String key) {
        if (namespace == null || namespace.isBlank() || key == null || key.isBlank()) return null;
        Map<String, String> lang = LANG_CACHE.computeIfAbsent(
                namespace.toLowerCase(Locale.ROOT),
                DisplayNameResolver::loadEnglishLanguage
        );
        return normalizeDisplayText(lang.get(key));
    }

    private static Map<String, String> loadEnglishLanguage(String namespace) {
        // Vanilla translations are normally available through Component on the
        // dedicated server. Mod translations are loaded directly from the mod file.
        if ("minecraft".equals(namespace)) return Collections.emptyMap();

        try {
            var modFileInfo = ModList.get().getModFileById(namespace);
            if (modFileInfo == null) return Collections.emptyMap();

            Path langPath = modFileInfo.getFile()
                    .findResource("assets/" + namespace + "/lang/en_us.json");

            if (!Files.isRegularFile(langPath)) return Collections.emptyMap();

            try (Reader reader = Files.newBufferedReader(langPath, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) return Collections.emptyMap();

                JsonObject object = root.getAsJsonObject();
                Map<String, String> result = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        result.put(entry.getKey(), value.getAsString());
                    }
                }
                return Collections.unmodifiableMap(result);
            }
        } catch (Throwable t) {
            LOGGER.debug("Could not read {} English language file for EventTrail display names", namespace, t);
            return Collections.emptyMap();
        }
    }

    private static boolean usable(String value, String key) {
        return value != null && !value.isBlank() && !value.equals(key);
    }

    private static String componentText(Component component, String untranslatedKey) {
        if (component == null) return null;
        try {
            String text = EventTrailTextBounds.literalDisplayText(component.getString());
            return usable(text, untranslatedKey) ? text : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ResourceLocation blockRegistryId(String serializedState) {
        if (serializedState == null || serializedState.isBlank()) return null;
        String value = serializedState.trim();
        if (value.startsWith("Block{")) value = value.substring("Block{".length());
        int openBrace = value.indexOf('{');
        if (openBrace >= 0) value = value.substring(openBrace + 1);
        int closeBrace = value.indexOf('}');
        if (closeBrace >= 0) value = value.substring(0, closeBrace);
        int properties = value.indexOf('[');
        if (properties >= 0) value = value.substring(0, properties);
        return ResourceLocation.tryParse(value.trim());
    }

    private static String blockEntityCustomName(BlockEntityEvidence evidence) {
        if (evidence == null) return null;
        String canonical = evidence.canonicalSnbt().orElse(null);
        if (canonical == null) return null;
        AuditSnbtParser.ParseResult parsed = AuditSnbtParser.parsePayload(canonical, 0);
        if (!parsed.successful()) return null;
        CompoundTag tag = parsed.tag();
        if (!tag.contains("CustomName")) return null;

        String serialized = tag.getString("CustomName");
        if (serialized == null || serialized.isBlank()) return null;
        if (AuditSnbtParser.validateComponentJson(serialized) != AuditSnbtParser.Status.OK) {
            return null;
        }
        try {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                Component component = Component.Serializer.fromJson(
                        serialized, server.registryAccess());
                String resolved = componentText(component, null);
                if (resolved != null) return boundDisplayName(resolved);
            }
        } catch (Throwable ignored) {
            // A malformed historical custom name must not hide the registered
            // block label or make rendering fail.
        }

        try {
            JsonElement json = JsonParser.parseString(serialized);
            String literal = literalComponentText(json, 0);
            if (literal != null) return boundDisplayName(literal);
        } catch (RuntimeException | StackOverflowError ignored) {
            // Fall through to the registered block name.
        }
        return null;
    }

    private static String literalComponentText(JsonElement element, int depth) {
        if (element == null || element.isJsonNull() || depth > 16) return null;
        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().isString()
                    ? EventTrailTextBounds.literalDisplayText(element.getAsString())
                    : null;
        }
        if (element.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement child : element.getAsJsonArray()) {
                String part = literalComponentText(child, depth + 1);
                if (part != null) text.append(part);
            }
            return EventTrailTextBounds.literalDisplayText(text.toString());
        }
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        StringBuilder text = new StringBuilder();
        if (object.has("text") && object.get("text").isJsonPrimitive()) {
            text.append(object.get("text").getAsString());
        }
        if (object.has("extra")) {
            String extra = literalComponentText(object.get("extra"), depth + 1);
            if (extra != null) text.append(extra);
        }
        return EventTrailTextBounds.literalDisplayText(text.toString());
    }

    private static String boundDisplayName(String value) {
        return EventTrailTextBounds.literalDisplayText(value);
    }

    private static String itemEventSummaryName(String storedSummary, int exactCount) {
        String summary = EventTrailTextBounds.literalDisplayText(storedSummary);
        if (summary == null) return null;
        String prefix = exactCount + "x ";
        if (!summary.startsWith(prefix)) return null;
        return EventTrailTextBounds.literalDisplayText(summary.substring(prefix.length()));
    }

    private static String unresolvedDisplay(String kind, String identity) {
        String safeIdentity = EventTrailTextBounds.literalDisplayText(identity);
        if (safeIdentity == null) return "Unknown";

        String prefix = "Unresolved " + kind + " (";
        int identityBudget = EventTrailTextBounds.MAX_DISPLAY_TEXT_CHARS - prefix.length() - 1;
        safeIdentity = EventTrailTextBounds.boundText(safeIdentity, identityBudget);
        return prefix + safeIdentity + ")";
    }

    private static String namespaceFromTranslationKey(String key) {
        if (key == null) return null;
        int first = key.indexOf('.');
        if (first < 0 || first + 1 >= key.length()) return null;
        int second = key.indexOf('.', first + 1);
        if (second < 0) return null;
        return key.substring(first + 1, second);
    }

    static String prettify(String path) {
        String safePath = EventTrailTextBounds.literalDisplayText(path);
        if (safePath == null) return null;
        String[] parts = safePath.replace('-', '_').split("_+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) out.append(part.substring(1));
        }
        return EventTrailTextBounds.literalDisplayText(out.toString());
    }
}
