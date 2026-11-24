package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveOwnerFacts;
import io.github.betapetey.eventtrail.inventory.graves.api.OwnerPortion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owner portions (graves design r6, 1.1-4, 1.3-8, Q194 B): who owns how many units of each identity in a grave, and
 * how a take is split over them with the pile rule (1.6.3). The default is one portion per identity: the grave's
 * owner, every unit. An adapter may supply its own (Simple Tomb's ledger).
 */
public final class OwnerPortions {
    private OwnerPortions() {
    }

    /** The default portions (1.1-4): one per identity of {@code contents}, the grave's owner, every unit. */
    public static List<OwnerPortion> defaults(Map<InventoryItemIdentity, Long> contents, GraveOwnerFacts owner) {
        Objects.requireNonNull(owner, "owner");
        List<OwnerPortion> portions = new ArrayList<>();
        contents.forEach((item, count) -> {
            if (count != null && count > 0L) portions.add(OwnerPortion.whole(item, owner, count));
        });
        return portions;
    }

    /**
     * The piles of a grave: for each identity of {@code contents} with a count above 0, its portions as named buckets
     * (equal share and owner summed, in first-seen order) and its {@link OwnerPortion.Share#UNKNOWN} units. An identity
     * whose portions do not sum to its count is counted {@code grave-portions-mismatch} and becomes one pile of unknown
     * units: owner unknown, never a guess. Portions of identities the grave does not hold are ignored.
     */
    public static Map<InventoryItemIdentity, Pile> piles(Map<InventoryItemIdentity, Long> contents,
            List<OwnerPortion> portions) {
        Map<InventoryItemIdentity, List<OwnerPortion>> byItem = new LinkedHashMap<>();
        for (OwnerPortion portion : portions) {
            byItem.computeIfAbsent(Objects.requireNonNull(portion, "portion").item(), item -> new ArrayList<>())
                    .add(portion);
        }
        Map<InventoryItemIdentity, Pile> piles = new LinkedHashMap<>();
        contents.forEach((item, count) -> {
            if (count == null || count <= 0L) return;
            Pile pile = Pile.of(item, byItem.getOrDefault(item, List.of()));
            if (pile.total() != count) {
                GraveCounts.count(GraveCounts.Reason.GRAVE_PORTIONS_MISMATCH);
                pile = new Pile(item, List.of(), count);
            }
            piles.put(item, pile);
        });
        return piles;
    }

    /**
     * One identity's pile: named portions (every share but UNKNOWN, each with a count above 0) and {@code unknown}
     * units, whose owner can no longer be told.
     */
    public record Pile(InventoryItemIdentity item, List<OwnerPortion> named, long unknown) {
        public Pile {
            Objects.requireNonNull(item, "item");
            named = List.copyOf(named);
            if (unknown < 0L) throw new IllegalArgumentException("unknown must not be negative");
            for (OwnerPortion portion : named) {
                if (!portion.named() || !portion.item().equals(item)) {
                    throw new IllegalArgumentException("A pile's named portions are named portions of its identity");
                }
            }
        }

        /** The pile of {@code portions} of one identity: equal share and owner summed, UNKNOWN to {@code unknown}. */
        public static Pile of(InventoryItemIdentity item, List<OwnerPortion> portions) {
            Map<Key, Long> named = new LinkedHashMap<>();
            long unknown = 0L;
            for (OwnerPortion portion : portions) {
                if (!portion.item().equals(item)) throw new IllegalArgumentException("A portion of another identity");
                if (portion.named()) {
                    named.merge(new Key(portion.share(), portion.owner()), portion.count(), Math::addExact);
                } else {
                    unknown = Math.addExact(unknown, portion.count());
                }
            }
            List<OwnerPortion> list = new ArrayList<>();
            named.forEach((key, count) -> list.add(new OwnerPortion(item, key.share(), key.owner(), count)));
            return new Pile(item, list, unknown);
        }

        /** An empty pile of {@code item}. */
        public static Pile empty(InventoryItemIdentity item) {
            return new Pile(item, List.of(), 0L);
        }

        public long total() {
            long sum = unknown;
            for (OwnerPortion portion : named) sum = Math.addExact(sum, portion.count());
            return sum;
        }

        /**
         * A take of {@code p} units (at least 0) by the pile rule: one part per named portion with a certain part above
         * 0, then one UNKNOWN part for the uncertain rest, and the pile that stays. A take larger than the pile takes
         * the whole pile and adds the excess to the UNKNOWN part (no unit is ever given a named owner beyond the pile).
         */
        public Taken take(long p) {
            if (p < 0L) throw new IllegalArgumentException("a take must not be negative");
            long total = total();
            long fromPile = Math.min(p, total);
            List<Long> counts = new ArrayList<>(named.size());
            for (OwnerPortion portion : named) counts.add(portion.count());
            PileRule.Split split = PileRule.take(counts, unknown, fromPile);
            List<OwnerPortion> parts = new ArrayList<>();
            List<OwnerPortion> kept = new ArrayList<>();
            for (int i = 0; i < named.size(); i++) {
                OwnerPortion portion = named.get(i);
                long part = split.certain().get(i);
                if (part > 0L) parts.add(new OwnerPortion(item, portion.share(), portion.owner(), part));
                long stays = split.keptNamed().get(i);
                if (stays > 0L) kept.add(new OwnerPortion(item, portion.share(), portion.owner(), stays));
            }
            long uncertain = split.uncertain() + (p - fromPile);
            if (uncertain > 0L) parts.add(new OwnerPortion(item, OwnerPortion.Share.UNKNOWN, null, uncertain));
            return new Taken(parts, new Pile(item, kept, split.keptUnknown()));
        }

        /** This pile with {@code units} more unknown units. */
        public Pile plusUnknown(long units) {
            if (units < 0L) throw new IllegalArgumentException("units must not be negative");
            return new Pile(item, named, Math.addExact(unknown, units));
        }

        /** The pile as portions (the named ones, then one UNKNOWN portion if any), for an adapter's ledger. */
        public List<OwnerPortion> portions() {
            List<OwnerPortion> portions = new ArrayList<>(named);
            if (unknown > 0L) portions.add(new OwnerPortion(item, OwnerPortion.Share.UNKNOWN, null, unknown));
            return portions;
        }
    }

    /** A take's parts (each a portion with the units taken from it) and the pile that stays. */
    public record Taken(List<OwnerPortion> parts, Pile remaining) {
        public Taken {
            parts = List.copyOf(parts);
            Objects.requireNonNull(remaining, "remaining");
        }

        public long units() {
            long sum = 0L;
            for (OwnerPortion part : parts) sum = Math.addExact(sum, part.count());
            return sum;
        }
    }

    private record Key(OwnerPortion.Share share, ShownCause.Owner owner) {
    }
}
