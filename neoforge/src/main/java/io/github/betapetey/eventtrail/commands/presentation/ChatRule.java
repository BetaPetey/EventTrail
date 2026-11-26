package io.github.betapetey.eventtrail.commands.presentation;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Map;
import java.util.Optional;

/**
 * The solid chat rules of the lookup and inspect frame (Owner 11-22, R6/R16): GRAY struck-through spaces, never
 * dashes, sized in pixels to the default chat width of 320 px with the inner text centred. Each side of L px uses
 * b = L mod 4 bold struck spaces (5 px) and (L - 5b) / 4 plain struck spaces (4 px). The left side is
 * floor((320 - inner) / 2) px and the right side the rest.
 *
 * <p>The server cannot measure the client's font: the widths are the vanilla 1.21.1 default font's advances (bold
 * +1 px), and only the glyphs used inside rules are listed. A forced Unicode font or a resource pack may wrap a rule;
 * that is accepted (plan G23). Rows are never measured: they wrap on their own (Design B).
 */
public final class ChatRule {
    public static final int CHAT_WIDTH_PX = 320;
    private static final int SPACE_PX = 4;
    private static final Map<Integer, Integer> ADVANCE = advances();

    private ChatRule() {}

    /** A rule of exactly {@link #CHAT_WIDTH_PX} px around {@code inner} (styled), or a solid rule when it is empty. */
    public static MutableComponent ruled(Component inner) {
        int innerWidth = width(inner);
        int rest = Math.max(0, CHAT_WIDTH_PX - innerWidth);
        int left = rest / 2;
        int right = rest - left;
        MutableComponent line = Component.empty();
        appendSide(line, left, true);
        line.append(inner);
        appendSide(line, right, false);
        return line;
    }

    /** The header rule under the two header lines: the same rule with no text. */
    public static MutableComponent solid() {
        return ruled(Component.empty());
    }

    /** Pixel width of a styled component in the default font; throws for a glyph outside the rule table. */
    public static int width(Component component) {
        int[] total = {0};
        component.visit((style, text) -> {
            total[0] += width(text, style.isBold());
            return Optional.empty();
        }, Style.EMPTY);
        return total[0];
    }

    static int width(String text, boolean bold) {
        int total = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            Integer advance = ADVANCE.get(codePoint);
            if (advance == null) {
                throw new IllegalArgumentException("No default-font width for U+"
                        + Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT) + " inside a chat rule");
            }
            total += advance + (bold ? 1 : 0);
            offset += Character.charCount(codePoint);
        }
        return total;
    }

    /** {plain count, bold count} for {@code px}: b = px mod 4 bold spaces, the rest plain; shrinks to fit when small. */
    static int[] segment(int px) {
        for (int length = Math.max(0, px); length >= 0; length--) {
            int bold = length % 4;
            int plainPx = length - (SPACE_PX + 1) * bold;
            if (plainPx >= 0 && plainPx % SPACE_PX == 0) return new int[]{plainPx / SPACE_PX, bold};
        }
        return new int[]{0, 0};
    }

    private static void appendSide(MutableComponent line, int px, boolean left) {
        int[] counts = segment(px);
        MutableComponent plain = counts[0] == 0 ? null : struck(counts[0], false);
        MutableComponent bold = counts[1] == 0 ? null : struck(counts[1], true);
        // Left: bold then plain; right: plain then bold (the mock's order, design03.py ruled()).
        MutableComponent first = left ? bold : plain;
        MutableComponent second = left ? plain : bold;
        if (first != null) line.append(first);
        if (second != null) line.append(second);
    }

    private static MutableComponent struck(int count, boolean bold) {
        MutableComponent spaces = Component.literal(" ".repeat(count))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.STRIKETHROUGH);
        return bold ? spaces.withStyle(ChatFormatting.BOLD) : spaces;
    }

    private static Map<Integer, Integer> advances() {
        java.util.HashMap<Integer, Integer> map = new java.util.HashMap<>();
        put(map, " ", 4);
        put(map, "0123456789/", 6);
        put(map, "ELNPT", 6);
        put(map, "I", 4);
        put(map, "acegnoprsuvx", 6);
        put(map, "i", 2);
        put(map, "k", 5);
        put(map, "l", 3);
        put(map, "t", 4);
        put(map, "|", 2);
        put(map, "·", 2);     // ·
        put(map, "»", 7);     // »
        put(map, "◀", 7);     // ◀
        put(map, "▶", 7);     // ▶
        return Map.copyOf(map);
    }

    private static void put(Map<Integer, Integer> map, String glyphs, int advance) {
        glyphs.codePoints().forEach(codePoint -> map.put(codePoint, advance));
    }
}
