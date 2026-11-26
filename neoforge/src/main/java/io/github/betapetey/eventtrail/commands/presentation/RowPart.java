package io.github.betapetey.eventtrail.commands.presentation;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One structured part of a row after its action word. Parts are joined exactly as given (a {@link Joiner} carries its
 * own spaces), so the chat renderer and {@link AuditRowView#plainText()} show the same text. Only a {@link Thing} with
 * an ID may carry a hover and a click (Owner 9:29 PM 11-22: small hovers on names and containers only).
 */
public sealed interface RowPart permits RowPart.Count, RowPart.Thing, RowPart.Joiner, RowPart.Note, RowPart.Name {
    /** The text this part shows, exactly as chat shows it without styling. */
    String plainText();

    /** "12× " before the thing it counts. No hover, no click. */
    record Count(long value) implements RowPart {
        public Count {
            if (value < 0L) throw new IllegalArgumentException("count cannot be negative");
        }

        @Override
        public String plainText() {
            return value + "× ";
        }
    }

    /**
     * An item, block, entity, container or weapon. {@code realName} is the thing's own name and is never shortened;
     * {@code customName} is a player's rename (shown compactly as {@code Dirt "Diamond"}), or null; {@code id} is the
     * registry ID its hover shows and its click copies, or null when no trustworthy ID is known (then no hover and no
     * click, never a wrong ID).
     */
    record Thing(String realName, String customName, String id, Role role) implements RowPart {
        private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

        public Thing {
            if (realName == null || realName.isBlank()) throw new IllegalArgumentException("realName must not be blank");
            if (customName != null && customName.isEmpty()) customName = null;
            id = id == null || !ID.matcher(id).matches() ? null : id;
            role = Objects.requireNonNull(role, "role");
        }

        public Optional<String> idText() {
            return Optional.ofNullable(id);
        }

        public boolean renamed() {
            return customName != null;
        }

        @Override
        public String plainText() {
            return customName == null ? realName : realName + " \"" + customName + "\"";
        }

        /** Whether {@code value} is a namespaced registry ID the hover may show and the click may copy. */
        public static boolean validId(String value) {
            return value != null && ID.matcher(value).matches();
        }
    }

    /** Joining words with their spaces: " to ", " from ", " with ", ", killed by ", or a single " ". GRAY, no hover. */
    record Joiner(String text) implements RowPart {
        public Joiner {
            if (text == null || text.isEmpty()) throw new IllegalArgumentException("joiner text must not be empty");
        }

        @Override
        public String plainText() {
            return text;
        }
    }

    /** A note such as " (items not tracked)" or an explosion part suffix, exactly as given. GRAY, no hover. */
    record Note(String text) implements RowPart {
        public Note {
            if (text == null || text.isEmpty()) throw new IllegalArgumentException("note text must not be empty");
        }

        @Override
        public String plainText() {
            return text;
        }
    }

    /**
     * A player's name inside the row text (a killer, a death credit). WHITE, with no hover and no click: the player-name
     * search click was Owner question Q243, decided B: no click on player names or action words.
     */
    record Name(String text) implements RowPart {
        public Name {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("name must not be blank");
        }

        @Override
        public String plainText() {
            return text;
        }
    }

    enum Role { OBJECT, CONTAINER, WEAPON, ENTITY }
}
