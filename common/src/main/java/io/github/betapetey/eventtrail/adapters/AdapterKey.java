package io.github.betapetey.eventtrail.adapters;

import java.util.Objects;
import java.util.Optional;

/**
 * One independently switchable EventTrail optional-mod adapter.
 *
 * <p>The OFF state, the de-duplicated skip warnings and the hook family gate
 * are kept per key, never per adapter id string, because an adapter id names a
 * behaviour path and one adapter may choose between several paths. Each of the
 * sixteen menu keys belongs to exactly one specification of
 * {@code OptionalMenuAdapterLoader}, which names its key explicitly (GF-ADAPTERSPECS:
 * a mod may have several specifications, each with its own appended menu key;
 * today each mod has one); the other twenty-two keys cover the
 * optional-mod bridges (the three part-removal readers, the three AE2
 * in-place hook keys and the seven grave-mod keys included), the
 * Sophisticated linked-group guard and the Sable location provider, which have
 * no menu specification.</p>
 *
 * <p>The constants and their mod ids are frozen at B0: the hook-to-family
 * table, the loader snapshot and the status lines all refer to them. Build B
 * Sophisticated part 4 appends {@link #SOPHISTICATED_LINKED_GROUPS} (the
 * linked-group commit hook and watch, Advisor Q5), so a linked-group failure
 * never switches off unlinked Sophisticated Storage or Storage in Motion
 * recording. {@link #METAL_BARRELS} was added after B0 with its adapter (Metal
 * Barrels B); it has no hooks, so it has no row in the hook-to-family table.</p>
 */
