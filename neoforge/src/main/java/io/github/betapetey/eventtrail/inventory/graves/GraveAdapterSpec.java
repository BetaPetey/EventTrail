package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.inventory.access.TrashTargets;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveOwnerFacts;
import io.github.betapetey.eventtrail.inventory.graves.api.OwnerPortion;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a grave adapter supplies to the shared base (graves design r6, 1.1). {@code G} is the adapter's own handle on
 * one grave (a block entity, an entity, a record), read only through these methods.
 *
 * <ul>
 *   <li>{@link #owner} and {@link #contents} read the grave's own saved data, before the mod's method runs and (the
 *   contents) again after it; a grave that is gone after the method (a removed block entity) has empty contents.</li>
 *   <li>{@link #portions} are the owner portions (1.1-4); the default is one portion per identity, the grave's owner,
 *   every unit. Simple Tomb supplies its ledger's.</li>
 *   <li>{@link #afterTake} receives the take's split after the rows (1.3-10), for a ledger.</li>
 *   <li>{@link #lossTarget} is the per-player loss target of a mod that destroys items during a take (Tombstone, 2.2
 *   hook 5).</li>
 * </ul>
 */
public interface GraveAdapterSpec<G> {

    /** The adapter's key: its fail-safe and its counted reasons. */
    AdapterKey key();

    /**
     * The mod's id, a registry namespace ({@code [a-z0-9_.-]+}): by default the key's own mod id, so the take rows'
     * provider id and the parenthesised mod name come from one value.
     */
    default String modId() {
        return key().modId();
    }

    /** The grave's owner (1.1-2, 1.2), read before the mod's method runs. Never throws (use {@link GraveOwners}). */
    GraveOwnerFacts owner(G grave, String site);

    /** The grave's contents (1.1-3) as counts per identity, each above 0; empty for a grave that is gone. */
    Map<InventoryItemIdentity, Long> contents(G grave);

    /** The grave's owner portions (1.1-4); the default is the grave's owner, every unit. */
    default List<OwnerPortion> portions(G grave, Map<InventoryItemIdentity, Long> contents, GraveOwnerFacts owner) {
        return OwnerPortions.defaults(contents, owner);
    }

    /** The take's split, after its rows (1.3-10): a ledger's update. The default does nothing. */
    default void afterTake(G grave, GraveTakes.TakeSplit split) {
    }

    /** The loss target for items the mod destroys during a take of {@code player} (2.2 hook 5); none by default. */
    default Optional<TrashTargets.TrashTarget> lossTarget(ServerPlayer player, String site) {
        return Optional.empty();
    }

    /** The take rows' provider id (1.4): {@code eventtrail.grave.<modid>}. */
    default String providerId() {
        return "eventtrail.grave." + modId();
    }

    /** The provider id of a take of a portion that is not the grave owner's (1.4, Simple Tomb). */
    default String pulledProviderId() {
        return providerId() + ".pulled";
    }

    /** The provider id of the paired loss rows (1.4, Tombstone). */
    default String lossProviderId() {
        return providerId() + ".loss";
    }
}
