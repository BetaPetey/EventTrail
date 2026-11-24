package io.github.betapetey.eventtrail.commands.presentation;

import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.inventory.graves.GraveAttribution;
import io.github.betapetey.eventtrail.inventory.graves.api.OriginLabelView;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Every grave wording of graves design r6, 1.9 (Q182, Q191, Q192, Q213, Q225): the text after a row's item, " • " not
 * included. Presentation only: nothing here is stored.
 *
 * <p>The parenthesised name is the {@link AdapterKey} display name of the grave mod, found by the mod id the row's
 * providerId (or, for a spill pickup, the origin's grave id) names. It is never the cause's display, which can carry a
 * player-chosen custom name. When no single bridge key has that mod id, the parenthesis is left out: no name is
 * invented. Colony graves ({@value #COLONY_MOD_ID}) have no parenthesis on any row: every colony row (a take, its
 * {@code .loss} and {@code .pulled} rows, a pickup) names the colony's owner ("from Bob's colony grave", Q191 A).</p>
 */
public final class GraveWording {
    /** Every grave row's providerId starts with this. */
    public static final String PROVIDER_PREFIX = "eventtrail.grave.";
    /** MineColonies' mod id: the colony grave's rows and its spill's grave id (1.9: no parenthesised mod name). */
    public static final String COLONY_MOD_ID = "minecolonies";
    /** The colony grave's providerId (1.9: no parenthesised mod name). */
    public static final String COLONY_PROVIDER = PROVIDER_PREFIX + COLONY_MOD_ID;
    /** Simple Tomb's portion of another owner (2.3). */
    static final String PULLED_SUFFIX = ".pulled";
    /** Tombstone's paired loss rows (2.2): the withdrawal reads as a take. */
    static final String LOSS_SUFFIX = ".loss";
    static final String MIXED = "from mixed drops, owner unknown";

    private static final GraveWording PRODUCTION = new GraveWording(GraveWording::adapterDisplayName);

    private final Function<String, Optional<String>> modNames;

    /** Test seam: the mod-id to parenthesised-name lookup. */
    GraveWording(Function<String, Optional<String>> modNames) {
        this.modNames = Objects.requireNonNull(modNames, "modNames");
    }

    /** The wording with the parenthesised names of {@link AdapterKey}. */
    public static GraveWording production() {
        return PRODUCTION;
    }

    /** Whether a providerId is a grave row's. */
    public static boolean isGraveProvider(String providerId) {
        return providerId != null && providerId.startsWith(PROVIDER_PREFIX);
    }

    /**
     * The wording of a grave transfer row, or empty when the row is not one this class words (not a grave providerId, an
     * unknown suffix, or no named cause): the caller then shows the row as before.
     */
    public Optional<String> transfer(String providerId, ShownCause cause, UUID indexedPlayer) {
        if (!isGraveProvider(providerId) || cause == null || cause.form() != ShownCause.Form.NAMED) {
            return Optional.empty();
        }
        String rest = providerId.substring(PROVIDER_PREFIX.length());
        int dot = rest.indexOf('.');
        String modId = dot < 0 ? rest : rest.substring(0, dot);
        String suffix = dot < 0 ? "" : rest.substring(dot);
        if (modId.isEmpty()) return Optional.empty();
        boolean pulled = suffix.equals(PULLED_SUFFIX);
        if (!pulled && !suffix.isEmpty() && !suffix.equals(LOSS_SUFFIX)) return Optional.empty();
        GraveAttribution attribution = GraveAttribution.of(cause.owner(), indexedPlayer);
        String owner = cause.owner() == null ? null : cause.owner().name();
        // A colony grave keeps the colony wording (Q191 A) on every row it has: the take, its .loss and its .pulled.
        if (COLONY_MOD_ID.equals(modId)) return Optional.of(colonyGrave(attribution, owner));
        if (pulled) {
            String text = attribution == GraveAttribution.UNKNOWN
                    ? "items of unknown owner, from a grave"
                    : owner + "'s items, from a grave";
            return Optional.of(text + parenthesis(modId));
        }
        return Optional.of(grave(attribution, owner) + parenthesis(modId));
    }

    /**
     * The wording of a pickup's known origin bucket: a tagged grave spill (parenthesis from the bucket's grave id), a
     * colony grave's spill (the colony wording, no parenthesis) or death drops (no parenthesis). The owner is the
     * bucket's; its name is shown, or its UUID text when it stored none.
     */
    public String pickup(OriginLabelView.Bucket bucket, UUID indexedPlayer) {
        Objects.requireNonNull(bucket, "bucket");
        GraveAttribution attribution = GraveAttribution.of(bucket.ownerUuid(), indexedPlayer);
        String owner = attribution == GraveAttribution.UNKNOWN ? null : ownerName(bucket);
        if (bucket.kind() == OriginLabelView.Kind.DEATH_DROPS) {
            return switch (attribution) {
                case OWN -> "from own death drops";
                case OTHER -> "from " + owner + "'s death drops";
                case UNKNOWN -> "from death drops, owner unknown";
            };
        }
        String modId = bucket.graveId() == null ? null : bucket.graveId().getNamespace();
        if (COLONY_MOD_ID.equals(modId)) return colonyGrave(attribution, owner);
        return grave(attribution, owner) + parenthesis(modId);
    }

    /** The uncertain part of a partial take of a mixed pile (1.6.3). */
    public String mixed() {
        return MIXED;
    }

    private static String grave(GraveAttribution attribution, String owner) {
        return switch (attribution) {
            case OWN -> "from own grave";
            case OTHER -> "from " + owner + "'s grave";
            case UNKNOWN -> "from a grave, owner unknown";
        };
    }

    /** A colony grave (Q191 A): the colony's owner, never a parenthesis. */
    private static String colonyGrave(GraveAttribution attribution, String owner) {
        return switch (attribution) {
            case OWN -> "from own colony grave";
            case OTHER -> "from " + owner + "'s colony grave";
            case UNKNOWN -> "from a colony grave, owner unknown";
        };
    }

    /** The parenthesised mod name; none for a colony grave, whatever the row's suffix. */
    private String parenthesis(String modId) {
        if (modId == null || modId.isEmpty() || COLONY_MOD_ID.equals(modId)) return "";
        Optional<String> name;
        try {
            name = modNames.apply(modId);
        } catch (RuntimeException | LinkageError failure) {
            return "";
        }
        return name == null || name.isEmpty() || name.get().isBlank() ? "" : " (" + name.get() + ")";
    }

    /** The bucket owner's name as safe text, or its UUID text when it has none (1.2: never looked up elsewhere). */
    private static String ownerName(OriginLabelView.Bucket bucket) {
        String uuidText = bucket.ownerUuid().toString();
        String name = bucket.ownerName();
        try {
            return ShownCause.Owner.capture(name == null || name.isBlank() ? uuidText : name, uuidText,
                    ShownCause.OwnerState.VALIDATED).name();
        } catch (IllegalArgumentException unreadable) {
            return uuidText;
        }
    }

    /**
     * The display name of the one bridge {@link AdapterKey} whose mod id this is; empty when there is none or more than
     * one (a lookup by mod id never picks between keys by declaration order).
     */
    static Optional<String> adapterDisplayName(String modId) {
        AdapterKey found = null;
        for (AdapterKey key : AdapterKey.values()) {
            if (key.kind() == AdapterKey.Kind.BRIDGE && key.modId().equals(modId)) {
                if (found != null) return Optional.empty();
                found = key;
            }
        }
        return found == null ? Optional.empty() : Optional.of(found.displayName());
    }
}
