package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;

import java.util.Objects;
import java.util.UUID;

/**
 * Sophisticated part 4 (guard design 01, 1.1; Advisor Q6, Q8): the one key and label rule of a linked storage group,
 * used by every linked adapter, so a linked chest, barrel and cart of one group share one trail.
 *
 * <ul>
 *   <li>Key text {@code sophisticatedcore-link-group|<groupId>}, with {@code UUID.toString()} (lower-case canonical
 *       form). No dimension (groups are server-wide saved data), no adapter id and no mod version (the group id is a
 *       persistent UUID in world data; a version would split one group's trail at every update). Only its SHA-256
 *       reaches a row, as {@code backing_ref}.</li>
 *   <li>Label: the group's own display name (player-chosen text) with its formatting codes (the section sign
 *       U+00A7 and one code character) removed by the game's own {@code StringUtil.stripColor}, repeated until none is
 *       left, then stripped; a missing name, or one that is blank after that, gives the group id (Advisor condition
 *       5). The text is cleaned where every container label enters EventTrail ({@code ContainerTarget}: line breaks
 *       and tabs become spaces, runs of white space one space, lone surrogates {@code ?}, at most 256 UTF-8 bytes),
 *       and the blank test here is the one that cleaning makes, run on the stripped name, so a name the cleaning
 *       would empty falls back to the group id, never to the generic word.</li>
 * </ul>
 */
public final class SophisticatedLinkGroupKey {
    /** The key text before the group id. */
    public static final String PREFIX = "sophisticatedcore-link-group|";

    private SophisticatedLinkGroupKey() {
    }

    /** The group's key text. */
    public static String of(UUID groupId) {
        return PREFIX + Objects.requireNonNull(groupId, "groupId");
    }

    /** The group's label from its host's display name ({@code host.getDisplayName()}). */
    public static String label(Component displayName, UUID groupId) {
        return label(displayName == null ? null : displayName.getString(), groupId);
    }

    /** The group's label from its display name text. */
    public static String label(String displayName, UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        if (displayName == null) return groupId.toString();
        String stripped = withoutFormattingCodes(displayName).strip();
        // trim() first: the label cleaning trims characters up to U+0020, then tests isBlank() (review-01 NIT-1).
        return stripped.trim().isBlank() ? groupId.toString() : stripped;
    }

    /** The text with no formatting code left: the game's own stripping, again while it still removes one. */
    private static String withoutFormattingCodes(String text) {
        String current = text;
        while (true) {
            String next = StringUtil.stripColor(current);
            if (next.length() == current.length()) return next;
            current = next;
        }
    }
}
