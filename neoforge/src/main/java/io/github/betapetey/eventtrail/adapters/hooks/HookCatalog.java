package io.github.betapetey.eventtrail.adapters.hooks;

import io.github.betapetey.eventtrail.adapters.AdapterKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static io.github.betapetey.eventtrail.adapters.AdapterKey.AE2;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.ARS_CAST;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.ARS_ITEM;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.BUILDING_GADGETS;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.CREATE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.ENDER_STORAGE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.FUNCTIONAL_STORAGE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.INTEGRATED_TERMINALS;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.IRONS;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.MINING_GADGETS;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.OCCULTISM;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.PARTS_ADVANCED_AE_INPLACE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.PARTS_AE2_INPLACE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.PARTS_APPLIED_PNEUMATICS_INPLACE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.POCKET_STORAGE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.REFINED_STORAGE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.RFTOOLS_STORAGE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.SABLE_FRAME;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.SOPHISTICATED_BACKPACKS;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.SOPHISTICATED_LINKED_GROUPS;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.SOPHISTICATED_STORAGE_CREATE;
import static io.github.betapetey.eventtrail.adapters.AdapterKey.STORAGE_IN_MOTION;

/**
 * The hook-to-key table: which adapter keys own each of the 120 optional-mod hook classes (work map R4-wire; the
 * 112th, Sophisticated part 4's linked-group commit hook, was the first hook added after the count fixture's capture,
 * the next six are the AE2 in-place hook family of parts readers design-03 section 5, one key per hooked mod, the
 * 119th is the Create train carriage's rotation hook, Part 1 SA-create-mounted, and the 120th is the Create mounted
 * storage interaction call site, Part 1 SCM-ENDER).
 *
 * <p>Each row names one hook class, relative to the mixin package as in the hook list, and its keys. The first key is
 * the one the hook's own handlers report under; any further key is another family that needs the hook. A hook is
 * checked only when the mod of at least one of its keys is installed, and a hook that does not pass the check stops
 * the hook-based recording of every one of its keys whose mod is installed (Owner risk 3, brief 4.6 Q9 (a)), unless
 * it is a denial-only hook ({@link Group#denialOnly()}). This
 * table wins over a key a family batch chose in its own code (lead decision L14 F4); the coverage test fails a hook
 * whose own code reads a key its row does not name (it does not check which key comes first, nor keys reached
 * through a helper class).</p>
 *
 * <p>Three shared rows (four classes):</p>
 * <ul>
 *   <li>{@code MovingStorageEntityMotionMixin}: Create and Storage in Motion (the handler picks the key per entity,
 *       lead decision L16 H3);</li>
 *   <li>the two Sophisticated Core payload hooks: reported under Sophisticated Backpacks, and they also serve the
 *       Storage in Motion and Sophisticated Storage Create Integration menus (lead decision L16 H2). No "absent mod
 *       means OFF" rule may apply to {@code SOPHISTICATED_BACKPACKS} unless these two hooks first get their own
 *       keying;</li>
 *   <li>{@code GadgetBlockEntityLifecycleMixin}: Mining Gadgets and Building Gadgets (reported under Mining Gadgets,
 *       the F2 lane's choice, which this table keeps).</li>
 * </ul>
 *
 * <p>Two exclusive groups ({@link Group}): Integrated Terminals legacy against current (the current variant wins
 * when both pass; the shared hooks belong to both variants), and the Ars Nouveau spell-casting hooks against
 * {@code ArsDamageDenialMixin} (any positive hook that does not pass skips the whole positive group, which stops
 * {@code ARS_CAST}, and applies the denial hook, brief 4.6 Q10 (a)). Every other hook is checked on its own. The
 * denial hook and the nine Ars damage watchers only deny vanilla last-hit credit (design r3 section 6, D2): they are
 * not evidence a family's recording needs, so skipping one warns and names the lost protection but stops no family
 * (brief 4.6 Q9 default covers evidence and protective hooks only). They keep working while {@code ARS_CAST} is
 * stopped or OFF: they call only {@code HookGuard.wrapDenial} and {@code HookGuard.observeDenial}, whose bodies
 * ignore the family gate and OFF (h review-02 MAJOR-1 (a)). The hook scan test's {@code DENIAL_ONLY_HOOKS} equals
 * the {@link Group#denialOnly()} rows of this table (pinned by the table's test).</p>
 *
 * <p>Links only to the JDK and {@link AdapterKey}, so the hook loader can read it.</p>
 */
