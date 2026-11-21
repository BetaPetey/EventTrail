package io.github.betapetey.eventtrail.inventory.adapters.create;

import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.bearing.ClockworkContraption;
import com.simibubi.create.content.contraptions.gantry.GantryContraption;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.piston.PistonContraption;
import com.simibubi.create.content.contraptions.pulley.PulleyContraption;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.ContainerLabelFallbacks;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedCoreMarkers;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedLinkedStorage;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedMenuSlots;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedStorageMarkers;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedStorageNames;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.spi.*;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageInventorySlot;
import net.p3pp3rf1y.sophisticatedcore.compat.create.MountedStorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;
import net.p3pp3rf1y.sophisticatedcore.util.NoopStorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.block.BarrelBlock;
import net.p3pp3rf1y.sophisticatedstorage.block.ChestBlock;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockBase;
import net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.entity.StorageHolderBase;
import net.p3pp3rf1y.sophisticatedstorage.item.WoodStorageBlockItem;
import net.p3pp3rf1y.sophisticatedstoragecreateintegration.common.MountedStorageContainerMenu;
import net.p3pp3rf1y.sophisticatedstoragecreateintegration.storage.MountedSophisticatedStorage;
import net.p3pp3rf1y.sophisticatedstoragecreateintegration.storage.MountedStorageHolder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Exact finite mounted Storage host; Core cell semantics are shared, native host authority is not.
 *
 * <p>No version is read (build B F1-3b, R1d/Row A): the behaviour path is chosen at first use by what is present
 * ({@link #path()}). No artifact is checked either (build B F1-4): the exact artifact pin that F1-3b kept as the
 * last condition of every recording path is gone, so a build that carries a path's markers records on that path
 * with its fixed provider id. Nothing checks the Create build itself.</p>
 *
 * <p>Build B soph-p2 (Owner decision received by 12:15 PM ET 2026-11-14): the current path records every chest and
 * barrel tier (its cells and upgrade cells read from the block's own declared counts), a mounted double chest (both
 * halves, through the main half's handler) and the contraption kinds of {@link #admittedKind}. What it does not record
 * is refused with one warning per reason and server start ({@link AdapterFailSafe#refusedOnce}); the storage still
 * opens normally for the player and the key stays ON: trains and every unlisted contraption class, shulker boxes,
 * limited barrels and every other mounted menu class, and (until the upgrade policy lane lands) any installed upgrade.
 * Nothing that recorded before records differently: the one admitted shape before soph-p2, a single plain iron chest
 * on a bearing, keeps its checks, provider id and backing key.</p>
 *
 * <p>soph-p2 r2 (review-01): a captured open that stops being current while its menu is still open warns (M-1); a
 * mounted double chest records at its main half (M-2); the current path's label is the stored stack's shown name (M-5,
 * one name rule); the older paths' refusals warn too (open question 5); the plain mounted settings screen is silent
 * (open question 3). Rows that recorded before keep their provider id and backing key; only the current path's label
 * and a double chest's row position (now always the main half) change.</p>
 */
public final class SophisticatedCreateMountedMenuAdapter implements MenuTransactionAdapter {
    /** Provider ids are row data, byte for byte: addon 0.1.21.209 (on Core 1.4.90 and 1.5.1) and 0.1.23.246. */
    static final String OLDER_ADAPTER_ID = "sophisticatedcreate-0.1.21.209";
    static final String CURRENT_ADAPTER_ID = "sophisticatedcreate-0.1.23.246";
    /** Never in a row: an OFF path matches nothing it could record. Keeps the loader's id prefix. */
    static final String OFF_ADAPTER_ID = "sophisticatedcreate-off";
    static final String PATH_SITE = "adapter:SophisticatedCreateMountedMenuAdapter.path";
    /** Build B F1-4: where a linked mounted chest is refused ({@link #linkedRefused}). */
    static final String LINKED_SITE = "adapter:SophisticatedCreateMountedMenuAdapter.linked";
    /** The OFF detail when a linked-check member is missing (at most 160 characters, the fail-safe's cap). */
    static final String LINKED_LOOKED_FOR = "MountedStorageContainerMenu.mountedStorage, openedEndpoint,"
            + " MountedStorageHolder.getLinkedStorageEndpointData(), isLinkedStorage(), LINKED_STORAGE_ENDPOINT";
    /** Concept marker of the current path: the holder method that path itself calls (AeroNativeContract.ENDPOINT). */
    static final String CURRENT_MARKER = "MountedStorageHolder.getLinkedStorageEndpointData()";
    /** Older-path marker: the addon 0.1.21.209 holder's own private menu factory (absent from 0.1.23.246). */
    static final String OLDER_MARKER = "MountedStorageHolder.createMenu(int, Player, int, BlockPos)";
    static final String CONCEPT_LOOKED_FOR = CURRENT_MARKER + " (current addon) or " + OLDER_MARKER + " (addon 0.1.21)";
    /** r2 (review-01 m-3): the current addon marker on a Storage without its own linked-storage field. */
    static final String CURRENT_UNPAIRED_LOOKED_FOR = CURRENT_MARKER + " with a Storage before "
            + SophisticatedStorageMarkers.LINKED_STORAGE_FIELD + "; that Storage is untested";
    static final String STORAGE_UNPAIRED_LOOKED_FOR = SophisticatedStorageMarkers.STORAGE_1_5_91_MARKER + " without "
            + SophisticatedStorageMarkers.LINKED_STORAGE_FIELD + " (Storage 1.5.91) under addon 0.1.21; that Storage is untested";
    static final String CORE_UNPAIRED_LOOKED_FOR = SophisticatedCoreMarkers.PRE_LINKED_REPOSITORY
            + " (Core 1.4.90 or 1.5.1) under addon 0.1.21; that Core is untested";
    /** Used only when building a selection failed itself (for example memory ran out); never cached. */
    private static final Selection UNPROBED = new Selection(Path.CONCEPT_UNUSABLE, OFF_ADAPTER_ID,
            SophisticatedCoreMarkers.PROBE_FAILED + "unknown", null, false);
    private static volatile Selection selected;
    private static final String CURRENT_IRON_CHEST = "sophisticatedstorage:iron_chest";
    private static final Field MENU_PLAYER = field(StorageContainerMenuBase.class, "player");
    /** Build B soph-p2: where an open this adapter recognises but does not record is refused (one line per reason). */
    static final String REFUSED_SITE = "adapter:SophisticatedCreateMountedMenuAdapter.refused";
    /** Another menu class of the addon or mounted namespace (limited barrels, settings menus, mounted backpacks). */
    static final String MENU_REASON = "mounted-menu-not-recorded";
    /** A contraption entity or contraption class outside {@link #admittedKind} (trains, elevators, stabilized, others). */
    static final String CONTRAPTION_REASON = "mounted-contraption-kind-not-recorded";
    /** A mounted storage block that is not exactly a Sophisticated chest or barrel (shulker boxes, limited barrels). */
    static final String STORAGE_KIND_REASON = "mounted-storage-kind-not-recorded";
    /** An installed upgrade; temporary until the upgrade policy lane (part 1) is admitted here. */
    static final String UPGRADES_REASON = "mounted-upgrades-not-recorded";
    /** No native opening was seen for the menu (it was not opened through the mounted interaction or open packet). */
    static final String OPENING_REASON = "mounted-opening-not-seen";
    /** The capture, or a later check of the open menu, refused the storage's native state. */
    static final String STORAGE_REASON = "mounted-storage-not-recorded";
    /** The F1-3b host shape (a single iron chest): 54 cells and 2 upgrade cells. */
    static final int IRON_CELLS = 54;
    static final int IRON_UPGRADE_CELLS = 2;
    /** The first refusal reason of each refused menu, so later refusals of that open count under the same reason. */
    private static final Map<AbstractContainerMenu, String> REFUSED_MENUS = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * r2 (open question 5): menus the LINKED_CORE linked rule refused with its own linked line and count
     * ({@link #currentCoreHostSupported}); {@link #refuse} writes and counts nothing more for them.
     */
    private static final Map<AbstractContainerMenu, Boolean> LINKED_MENUS = Collections.synchronizedMap(new WeakHashMap<>());
    /** r2 (open question 3): the plain mounted settings screen (addon 0.1.23.246), matched by exact class name. */
    static final String SETTINGS_MENU = "net.p3pp3rf1y.sophisticatedstoragecreateintegration.common.MountedStorageSettingsContainerMenu";
    /** r2 (open question 3): Core's settings menu, whose click and quick-move do nothing (Core 1.5.17 bytes). */
    static final String CORE_SETTINGS_MENU = "net.p3pp3rf1y.sophisticatedcore.common.gui.SettingsContainerMenu";

    /**
     * The behaviour path, chosen by what is present (build B R1d/Row A). Only CURRENT, LINKED_CORE and LEGACY
     * record; the other two switch the key OFF at first use with one warning naming what was looked for.
     */
    enum Path {
        /** The addon's holder declares the linked-storage endpoint getter. */
        CURRENT,
        /**
         * Addon 0.1.21 (older marker, no current marker) with Storage 1.5.91 and a Core that has the linked-storage
         * endpoint interface, the pre-linked factory and the reviewed-linked-Core marker (Core 1.5.1;
         * {@link #reviewedLinkedCoreOnly}). Core 1.5.5, 1.5.12 and 1.5.14 have the first two only and are OFF.
         */
        LINKED_CORE,
        /** Addon 0.1.21 with Storage 1.5.91 and a Core with the pre-linked factory and no linked storage. */
        LEGACY,
        /**
         * Present but unusable: the older marker with an untested Storage or Core, or a probe that failed. Never a
         * fall-back to a laxer path.
         */
        CONCEPT_UNUSABLE,
        /** Neither addon marker: the build cannot be placed. */
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
     * Path rule over the six probes, each read only when needed. The current marker selects CURRENT when Storage's own
     * linked-storage field is present too (else OFF) and never leads to an older path. The older paths need the older
     * marker, the Storage 1.5.91 generation (its marker present, Storage's own linked-storage field absent) and the
     * Core pre-linked factory (present in Core 1.4.86, 1.4.90 and 1.5.1, and also in the unreviewed 1.4.87, 1.5.5,
     * 1.5.12 and 1.5.14); the Core linked-storage interface then picks LINKED_CORE over LEGACY, and {@link #probe()}
     * keeps a LINKED_CORE choice only with the reviewed-linked-Core marker ({@link #reviewedLinkedCoreOnly}). No
     * artifact pin is read (build B F1-4). A probe that throws is present-but-unusable: OFF naming the failure,
     * cached unless it is a {@link VirtualMachineError}.
     */
    static Selection choose(BooleanSupplier currentMarker, BooleanSupplier olderMarker,
            BooleanSupplier storageLinkedField, BooleanSupplier storage1591Marker, BooleanSupplier linkedCoreMarker,
            BooleanSupplier preLinkedCoreMarker) {
        try {
            if (currentMarker.getAsBoolean()) {
                // r2 (review-01 m-3): the Storage 1.6 generation the pin carried stays a condition of CURRENT.
                return storageLinkedField.getAsBoolean() ? Selection.recording(Path.CURRENT, CURRENT_ADAPTER_ID)
                        : Selection.off(Path.CONCEPT_UNUSABLE, CURRENT_UNPAIRED_LOOKED_FOR);
            }
            if (!olderMarker.getAsBoolean()) return Selection.off(Path.CONCEPT_NOT_FOUND, CONCEPT_LOOKED_FOR);
            if (storageLinkedField.getAsBoolean() || !storage1591Marker.getAsBoolean()) {
                return Selection.off(Path.CONCEPT_UNUSABLE, STORAGE_UNPAIRED_LOOKED_FOR);
            }
            if (!preLinkedCoreMarker.getAsBoolean()) return Selection.off(Path.CONCEPT_UNUSABLE, CORE_UNPAIRED_LOOKED_FOR);
            return Selection.recording(linkedCoreMarker.getAsBoolean() ? Path.LINKED_CORE : Path.LEGACY,
                    OLDER_ADAPTER_ID);
        } catch (Throwable failed) {
            return new Selection(Path.CONCEPT_UNUSABLE, OFF_ADAPTER_ID, SophisticatedCoreMarkers.probeFailed(failed),
                    failed, !(failed instanceof VirtualMachineError));
        }
    }

    /** Probed at first use (presence only); cached unless the choice was a VM error. Never throws. */
    static Selection selection() {
        try {
            return cachedOr(selected, SophisticatedCreateMountedMenuAdapter::probe, chosen -> selected = chosen);
        } catch (Throwable unbuilt) {
            return UNPROBED; // only if the two lambdas above could not be made; cachedOr itself catches the build
        }
    }

    /**
     * The cache rule of {@link #selection()}, kept pure for its tests (Advisor M-1): a cached choice is returned as
     * it is and nothing is built; otherwise the choice is built and only a cacheable one is stored. A build that
     * throws gives {@link #UNPROBED}, which is never stored.
     */
    static Selection cachedOr(Selection cached, Supplier<Selection> build, Consumer<Selection> store) {
        if (cached != null) return cached;
        Selection current;
        try {
            current = build.get();
        } catch (Throwable unbuilt) {
            return UNPROBED;
        }
        if (current.cacheable()) store.accept(current);
        return current;
    }

    /**
     * Build B SOPH-LINKED-VERSIONS: a LINKED_CORE choice stands only on the reviewed linked Core (Core 1.5.1.2341);
     * on any other Core it is OFF with {@link SophisticatedCoreMarkers#UNREVIEWED_LINKED_CORE_LOOKED_FOR}, never
     * LEGACY. Every other choice is returned as it is, and the marker is not read for it. A marker probe that throws
     * is OFF naming the failure, cached unless it is a {@link VirtualMachineError}.
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
        return reviewedLinkedCoreOnly(choose(SophisticatedCreateMountedMenuAdapter::currentMarkerPresent,
                SophisticatedCreateMountedMenuAdapter::olderMarkerPresent,
                SophisticatedStorageMarkers::linkedStorageFieldPresent,
                SophisticatedStorageMarkers::storage1591MarkerPresent,
                SophisticatedCoreMarkers::linkedEndpointProviderPresent,
                SophisticatedCoreMarkers::preLinkedRepositoryPresent),
                SophisticatedCoreMarkers::reviewedLinkedCoreMarkerPresent);
    }

    static Path path() {
        return selection().path();
    }

    /**
     * A cached OFF choice switches the key OFF until restart with its detail. An uncached one (VM error) is
     * reported instead: the fail-safe's classifier sorts it (stack/memory errors are transient), so it never
     * locks the key OFF.
     */
    static Path switchOffUnlessRecording(Selection selection) {
        if (selection.path().records()) return selection.path();
        if (selection.cacheable()) {
            AdapterFailSafe.conceptNotFound(AdapterKey.SOPHISTICATED_STORAGE_CREATE, PATH_SITE, selection.offDetail());
        } else {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE_CREATE, PATH_SITE, selection.failure());
        }
        return selection.path();
    }

    /**
     * The holder declares the endpoint getter (addon 0.1.23.246; absent from 0.1.21.209). Declared, not inherited:
     * {@code getDeclaredMethod} sees only the holder's own table. Only "not there" is false; any other failure (for
     * example {@code NoClassDefFoundError} for a Core type while the lookup resolves the holder's method types)
     * propagates to the path choice, which goes OFF.
     */
    static boolean currentMarkerPresent() {
        try {
            return MountedStorageHolder.class.getDeclaredMethod("getLinkedStorageEndpointData") != null;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    /** The holder declares {@code createMenu(int, Player, int, BlockPos)} (private; 0.1.21.209 only). */
    static boolean olderMarkerPresent() {
        try {
            return MountedStorageHolder.class.getDeclaredMethod("createMenu",
                    int.class, Player.class, int.class, BlockPos.class) != null;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    @Override public String adapterId() {
        return selection().adapterId();
    }
    @Override public boolean exact() { return true; }
    @Override public boolean usesHandlerBackedView() { return true; }
    @Override public boolean usesIndependentBackingTarget() { return true; }
    @Override public boolean usesMovingBackingTarget() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.ACTION_SCOPED_PHYSICAL; }
    @Override public AdapterMatch match(MenuAdapterContext context) {
        return matchWith(context, SophisticatedCreateMountedMenuAdapter::selection);
    }

    /**
     * {@link #match} over a given path choice; production passes {@link #selection()}. A package seam so the OFF gate
     * is tested with every path (Advisor M-1). A non-recording path switches the key OFF (idempotent, one warning).
     */
    static AdapterMatch matchWith(MenuAdapterContext context, Supplier<Selection> paths) {
        if (context == null || context.menu() == null) return AdapterMatch.NO_MATCH;
        if (!(context.menu() instanceof MountedStorageContainerMenuBase)) {
            if (!io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader
                    .isSophisticatedCreateMountedMenu(context.menu().getClass())) return AdapterMatch.NO_MATCH;
            // Build B soph-p2 (A2): a namespace menu outside the mounted base (a settings menu) is still not taken,
            // now with one warning per server start instead of silently. r2 (open question 3): the plain mounted
            // settings screen writes no line and counts nothing: its storage slots are view-only and Core's
            // SettingsContainerMenu ignores every click and quick-move (Core 1.5.17 bytes), so nothing can move
            // there. Every other such screen (the limited barrel's settings screen too) keeps the warning.
            // r2 (N-6): this branch runs before the path is chosen, so on a build whose path goes OFF the first such
            // open writes this line before the OFF line (refuse writes nothing once the key is OFF).
            if (!settingsScreen(context.menu().getClass())) refuse(context.menu(), MENU_REASON);
            return AdapterMatch.AMBIGUOUS;
        }
        // An OFF path still owns its menus: blocked, never handed to the generic fallback.
        Path path = switchOffUnlessRecording(paths.get());
        if (!path.records()) return AdapterMatch.AMBIGUOUS;
        if (context.menu().getClass() != MountedStorageContainerMenu.class) {
            // Build B soph-p2 (A2): still not taken, now with one warning per server start instead of silently.
            refuse(context.menu(), MENU_REASON);
            return AdapterMatch.AMBIGUOUS;
        }
        if (path != Path.CURRENT) return AdapterMatch.SUPPORTED;
        var menu = (MountedStorageContainerMenu) context.menu();
        return linkedRefused(menu) || auxLinkedRefused(menu) || openRefused(menu)
                ? AdapterMatch.AMBIGUOUS : AdapterMatch.SUPPORTED;
    }

    /**
     * Build B soph-p2: counts one refused open of {@code menu} under its first reason, with one warning line per reason
     * and server start ({@link AdapterFailSafe#refusedOnce}); the key stays ON and the menu stays open. A key already
     * switched OFF has said so in its own line, so nothing more is written. Never throws.
     */
    static void refuse(AbstractContainerMenu menu, String reason) {
        if (AdapterFailSafe.switchedOff(AdapterKey.SOPHISTICATED_STORAGE_CREATE)) return;
        String first = null;
        try {
            // r2 (open question 5): the LINKED_CORE linked rule already wrote its own line and counted this open.
            if (menu != null && LINKED_MENUS.containsKey(menu)) return;
            if (menu != null) first = REFUSED_MENUS.putIfAbsent(menu, reason);
        } catch (RuntimeException unavailable) {
            first = null; // the memo is a label aid only; the refusal itself is still counted below
        }
        AdapterFailSafe.refusedOnce(AdapterKey.SOPHISTICATED_STORAGE_CREATE, REFUSED_SITE, first == null ? reason : first);
    }

    /** r2 (open question 5): marks a menu the LINKED_CORE linked rule refused. Never throws for a non-VM failure. */
    static void linkedRefusedMenu(AbstractContainerMenu menu) {
        try {
            if (menu != null) LINKED_MENUS.put(menu, Boolean.TRUE);
        } catch (RuntimeException unavailable) {
            // the mark only avoids a second line; the linked line and count are already written
        }
    }

    /**
     * r2 (open question 3): the plain mounted settings screen, by exact class name and a Core
     * {@code SettingsContainerMenu} superclass (names only, so no Core or addon type is linked here). Never throws.
     */
    static boolean settingsScreen(Class<?> type) {
        if (type == null || !SETTINGS_MENU.equals(type.getName())) return false;
        for (Class<?> parent = type.getSuperclass(); parent != null; parent = parent.getSuperclass()) {
            if (CORE_SETTINGS_MENU.equals(parent.getName())) return true;
        }
        return false;
    }

    /**
     * Build B soph-p2: the exact entity classes the current path records on. {@link CreateMountedBinding#admittedEntity}
     * admits the same three (trains have a separate native yaw/pitch writer that the Create motion hooks do not see:
     * {@code Carriage$DimensionalCarriageEntity.alignEntity} writes the carriage's {@code yaw} and {@code pitch} fields
     * directly, Create 6.0.10 bytes).
     */
    static boolean admittedEntityClass(Class<?> entity) {
        return entity == ControlledContraptionEntity.class || entity == OrientedContraptionEntity.class
                || entity == GantryContraptionEntity.class;
    }

    /**
     * Build B soph-p2: the exact (entity class, contraption class) pairs the current path records: bearing (also the
     * windmill bearing), clockwork bearing, piston and rope pulley on a controlled entity; a minecart contraption on an
     * oriented entity; a gantry. Every other pair (a train carriage, an elevator, a stabilized bearing, any subclass or
     * another mod's contraption) is refused with a warning.
     */
    static boolean admittedKind(Class<?> entity, Class<?> contraption) {
        if (entity == ControlledContraptionEntity.class) {
            return contraption == BearingContraption.class || contraption == ClockworkContraption.class
                    || contraption == PistonContraption.class || contraption == PulleyContraption.class;
        }
        if (entity == OrientedContraptionEntity.class) return contraption == MountedContraption.class;
        return entity == GantryContraptionEntity.class && contraption == GantryContraption.class;
    }

    /** Build B soph-p2: only an exact Sophisticated chest or barrel block is recorded (no subclass, no shulker box). */
    static boolean storageKindRecorded(Block block) {
        return block != null && (block.getClass() == ChestBlock.class || block.getClass() == BarrelBlock.class);
    }

    /**
     * Build B soph-p2: the cells and upgrade cells one mounted storage holds, from its block's own declared counts. A
     * double chest is held by its main (right) half, whose handler holds both halves' cells (Storage 1.6.8
     * {@code ChestBlockEntity} doubles the main handler; the addon opens every double chest at its main half). Null for
     * anything else: another block, a left half, a count below one, an overflow.
     */
    record Shape(int cells, int upgradeCells, boolean doubleChest) {}

    static Shape declaredShape(BlockState state) {
        if (state == null || !storageKindRecorded(state.getBlock())
                || !(state.getBlock() instanceof StorageBlockBase block)) return null;
        boolean doubleChest = false;
        if (block.getClass() == ChestBlock.class) {
            if (!state.hasProperty(ChestBlock.TYPE)) return null;
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type != ChestType.SINGLE && type != ChestType.RIGHT) return null;
            doubleChest = type == ChestType.RIGHT;
        }
        int cells = block.getNumberOfInventorySlots(), upgrades = block.getNumberOfUpgradeSlots();
        if (cells < 1 || upgrades < 0 || doubleChest && cells > Integer.MAX_VALUE / 2) return null;
        return new Shape(doubleChest ? cells * 2 : cells, upgrades, doubleChest);
    }

    /**
     * Build B soph-p2: an open the current capture would refuse is refused here, at the open, with its reason (the
     * same rule as the linked refusal: only opens that recorded nothing before are refused, so no row is lost). Only
     * what is positively read is refused; an unbuilt menu, a missing contraption or block entry names nothing and the
     * capture decides (it warns too, {@link #firstCapture}). A failing read is reported and refuses the open. Never
     * throws for a non-VM failure.
     */
    static boolean openRefused(MountedStorageContainerMenu menu) {
        String reason;
        try {
            reason = openRefusal(menu);
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE_CREATE, REFUSED_SITE, failure);
            return true;
        }
        if (reason == null) return false;
        refuse(menu, reason);
        return true;
    }

    static String openRefusal(MountedStorageContainerMenu menu) {
        IStorageWrapper wrapper = menu.getStorageWrapper();
        if (wrapper == null) return null; // an unbuilt fixture only; the capture decides
        Object owner = menu.getContraptionEntity().orElse(null);
        if (owner instanceof AbstractContraptionEntity entity) {
            if (!admittedEntityClass(entity.getClass())) return CONTRAPTION_REASON;
            Contraption contraption = entity.getContraption();
            if (contraption != null) {
                if (!admittedKind(entity.getClass(), contraption.getClass())) return CONTRAPTION_REASON;
                var blocks = contraption.getBlocks();
                var info = blocks == null || menu.getLocalPos() == null ? null : blocks.get(menu.getLocalPos());
                if (info != null && info.state() != null && !storageKindRecorded(info.state().getBlock())) {
                    return STORAGE_KIND_REASON;
                }
            }
        }
        return upgradesInstalled(wrapper) ? UPGRADES_REASON : null; // part-1 hook-up (r2 M-3, point 1 of 7)
    }

    /** Build B soph-p2: an upgrade cell of the storage holds an item. Reads Core's own handler only. */
    static boolean upgradesInstalled(IStorageWrapper wrapper) {
        UpgradeHandler upgrades = wrapper.getUpgradeHandler();
        if (upgrades == null) return false;
        for (int slot = 0; slot < upgrades.getSlots(); slot++) {
            ItemStack stack = upgrades.getStackInSlot(slot);
            if (stack != null && !stack.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Build B soph-p2: the second half of a mounted double chest is read for a link the same way as the opened half
     * ({@link #linkedRefused}); a linked half refuses the whole open with the linked warning. Only a main (right) chest
     * half on a built contraption has a second half to read. Never throws for a non-VM failure.
     */
    static boolean auxLinkedRefused(MountedStorageContainerMenu menu) {
        MountedSophisticatedStorage aux;
        try {
            if (menu.getStorageWrapper() == null
                    || !(menu.getContraptionEntity().orElse(null) instanceof AbstractContraptionEntity entity)
                    || entity.getContraption() == null || menu.getLocalPos() == null) return false;
            var contraption = entity.getContraption();
            var info = contraption.getBlocks() == null ? null : contraption.getBlocks().get(menu.getLocalPos());
            if (info == null || info.state() == null || info.state().getBlock().getClass() != ChestBlock.class
                    || !info.state().hasProperty(ChestBlock.TYPE)
                    || info.state().getValue(ChestBlock.TYPE) != ChestType.RIGHT
                    || contraption.getStorage() == null) return false;
            BlockPos auxLocal = menu.getLocalPos().relative(ChestBlock.getConnectedDirection(info.state()));
            if (!(contraption.getStorage().getAllItemStorages().get(auxLocal) instanceof MountedSophisticatedStorage other)) {
                return false; // no second half: the capture refuses the double chest
            }
            aux = other;
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE_CREATE, LINKED_SITE, failure);
            return true;
        }
        final MountedSophisticatedStorage second = aux;
        return SophisticatedLinkedStorage.refusedOpen(AdapterKey.SOPHISTICATED_STORAGE_CREATE, LINKED_SITE,
                LINKED_LOOKED_FOR, () -> AeroNativeContract.storageLinkage(second));
    }

    /**
     * Build B F1-4 ({@link SophisticatedLinkedStorage}; temporary until the linked-group recording path, Owner option
     * B): on CURRENT a linked mounted chest is not taken here (nothing in that open is recorded; the storage still
     * opens normally for the player), with one warning per server start; the key stays ON. Before F1-4 it matched
     * SUPPORTED and then failed the endpoint checks of the capture, so its open recorded nothing with no warning. The
     * reads are the capture's own first endpoint checks; only opens the capture already refuses are refused, so no row
     * that was written before is lost. (LINKED_CORE refuses a linked chest in {@link #currentCoreHostSupported};
     * LEGACY has no linked storage.) Never throws for a non-VM failure (a failure to read the wrapper is reported;
     * {@link SophisticatedLinkedStorage#refusedOpen} sorts a failing read); a {@link VirtualMachineError} (stack or
     * memory) propagates.
     */
    static boolean linkedRefused(MountedStorageContainerMenu menu) {
        Object wrapper;
        try {
            wrapper = menu.getStorageWrapper();
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE_CREATE, LINKED_SITE, failure);
            return true;
        }
        // No storage wrapper: nothing opened that could be linked (an unbuilt fixture only); the capture decides.
        if (wrapper == null) return false;
        return SophisticatedLinkedStorage.refusedOpen(AdapterKey.SOPHISTICATED_STORAGE_CREATE, LINKED_SITE,
                LINKED_LOOKED_FOR, () -> AeroNativeContract.linkage(menu));
    }

    private Optional<CreateMountedBinding> current(MenuAdapterContext context) {
        if (context == null || context.player() == null || context.player().containerMenu != context.menu()
                || match(context) != AdapterMatch.SUPPORTED || !(context.menu() instanceof CreateStorageCarrier carrier)) return Optional.empty();
        var menu = (MountedStorageContainerMenu) context.menu();
        try {
            if (MENU_PLAYER.get(menu) != context.player()) return Optional.empty();
        } catch (IllegalAccessException failure) { throw new IllegalStateException("Mounted menu player unavailable", failure); }
        Path path = path(); // match() above was SUPPORTED, so this is a recording path
        var binding = carrier.eventtrail$createBinding();
        if (binding == null) {
            binding = firstCapture(path, CreateOpeningScope.current(context.player()), menu);
            if (binding == null) return Optional.empty();
            carrier.eventtrail$createBinding(binding);
        }
        if (binding.player != context.player() || !(binding.detail instanceof Details details) || !binding.current()) {
            // r2 (review-01 M-1): a captured open whose binding stopped being current while the menu is still open
            // (the contraption started moving, the anchor left the trackable world, a half or its storage was
            // replaced, the entity's context changed) records nothing more, now with one warning per reason and
            // server start. CREATE OFF writes its own line; a menu out of reach is closing and writes none.
            staleRefusal(menu, CreateMountedBinding.createHooksRunning(), () -> menu.stillValid(context.player()));
            return Optional.empty();
        }
        final CreateMountedBinding bound = binding;
        if (path == Path.CURRENT) {
            // A closing menu (out of reach) is not a refusal: it records nothing more and writes no line. Any other
            // failed re-check (an upgrade went in, a member's native state changed) stops this open with a warning.
            return recheck(menu, () -> menu.stillValid(context.player()),
                    () -> currentRecheckRefusal(details.aero(), bound.members.size(), bound.entity.getClass(),
                            bound.contraption.getClass(),
                            bound.members.isEmpty() ? null : bound.members.getFirst().state(), details.stack(),
                            () -> aeroNativeHostRefusal(details.aero(), menu, context.player(), bound.entity,
                                    context.player().level(), details.local())),
                    bound::invalidate) ? Optional.of(bound) : Optional.empty();
        }
        // r2 (open question 5): a failed re-check on the older paths stops the open with a warning too.
        return recheck(menu, () -> true, () -> olderRecheckRefusal(path, menu, bound, details), bound::invalidate)
                ? Optional.of(bound) : Optional.empty();
    }

    /**
     * r2 (review-01 M-1): the exit of {@link #current} for a binding that is no longer current. While the CREATE hooks
     * run and the menu is still valid for its player, the open is refused with {@link #STORAGE_REASON} (one line per
     * reason and server start; later refusals of the same open count under its first reason). With CREATE OFF the
     * CREATE line already says it, and a menu out of reach is closing: nothing is written ({@code stillValid} is then
     * not read). Never throws for a non-VM failure of the refusal itself.
     */
    static void staleRefusal(AbstractContainerMenu menu, boolean createHooksRunning, BooleanSupplier stillValid) {
        if (createHooksRunning && stillValid.getAsBoolean()) refuse(menu, STORAGE_REASON);
    }

    /**
     * r2 (review-01 M-4): the wiring of every re-check of a captured open. A menu out of reach is closing: the open
     * stops recording and nothing is written. A refusal reason stops it with one warning per reason and server start
     * ({@link #refuse}). Otherwise the open keeps recording (true). {@code refusal} is read only for a valid menu.
     */
    static boolean recheck(AbstractContainerMenu menu, BooleanSupplier stillValid, Supplier<String> refusal,
            Runnable invalidate) {
        if (!stillValid.getAsBoolean()) { invalidate.run(); return false; }
        String reason = refusal.get();
        if (reason == null) return true;
        refuse(menu, reason); invalidate.run(); return false;
    }

    /**
     * r2 (review-01 M-4): why a captured current-path open is no longer recorded, or null while it is. The binding's
     * own shape (a captured host, the host's member count, the admitted kind, the main half's block and stack) is
     * {@link #STORAGE_REASON}; only then is the host itself read ({@code hostRefusal}, {@link #aeroNativeHostRefusal}).
     */
    static String currentRecheckRefusal(AeroHost aero, int memberCount, Class<?> entity, Class<?> contraption,
            BlockState mainState, ItemStack stack, Supplier<String> hostRefusal) {
        if (aero == null || memberCount != aero.memberCount() || !admittedKind(entity, contraption)
                || !currentStateSupported(mainState, stack)) return STORAGE_REASON;
        return hostRefusal.get();
    }

    /** r2 (open question 5): the older paths' re-check, unchanged apart from naming its refusal. */
    private static String olderRecheckRefusal(Path path, MountedStorageContainerMenu menu, CreateMountedBinding binding,
            Details details) {
        if (details.aero() != null || binding.members.isEmpty()
                || (path == Path.LINKED_CORE && binding.members.size() != 1)
                || !currentCoreHostSupported(path, menu,
                binding.members.get(0).state(), details.holder(), details.wrapper(), details.stack())) {
            return STORAGE_REASON;
        }
        if (menu.getContraptionEntity().orElse(null) != binding.entity || !menu.getLocalPos().equals(details.local())
                || menu.getStorageWrapper() != details.wrapper() || details.storage().getStorageHolder() != details.holder()
                || details.storage().getStorageWrapper() != details.wrapper()
                || details.holder().getStorageWrapper() != details.wrapper()
                || details.storage().getStorageStack() != details.stack()
                || details.wrapper().getWrappedStorageStack() != details.stack()
                || !details.uuid().equals(details.stack().get(ModCoreDataComponents.STORAGE_UUID))
                || !details.wrapper().getContentsUuid().filter(details.uuid()::equals).isPresent()
                || WoodStorageBlockItem.isPacked(details.stack()) || !details.holder().isMainStorage()) {
            return STORAGE_REASON;
        }
        return null;
    }

    /**
     * Build B soph-p2: the first capture of an open. An open with no native opening, or one whose capture is refused,
     * is counted with one warning per reason and server start instead of silently (A1). r2 (open question 5): on every
     * recording path, the older ones too. r2 (N-7): a refused capture writes nothing while the CREATE key is OFF (every
     * capture then fails at its target, and the CREATE OFF line names the cause). Returns null when nothing is captured.
     */
    static CreateMountedBinding firstCapture(Path path, CreateOpeningScope.Opening opening, MountedStorageContainerMenu menu) {
        if (opening == null) {
            refuse(menu, OPENING_REASON);
            return null;
        }
        var binding = capture(path, opening, menu).orElse(null);
        if (binding == null && CreateMountedBinding.createHooksRunning()) refuse(menu, STORAGE_REASON);
        return binding;
    }

    static Optional<CreateMountedBinding> capture(Path path, CreateOpeningScope.Opening opening, MountedStorageContainerMenu menu) {
        if (path == null || !path.records()) return Optional.empty();
        var entity = opening.entity(); var contraption = entity.getContraption();
        // Part 1 SA-create-mounted r2 (review-01 M-1): every path keeps its own three entity classes. The binding's
        // wider list (train carriage, two Bits 'n' Bobs entities) is not reviewed for Sophisticated: refused, counted.
        if (!CreateMountedBinding.admittedEntity(entity) || !admittedEntityClass(entity.getClass()) || contraption == null
                || menu.getContraptionEntity().orElse(null) != entity) return Optional.empty();
        var local = menu.getLocalPos(); var info = contraption.getBlocks().get(local);
        MountedItemStorage mounted = contraption.getStorage().getAllItemStorages().get(local);
        if (info == null || !supportedState(info.state()) || mounted == null
                || mounted.getClass() != MountedSophisticatedStorage.class) return Optional.empty();
        var storage = (MountedSophisticatedStorage) mounted;
        if (path == Path.CURRENT) return captureAero(opening, menu, storage, info.state());
        var holder = storage.getStorageHolder(); var stack = storage.getStorageStack();
        var wrapper = storage.getStorageWrapper();
        UUID uuid = stack.get(ModCoreDataComponents.STORAGE_UUID);
        if (holder == null || holder.getClass() != MountedStorageHolder.class || !holder.isMainStorage()
                || !wrapper.getClass().getName().equals("net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper$1") || menu.getStorageWrapper() != wrapper
                || wrapper.getWrappedStorageStack() != stack || uuid == null || stack.isEmpty() || WoodStorageBlockItem.isPacked(stack)
                || !wrapper.getContentsUuid().filter(uuid::equals).isPresent()) return Optional.empty();
        if (!currentCoreHostSupported(path, menu, info.state(), holder, wrapper, stack)) return Optional.empty();
        List<CreateMountedBinding.Member> members = new ArrayList<>();
        members.add(new CreateMountedBinding.Member(local, info.state(), storage));
        if (info.state().getBlock().getClass() == ChestBlock.class && info.state().getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            if (info.state().getValue(ChestBlock.TYPE) != ChestType.RIGHT || !holder.isDoubleChest()) return Optional.empty();
            var otherPos = local.relative(ChestBlock.getConnectedDirection(info.state()));
            var otherInfo = contraption.getBlocks().get(otherPos);
            var other = contraption.getStorage().getAllItemStorages().get(otherPos);
            if (otherInfo == null || other == null || other == storage || other.getClass() != MountedSophisticatedStorage.class
                    || otherInfo.state().getBlock() != info.state().getBlock()
                    || otherInfo.state().getValue(ChestBlock.TYPE) != ChestType.LEFT
                    || otherInfo.state().getValue(ChestBlock.FACING) != info.state().getValue(ChestBlock.FACING)
                    || !otherPos.relative(ChestBlock.getConnectedDirection(otherInfo.state())).equals(local)
                    || ((MountedSophisticatedStorage)other).getStorageHolder().isMainStorage()
                    || ((MountedSophisticatedStorage)other).getStorageHolder().getMainStorageHolder() != holder) return Optional.empty();
            members.add(new CreateMountedBinding.Member(otherPos, otherInfo.state(), other));
        } else if (holder.isDoubleChest()) return Optional.empty();
        if (members.stream().noneMatch(member -> member.local().equals(opening.local()))) return Optional.empty();
        String label = mountedLabel(stack, info.state().getBlock(), OLDER_LABEL_SITE);
        var details = new Details(local.immutable(), storage, holder, wrapper, stack, uuid, null);
        var binding = new CreateMountedBinding(opening, members, wrapper, details, "sophisticatedcreate:0.1.21.209:" + uuid, label);
        return binding.target().isPresent() ? Optional.of(binding) : Optional.empty();
    }
    private static boolean supportedState(BlockState state) {
        return state.getBlock().getClass() == ChestBlock.class || state.getBlock().getClass() == BarrelBlock.class;
    }
    /**
     * Reached only on {@link Path#CURRENT} (from {@link #capture}). Build B soph-p2: any chest or barrel tier, a double
     * chest through its main half (the player may have clicked either half), on any {@link #admittedKind}. The menu
     * is always at the storage's own position (the addon opens a double chest at its main half). r2 (M-2): the binding
     * is built at the main half too, as the stationary Sophisticated adapter records at its main block entity, so every
     * row of a double chest has the main half's position whichever half was clicked (the key is unchanged).
     */
    private static Optional<CreateMountedBinding> captureAero(CreateOpeningScope.Opening opening,
            MountedStorageContainerMenu menu, MountedSophisticatedStorage storage, BlockState state) {
        var entity = opening.entity(); var contraption = entity.getContraption(); var local = menu.getLocalPos();
        if (!admittedKind(entity.getClass(), contraption.getClass())) return Optional.empty();
        var stack = storage.getStorageStack();
        Shape shape = declaredShape(state);
        if (shape == null || !currentStateSupported(state, stack)) return Optional.empty();
        BlockPos auxLocal = null; MountedSophisticatedStorage aux = null; BlockState auxState = null;
        if (shape.doubleChest()) {
            auxLocal = local.relative(ChestBlock.getConnectedDirection(state)).immutable();
            var auxInfo = contraption.getBlocks().get(auxLocal);
            if (auxInfo == null || !(contraption.getStorage().getAllItemStorages().get(auxLocal)
                    instanceof MountedSophisticatedStorage other) || other == storage) return Optional.empty();
            aux = other; auxState = auxInfo.state();
        }
        AeroBindingPlan plan = aeroBindingPlan(opening, local, state, storage, auxLocal, auxState, aux);
        if (plan == null) return Optional.empty();
        var host = AeroNativeContract.capture(storage, menu.getStorageWrapper(), shape, auxLocal, aux);
        if (host.isEmpty() || !aeroNativeHostCurrent(host.orElseThrow(), menu, opening.player(), entity,
                opening.player().level(), local) || !menu.stillValid(opening.player())) return Optional.empty();
        var owned = host.orElseThrow();
        var details = new Details(plan.local(), storage, owned.holder(), owned.wrapper(), stack, owned.uuid(), owned);
        var binding = new CreateMountedBinding(plan.opening(), plan.members(), owned.wrapper(), details,
                "sophisticatedcreate:0.1.23.246:" + owned.uuid(), mountedLabel(stack, state.getBlock()));
        return binding.target().isPresent() ? Optional.of(binding) : Optional.empty();
    }

    /**
     * r2 (review-01 M-2, M-4): what {@link #captureAero} binds, pure so its rules are unit-tested: the opening moved to
     * the main half ({@code local}, the menu's own position) with the clicked opening's player, entity and generation;
     * the members, main half first and then the second half of a double chest; and the details' position, the main
     * half. Null when the clicked half is neither half ({@link #clickedHalfAccepted}) or the second half is incomplete.
     */
    record AeroBindingPlan(CreateOpeningScope.Opening opening, List<CreateMountedBinding.Member> members, BlockPos local) {}

    static AeroBindingPlan aeroBindingPlan(CreateOpeningScope.Opening clicked, BlockPos local, BlockState state,
            MountedItemStorage storage, BlockPos auxLocal, BlockState auxState, MountedItemStorage aux) {
        if (clicked == null || local == null || state == null || storage == null
                || (auxLocal == null) != (aux == null) || (aux == null) != (auxState == null)
                || !clickedHalfAccepted(clicked.local(), local, auxLocal)) return null;
        BlockPos main = local.immutable();
        return new AeroBindingPlan(new CreateOpeningScope.Opening(clicked.player(), clicked.entity(), main,
                clicked.generation()), members(main, state, storage, auxLocal, auxState, aux), main);
    }

    /** r2 (review-01 M-4): the binding's members: the main half, then the second half of a double chest if any. */
    static List<CreateMountedBinding.Member> members(BlockPos local, BlockState state, MountedItemStorage storage,
            BlockPos auxLocal, BlockState auxState, MountedItemStorage aux) {
        List<CreateMountedBinding.Member> members = new ArrayList<>();
        members.add(new CreateMountedBinding.Member(local, state, storage));
        if (aux != null) members.add(new CreateMountedBinding.Member(auxLocal, auxState, aux));
        return List.copyOf(members);
    }

    /** r2 (review-01 M-4): the player clicked the opened storage or, for a double chest, its second half. */
    static boolean clickedHalfAccepted(BlockPos clicked, BlockPos local, BlockPos auxLocal) {
        return clicked != null && local != null && (clicked.equals(local) || auxLocal != null && clicked.equals(auxLocal));
    }

    /** SOPH-LABELS: the fallback-count sites of the two paths' mounted labels (never fail-safe sites). */
    static final String CURRENT_LABEL_SITE = "adapter:SophisticatedCreateMountedMenuAdapter.mountedLabel";
    static final String OLDER_LABEL_SITE = "adapter:SophisticatedCreateMountedMenuAdapter.capture.label";
    private static final String LABEL_OWNER = "Sophisticated Storage (Create mounted)";

    /**
     * SOPH-LABELS (the Owner's one name rule): the mounted storage's one name, the same the storage reads placed
     * ({@link SophisticatedStorageNames#forStack}, read from the stack the contraption holds): {@code Oak Iron Chest},
     * {@code Acacia Iron Chest} for no wood type, {@code Iron Chest (Oak Iron Chest)} fully tinted,
     * {@code Oak Iron Chest (renamed "Bank")}. Both addon paths, every tier, the barrel and the iron chest alike.
     */
    static String mountedLabel(ItemStack stack, Block block) {
        return mountedLabel(stack, block, CURRENT_LABEL_SITE);
    }

    static String mountedLabel(ItemStack stack, Block block, String site) {
        return mountedLabel(stack, block, site, (named, placed) -> SophisticatedStorageNames.forStack(named, placed));
    }

    /**
     * Test seam (review-01 MINOR-1, MINOR-6): the call site's own {@code catch (Throwable)}, so not even a helper that
     * fails to link reaches the adapter's guard. A throw, or a blank name, is counted as a name recorded with the
     * block's own name ({@link ContainerLabelFallbacks}) and gives the block's own name with the renamed part;
     * "Container" when nothing is readable. Never throws.
     */
    static String mountedLabel(ItemStack stack, Block block, String site, BiFunction<ItemStack, Block, String> namer) {
        try {
            String name = namer.apply(stack, block);
            if (name != null && !name.isBlank()) return name;
            ContainerLabelFallbacks.fellBack(LABEL_OWNER, site, null);
        } catch (Throwable failure) {
            ContainerLabelFallbacks.fellBack(LABEL_OWNER, site, failure);
        }
        try {
            return DisplayNameResolver.formatName(DisplayNameResolver.blockDisplayName(block), null,
                    DisplayNameResolver.RenameState.ofStack(stack));
        } catch (Throwable unreadable) {
            return "Container";
        }
    }
    /**
     * Build B soph-p2: an exact Sophisticated chest (single, or the main right half of a double chest) or barrel
     * block, whose stack is that block's own item. Replaces the F1-3b iron-chest-only check; the iron chest passes it.
     */
    static boolean currentStateSupported(BlockState state, ItemStack stack) {
        if (state == null || stack == null || stack.isEmpty() || declaredShape(state) == null) return false;
        return stack.getItem() instanceof BlockItem item && item.getBlock() == state.getBlock();
    }

    // This is the exact native graph used at capture and every subsequent boundary.
    // The live entity/map/pose and Iron Chest state are independently checked by the producer.
    // Build B F1-3b: both callers (current() and captureAero()) run only on Path.CURRENT, chosen by presence alone
    // since F1-4 (no artifact pin); this check reads no version itself.
    static boolean aeroNativeHostCurrent(AeroHost host, MountedStorageContainerMenu menu, Player player,
            AbstractContraptionEntity entity, Level level, BlockPos local) {
        return AeroNativeContract.current(host, menu, player, entity, level, local);
    }

    /**
     * Build B soph-p2: why a captured host is no longer recorded, or null while it is current. An installed upgrade is
     * named on its own (temporary until the upgrade policy lane); every other failed check is {@link #STORAGE_REASON}.
     */
    static String aeroNativeHostRefusal(AeroHost host, MountedStorageContainerMenu menu, Player player,
            AbstractContraptionEntity entity, Level level, BlockPos local) {
        if (AeroNativeContract.upgradesPresent(host)) return UPGRADES_REASON; // part-1 hook-up (r2 M-3, point 2 of 7)
        return aeroNativeHostCurrent(host, menu, player, entity, level, local) ? null : STORAGE_REASON;
    }

    /**
     * The captured native host. Build B soph-p2: {@code cells} and {@code upgradeCells} are the block's declared
     * counts at capture ({@link #declaredShape}); a double chest also carries its second (left) half, whose cells the
     * main half's handler holds.
     */
    record AeroHost(MountedSophisticatedStorage storage, MountedStorageHolder holder, IStorageWrapper wrapper,
            ItemStack stack, UUID uuid, InventoryHandler inventory, UpgradeHandler upgrades, int cells, int upgradeCells,
            BlockPos auxLocal, MountedSophisticatedStorage auxStorage, MountedStorageHolder auxHolder) {
        /** The F1-3b host: a single storage of 54 cells and 2 upgrade cells (the plain iron chest). */
        AeroHost(MountedSophisticatedStorage storage, MountedStorageHolder holder, IStorageWrapper wrapper,
                ItemStack stack, UUID uuid, InventoryHandler inventory, UpgradeHandler upgrades) {
            this(storage, holder, wrapper, stack, uuid, inventory, upgrades, IRON_CELLS, IRON_UPGRADE_CELLS, null, null, null);
        }
        boolean doubleChest() {
            return auxLocal != null;
        }
        int memberCount() {
            return doubleChest() ? 2 : 1;
        }
    }

    /**
     * In production reached only on Path.CURRENT (the current addon marker; no artifact pin since build B F1-4);
     * missing or inaccessible native state denies capture.
     */
    private static final class AeroNativeContract {
        private static final Class<?> FINITE = nativeClass("net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper$1");
        private static final Class<?> INVENTORY = nativeClass("net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper$2");
        private static final Class<?> UPGRADES = nativeClass("net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper$4");
        private static final Class<?> STORAGE_SLOT = nativeClass("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase$1");
        private static final Class<?> UPGRADE_SLOT = nativeClass("net.p3pp3rf1y.sophisticatedstoragecreateintegration.common.MountedStorageContainerMenu$1");
        private static final Field LOCAL_WRAPPER = nativeField(StorageHolderBase.class, "storageWrapper");
        private static final Field CLIENT_HOST = nativeField(StorageHolderBase.class, "clientLinkedStorageHost");
        private static final Field CLIENT_ENDPOINT = nativeField(StorageHolderBase.class, "clientLinkedStorageEndpoint");
        private static final Field HOLDER_ENTITY = nativeField(MountedStorageHolder.class, "contraptionEntityRef");
        private static final Field HOLDER_LEVEL = nativeField(MountedStorageHolder.class, "level");
        private static final Field HOLDER_LOCAL = nativeField(MountedStorageHolder.class, "localPos");
        private static final Field OTHER_WRAPPER = nativeField(MountedStorageHolder.class, "otherHalfStorageWrapper");
        /**
         * Build B soph-p2: the other half's local position (addon 0.1.23.246; {@code BlockPos.ZERO} on a single). It is
         * read only for a double chest and is not part of {@link #available()}: r2 (N-4), on a build without it every
         * double chest is refused with {@link #STORAGE_REASON} (that line names no member) while singles still record.
         */
        private static final Field CHEST_OTHER = nativeField(MountedStorageHolder.class, "chestOtherPartPos");
        private static final Field MENU_STORAGE = nativeField(MountedStorageContainerMenuBase.class, "mountedStorage");
        private static final Field MENU_PARENT = nativeField(StorageContainerMenuBase.class, "parentStorageWrapper");
        private static final Field OPENED_ITEM = nativeField(MountedStorageContainerMenu.class, "openedItem");
        private static final Field OPENED_ENDPOINT = nativeField(MountedStorageContainerMenu.class, "openedEndpoint");
        private static final Field OPENED_WRAPPER = nativeField(MountedStorageContainerMenu.class, "openedWrapper");
        private static final Field RAW_STACK = nativeField(MovingStorageWrapper.class, "storageStack");
        private static final Field RAW_INVENTORY = nativeField(MovingStorageWrapper.class, "inventoryHandler");
        private static final Field RAW_UPGRADES = nativeField(MovingStorageWrapper.class, "upgradeHandler");
        private static final Field INVENTORY_OWNER = nativeField(InventoryHandler.class, "storageWrapper");
        private static final Field INVENTORY_OUTER = nativeField(INVENTORY, "this$0");
        private static final Field UPGRADE_OWNER = nativeField(UpgradeHandler.class, "storageWrapper");
        private static final Field UPGRADE_WRAPPERS = nativeField(UpgradeHandler.class, "slotWrappers");
        private static final Method INSTALLED = nativeMethod(MountedStorageHolder.class, "getInstalledStorageItem");
        private static final Method ENDPOINT = nativeMethod(MountedStorageHolder.class, "getLinkedStorageEndpointData");
        private static final Method LINKED = nativeMethod(StorageHolderBase.class, "isLinkedStorage");
        private static final Supplier<?> ENDPOINT_COMPONENT = endpointComponent();

        private static Class<?> nativeClass(String name) {
            try { return Class.forName(name, false, MovingStorageWrapper.class.getClassLoader()); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }
        private static Field nativeField(Class<?> type, String name) {
            try {
                if (type == null) return null;
                var field = type.getDeclaredField(name);
                return field.trySetAccessible() ? field : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }
        private static Method nativeMethod(Class<?> type, String name) {
            try { return type.getMethod(name); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }
        private static Supplier<?> endpointComponent() {
            try {
                Object value = ModCoreDataComponents.class.getField("LINKED_STORAGE_ENDPOINT").get(null);
                return value instanceof Supplier<?> supplier ? supplier : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }
        private static boolean available() {
            return FINITE != null && INVENTORY != null && UPGRADES != null && STORAGE_SLOT != null && UPGRADE_SLOT != null && LOCAL_WRAPPER != null
                    && CLIENT_HOST != null && CLIENT_ENDPOINT != null && HOLDER_ENTITY != null && HOLDER_LEVEL != null
                    && HOLDER_LOCAL != null && OTHER_WRAPPER != null && MENU_STORAGE != null && MENU_PARENT != null
                    && OPENED_ITEM != null && OPENED_ENDPOINT != null && OPENED_WRAPPER != null && RAW_STACK != null
                    && RAW_INVENTORY != null && RAW_UPGRADES != null && INVENTORY_OWNER != null && INVENTORY_OUTER != null
                    && UPGRADE_OWNER != null && UPGRADE_WRAPPERS != null && INSTALLED != null && ENDPOINT != null
                    && LINKED != null && ENDPOINT_COMPONENT != null;
        }
        /**
         * Build B soph-p2: the host of one shape ({@link #declaredShape}); a double chest also names its second half,
         * which must be another exact mounted storage. Its holder is read here and checked at every boundary.
         */
        static Optional<AeroHost> capture(MountedSophisticatedStorage storage, IStorageWrapper wrapper, Shape shape,
                BlockPos auxLocal, MountedSophisticatedStorage aux) {
            try {
                if (!available() || storage == null || storage.getClass() != MountedSophisticatedStorage.class
                        || wrapper == null || wrapper.getClass() != FINITE || shape == null
                        || shape.doubleChest() != (auxLocal != null) || (auxLocal == null) != (aux == null)
                        || aux != null && (aux.getClass() != MountedSophisticatedStorage.class || aux == storage
                        || aux.getStorageHolder() == null)) return Optional.empty();
                var stack = storage.getStorageStack();
                UUID uuid = stack == null ? null : stack.get(ModCoreDataComponents.STORAGE_UUID);
                Object inventory = RAW_INVENTORY.get(wrapper), upgrades = RAW_UPGRADES.get(wrapper);
                if (uuid == null || inventory == null || inventory.getClass() != INVENTORY
                        || upgrades == null || upgrades.getClass() != UPGRADES) return Optional.empty();
                return Optional.of(new AeroHost(storage, storage.getStorageHolder(), wrapper, stack, uuid,
                        (InventoryHandler)inventory, (UpgradeHandler)upgrades, shape.cells(), shape.upgradeCells(),
                        auxLocal == null ? null : auxLocal.immutable(), aux, aux == null ? null : aux.getStorageHolder()));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE_CREATE,
                        "adapter:SophisticatedCreateMountedMenuAdapter.aeroCapture", unavailable);
                return Optional.empty();
            }
        }
        /**
         * Build B F1-4: the link state from the endpoint members {@link #current} reads first, in its order (the stack's
         * endpoint component, the holder's endpoint data and linked flag), then the menu's opened endpoint. A mounted
         * storage or holder that is not the addon's is UNLINKED here: the capture refuses it as before. A detection
         * member that is missing (a field or method this adapter could not resolve, or a component supplier of another
         * shape) is UNREADABLE, so the key goes OFF; one holder's flag that is not a Boolean is STATE_UNREADABLE, so
         * only that open is refused (build B F1-4 r2, m-1 and m-2).
         */
        static SophisticatedLinkedStorage.Linkage linkage(MountedStorageContainerMenu menu)
                throws ReflectiveOperationException {
            if (MENU_STORAGE == null || OPENED_ENDPOINT == null || ENDPOINT == null || LINKED == null
                    || ENDPOINT_COMPONENT == null || !(ENDPOINT_COMPONENT.get() instanceof DataComponentType<?> component)) {
                return SophisticatedLinkedStorage.Linkage.UNREADABLE;
            }
            if (MENU_STORAGE.get(menu) instanceof MountedSophisticatedStorage storage) {
                var linkage = storageLinkage(storage, component);
                if (linkage != SophisticatedLinkedStorage.Linkage.UNLINKED) return linkage;
            }
            return OPENED_ENDPOINT.get(menu) != null ? SophisticatedLinkedStorage.Linkage.LINKED
                    : SophisticatedLinkedStorage.Linkage.UNLINKED;
        }
        /**
         * Build B soph-p2: the link state of one mounted storage (the second half of a double chest), read as
         * {@link #linkage} reads the opened one: the stack's endpoint component, then the holder's endpoint data and
         * linked flag. A missing detection member is UNREADABLE (the key goes OFF), as in {@link #linkage}.
         */
        static SophisticatedLinkedStorage.Linkage storageLinkage(MountedSophisticatedStorage storage)
                throws ReflectiveOperationException {
            if (ENDPOINT == null || LINKED == null || ENDPOINT_COMPONENT == null
                    || !(ENDPOINT_COMPONENT.get() instanceof DataComponentType<?> component)) {
                return SophisticatedLinkedStorage.Linkage.UNREADABLE;
            }
            return storage == null ? SophisticatedLinkedStorage.Linkage.UNLINKED : storageLinkage(storage, component);
        }
        private static SophisticatedLinkedStorage.Linkage storageLinkage(MountedSophisticatedStorage storage,
                DataComponentType<?> component) throws ReflectiveOperationException {
            ItemStack stack = storage.getStorageStack();
            if (stack != null && stack.get(component) != null) return SophisticatedLinkedStorage.Linkage.LINKED;
            MountedStorageHolder holder = storage.getStorageHolder();
            if (holder != null) {
                if (ENDPOINT.invoke(holder) != null) return SophisticatedLinkedStorage.Linkage.LINKED;
                if (!(LINKED.invoke(holder) instanceof Boolean linkedFlag)) {
                    return SophisticatedLinkedStorage.Linkage.STATE_UNREADABLE;
                }
                if (linkedFlag) return SophisticatedLinkedStorage.Linkage.LINKED;
            }
            return SophisticatedLinkedStorage.Linkage.UNLINKED;
        }
        /**
         * Build B soph-p2: the captured host positively shows an installed upgrade (an item in an upgrade cell, or an
         * upgrade wrapper). False when it cannot tell; {@link #current} then decides. Never throws for a non-VM failure.
         */
        static boolean upgradesPresent(AeroHost host) {
            try {
                if (host == null || host.upgrades() == null) return false;
                for (int slot = 0; slot < host.upgrades().getSlots(); slot++) {
                    var stack = host.upgrades().getStackInSlot(slot);
                    if (stack != null && !stack.isEmpty()) return true;
                }
                return UPGRADE_WRAPPERS != null && UPGRADE_WRAPPERS.get(host.upgrades()) instanceof Map<?, ?> wrappers
                        && !wrappers.isEmpty();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
                return false;
            }
        }
        static boolean current(AeroHost host, MountedStorageContainerMenu menu, Player player,
                AbstractContraptionEntity entity, Level level, BlockPos local) {
            try {
                if (!available() || host == null || menu == null || player == null || entity == null || level == null || local == null
                        || menu.getClass() != MountedStorageContainerMenu.class || player.containerMenu != menu
                        || player.getInventory() == null || player.getInventory().player != player
                        || MENU_PLAYER.get(menu) != player || MENU_STORAGE.get(menu) != host.storage()
                        || MENU_PARENT.get(menu) != NoopStorageWrapper.INSTANCE
                        || menu.getContraptionEntity().orElse(null) != entity || !local.equals(menu.getLocalPos())
                        || host.storage() == null || host.storage().getClass() != MountedSophisticatedStorage.class
                        || host.holder() == null || host.holder().getClass() != MountedStorageHolder.class
                        || host.wrapper() == null || host.wrapper().getClass() != FINITE
                        || host.stack() == null || host.stack().isEmpty() || host.stack().getCount() != 1 || host.uuid() == null
                        || host.inventory() == null || host.inventory().getClass() != INVENTORY
                        || host.upgrades() == null || host.upgrades().getClass() != UPGRADES) return false;
                // Endpoint absence precedes every native getter capable of resolving a canonical remote host.
                if (!(ENDPOINT_COMPONENT.get() instanceof DataComponentType<?> component)
                        || host.stack().get(component) != null || ENDPOINT.invoke(host.holder()) != null
                        || !Boolean.FALSE.equals(LINKED.invoke(host.holder()))
                        || CLIENT_HOST.get(host.holder()) != null || CLIENT_ENDPOINT.get(host.holder()) != null
                        || OTHER_WRAPPER.get(host.holder()) != null || WoodStorageBlockItem.isPacked(host.stack())
                        || OPENED_ENDPOINT.get(menu) != null) return false;
                if (!(HOLDER_ENTITY.get(host.holder()) instanceof Reference<?> nativeEntity) || nativeEntity.get() != entity
                        || !(HOLDER_LEVEL.get(host.holder()) instanceof Reference<?> nativeLevel) || nativeLevel.get() != level
                        || !local.equals(HOLDER_LOCAL.get(host.holder())) || !host.holder().isMainStorage()
                        || host.holder().getMainStorageHolder() != host.holder()
                        || (host.doubleChest() ? !host.holder().isDoubleChest() || !auxCurrent(host, entity, level, local)
                        : host.holder().isDoubleChest() || host.holder().getAuxiliaryStorageHolder().isPresent())
                        || host.storage().getStorageHolder() != host.holder()
                        || host.storage().getStorageStack() != host.stack() || INSTALLED.invoke(host.holder()) != host.stack()
                        || RAW_STACK.get(host.wrapper()) != host.stack() || LOCAL_WRAPPER.get(host.holder()) != host.wrapper()
                        || OPENED_ITEM.get(menu) != host.stack().getItem() || OPENED_WRAPPER.get(menu) != host.wrapper()
                        || menu.getStorageWrapper() != host.wrapper() || host.storage().getStorageWrapper() != host.wrapper()
                        || host.holder().getStorageWrapper() != host.wrapper() || host.holder().getMainStorageWrapper() != host.wrapper()
                        || host.wrapper().getWrappedStorageStack() != host.stack()
                        || !host.uuid().equals(host.stack().get(ModCoreDataComponents.STORAGE_UUID))
                        || host.wrapper().getContentsUuid().filter(host.uuid()::equals).isEmpty()) return false;
                if (RAW_INVENTORY.get(host.wrapper()) != host.inventory() || RAW_UPGRADES.get(host.wrapper()) != host.upgrades()
                        || host.wrapper().getInventoryHandler() != host.inventory() || host.wrapper().getUpgradeHandler() != host.upgrades()
                        || INVENTORY_OWNER.get(host.inventory()) != host.wrapper() || INVENTORY_OUTER.get(host.inventory()) != host.wrapper()
                        || UPGRADE_OWNER.get(host.upgrades()) != host.wrapper() || host.cells() < 1 || host.upgradeCells() < 0
                        || host.inventory().getSlots() != host.cells() || host.upgrades().getSlots() != host.upgradeCells()
                        // part-1 hook-up (r2 M-3, point 3 of 7): no upgrade wrapper
                        || !(UPGRADE_WRAPPERS.get(host.upgrades()) instanceof Map<?, ?> wrappers) || !wrappers.isEmpty()) return false;
                // part-1 hook-up (r2 M-3, point 4 of 7): a stack upgrade raises the slot limit above 64 and an
                // infinity or creative upgrade makes a cell infinite (base limit 64: getBaseStackSizeMultiplier is 1
                // for chests and barrels).
                for (int slot = 0; slot < host.cells(); slot++) {
                    int limit = host.inventory().getSlotLimit(slot);
                    if (host.inventory().isInfinite(slot) || limit < 1 || limit > 64
                            || host.inventory().getStackInSlot(slot) == null) return false;
                }
                // part-1 hook-up (r2 M-3, point 5 of 7): every upgrade cell empty
                for (int slot = 0; slot < host.upgradeCells(); slot++) {
                    var stack = host.upgrades().getStackInSlot(slot);
                    if (stack == null || !stack.isEmpty()) return false;
                }
                return cellsCurrent(host, menu, player);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                AdapterFailSafe.report(AdapterKey.SOPHISTICATED_STORAGE_CREATE,
                        "adapter:SophisticatedCreateMountedMenuAdapter.aeroCurrent", unavailable);
                return false;
            }
        }
        /**
         * Build B soph-p2: the native menu holds exactly the host's cells, in Core's order (Core 1.5.17
         * {@code StorageContainerMenuBase}: every inventory cell, then the 36 player cells, then the upgrade cells).
         */
        private static boolean cellsCurrent(AeroHost host, MountedStorageContainerMenu menu, Player player) {
            int cells = host.cells();
            // part-1 hook-up (r2 M-3, point 6 of 7): upgrades with their own tab slots (crafting, stonecutter and the
            // like) add menu slots and extra slots, so this exact count holds for upgrade cells only. Point 7 of 7 is
            // the upgrade-tab slot roles in the shared SophisticatedMenuSlots (Part 1 owns that file).
            if (menu.slots.size() != cells + 36 || menu.upgradeSlots.size() != host.upgradeCells()
                    || !menu.getExtraSlots().isEmpty()) return false;
            for (int cell = 0; cell < cells; cell++) {
                Slot slot = menu.slots.get(cell);
                if (slot == null || slot.getClass() != STORAGE_SLOT || slot.index != cell
                        || slot.getContainerSlot() != cell || ((StorageInventorySlot)slot).getItemHandler() != host.inventory()) return false;
            }
            // Native current constructor uses main 9..35 then hotbar 0..8, with no locked item slot.
            for (int offset = 0; offset < 36; offset++) {
                Slot slot = menu.slots.get(cells + offset);
                int cell = offset < 27 ? offset + 9 : offset - 27;
                if (slot == null || slot.getClass() != Slot.class || slot.index != cells + offset
                        || slot.container != player.getInventory() || slot.getContainerSlot() != cell) return false;
            }
            for (int cell = 0; cell < host.upgradeCells(); cell++) {
                Slot slot = menu.upgradeSlots.get(cell);
                if (slot == null || slot.getClass() != UPGRADE_SLOT || slot.index != cells + 36 + cell
                        || slot.getContainerSlot() != cell
                        || ((StorageContainerMenuBase<?>.StorageUpgradeSlot)slot).getItemHandler() != host.upgrades()) return false;
            }
            return true;
        }

        /**
         * Build B soph-p2: the second (left) half of a captured double chest is still exactly the one the addon pairs
         * with the main half: both block entries and both storages unchanged on the live contraption, the left half's
         * holder not main, pointing back at the main half by position and by native lookup, on the same entity, level
         * and position, unlinked, unpacked, with no client host. Its own handler is never read (the menu shows the main
         * half's handler, which holds both halves' cells); a cached main wrapper on it must be the main half's.
         */
        private static boolean auxCurrent(AeroHost host, AbstractContraptionEntity entity, Level level, BlockPos local)
                throws ReflectiveOperationException {
            if (CHEST_OTHER == null || host.auxLocal() == null || host.auxStorage() == null || host.auxHolder() == null
                    || entity.getContraption() == null) return false;
            var contraption = entity.getContraption();
            var mainInfo = contraption.getBlocks().get(local);
            var auxInfo = contraption.getBlocks().get(host.auxLocal());
            if (mainInfo == null || auxInfo == null || mainInfo.state() == null || auxInfo.state() == null) return false;
            BlockState mainState = mainInfo.state(), auxState = auxInfo.state();
            if (mainState.getBlock().getClass() != ChestBlock.class || auxState.getBlock() != mainState.getBlock()
                    || !mainState.hasProperty(ChestBlock.TYPE) || !auxState.hasProperty(ChestBlock.TYPE)
                    || mainState.getValue(ChestBlock.TYPE) != ChestType.RIGHT || auxState.getValue(ChestBlock.TYPE) != ChestType.LEFT
                    || auxState.getValue(ChestBlock.FACING) != mainState.getValue(ChestBlock.FACING)
                    || !local.relative(ChestBlock.getConnectedDirection(mainState)).equals(host.auxLocal())
                    || !host.auxLocal().relative(ChestBlock.getConnectedDirection(auxState)).equals(local)) return false;
            var storages = contraption.getStorage().getAllItemStorages();
            MountedStorageHolder auxHolder = host.auxHolder();
            if (storages.get(local) != host.storage() || storages.get(host.auxLocal()) != host.auxStorage()
                    || host.auxStorage().getClass() != MountedSophisticatedStorage.class
                    || auxHolder.getClass() != MountedStorageHolder.class || host.auxStorage().getStorageHolder() != auxHolder
                    || auxHolder == host.holder() || auxHolder.isMainStorage() || !auxHolder.isDoubleChest()
                    || auxHolder.getMainStorageHolder() != host.holder()
                    || host.holder().getAuxiliaryStorageHolder().filter(found -> found == auxHolder).isEmpty()
                    || !host.auxLocal().equals(CHEST_OTHER.get(host.holder())) || !local.equals(CHEST_OTHER.get(auxHolder))
                    || !(HOLDER_ENTITY.get(auxHolder) instanceof Reference<?> auxEntity) || auxEntity.get() != entity
                    || !(HOLDER_LEVEL.get(auxHolder) instanceof Reference<?> auxLevel) || auxLevel.get() != level
                    || !host.auxLocal().equals(HOLDER_LOCAL.get(auxHolder))
                    || CLIENT_HOST.get(auxHolder) != null || CLIENT_ENDPOINT.get(auxHolder) != null) return false;
            ItemStack auxStack = host.auxStorage().getStorageStack();
            if (auxStack == null || auxStack.isEmpty() || auxStack.getCount() != 1 || WoodStorageBlockItem.isPacked(auxStack)
                    || !(auxStack.getItem() instanceof BlockItem auxItem) || auxItem.getBlock() != auxState.getBlock()
                    || !(ENDPOINT_COMPONENT.get() instanceof DataComponentType<?> component) || auxStack.get(component) != null
                    || ENDPOINT.invoke(auxHolder) != null || !Boolean.FALSE.equals(LINKED.invoke(auxHolder))) return false;
            Object cached = OTHER_WRAPPER.get(auxHolder);
            return cached == null || cached instanceof Reference<?> reference
                    && (reference.get() == null || reference.get() == host.wrapper());
        }
    }
    /** The linked-storage Core (1.5.1, LINKED_CORE) has a new linked-host constructor branch and upgrade quick-move paths. */
    private static boolean currentCoreHostSupported(Path path, AbstractContainerMenu menu, BlockState state,
            MountedStorageHolder holder, IStorageWrapper wrapper, ItemStack stack) {
        if (path == Path.LEGACY) return true; // The legacy exact family is unchanged.
        if (path != Path.LINKED_CORE) return false;
        if (state == null || holder == null || wrapper == null || stack == null
                || state.getBlock().getClass() != ChestBlock.class || !state.hasProperty(ChestBlock.TYPE)
                || !stack.is(state.getBlock().asItem())) return false;
        var inventory = wrapper.getInventoryHandler();
        var upgrades = wrapper.getUpgradeHandler();
        if (inventory == null || upgrades == null || inventory.getSlots() != 54 || upgrades.getSlots() != 2) return false;
        boolean emptyUpgrades = true;
        for (int slot = 0; slot < 2; slot++) {
            if (!upgrades.getStackInSlot(slot).isEmpty()) { emptyUpgrades = false; break; }
        }
        boolean linked;
        try {
            linked = Class.forName("net.p3pp3rf1y.sophisticatedcore.linkedstorage.ILinkedStorageEndpointProvider",
                    false, wrapper.getClass().getClassLoader()).isInstance(wrapper);
        } catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) { return false; }
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (linked && currentCoreSimpleIronForm(path, blockId, state.getValue(ChestBlock.TYPE), holder.isDoubleChest(),
                inventory.getSlots(), upgrades.getSlots(), emptyUpgrades, false)) {
            // Build B F1-4 (SophisticatedLinkedStorage; temporary until Owner option B): the admitted chest, but
            // linked. Still not captured, as before (nothing recorded; the storage still opens for the player), now with
            // one warning per server start; the key stays ON.
            SophisticatedLinkedStorage.refused(AdapterKey.SOPHISTICATED_STORAGE_CREATE, LINKED_SITE,
                    SophisticatedLinkedStorage.Linkage.LINKED, null);
            // r2 (open question 5): the caller's storage refusal now warns; this open already has the linked line.
            linkedRefusedMenu(menu);
            return false;
        }
        return currentCoreSimpleIronForm(path, blockId, state.getValue(ChestBlock.TYPE),
                holder.isDoubleChest(), inventory.getSlots(), upgrades.getSlots(), emptyUpgrades, linked);
    }
    static boolean currentCoreSimpleIronForm(Path path, String blockId, ChestType type,
            boolean doubleChest, int inventorySlots, int upgradeSlots, boolean emptyUpgrades, boolean linked) {
        return path == Path.LEGACY || path == Path.LINKED_CORE
                && CURRENT_IRON_CHEST.equals(blockId)
                && type == ChestType.SINGLE && !doubleChest && inventorySlots == 54 && upgradeSlots == 2
                && emptyUpgrades && !linked;
    }
    @Override public Optional<ContainerTarget> resolveMenuBackingTarget(MenuAdapterContext context) {
        return current(context).flatMap(CreateMountedBinding::target);
    }
    @Override public Optional<MenuAccessTarget> resolveContainerAccessTarget(MenuAdapterContext context) {
        return current(context).flatMap(binding -> binding.target().map(target -> new MenuAccessTarget(target, binding.targetId())));
    }
    @Override public Optional<BooleanSupplier> captureMovingAccessGuard(MenuAdapterContext context) {
        return current(context).map(binding -> binding.guard(() -> current(context).orElse(null) == binding));
    }
    @Override public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        return current(context).flatMap(binding -> describeBoundSlots((MountedStorageContainerMenu)context.menu(),
                context.player().getInventory(), binding));
    }
    static Optional<MenuPhysicalBacking.Description> describeBoundSlots(MountedStorageContainerMenu menu,
            net.minecraft.world.entity.player.Inventory playerInventory, Object owner) {
        return SophisticatedMenuSlots.describe(menu, playerInventory, owner, List.of(owner), true);
    }
    @Override public InventorySlotRole classifySlot(MenuAdapterContext context, int index, Slot slot) {
        return context == null || context.player() == null || !(context.menu() instanceof MountedStorageContainerMenu menu)
                ? InventorySlotRole.UNKNOWN : SophisticatedMenuSlots.classify(menu, context.player().getInventory(), slot);
    }
    @Override public List<MenuObservedSlot> additionalObservedSlots(MenuAdapterContext context) {
        return context != null && context.menu().getClass() == MountedStorageContainerMenu.class
                ? SophisticatedMenuSlots.additional((MountedStorageContainerMenu)context.menu()) : List.of();
    }
    private static Field field(Class<?> type, String name) {
        try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Mounted Sophisticated contract unavailable", failure); }
    }
    private record Details(net.minecraft.core.BlockPos local, MountedSophisticatedStorage storage,
            MountedStorageHolder holder, IStorageWrapper wrapper, ItemStack stack, UUID uuid, AeroHost aero) {}
}