public enum AdapterKey {
    LOOTR(Kind.MENU, "lootr", "Lootr"),
    SOPHISTICATED_BACKPACKS(Kind.MENU, "sophisticatedbackpacks", "Sophisticated Backpacks"),
    SOPHISTICATED_STORAGE(Kind.MENU, "sophisticatedstorage", "Sophisticated Storage"),
    CREATE(Kind.MENU, "create", "Create mounted storage"),
    ENDER_STORAGE(Kind.MENU, "enderstorage", "Ender Storage"),
    INTEGRATED_TERMINALS(Kind.MENU, "integratedterminals", "Integrated Terminals"),
    OCCULTISM(Kind.MENU, "occultism", "Occultism dimensional storage"),
    RFTOOLS_STORAGE(Kind.MENU, "rftoolsstorage", "RFTools Storage scanner"),
    POCKET_STORAGE(Kind.MENU, "pocketstorage", "Pocket Storage"),
    SOPHISTICATED_STORAGE_CREATE(
            Kind.MENU, "sophisticatedstoragecreateintegration", "Sophisticated Storage Create Integration"),
    STORAGE_IN_MOTION(Kind.MENU, "sophisticatedstorageinmotion", "Sophisticated Storage in Motion"),
    MEKANISM(Kind.MENU, "mekanism", "Mekanism"),
    AE2(Kind.MENU, "ae2", "Applied Energistics 2"),
    AE2WTLIB(Kind.MENU, "ae2wtlib", "AE2WTLib"),
    REFINED_STORAGE(Kind.MENU, "refinedstorage", "Refined Storage"),
    METAL_BARRELS(Kind.MENU, "metalbarrels", "Metal Barrels"),
    FUNCTIONAL_STORAGE(Kind.BRIDGE, "functionalstorage", "Functional Storage"),
    ARS_CAST(Kind.BRIDGE, "ars_nouveau", "Ars Nouveau spell casting"),
    ARS_ITEM(Kind.BRIDGE, "ars_nouveau", "Ars Nouveau item actions"),
    IRONS(Kind.BRIDGE, "irons_spellbooks", "Iron's Spells 'n Spellbooks"),
    MINING_GADGETS(Kind.BRIDGE, "mininggadgets", "Mining Gadgets"),
    BUILDING_GADGETS(Kind.BRIDGE, "buildinggadgets2", "Building Gadgets 2"),
    /** Parts A: single-part removals; share mod ids with other keys, so status and OFF stay per key. */
    PARTS_AE2(Kind.BRIDGE, "ae2", "Applied Energistics 2 parts"),
    PARTS_INTEGRATED_DYNAMICS(Kind.BRIDGE, "integrateddynamics", "Integrated Dynamics parts"),
    PARTS_ENDERIO(Kind.BRIDGE, "enderio", "Ender IO parts"),
    SABLE_RECOGNIZER(Kind.LOCATION, "sable", "Sable sub-level recognizer"),
    SABLE_FRAME(Kind.LOCATION, "sable", "Sable moving frames"),
    /**
     * Sophisticated part 4: Core's linked-storage group commit hook and the EventTrail-side group watch. A bridge
     * key on Core's mod id: the hook target is a Core class, and no menu specification belongs to it. Installed only
     * while Core's file holds the linked-storage package ({@link AdapterKeyPresence}): on a Core 1.4.x its hook is left
     * out quietly and its status line reads "absent".
     */
    SOPHISTICATED_LINKED_GROUPS(Kind.BRIDGE, "sophisticatedcore", "Sophisticated linked storage groups"),
    /**
     * Parts readers design-03 5.1: the AE2 in-place hook family (memory-card pastes of upgrade cards and patterns,
     * crafting-unit upgrades), one key per mod whose classes are hooked, so an absent addon is left out quietly and a
     * changed member turns off only that mod's hooks, never the AE2 part reader. Rows leave through the part-removal
     * bridge's open use action.
     */
    PARTS_AE2_INPLACE(Kind.BRIDGE, "ae2", "Applied Energistics 2 in-place moves"),
    PARTS_ADVANCED_AE_INPLACE(Kind.BRIDGE, "advanced_ae", "Advanced AE in-place moves"),
    PARTS_APPLIED_PNEUMATICS_INPLACE(Kind.BRIDGE, "appliedpneumatics", "Applied Pneumatics in-place moves"),
    /**
     * Graves: one bridge key per grave mod, appended so no earlier ordinal moves. The mod id and the display name are
     * the mod's own (its mods.toml, the name the game's mod list shows); the display name is also the parenthesised
     * mod name of a grave row, so each grave mod id has exactly one bridge key. MineColonies' colony grave rows show
     * no parenthesis.
     */
    GRAVESTONE(Kind.BRIDGE, "gravestone", "Gravestone Mod"),
    TOMBSTONE(Kind.BRIDGE, "tombstone", "Corail Tombstone"),
    SIMPLE_TOMB(Kind.BRIDGE, "simpletomb", "Simple Tombstone"),
    CORPSE(Kind.BRIDGE, "corpse", "Corpse"),
    YIGD(Kind.BRIDGE, "yigd", "You're in Grave Danger"),
    EPITAPHS(Kind.BRIDGE, "epitaphs", "Epitaphs"),
    MINECOLONIES(Kind.BRIDGE, "minecolonies", "MineColonies");

    /** What kind of EventTrail code the key switches. */
    public enum Kind {
        /** A menu adapter registered by the optional menu adapter loader. */
        MENU,
        /** An optional-mod bridge reached from hooks or lifecycle callbacks. */
        BRIDGE,
        /** The optional location provider. */
        LOCATION
    }

    private final Kind kind;
    private final String modId;
    private final String displayName;

    AdapterKey(Kind kind, String modId, String displayName) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.modId = Objects.requireNonNull(modId, "modId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    public Kind kind() {
        return kind;
    }

    /** The installed mod whose presence this key's adapter depends on. */
    public String modId() {
        return modId;
    }

    /** Operator-facing name used in warnings and status lines. */
    public String displayName() {
        return displayName;
    }

    /**
     * The menu key whose loader specification has this mod id. Bridge and
     * location keys are never returned, because two of them can share a mod id.
     * GF-ADAPTERSPECS: when several menu keys share the mod id (a mod with
     * several specifications), none is returned, so a lookup by mod id never
     * picks one of them by declaration order: look such keys up by key.
     */
    public static Optional<AdapterKey> forMenuModId(String modId) {
        if (modId == null) return Optional.empty();
        AdapterKey found = null;
        for (AdapterKey key : values()) {
            if (key.kind == Kind.MENU && key.modId.equals(modId)) {
                if (found != null) return Optional.empty();
                found = key;
            }
        }
        return Optional.ofNullable(found);
    }
}