public final class HookCatalog {
    /** How a hook takes part in an exclusive choice. */
    public enum Group {
        /** Checked on its own. */
        NONE,
        /** Integrated Terminals: part of both the legacy and the current variant. */
        INTEGRATED_BOTH,
        /** Integrated Terminals: only in the legacy variant. */
        INTEGRATED_LEGACY,
        /** Integrated Terminals: only in the current variant. */
        INTEGRATED_CURRENT,
        /** Ars Nouveau spell casting: the positive group, applied only as a whole. */
        ARS_POSITIVE,
        /** Ars Nouveau: the damage denial hook, applied only when the positive group is not. */
        ARS_DENIAL,
        /**
         * Ars Nouveau: a damage watcher (record-less path D2), checked on its own. It denies vanilla last-hit credit
         * and records nothing, so a skip warns and names the lost protection but stops no family.
         */
        ARS_WATCHER;

        /** Whether the hook only denies credit (D2): skipping it stops no family's recording. */
        public boolean denialOnly() {
            return this == ARS_DENIAL || this == ARS_WATCHER;
        }
    }

    /**
     * One hook class and its keys.
     *
     * @param hookClass the hook class relative to the mixin package, as written in the hook list
     * @param keys      the owning keys; the first is the key the hook's handlers report under
     * @param group     its exclusive group, or {@link Group#NONE}
     * @param note      why a shared row has more than one key; empty otherwise
     */
    public record Entry(String hookClass, List<AdapterKey> keys, Group group, String note) {
        public Entry {
            Objects.requireNonNull(hookClass, "hookClass");
            Objects.requireNonNull(group, "group");
            keys = List.copyOf(keys);
            if (keys.isEmpty()) throw new IllegalArgumentException("A hook needs at least one key: " + hookClass);
            note = note == null ? "" : note;
        }

        /** The key the hook's own handlers report under. */
        public AdapterKey primaryKey() {
            return keys.get(0);
        }
    }

    private static final String MOTION_NOTE = "Create contraption entities report under CREATE, other moving storage"
            + " entities under STORAGE_IN_MOTION (L16 H3)";
    private static final String PAYLOAD_NOTE = "Sophisticated Core payloads: reported under SOPHISTICATED_BACKPACKS;"
            + " also serves the Storage in Motion and Sophisticated Storage Create Integration menus (L16 H2)";
    private static final String GADGET_NOTE = "BlockEntity.setRemoved for both gadget mods' placeholders; reported"
            + " under MINING_GADGETS (F2 lane choice, kept)";

    private static final List<Entry> ENTRIES;
    private static final Map<String, Entry> BY_CLASS;

