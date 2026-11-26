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
        List<RowPart> parts) {

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

    /**
     * The earlier six-field form, kept so existing callers compile: no exact time (so no time hover), the Title Case
     * label as a GRAY verb, and the flat detail as one note.
     */
    public AuditRowView(
            long ordinal,
            String relativeAge,
            String indexedParty,
            String actionLabel,
            String detail,
            RowLocation location) {
        this(ordinal, relativeAge, indexedParty, actionLabel, detail, location, Optional.empty(),
                new Verb(required(actionLabel, "actionLabel"), Tone.GRAY),
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

    /** The row's lower-case action word and its colour (never colour alone: the word stays). */
    public record Verb(String text, Tone tone) {
        public Verb {
            text = required(text, "verb");
            tone = Objects.requireNonNull(tone, "tone");
        }
    }

    /** Action-word colours; the renderer maps each to its chat colour. */
    public enum Tone { RED, GREEN, YELLOW, GOLD, SKY_BLUE, DARK_AQUA, GRAY }

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
