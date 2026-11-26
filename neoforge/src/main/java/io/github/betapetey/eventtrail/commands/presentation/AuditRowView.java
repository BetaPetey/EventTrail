package io.github.betapetey.eventtrail.commands.presentation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable presentation facts for one audit row.
 *
 * <p>The chat renderer (Design B, 11-22) uses only the structured fields: {@code relativeAge}, {@code occurredAt} (the
 * time hover), {@code indexedParty}, {@code verb} and {@code parts}, plus the location. {@code actionLabel} (Title Case)
 * and {@code detail} (the flat text) are kept, computed as before, for the existing row checks and gate observers that
 * read them; they are no longer shown in chat.
 *
 * <p>{@code partyIsPlayer} says whether {@code indexedParty} names a player (taken from the stored identity kind, never
 * from the name text). Only a player's name is drawn in the player colour; an environment actor such as "Creeper" or
 * "Explosion source" is a thing and is drawn like one.
 */
public record AuditRowView(
        long ordinal,
        String relativeAge,
        String indexedParty,
        String actionLabel,
        String detail,
        RowLocation location,
        Optional<Instant> occurredAt,
        Verb verb,
        List<RowPart> parts,
        boolean partyIsPlayer) {

    public AuditRowView {
        if (ordinal < 1L) throw new IllegalArgumentException("ordinal must be positive");
        relativeAge = required(relativeAge, "relativeAge");
        indexedParty = required(indexedParty, "indexedParty");
        actionLabel = required(actionLabel, "actionLabel");
        detail = required(detail, "detail");
        location = Objects.requireNonNull(location, "location");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        verb = Objects.requireNonNull(verb, "verb");
        parts = List.copyOf(parts);
    }

    /** The nine-field form: the party is a player. */
    public AuditRowView(
            long ordinal,
            String relativeAge,
            String indexedParty,
            String actionLabel,
            String detail,
            RowLocation location,
            Optional<Instant> occurredAt,
            Verb verb,
            List<RowPart> parts) {
        this(ordinal, relativeAge, indexedParty, actionLabel, detail, location, occurredAt, verb, parts, true);
    }

    /**
     * The earlier six-field form, kept so existing callers compile: no exact time (so no time hover), the Title Case
     * label as a GRAY verb, the flat detail as one note, and the party a player.
     */
    public AuditRowView(
            long ordinal,
            String relativeAge,
            String indexedParty,
            String actionLabel,
            String detail,
            RowLocation location) {
        this(ordinal, relativeAge, indexedParty, actionLabel, detail, location, Optional.empty(),
                new Verb(required(actionLabel, "actionLabel")),
                List.of(new RowPart.Joiner(" "), new RowPart.Note(required(detail, "detail"))));
    }

    /**
     * The row as chat shows it, without styling or hovers: age, player, action word, parts and, when
     * {@code withCoordinates}, " x y z". Used for logs and tests; no console lookup exists (lookup is players only).
     */
    public String plainText(boolean withCoordinates) {
        StringBuilder text = new StringBuilder()
                .append(relativeAge).append(' ')
                .append(indexedParty).append(' ')
                .append(verb.text());
        for (RowPart part : parts) text.append(part.plainText());
        if (withCoordinates) text.append(' ').append(location.coordinates());
        return text.toString();
    }

    /** The lookup form of {@link #plainText(boolean)}, with the coordinates. */
    public String plainText() {
        return plainText(true);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** The row's lower-case action word. The renderer draws every action word GRAY (Owner Q443 B). */
    public record Verb(String text) {
        public Verb {
            text = required(text, "verb");
        }
    }

    /**
     * Dimension and coordinates are one identity; only navigationCommand is interactive. {@code showDimension} means
     * the coordinate hover names the dimension, because no other line on that screen does (a global lookup).
     */
    public record RowLocation(
            ResourceLocation dimensionId,
            BlockPos position,
            boolean showDimension,
            Optional<String> navigationCommand) {
        public RowLocation {
            dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
            position = Objects.requireNonNull(position, "position").immutable();
            navigationCommand = Objects.requireNonNull(navigationCommand, "navigationCommand")
                    .filter(command -> !command.isBlank());
        }

        /** "x y z", with no "@" (Design B rows). */
        public String coordinates() {
            return position.getX() + " " + position.getY() + " " + position.getZ();
        }
    }
}
