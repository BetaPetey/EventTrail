package io.github.betapetey.eventtrail.inventory.adapters.gravestone;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.inventory.graves.GraveContents;
import io.github.betapetey.eventtrail.inventory.graves.GraveOwnerReader;
import io.github.betapetey.eventtrail.inventory.graves.GraveOwnerReader.UuidKey;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Gravestone Mod's grave key-spec: pure constants. It names no class of the mod and holds no hook; the hooks go in
 * {@code mixin.gravestone} and the grave adapter is {@link GraveStoneGraves}.
 *
 * <p>The keys are the mod's own saved data (Gravestone 1.0.40, checked against the mod's jar): the grave block entity
 * saves its death record under {@code Death}, with the owner as {@code PlayerUuid} (an int array) and
 * {@code PlayerName}, and the items in {@code MainInventory}, {@code ArmorInventory}, {@code OffHandInventory} (lists of
 * slot compounds) and {@code Items} (a list of stacks). The mod reads two older forms on load, the two-long
 * {@code PlayerUuidMost}/{@code PlayerUuidLeast} and, for a grave with no death record, a {@code PlayerUUID} string, so
 * the owner read accepts both. {@code Equipment} is a copy the mod keeps to draw the ghost and is never handed out, so
 * it is not contents. A zero owner is the mod's own "no owner set" (a grave block placed by hand): the mod lets anyone
 * break such a grave.</p>
 */
public final class GraveStoneGraveSpec {
    /** The grave adapter's key: its fail-safe, its counted reasons, its status line and its parenthesised name. */
    public static final AdapterKey KEY = AdapterKey.GRAVESTONE;
    /** The mod's id: the namespace of its registry ids and of its take rows' provider id. */
    public static final String MOD_ID = "gravestone";
    /** The grave block's registry id (also its block entity type's). */
    public static final ResourceLocation BLOCK_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "gravestone");
    /** The owner keys, in the order the mod itself reads them. */
    public static final GraveOwnerReader.Spec OWNER = new GraveOwnerReader.Spec(List.of(
            UuidKey.mostLeast("Death.PlayerUuidMost", "Death.PlayerUuidLeast"),
            UuidKey.intArray("Death.PlayerUuid"),
            UuidKey.string("PlayerUUID")),
            "Death.PlayerName", false, true);
    /** The contents lists: what the mod hands out or drops (its own list of all items, in the same order). */
    public static final GraveContents.Spec CONTENTS = new GraveContents.Spec(List.of(
            "Death.MainInventory",
            "Death.ArmorInventory",
            "Death.OffHandInventory",
            "Death.Items"));

    private GraveStoneGraveSpec() {
    }
}
