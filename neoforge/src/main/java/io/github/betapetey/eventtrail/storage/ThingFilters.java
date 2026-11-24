package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditPayloadCodec;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayloadCodec;
import io.github.betapetey.eventtrail.util.EntityEventPayloadCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The {@code block:}, {@code item:}, {@code entity:} and {@code mod:} filters of one lookup (Owner 11-22 5:12 AM,
 * items 4-13). Values of one filter are ORed; filters are ANDed with each other and with every other lookup option.
 *
 * <p>Which parts of a row each filter reads:</p>
 * <ul>
 *   <li>{@code block:} the block of break, place and explosion rows ({@code block_state} up to '[', and the block's
 *   logged name); and, for a quoted name only, the container of container access and transfer rows by its logged
 *   name (G17 (a) name half, lead D-380).</li>
 *   <li>{@code item:} the item of drop and pickup rows ({@code block_state} and the item's logged name), of
 *   container deposit and withdrawal rows (the item ID that opens the transfer payload), and the weapon of kill and
 *   death rows (the weapon's stored item ID) (G19 (a)).</li>
 *   <li>{@code entity:} the victim, the killer and the "via" entity of kill and death rows: the victim by
 *   {@code block_state} and its logged names, the others by the stored direct and causing entities' IDs and logged
 *   names (G19 (a)).</li>
 *   <li>{@code mod:} the namespace of every block, item and entity ID the row names (R36): the block, the item,
 *   the victim, the killer, the "via" entity, the killer's owner and the weapon.</li>
 * </ul>
 *
 * <p>HELD, not built (lookup-01 open-g.md): G17's ID half and Q254, {@code block:} IDs and {@code mod:} on
 * container access and transfer rows by the container's registry ID, once the Q254 column stores it. The places are
 * marked "HELD (G17, Q254)": {@link #containerPart}, {@code Row.partIds}, and {@link AbstractJdbcStorage}'s thing
 * predicate.</p>
 *
 * <p>A quoted name on a container transfer row's item and on a kill's weapon matches by the item's normal-name IDs
 * only: the item's custom name there is inside the stack metadata, which only the game's stack decoder reads.</p>
 *
 * <p>Immutable and safe on any thread.</p>
 */
public record ThingFilters(
        List<ThingFilter> blocks, List<ThingFilter> items, List<ThingFilter> entities, List<String> mods) {
    /** No thing filter. */
    public static final ThingFilters NONE = new ThingFilters(List.of(), List.of(), List.of(), List.of());

    /** A literal mod ID: the namespace of a registry ID, lowercase, no {@code *}. */
    public static final Pattern MOD_ID_PATTERN = Pattern.compile("[a-z0-9_.-]+");

    static final List<AuditAction> BLOCK_ACTIONS =
            List.of(AuditAction.BLOCK_BREAK, AuditAction.BLOCK_PLACE, AuditAction.EXPLOSION);
    static final List<AuditAction> ITEM_ACTIONS = List.of(AuditAction.ITEM_DROP, AuditAction.ITEM_PICKUP);
    static final List<AuditAction> TRANSFER_ACTIONS =
            List.of(AuditAction.CONTAINER_DEPOSIT, AuditAction.CONTAINER_WITHDRAWAL);
    static final List<AuditAction> ENTITY_ACTIONS = List.of(AuditAction.ENTITY_KILL, AuditAction.ENTITY_DEATH);
    /** Container access and transfer rows, which a quoted {@code block:} name reads by the container (G17). */
    static final List<AuditAction> CONTAINER_ACTIONS = List.of(
            AuditAction.CONTAINER_ACCESS, AuditAction.CONTAINER_DEPOSIT, AuditAction.CONTAINER_WITHDRAWAL);
    /** The stored columns {@link #matchesRow} reads, in this order. */
    static final List<String> COLUMNS = List.of("action_type", "block_state", "extra_data");

    private static final String RENAMED = " (renamed ";
    private static final Pattern ITEM_COUNT_PREFIX = Pattern.compile("[0-9]+x ");

    public ThingFilters {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        items = items == null ? List.of() : List.copyOf(items);
        entities = entities == null ? List.of() : List.copyOf(entities);
        mods = mods == null ? List.of() : List.copyOf(mods);
        for (String mod : mods) {
            if (!MOD_ID_PATTERN.matcher(mod).matches()) throw new IllegalArgumentException("Not a mod ID: " + mod);
        }
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && items.isEmpty() && entities.isEmpty() && mods.isEmpty();
    }

    /**
     * True when the stored row passes every given filter. A row the read path would refuse to read, where a
     * filter needs to read it, fails the lookup with the data-read failure {@code mapRow} raises (as
     * {@link LoggedNameFilter#matches} does), so a corrupt row never reads as "no match".
     */
    boolean matchesRow(AuditPayloadCodec codec, String actionCode, String subject, String payload) {
        AuditAction action = actionCode == null ? null : AuditAction.fromStorageCode(actionCode).orElse(null);
        if (action == null) {
            throw EventTrailQueryException.dataReadFailure(
                    new IllegalArgumentException("Unknown schema-v1 action code"));
        }
        Row row = new Row(codec, action, subject, payload);
        return (blocks.isEmpty() || matchesBlocks(row))
                && (items.isEmpty() || matchesItems(row))
                && (entities.isEmpty() || matchesEntities(row))
                && (mods.isEmpty() || matchesMods(row));
    }

    private boolean matchesBlocks(Row row) {
        if (BLOCK_ACTIONS.contains(row.action)) return anyMatches(blocks, blockId(row.subject()), row::shownNames);
        return containerPart(row, blocks);
    }

    /**
     * G17 (a), name half (lead D-380): a quoted {@code block:} name on a container access or transfer row matches
     * the container's logged name (whole name, ignoring case; a renamed container also by its real and its custom
     * name). HELD (G17, Q254): the ID half, a {@code block:} ID (or a name's normal-name IDs) against the container's
     * registry ID, waits for the Q254 column; until then an ID value never matches a container row.
     */
    private static boolean containerPart(Row row, List<ThingFilter> values) {
        if (!CONTAINER_ACTIONS.contains(row.action)) return false;
        List<String> names = null;
        for (ThingFilter value : values) {
            if (value.form() != ThingFilter.Form.NAME) continue;
            if (names == null) names = row.containerNames();
            for (String name : names) {
                if (value.matchesShownName(name)) return true;
            }
        }
        return false;
    }

    /**
     * G19 (a): the item of drop, pickup and transfer rows, and the weapon of kill and death rows (by its stored item
     * ID; a quoted name by its normal-name IDs, since the weapon's custom name is inside the stack metadata).
     */
    private boolean matchesItems(Row row) {
        if (ITEM_ACTIONS.contains(row.action)) return anyMatches(items, row.subject(), row::shownNames);
        if (TRANSFER_ACTIONS.contains(row.action)) return anyMatches(items, transferItemId(row.payload), List::of);
        if (ENTITY_ACTIONS.contains(row.action)) return anyMatches(items, row.weaponId(), List::of);
        return false;
    }

    /** G19 (a): the victim, the killer or the "via" entity of kill and death rows, by ID or logged names. */
    private boolean matchesEntities(Row row) {
        if (!ENTITY_ACTIONS.contains(row.action)) return false;
        if (anyMatches(entities, row.subject(), row::victimNames)) return true;
        for (CompoundTag part : row.damageEntities()) {
            if (anyMatches(entities, identityId(part), () -> identityNames(part))) return true;
        }
        return false;
    }

    private boolean matchesMods(Row row) {
        for (String id : row.partIds()) {
            int colon = id.indexOf(':');
            if (colon > 0 && mods.contains(id.substring(0, colon))) return true;
        }
        return false;
    }

    private static boolean anyMatches(List<ThingFilter> values, String id, Supplier<List<String>> shownNames) {
        List<String> names = null;
        for (ThingFilter value : values) {
            if (value.matchesId(id)) return true;
            if (value.form() == ThingFilter.Form.NAME) {
                if (names == null) names = shownNames.get();
                for (String name : names) {
                    if (value.matchesShownName(name)) return true;
                }
            }
        }
        return false;
    }

    /** A stored entity identity's type ID, or null. */
    static String identityId(CompoundTag identity) {
        if (!identity.contains("id", Tag.TAG_STRING)) return null;
        String id = identity.getString("id");
        return id.isBlank() ? null : id;
    }

    /** A stored entity identity's logged names: its custom name, display name, type name and canonical name. */
    static List<String> identityNames(CompoundTag identity) {
        List<String> names = new ArrayList<>(4);
        for (String key : List.of("custom_name", "display_name", "type_name", "canonical_name")) {
            String name = identity.getString(key);
            if (!name.isBlank()) names.add(name);
        }
        return names;
    }

    /** A block row's block ID: the stored block state up to its property list. */
    static String blockId(String blockState) {
        int properties = blockState.indexOf('[');
        return properties < 0 ? blockState : blockState.substring(0, properties);
    }

    /** The item ID that opens a container transfer payload ({@code PREFIX + itemId + '|' ...}), or null. */
    static String transferItemId(String payload) {
        String prefix = ContainerTransferPayloadCodec.PREFIX;
        if (payload == null || !payload.startsWith(prefix)) return null;
        int end = payload.indexOf('|', prefix.length());
        return end < 0 ? null : payload.substring(prefix.length(), end);
    }

    /**
     * The names a logged name shows: the whole text, and for {@code Real (renamed "Custom")} (and
     * {@code Real (renamed, name unreadable)}) also the real and the custom name on their own.
     */
    static List<String> shownNames(String shown) {
        if (shown == null || shown.isBlank()) return List.of();
        List<String> names = new ArrayList<>(3);
        names.add(shown);
        int renamed = shown.indexOf(RENAMED);
        if (renamed > 0) {
            names.add(shown.substring(0, renamed));
            String rest = shown.substring(renamed + RENAMED.length());
            if (rest.length() >= 3 && rest.startsWith("\"") && rest.endsWith("\")")) {
                names.add(unquote(rest.substring(1, rest.length() - 2)));
            }
        }
        return names;
    }

    /** Undoes DisplayNameResolver.quoted: {@code \\} and {@code \"} back to one character. */
    private static String unquote(String quoted) {
        StringBuilder text = new StringBuilder(quoted.length());
        for (int index = 0; index < quoted.length(); index++) {
            char character = quoted.charAt(index);
            if (character == '\\' && index + 1 < quoted.length()) character = quoted.charAt(++index);
            text.append(character);
        }
        return text.toString();
    }

    /** One stored row, decoded only as far as a filter asks. */
    private static final class Row {
        private final AuditPayloadCodec codec;
        private final AuditAction action;
        private final String subject;
        private final String payload;
        private CompoundTag entityTag;
        private boolean entityTagRead;

        private Row(AuditPayloadCodec codec, AuditAction action, String subject, String payload) {
            this.codec = codec;
            this.action = action;
            this.subject = subject;
            this.payload = payload;
        }

        /** The stored subject identity; a row without one cannot be read at all. */
        private String subject() {
            if (subject == null || subject.isBlank()) {
                throw EventTrailQueryException.dataReadFailure(
                        new IllegalArgumentException("Stored subject identity is missing"));
            }
            return subject;
        }

        /** The subject's logged name as the read path decodes it, split by {@link #shownNames}. */
        private List<String> shownNames() {
            AuditPayloadCodec.DecodedProjection projection = projection();
            if (projection == null) return List.of();
            String shown = projection.subjectPresentationIdentity();
            if (ITEM_ACTIONS.contains(action) && shown != null) {
                // Drop and pickup rows log "<count>x <name>" (the item evidence normalizers): match the name.
                java.util.regex.Matcher count = ITEM_COUNT_PREFIX.matcher(shown);
                if (count.lookingAt()) shown = shown.substring(count.end());
            }
            return ThingFilters.shownNames(shown);
        }

        /**
         * The row as the read path decodes it, or null when the codec reports its evidence unavailable (an older or
         * unreadable payload format, which has no logged name). A row the read path would refuse fails the lookup.
         */
        private AuditPayloadCodec.DecodedProjection projection() {
            AuditPayloadCodec.DecodedProjection projection;
            try {
                projection = Objects.requireNonNull(codec.decode(action, subject(), payload),
                        "payload codec returned no projection");
            } catch (EventTrailQueryException failure) {
                throw failure;
            } catch (RuntimeException undecodable) {
                throw EventTrailQueryException.dataReadFailure(undecodable);
            }
            return projection.evidence() instanceof AuditEvidence.Unavailable ? null : projection;
        }

        /** The victim's logged names (see {@link ThingFilters#identityNames}). */
        private List<String> victimNames() {
            CompoundTag root = entityTag();
            return root == null ? List.of() : identityNames(root.getCompound("victim"));
        }

        /**
         * The container's logged name on a container access or transfer row, split by {@link #shownNames}: the
         * subject the read path decodes, when it is a block or container (as the removed name: filter read it).
         */
        private List<String> containerNames() {
            AuditPayloadCodec.DecodedProjection projection = projection();
            if (projection == null) return List.of();
            AuditSubject.Kind kind = projection.subjectKind();
            if (kind != AuditSubject.Kind.BLOCK && kind != AuditSubject.Kind.CONTAINER) return List.of();
            return ThingFilters.shownNames(projection.subjectPresentationIdentity());
        }

        /**
         * The entities the damage names: the direct and the causing entity, each when stored. Between them they are
         * the killer and the "via" entity (EntityKillAttribution.killingEntity picks which one is the killer), or
         * one entity that is both direct and causing.
         */
        private List<CompoundTag> damageEntities() {
            CompoundTag root = entityTag();
            if (root == null) return List.of();
            CompoundTag damage = root.getCompound("damage");
            List<CompoundTag> parts = new ArrayList<>(2);
            for (String key : List.of("direct", "causing")) {
                if (damage.contains(key, Tag.TAG_COMPOUND) && !damage.getCompound(key).isEmpty()) {
                    parts.add(damage.getCompound(key));
                }
            }
            return parts;
        }

        /** The weapon's stored item ID on a kill or death row (as the row's weapon label reads it), or null. */
        private String weaponId() {
            CompoundTag root = entityTag();
            if (root == null) return null;
            CompoundTag damage = root.getCompound("damage");
            if (!damage.contains("weapon", Tag.TAG_COMPOUND)) return null;
            String id = damage.getCompound("weapon").getString("id").replace("\"", "");
            return id.isBlank() ? null : id;
        }

        /** Every block, item and entity ID the row names (R36). */
        private List<String> partIds() {
            if (BLOCK_ACTIONS.contains(action)) return List.of(blockId(subject()));
            if (ITEM_ACTIONS.contains(action)) return List.of(subject());
            if (TRANSFER_ACTIONS.contains(action)) {
                // HELD (G17, Q254): the container's own ID joins here once the Q254 column stores it.
                String item = transferItemId(payload);
                return item == null ? List.of() : List.of(item);
            }
            if (ENTITY_ACTIONS.contains(action)) {
                List<String> ids = new ArrayList<>(6);
                ids.add(subject());
                CompoundTag root = entityTag();
                if (root != null) {
                    CompoundTag damage = root.getCompound("damage");
                    addId(ids, damage.getCompound("direct"));
                    addId(ids, damage.getCompound("causing"));
                    addId(ids, damage.getCompound("weapon"));
                    addId(ids, root.getCompound("attribution").getCompound("related"));
                }
                return ids;
            }
            // HELD (G17, Q254): container access rows name only the container.
            return List.of();
        }

        private static void addId(List<String> ids, CompoundTag part) {
            if (part.contains("id", Tag.TAG_STRING)) {
                String id = part.getString("id");
                if (!id.isBlank()) ids.add(id);
            }
        }

        /** The entity payload's tag, or null when the payload is not a readable entity payload. */
        private CompoundTag entityTag() {
            if (!entityTagRead) {
                entityTagRead = true;
                EntityEventPayloadCodec.DecodedPayload decoded = EntityEventPayloadCodec.decode(payload);
                entityTag = decoded.successful() ? decoded.tag() : null;
            }
            return entityTag;
        }
    }
}