    static {
        List<Entry> rows = new ArrayList<>();
        // F1: Create mounted storage, Storage in Motion, Sophisticated Backpacks payloads.
        one(rows, CREATE,
                "create.CreateInteractionPacketMixin",
                "create.CreateMountedItemInteractionMixin",
                "create.CreateStorageWrapperMixin",
                "create.CreateEntityBindingMixin",
                "create.CreateControlledMotionMixin",
                "create.CreateOrientedMotionMixin",
                "create.CreateCarriageMotionMixin",
                "create.CreateStorageCallSiteMixin");
        one(rows, SOPHISTICATED_STORAGE_CREATE,
                "create.SophisticatedMountedMenuCarrierMixin",
                "create.SophisticatedMountedOpenPacketMixin");
        rows.add(new Entry("MovingStorageEntityMotionMixin", List.of(CREATE, STORAGE_IN_MOTION), Group.NONE,
                MOTION_NOTE));
        one(rows, STORAGE_IN_MOTION, "storagemotion.StorageInMotionMotionMixin");
        for (String payload : List.of("SophisticatedBackpackBulkPayloadMixin",
                "SophisticatedBackpackFullSlotPayloadMixin")) {
            rows.add(new Entry(payload,
                    List.of(SOPHISTICATED_BACKPACKS, STORAGE_IN_MOTION, SOPHISTICATED_STORAGE_CREATE), Group.NONE,
                    PAYLOAD_NOTE));
        }
        // F2: Ars Nouveau spell casting (positive group, denial hook, nine damage watchers checked one by one).
        grouped(rows, ARS_CAST, Group.ARS_POSITIVE,
                "ArsNetworkMixin",
                "ArsCastPacketMixin",
                "ArsAbstractCasterMixin",
                "ArsSpellResolverMixin",
                "ArsSpellContextMixin",
                "ArsTouchMixin",
                "ArsProjectileMethodMixin",
                "ArsProjectileSpellMixin",
                "ArsHarmMixin",
                "ArsDamageEffectMixin");
        grouped(rows, ARS_CAST, Group.ARS_DENIAL, "ArsDamageDenialMixin");
        grouped(rows, ARS_CAST, Group.ARS_WATCHER,
                "ArsExplosionMixin",
                "ArsWindburstMixin",
                "ArsExplosionConstructionMixin",
                "ArsIgniteMixin",
                "ArsPotionEffectMixin",
                "ArsShockedEffectMixin",
                "ArsLightningEntityMixin",
                "ArsChimeraProjectileMixin",
                "ArsEvokerFangsMixin");
        // F2: Ars Nouveau item actions.
        one(rows, ARS_ITEM,
                "arsitem.ArsItemNetworkMixin",
                "arsitem.ArsItemCastPacketMixin",
                "arsitem.ArsItemAbstractCasterMixin",
                "arsitem.ArsItemResolverMixin",
                "arsitem.ArsItemSelfMixin",
                "arsitem.ArsItemTouchMixin",
                "arsitem.ArsItemPickupMixin",
                "arsitem.ArsItemTossMixin",
                "arsitem.ArsItemExtractedStackMixin");
        // F2: Iron's Spells 'n Spellbooks.
        one(rows, IRONS,
                "IronsCastPacketMixin",
                "IronsQuickCastPacketMixin",
                "IronsAbstractSpellMixin",
                "IronsMagicDataMixin",
                "IronsMagicManagerMixin",
                "IronsFireboltSpellMixin",
                "IronsShockwaveSpellMixin",
                "IronsMagicProjectileMixin",
                "IronsFireboltProjectileMixin",
                "IronsProjectileLifecycleMixin",
                "IronsEntityLifecycleMixin");
        // F2: Building Gadgets 2 and Mining Gadgets.
        one(rows, BUILDING_GADGETS,
                "BuildingGadgetsQueueAuditMixin",
                "BuildingGadgetsRenderAuditMixin",
                "BuildingGadgetsUndoAuditMixin");
        rows.add(new Entry("GadgetBlockEntityLifecycleMixin", List.of(MINING_GADGETS, BUILDING_GADGETS), Group.NONE,
                GADGET_NOTE));
        one(rows, MINING_GADGETS,
                "MiningGadgetItemMixin",
                "MiningGadgetRenderBlockMixin",
                "MiningGadgetUseLifecycleMixin");
        // F2: Functional Storage and Sable (L14 F4).
        one(rows, FUNCTIONAL_STORAGE,
                "FunctionalStorageDrawerMixin",
                "FunctionalStoragePlayerPacketMixin");
        one(rows, SABLE_FRAME,
                "SableSubLevelAuditMixin",
                "SableSubLevelContainerAuditMixin",
                "SablePhysicsPoseAuditMixin",
                "SableRapierPhysicsAuditMixin",
                "SableStaticPhysicsAuditMixin",
                "SableAssemblyAuditMixin");
        // F3: Applied Energistics 2 and Refined Storage.
        one(rows, AE2,
                "Ae2MEStorageMenuMixin",
                "Ae2StorageHelperMixin");
        one(rows, REFINED_STORAGE,
                "RefinedStorageCraftingGridTargetMixin",
                "RefinedStorageGridMenuMixin",
                "RefinedStorageGridOperationsMixin",
                "RefinedStorageGridTargetMixin",
                "RefinedStoragePatternGridTargetMixin",
                "RefinedStoragePortableGridBlockTargetMixin",
                "RefinedStoragePortableGridItemAccessMixin",
                "RefinedStorageWirelessGridAccessMixin");
        // F3: Integrated Terminals (legacy 1.8.0 against current 1.10.0; the rest belongs to both).
        grouped(rows, INTEGRATED_TERMINALS, Group.INTEGRATED_BOTH,
                "integrated.IntegratedNetworkMixin",
                "integrated.IntegratedTerminalMenuMixin",
                "integrated.IntegratedTabAccessor",
                "integrated.IntegratedComponentStorageAccessor");
        grouped(rows, INTEGRATED_TERMINALS, Group.INTEGRATED_LEGACY, "integrated.IntegratedTerminalPacketMixin");
        grouped(rows, INTEGRATED_TERMINALS, Group.INTEGRATED_CURRENT,
                "integrated.IntegratedTerminalCurrentPacketMixin",
                "integrated.IntegratedCountingStorageAccessor");
        grouped(rows, INTEGRATED_TERMINALS, Group.INTEGRATED_BOTH, "integrated.IntegratedItemHandlerMixin");
        grouped(rows, INTEGRATED_TERMINALS, Group.INTEGRATED_LEGACY, "integrated.IntegratedChannelMixin");
        grouped(rows, INTEGRATED_TERMINALS, Group.INTEGRATED_CURRENT, "integrated.IntegratedCurrentChannelMixin");
        grouped(rows, INTEGRATED_TERMINALS, Group.INTEGRATED_BOTH, "integrated.IntegratedInvWrapperMixin");
        // F3: Occultism, RFTools Storage, Ender Storage, Pocket Storage.
        one(rows, OCCULTISM,
                "occultism.OccultismStorageMenuMixin",
                "occultism.OccultismStorageConcreteMenuMixin",
                "occultism.OccultismStorageBlockOpeningMixin",
                "occultism.OccultismStorageRemoteOpeningMixin",
                "occultism.OccultismStorageRemoteMenuMixin",
                "occultism.OccultismStorageRemotePacketMixin",
                "occultism.OccultismStoragePacketMixin",
                "occultism.OccultismStorageControllerMixin",
                "occultism.OccultismStorageMapMixin",
                "occultism.OccultismStorageWormholeMixin");
        one(rows, RFTOOLS_STORAGE,
                "rftools.RftoolsRequestPacketMixin",
                "rftools.RftoolsScannerMixin",
                "rftools.RftoolsScannerMenuMixin");
        one(rows, ENDER_STORAGE,
                "enderstorage.EnderChestOpenMixin",
                "enderstorage.EnderPouchOpenMixin",
                "enderstorage.EnderMenuCarrierMixin",
                "enderstorage.EnderFrequencyEpochMixin",
                "enderstorage.EnderStorageEpochMixin",
                "enderstorage.EnderItemCapabilityMixin");
        one(rows, POCKET_STORAGE,
                "pocketstorage.PocketStorageItemMixin",
                "pocketstorage.PocketStorageMenuMixin",
                "pocketstorage.PocketStoragePacketMixin",
                "pocketstorage.PocketStorageHandlerMixin");
        // Sophisticated part 4: the linked-group commit hook (guard design 01, G3), under its own key so a linked
        // failure never stops unlinked Sophisticated recording.
        one(rows, SOPHISTICATED_LINKED_GROUPS,
                "sophisticated.SophisticatedLinkedCommitMixin");
        // Parts readers design-03 section 5: the AE2 in-place hook family, one key per mod whose classes are hooked,
        // so an absent addon is left out quietly and a changed member stops only that mod's hooks.
        one(rows, PARTS_AE2_INPLACE,
                "ae2inplace.Ae2MemoryCardPasteMixin",
                "ae2inplace.Ae2PatternPasteMixin",
                "ae2inplace.Ae2CraftingUnitUpgradeMixin");
        one(rows, PARTS_ADVANCED_AE_INPLACE,
                "ae2inplace.AdvAePatternPasteMixin",
                "ae2inplace.AdvAeQuantumCrafterPasteMixin");
        one(rows, PARTS_APPLIED_PNEUMATICS_INPLACE,
                "ae2inplace.ApnPatternPasteMixin");

        Map<String, Entry> byClass = new LinkedHashMap<>();
        for (Entry row : rows) {
            if (byClass.put(row.hookClass(), row) != null) {
                throw new IllegalStateException("Hook class named twice in the hook-to-key table: " + row.hookClass());
            }
        }
        ENTRIES = List.copyOf(rows);
        BY_CLASS = Collections.unmodifiableMap(byClass);
    }

    private HookCatalog() {
    }

    private static void one(List<Entry> rows, AdapterKey key, String... hookClasses) {
        grouped(rows, key, Group.NONE, hookClasses);
    }

    private static void grouped(List<Entry> rows, AdapterKey key, Group group, String... hookClasses) {
        for (String hookClass : hookClasses) rows.add(new Entry(hookClass, List.of(key), group, ""));
    }

    /** Every row, in table order. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** The row of {@code hookClass} (relative to the mixin package), if the table names it. */
    public static Optional<Entry> find(String hookClass) {
        return hookClass == null ? Optional.empty() : Optional.ofNullable(BY_CLASS.get(hookClass));
    }
}
