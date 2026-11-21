package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.ContainerLabelFallbacks;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.spi.AdapterMatch;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.spi.MenuPhysicalBlockTarget;
import io.github.betapetey.eventtrail.inventory.spi.MenuPhysicalBacking;
import io.github.betapetey.eventtrail.inventory.spi.MenuObservedSlot;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapter;
import io.github.betapetey.eventtrail.inventory.spi.ObservationStrategy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.p3pp3rf1y.sophisticatedcore.common.gui.IFilterSlot;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot;
import net.p3pp3rf1y.sophisticatedstorage.block.BarrelBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.ChestBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.ChestBlock;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.common.gui.StorageContainerMenu;

import java.util.Optional;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Exact adapter for Sophisticated Storage's directly opened chest and barrel
 * menus in the validated 1.21.1 generation.
 *
 * <p>The optional-mod types in this class are deliberately isolated from the
 * neutral adapter registry. No version is read and no artifact is checked (build B
 * R1d and F1-4): the behaviour path is chosen at first use by what is present
 * ({@link #path()}).</p>
 *
 * <p>Controller/proxy menus, settings menus, shulker boxes, and heterogeneous
 * upgrade-tab slots are intentionally outside this adapter's claim.</p>
 */
public final class SophisticatedStorageMenuAdapter implements MenuTransactionAdapter {
    /** Provider ids are row data: byte for byte per tested build (1.6.8/1.5.17, 1.5.91/1.5.1 and 1.4.90, 1.5.85/1.4.86). */
    static final String CURRENT_ADAPTER_ID = "sophisticated-storage-1.6.8.2196";
    static final String STORAGE_1_5_91_ADAPTER_ID = "sophisticated-storage-1.5.91.2127";
    static final String STORAGE_1_5_85_ADAPTER_ID = "sophisticated-storage-1.5.85.2077";
    /** Never in a row: an OFF path matches nothing it could record. Keeps the loader's id prefix. */
    static final String OFF_ADAPTER_ID = "sophisticated-storage-off";
    static final String PATH_SITE = "adapter:SophisticatedStorageMenuAdapter.path";
    /** Concept marker of the current path: the field CurrentNativeContract.DELEGATE reads. */
    static final String CURRENT_MARKER = "StorageWrapper.linkedStorage";
    /**
     * Storage 1.5.91 marker: added in Storage 1.5.91.2127, absent from 1.5.85.2077. Required for LINKED_CORE (else
     * OFF); on the legacy path it only picks the provider id.
     */
    static final String STORAGE_1_5_91_LABEL_MARKER = "PaintbrushItem.MAX_REMAINING_PARTS";
    /**
     * The OFF detail when no path marker is present: current Storage, linked-storage Core, Core before linked storage.
     * At most 160 characters (the fail-safe's member-text cap), so the warning is never cut (build B F1-4 r2, B-1).
     */
    static final String CONCEPT_LOOKED_FOR = CURRENT_MARKER + ", "
            + SophisticatedCoreMarkers.LINKED_ENDPOINT_PROVIDER + " or "
            + SophisticatedCoreMarkers.PRE_LINKED_REPOSITORY;
    static final String LINKED_UNPAIRED_LOOKED_FOR = STORAGE_1_5_91_LABEL_MARKER
            + " (Storage 1.5.91) with a linked-storage Core; that Core has an untested Storage";
    /** OFF detail for a linked-storage Core without the pre-linked factory (Core 1.5.17+; Advisor F1-3a-1). */
    static final String LINKED_CORE_UNPAIRED_LOOKED_FOR = SophisticatedCoreMarkers.LINKED_ENDPOINT_PROVIDER + " with "
            + SophisticatedCoreMarkers.PRE_LINKED_REPOSITORY + " (Core 1.5.1); that Core has an untested Storage";
    /** Build B F1-4: where a linked chest or barrel is refused ({@link #linkedRefused}). */
    static final String LINKED_SITE = "adapter:SophisticatedStorageMenuAdapter.linked";
    /** The OFF detail when a linked-check member is missing (at most 160 characters, the fail-safe's cap). */
    static final String LINKED_LOOKED_FOR = "StorageWrapper.linkedStorage, getLinkedStorageEndpoint(),"
            + " getLinkedStorageEndpointRole(), StorageBlockEntity.getLinkedStorageEndpointData(), isLinkedStorage()";
    /** Build B soph-p1: where an upgraded chest or barrel is refused ({@link SophisticatedUpgradePolicy}). */
    static final String UPGRADE_SITE = "adapter:SophisticatedStorageMenuAdapter.upgrades";
    /** Build B soph-p1: where a subclass of the chest/barrel menu (a limited barrel's menu) is refused on CURRENT. */
    static final String MENU_SITE = "adapter:SophisticatedStorageMenuAdapter.menu";
    /** The reason of a refused subclass menu (limited barrels: their own menu and per-cell limits are not modelled). */
    static final String MENU_REASON = "storage-menu-subclass-not-recorded";
    /** SOPH-LABELS: the fallback-count site of {@link #physicalContainerLabel} (never a fail-safe site). */
    static final String LABEL_SITE = "adapter:SophisticatedStorageMenuAdapter.physicalContainerLabel";
    /** Used only when building a selection failed itself (for example memory ran out); never cached. */
    private static final Selection UNPROBED = new Selection(Path.CONCEPT_UNUSABLE, OFF_ADAPTER_ID,
            SophisticatedCoreMarkers.PROBE_FAILED + "unknown", null, false);
    private static volatile Selection selected;

    /**
     * The behaviour path, chosen by what is present (build B R1d/Row A). Only CURRENT, LINKED_CORE and LEGACY
     * record; the other two switch the key OFF at first use with one warning naming what was looked for.
     */
    enum Path {
        /** Storage's own linked-storage field is present (no artifact check since build B F1-4). */
        CURRENT,
        /**
         * No Storage linked-storage field; Core's linked-storage endpoint interface is present (1.5.1-style), and
         * the Storage 1.5.91 marker is present, and Core's pre-linked factory is present too (absent from 1.5.17,
         * 1.5.25 and 1.5.32, which changed the custody classes this path reads), and the reviewed-linked-Core marker
         * is present ({@link #reviewedLinkedCoreOnly}). The factory alone does not single out Core 1.5.1: Core 1.5.5,
         * 1.5.12 and 1.5.14 have it too, with changed menu-base classes, and are OFF.
         */
        LINKED_CORE,
        /** No linked-storage concept anywhere, and the Core marker that predates it is present. */
        LEGACY,
        /**
         * Present but unusable: a linked-storage Core without the Storage 1.5.91 marker, without the pre-linked
         * factory or without the reviewed-linked-Core marker, or a probe that failed. Never a fall-back to a laxer
         * path.
         */
        CONCEPT_UNUSABLE,
        /** No concept marker and no pre-concept marker: the build cannot be placed. */
        CONCEPT_NOT_FOUND;

        boolean records() {
            return this == CURRENT || this == LINKED_CORE || this == LEGACY;
        }
    }

    /**
     * One chosen path and its fixed provider id. {@code offDetail} is the OFF warning's detail (null on a recording
     * path); {@code failure} is the probe failure, if any; {@code cacheable} is false only for a VM error (or a
     * choice that could not be built), which is OFF for that call and probed again at the next.
     */
    record Selection(Path path, String adapterId, String offDetail, Throwable failure, boolean cacheable) {
        static Selection recording(Path path, String adapterId) {
            return new Selection(path, adapterId, null, null, true);
        }

        static Selection off(Path path, String offDetail) {
            return new Selection(path, OFF_ADAPTER_ID, offDetail, null, true);
        }
    }

    /**
     * Path rule over the four probes, each read only when needed. A concept marker never leads to a laxer path.
     * The current marker alone selects CURRENT (build B F1-4 removed the artifact pin that also had to hold).
     * LINKED_CORE needs the Storage 1.5.91 marker and then Core's pre-linked factory (else OFF), and then the
     * reviewed-linked-Core marker, which {@link #probe()} applies to this rule's choice ({@link #reviewedLinkedCoreOnly});
     * on the legacy path that marker only picks the provider id. A probe that throws is present-but-unusable: OFF
     * naming the failure, cached unless it is a {@link VirtualMachineError}.
     */
    static Selection choose(BooleanSupplier currentMarker, BooleanSupplier linkedCoreMarker,
            BooleanSupplier legacyOnlyMarker, BooleanSupplier storage1591Marker) {
        try {
            if (currentMarker.getAsBoolean()) return Selection.recording(Path.CURRENT, CURRENT_ADAPTER_ID);
            if (linkedCoreMarker.getAsBoolean()) {
                if (!storage1591Marker.getAsBoolean()) {
                    return Selection.off(Path.CONCEPT_UNUSABLE, LINKED_UNPAIRED_LOOKED_FOR);
                }
                // Advisor F1-3a-1: a linked Core without the pre-linked factory (1.5.17+) is OFF. The factory alone is
                // not Core 1.5.1 (1.5.5, 1.5.12 and 1.5.14 keep it): reviewedLinkedCoreOnly narrows LINKED_CORE further.
                return legacyOnlyMarker.getAsBoolean()
                        ? Selection.recording(Path.LINKED_CORE, STORAGE_1_5_91_ADAPTER_ID)
                        : Selection.off(Path.CONCEPT_UNUSABLE, LINKED_CORE_UNPAIRED_LOOKED_FOR);
            }
            if (legacyOnlyMarker.getAsBoolean()) return Selection.recording(Path.LEGACY,
                    storage1591Marker.getAsBoolean() ? STORAGE_1_5_91_ADAPTER_ID : STORAGE_1_5_85_ADAPTER_ID);
            return Selection.off(Path.CONCEPT_NOT_FOUND, CONCEPT_LOOKED_FOR);
        } catch (Throwable failed) {
            return new Selection(Path.CONCEPT_UNUSABLE, OFF_ADAPTER_ID, SophisticatedCoreMarkers.probeFailed(failed),
                    failed, !(failed instanceof VirtualMachineError));
        }
    }

    /** Probed at first use (presence only); cached unless the choice was a VM error. Never throws. */
    static Selection selection() {
        Selection current = selected;
        if (current != null) return current;
        try {
            current = probe();
        } catch (Throwable unbuilt) {
            return UNPROBED;
        }
        if (current.cacheable()) selected = current;
        return current;
    }

    /**
     * Build B SOPH-LINKED-VERSIONS: a LINKED_CORE choice stands only on the reviewed linked Core (Core 1.5.1.2341);
     * on any other Core it is OFF with {@link SophisticatedCoreMarkers#UNREVIEWED_LINKED_CORE_LOOKED_FOR}. Every other
     * choice is returned as it is, and the marker is not read for it. A marker probe that throws is OFF naming the
     * failure, cached unless it is a {@link VirtualMachineError}.
     */
    static Selection reviewedLinkedCoreOnly(Selection chosen, BooleanSupplier reviewedLinkedCoreMarker) {
        if (chosen.path() != Path.LINKED_CORE) return chosen;
        try {
            return reviewedLinkedCoreMarker.getAsBoolean() ? chosen
                    : Selection.off(Path.CONCEPT_UNUSABLE, SophisticatedCoreMarkers.UNREVIEWED_LINKED_CORE_LOOKED_FOR);
        } catch (Throwable failed) {
            return new Selection(Path.CONCEPT_UNUSABLE, OFF_ADAPTER_ID, SophisticatedCoreMarkers.probeFailed(failed),
                    failed, !(failed instanceof VirtualMachineError));
        }
    }

    /** The real probes wired into the rule, uncached (tests read it without touching the process-wide cache). */
    static Selection probe() {
        return reviewedLinkedCoreOnly(choose(SophisticatedStorageMenuAdapter::currentConceptProbe,
                SophisticatedCoreMarkers::linkedEndpointProviderPresent,
                SophisticatedCoreMarkers::preLinkedRepositoryPresent,
                SophisticatedStorageMenuAdapter::storage1591LabelMarkerPresent),
                SophisticatedCoreMarkers::reviewedLinkedCoreMarkerPresent);
    }

    static Path path() {
        return selection().path();
    }

    /** The path for recording work; a non-recording path switches the key OFF (idempotent, one warning). */
    static Path recordingPath() {
        return switchOffUnlessRecording(selection());
    }

    /**
     * A cached OFF choice switches the key OFF until restart with its detail. An uncached one (VM error) is
     * reported instead: the fail-safe's classifier sorts it (stack/memory errors are transient), so it never
     * locks the key OFF.
     */
    static Path switchOffUnlessRecording(Selection selection) {
        if (selection.path().records()) return selection.path();
        if (selection.cacheable()) {
            AdapterFailSafe.conceptNotFound(AdapterKey.SOPHISTICATED_STORAGE, PATH_SITE, selection.offDetail());
        } else {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE, PATH_SITE, selection.failure());
        }
        return selection.path();
    }

    static boolean currentRuntime() {
        return path() == Path.CURRENT;
    }

    static boolean linkedCoreRuntime() {
        return path() == Path.LINKED_CORE;
    }

    /**
     * Storage's own linked-storage field (declared in Storage 1.6.x; absent from 1.5.85 and 1.5.91). Only "not
     * there" is false; any other failure (for example {@code NoClassDefFoundError} for a Core linked-storage type
     * the field lookup resolves) propagates to the path choice, which goes OFF.
     */
    static boolean currentConceptProbe() {
        try {
            return StorageWrapper.class.getDeclaredField("linkedStorage") != null;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    /**
     * Test-side presence check (never used for the path choice): {@link #currentConceptProbe()} with any failure
     * read as false, uncached, so test fixtures can recognise the current classpath before they install its mod
     * list.
     */
    static boolean currentConceptPresent() {
        try {
            return currentConceptProbe();
        } catch (LinkageError | RuntimeException unusable) {
            return false;
        }
    }

    /** A constant added in Storage 1.5.91.2127 (absent from 1.5.85.2077; read from both jars). Not-there only is false. */
    private static boolean storage1591LabelMarkerPresent() {
        try {
            return Class.forName("net.p3pp3rf1y.sophisticatedstorage.item.PaintbrushItem", false,
                    StorageBlockEntity.class.getClassLoader()).getDeclaredField("MAX_REMAINING_PARTS") != null;
        } catch (ClassNotFoundException | NoSuchFieldException absent) {
            return false;
        }
    }

    @Override
    public String adapterId() {
        return selection().adapterId();
    }

    @Override
    public boolean exact() {
        return true;
    }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof StorageContainerMenuBase<?>)) {
            return AdapterMatch.NO_MATCH;
        }
        if (io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader
                .isNonStationarySophisticatedMenu(context.menu().getClass())) return AdapterMatch.NO_MATCH;

        // An OFF path still owns its menus: blocked, never handed to the generic fallback.
        if (!recordingPath().records()) {
            return AdapterMatch.AMBIGUOUS;
        }

        if (!(context.menu() instanceof StorageContainerMenu menu)) {
            return AdapterMatch.AMBIGUOUS;
        }
        if (currentRuntime() && menu.getClass() != StorageContainerMenu.class) {
            // Build B soph-p1 (estimate-01 1.2 step 6): a limited barrel's menu (or any other subclass) stays refused,
            // now with one warning per server start instead of silently.
            AdapterFailSafe.refusedOnce(AdapterKey.SOPHISTICATED_STORAGE, MENU_SITE, MENU_REASON);
            return AdapterMatch.AMBIGUOUS;
        }

        StorageBlockEntity storage = menu.getStorageBlockEntity();
        if (!isSupportedDirectStorage(storage)) return AdapterMatch.AMBIGUOUS;
        return linkedRefused(storage) ? AdapterMatch.AMBIGUOUS : AdapterMatch.SUPPORTED;
    }

    /**
     * Build B F1-4 ({@link SophisticatedLinkedStorage}; temporary until the linked-group recording path, Owner option
     * B): a linked chest or barrel is not taken here (the adapter answers AMBIGUOUS, so nothing in that open is
     * recorded; the storage still opens normally for the player), with one warning per server start; the key stays ON.
     * Before F1-4 it matched SUPPORTED and then failed the unlinked checks of {@link #resolvePhysicalBlockTarget}, so
     * its open recorded nothing with no warning. Only opens those checks already refuse are refused here, so no row
     * that was written before is lost. Never throws for a non-VM failure ({@link SophisticatedLinkedStorage#refusedOpen}
     * reports or sorts it); a {@link VirtualMachineError} (stack or memory) propagates.
     */
    static boolean linkedRefused(StorageBlockEntity storage) {
        return SophisticatedLinkedStorage.refusedOpen(AdapterKey.SOPHISTICATED_STORAGE, LINKED_SITE, LINKED_LOOKED_FOR,
                () -> linkage(path(), storage));
    }

    /**
     * The opened storage's link state on {@code path}, read with the members the unlinked checks read. On CURRENT the
     * other half of a double chest is read too (its unlinked state is checked when the slots are described), through
     * the chest's own level. Throws what a read throws.
     */
    static SophisticatedLinkedStorage.Linkage linkage(Path path, StorageBlockEntity storage)
            throws ReflectiveOperationException {
        Level level = storage == null ? null : storage.getLevel();
        return linkage(path, storage, level == null ? null : level::getBlockEntity);
    }

    /**
     * {@link #linkage(Path, StorageBlockEntity)} with the partner lookup as a seam (review-01 m-4): production passes
     * the chest level's {@code getBlockEntity}; a null lookup reads no partner.
     */
    static SophisticatedLinkedStorage.Linkage linkage(Path path, StorageBlockEntity storage,
            Function<BlockPos, BlockEntity> partnerLookup) throws ReflectiveOperationException {
        SophisticatedLinkedStorage.Linkage own = ownLinkage(path, storage);
        if (own != SophisticatedLinkedStorage.Linkage.UNLINKED || path != Path.CURRENT || partnerLookup == null
                || !(storage instanceof ChestBlockEntity chest)) return own;
        BlockState state = chest.getBlockState();
        if (state == null || !state.hasProperty(ChestBlock.TYPE) || !state.hasProperty(ChestBlock.FACING)
                || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return own;
        return partnerLookup.apply(chest.getBlockPos().relative(ChestBlock.getConnectedDirection(state)))
                instanceof ChestBlockEntity partner && partner != chest ? ownLinkage(path, partner) : own;
    }

    private static SophisticatedLinkedStorage.Linkage ownLinkage(Path path, StorageBlockEntity storage)
            throws ReflectiveOperationException {
        // LEGACY: a Core before linked storage, so nothing can be linked. An OFF path admits no storage at all.
        if (path == Path.LEGACY || !path.records() || storage == null) return SophisticatedLinkedStorage.Linkage.UNLINKED;
        StorageWrapper wrapper = storage.getStorageWrapper();
        // No wrapper: no contents that could be linked (an unbuilt fixture only); the target checks still refuse it.
        if (wrapper == null) return SophisticatedLinkedStorage.Linkage.UNLINKED;
        if (path == Path.LINKED_CORE) {
            // The instance check of the LINKED_CORE wrapper rule (isSupportedPhysicalWrapper).
            return SophisticatedLinkedStorage.endpointProvider(wrapper)
                    ? SophisticatedLinkedStorage.Linkage.LINKED : SophisticatedLinkedStorage.Linkage.UNLINKED;
        }
        return CurrentNativeContract.linkage(wrapper, storage);
    }

    @Override
    public ObservationStrategy observationStrategy() {
        return ObservationStrategy.ACTION_SCOPED_PHYSICAL;
    }

    @Override
    public boolean usesHandlerBackedView() {
        return true;
    }

    @Override
    public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        if (context == null || context.player() == null || context.menu() == null
                || context.player().containerMenu != context.menu()
                || context.menu().getClass() != StorageContainerMenu.class
                || match(context) != AdapterMatch.SUPPORTED) return Optional.empty();
        StorageContainerMenu menu = (StorageContainerMenu) context.menu();
        StorageBlockEntity storage = menu.getStorageBlockEntity();
        Level level = context.player().level();
        if (resolvePhysicalBlockTarget(context).isEmpty() || storage.isBeingUpgraded()) return Optional.empty();
        Optional<List<Object>> members = ownershipMembers(storage, level);
        return members.flatMap(owners -> describeBoundSlots(menu, context.player().getInventory(), owners));
    }

    /** Full live compound proof is independent of the handler's complete cell coverage. */
    private static Optional<List<Object>> ownershipMembers(StorageBlockEntity storage, Level level) {
        return ownershipMembers(storage, level, pos -> level.getBlockEntity(pos));
    }

    /**
     * {@link #ownershipMembers(StorageBlockEntity, Level)} with the partner lookup as a seam (soph-p1 review-01 NIT-4,
     * test only; production passes the level's {@code getBlockEntity}, so behaviour is unchanged): the partner refusal
     * can then be run without a world.
     */
    static Optional<List<Object>> ownershipMembers(StorageBlockEntity storage, Level level,
            Function<BlockPos, BlockEntity> partnerLookup) {
        if (!(storage instanceof ChestBlockEntity chest)) return Optional.of(List.of(storage));
        BlockState state = chest.getBlockState();
        if (!state.hasProperty(ChestBlock.TYPE) || !state.hasProperty(ChestBlock.FACING)
                || chest.getMainChestBlockEntity() != chest) return Optional.empty();
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) return Optional.of(List.of(storage));
        var partnerPos = chest.getBlockPos().relative(ChestBlock.getConnectedDirection(state));
        if (!(partnerLookup.apply(partnerPos) instanceof ChestBlockEntity partner)
                || partner == chest || partner.isBeingUpgraded()
                || currentRuntime() && !isSupportedCurrentStorage(partner)
                || MenuPhysicalBlockTarget.fromBlockEntity(partner, level, level.dimension()).isEmpty()) {
            return Optional.empty();
        }
        BlockState other = partner.getBlockState();
        if (other.getBlock() != state.getBlock() || !other.hasProperty(ChestBlock.TYPE)
                || !other.hasProperty(ChestBlock.FACING)
                || other.getValue(ChestBlock.TYPE) != (type == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT)
                || other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
                || !partnerPos.relative(ChestBlock.getConnectedDirection(other)).equals(chest.getBlockPos())
                || partner.getMainChestBlockEntity() != chest) return Optional.empty();
        return Optional.of(List.of(chest, partner));
    }

    /** The public producer separately proves live-world/compound membership before calling this. */
    static Optional<MenuPhysicalBacking.Description> describeBoundSlots(StorageContainerMenu menu,
            Inventory playerInventory, List<Object> ownershipMembers) {
        StorageBlockEntity storage = menu.getStorageBlockEntity();
        if (!isSupportedDirectStorage(storage) || playerInventory == null
                || menu.getStorageWrapper() == null
                || menu.getStorageWrapper() != storage.getStorageWrapper()
                || !isSupportedPhysicalWrapper(menu.getStorageWrapper())
                || currentRuntime() && (!CurrentNativeContract.menuOwnedBy(menu, playerInventory.player)
                || !areSupportedCurrentMembers(storage, ownershipMembers))) return Optional.empty();
        return SophisticatedMenuSlots.describe(menu, playerInventory, storage, ownershipMembers, true);
    }

    @Override
    public Optional<MenuPhysicalBlockTarget> resolvePhysicalBlockTarget(
            MenuAdapterContext context) {
        if (context == null
                || context.player() == null
                || !(context.menu() instanceof StorageContainerMenu menu)) {
            return Optional.empty();
        }
        StorageBlockEntity storage = menu.getStorageBlockEntity();
        if (currentRuntime() && (storage == null || context.player().containerMenu != menu
                || menu.getClass() != StorageContainerMenu.class
                || !CurrentNativeContract.menuOwnedBy(menu, context.player())
                || menu.getStorageWrapper() != storage.getStorageWrapper())) return Optional.empty();
        if (!isSupportedDirectStorage(storage)
                || !isSupportedPhysicalWrapper(storage.getStorageWrapper())
                || currentRuntime() && !isSupportedCurrentStorage(storage)) return Optional.empty();
        return MenuPhysicalBlockTarget.fromBlockEntity(
                storage,
                context.player().level(),
                context.player().level().dimension());
    }

    /**
     * SOPH-LABELS: the one name of the chest or barrel this open already admitted ({@link SophisticatedStorageNames}).
     * Review-01 MAJOR-1: only checks that report nothing (a recording path, the menu, a storage, the player's open
     * menu, the wrapper identity); the admission checks of {@link #resolvePhysicalBlockTarget} are not run again.
     * Never throws: any failure is counted as a name recorded with the block's own name, and gives empty.
     */
    @Override
    public Optional<String> physicalContainerLabel(MenuAdapterContext context) {
        try {
            if (context == null || context.player() == null || !path().records()
                    || !(context.menu() instanceof StorageContainerMenu menu)) return Optional.empty();
            StorageBlockEntity storage = menu.getStorageBlockEntity();
            if (storage == null || context.player().containerMenu != menu
                    || menu.getStorageWrapper() != storage.getStorageWrapper()) return Optional.empty();
            return Optional.of(SophisticatedStorageNames.forBlockEntity(storage));
        } catch (Throwable failure) {
            ContainerLabelFallbacks.fellBack(SophisticatedStorageNames.OWNER, LABEL_SITE, failure);
            return Optional.empty();
        }
    }

    @Override
    public List<MenuObservedSlot> additionalObservedSlots(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof StorageContainerMenu menu)) {
            return List.of();
        }
        if (!isSupportedDirectStorage(menu.getStorageBlockEntity())) return List.of();

        // Sophisticated keeps real upgrade-handler slots outside menu.slots and
        // synchronizes them with their own packet indices. The same public list
        // can also contain an open upgrade tab's filter controls, so the concrete
        // custody slot type is required; ghost/configuration slots stay excluded.
        return menu.upgradeSlots.stream()
                .filter(slot -> slot instanceof StorageContainerMenuBase<?>.StorageUpgradeSlot)
                .map(slot -> new MenuObservedSlot(slot.index, slot))
                .toList();
    }

    @Override
    public InventorySlotRole classifySlot(
            MenuAdapterContext context, int menuSlotIndex, Slot slot) {
        if (context == null
                || !(context.menu() instanceof StorageContainerMenu menu)
                || slot == null) {
            return InventorySlotRole.UNKNOWN;
        }

        if (slot.container instanceof Inventory) {
            return InventorySlotRole.PLAYER;
        }
        if (slot instanceof IFilterSlot) {
            return InventorySlotRole.GHOST_FILTER;
        }
        if (slot instanceof StorageInventorySlot) {
            return InventorySlotRole.STORAGE;
        }
        if (slot instanceof StorageContainerMenuBase<?>.StorageUpgradeSlot) {
            return menu.upgradeSlots.contains(slot)
                    ? InventorySlotRole.REAL_UPGRADE
                    : InventorySlotRole.UNKNOWN;
        }

        // Upgrade-tab extras are heterogeneous (filters, crafting/configuration,
        // or mod-added slots). Only the concrete real slot classes above are safe.
        if (menu.getExtraSlots().contains(slot) || slot instanceof SlotItemHandler) {
            return InventorySlotRole.UNKNOWN;
        }
        return InventorySlotRole.UNKNOWN;
    }

    static boolean isSupportedDirectStorage(StorageBlockEntity storage) {
        Path path = path();
        if (path == Path.CURRENT) return storage != null
                && (storage.getClass() == ChestBlockEntity.class || storage.getClass() == BarrelBlockEntity.class);
        // An OFF path admits no storage, so no descriptor, target or observed slot is produced on it.
        if (!path.records()) return false;
        return storage instanceof ChestBlockEntity || storage instanceof BarrelBlockEntity;
    }

    static boolean isSupportedPhysicalWrapper(Object wrapper) {
        Path path = path();
        if (path == Path.CURRENT) return CurrentNativeContract.unlinkedWrapper(wrapper);
        if (path == Path.LEGACY) return true;
        if (path != Path.LINKED_CORE || wrapper == null) return false;
        try {
            ClassLoader loader = StorageBlockEntity.class.getClassLoader();
            // Addon block-entity subclasses can override getStorageWrapper().
            // Identity agreement alone does not prove local backing under Core's
            // new linked-storage constructor contract. Admit only the reviewed
            // direct wrapper, and reject even a transformed linked version of it.
            return wrapper.getClass() == Class.forName(
                    "net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity$1", false, loader)
                    && !Class.forName("net.p3pp3rf1y.sophisticatedcore.linkedstorage.ILinkedStorageEndpointProvider",
                    false, loader).isInstance(wrapper);
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            return false;
        }
    }

    /**
     * Current local ownership is re-proved by the producer at both sides of every capture. The installed upgrades must
     * pass {@link SophisticatedUpgradePolicy} (build B soph-p1; before it, any upgrade made the open record nothing, with
     * no warning).
     */
    static boolean isSupportedCurrentStorage(StorageBlockEntity storage) {
        if (!isSupportedDirectStorage(storage)) return false;
        try {
            StorageWrapper wrapper = storage.getStorageWrapper();
            if (!CurrentNativeContract.unlinkedWrapper(wrapper)
                    || !CurrentNativeContract.ownedBy(wrapper, storage)
                    || !CurrentNativeContract.unlinkedBlock(storage)) return false;
            // Build B soph-p1: admitted upgrades record like no upgrade; any other is refused with one warning per
            // server start. Run for each member at both sides of every capture, so an upgrade the click adds that is
            // not admitted fails the after-check and that action records nothing (so does a click that makes the menu
            // rebuild an upgrade's settings slots: the descriptor no longer matches). An admitted upgrade put in or
            // taken out with no such rebuild passes both checks and is recorded as that item moving into or out of
            // the upgrade cell.
            return SophisticatedUpgradePolicy.admitted(AdapterKey.SOPHISTICATED_STORAGE, UPGRADE_SITE,
                    wrapper.getUpgradeHandler());
        } catch (RuntimeException | LinkageError unavailable) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE, "adapter:SophisticatedStorageMenuAdapter.isSupportedCurrentStorage", unavailable);
            return false;
        }
    }

    static boolean areSupportedCurrentMembers(StorageBlockEntity storage, List<Object> members) {
        if (members == null || members.isEmpty() || members.size() > 2 || !members.contains(storage)) return false;
        for (Object member : members) {
            if (!(member instanceof StorageBlockEntity block) || !isSupportedCurrentStorage(block)) return false;
        }
        return true;
    }

    /** Reflects only the authenticated new API; inaccessible native state denies support. */
    private static final class CurrentNativeContract {
        private static final Class<?> WRAPPER = wrapperClass();
        private static final java.lang.reflect.Field DELEGATE = field(StorageWrapper.class, "linkedStorage");
        private static final java.lang.reflect.Field OWNER = field(WRAPPER, "this$0");
        private static final java.lang.reflect.Field MENU_PLAYER = field(StorageContainerMenuBase.class, "player");

        private static Class<?> wrapperClass() {
            try { return Class.forName("net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity$1", false,
                    StorageBlockEntity.class.getClassLoader()); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }

        private static java.lang.reflect.Field field(Class<?> type, String name) {
            try {
                if (type == null) return null;
                var field = type.getDeclaredField(name);
                return field.trySetAccessible() ? field : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }

        static boolean unlinkedWrapper(Object wrapper) {
            if (wrapper == null || WRAPPER == null || wrapper.getClass() != WRAPPER || DELEGATE == null) return false;
            try {
                return DELEGATE.get(wrapper) == null
                        && StorageWrapper.class.getMethod("getLinkedStorageEndpoint").invoke(wrapper) instanceof Optional<?> endpoint
                        && endpoint.isEmpty()
                        && StorageWrapper.class.getMethod("getLinkedStorageEndpointRole").invoke(wrapper) instanceof Optional<?> role
                        && role.isEmpty();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
        }

        /**
         * Build B F1-4: the link state from the same members {@link #unlinkedWrapper} and {@link #unlinkedBlock} read
         * (any of them set means linked). The wrapper's own class is not required here: an unadmitted wrapper class is
         * refused by the target checks as before. A member that is missing (the delegate field this adapter could not
         * open, or a method lookup that throws NoSuchMethodException) is UNREADABLE, so the key goes OFF; one object's
         * value of another shape is STATE_UNREADABLE, so only that open is refused (build B F1-4 r2, m-1 and m-2).
         */
        static SophisticatedLinkedStorage.Linkage linkage(StorageWrapper wrapper, StorageBlockEntity storage)
                throws ReflectiveOperationException {
            if (DELEGATE == null) return SophisticatedLinkedStorage.Linkage.UNREADABLE;
            if (DELEGATE.get(wrapper) != null) return SophisticatedLinkedStorage.Linkage.LINKED;
            Object endpoint = StorageWrapper.class.getMethod("getLinkedStorageEndpoint").invoke(wrapper);
            Object role = StorageWrapper.class.getMethod("getLinkedStorageEndpointRole").invoke(wrapper);
            if (!(endpoint instanceof Optional<?> endpointValue) || !(role instanceof Optional<?> roleValue)) {
                return SophisticatedLinkedStorage.Linkage.STATE_UNREADABLE;
            }
            if (endpointValue.isPresent() || roleValue.isPresent()) return SophisticatedLinkedStorage.Linkage.LINKED;
            Object data = StorageBlockEntity.class.getMethod("getLinkedStorageEndpointData").invoke(storage);
            Object linked = StorageBlockEntity.class.getMethod("isLinkedStorage").invoke(storage);
            if (!(linked instanceof Boolean linkedValue)) return SophisticatedLinkedStorage.Linkage.STATE_UNREADABLE;
            return data != null || linkedValue ? SophisticatedLinkedStorage.Linkage.LINKED
                    : SophisticatedLinkedStorage.Linkage.UNLINKED;
        }

        static boolean ownedBy(Object wrapper, StorageBlockEntity storage) {
            try { return OWNER != null && OWNER.get(wrapper) == storage; }
            catch (IllegalAccessException | RuntimeException unavailable) {
                AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE, "adapter:SophisticatedStorageMenuAdapter.ownedBy", unavailable);
                return false;
            }
        }

        static boolean unlinkedBlock(StorageBlockEntity storage) {
            try {
                return StorageBlockEntity.class.getMethod("getLinkedStorageEndpointData").invoke(storage) == null
                        && Boolean.FALSE.equals(StorageBlockEntity.class.getMethod("isLinkedStorage").invoke(storage));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
        }

        static boolean menuOwnedBy(StorageContainerMenu menu, net.minecraft.world.entity.player.Player player) {
            try { return MENU_PLAYER != null && MENU_PLAYER.get(menu) == player; }
            catch (IllegalAccessException | RuntimeException unavailable) {
                AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE, "adapter:SophisticatedStorageMenuAdapter.menuOwnedBy", unavailable);
                return false;
            }
        }
    }
}
