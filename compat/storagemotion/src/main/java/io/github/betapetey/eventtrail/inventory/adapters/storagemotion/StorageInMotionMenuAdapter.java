package io.github.betapetey.eventtrail.inventory.adapters.storagemotion;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedCoreMarkers;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedLinkedStorage;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedMenuSlots;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedStorageMarkers;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageEpoch;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageMotion;
import io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader;
import io.github.betapetey.eventtrail.inventory.spi.*;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents;
import net.p3pp3rf1y.sophisticatedcore.util.NoopStorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.entity.StorageHolderBase;
import net.p3pp3rf1y.sophisticatedstorage.item.BarrelBlockItem;
import net.p3pp3rf1y.sophisticatedstorage.item.ChestBlockItem;
import net.p3pp3rf1y.sophisticatedstorage.item.ShulkerBoxItem;
import net.p3pp3rf1y.sophisticatedstorageinmotion.common.gui.MovingLimitedBarrelContainerMenu;
import net.p3pp3rf1y.sophisticatedstorageinmotion.common.gui.MovingStorageContainerMenu;
import net.p3pp3rf1y.sophisticatedstorageinmotion.entity.EntityStorageHolder;
import net.p3pp3rf1y.sophisticatedstorageinmotion.entity.IMovingStorageEntity;
import net.p3pp3rf1y.sophisticatedstorageinmotion.entity.StorageBoat;
import net.p3pp3rf1y.sophisticatedstorageinmotion.entity.StorageMinecart;
import net.p3pp3rf1y.sophisticatedstorageinmotion.network.RequestMovingStorageInventoryContentsPayload;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Exact cart/boat hosts for Core's bounded finite custody; no generic entity inventory discovery.
 *
 * <p>No version is read (build B F1-3b, R1d/Row A): the behaviour path is chosen at first use by what is present
 * ({@link #selection()}). No artifact is checked either (build B F1-4): the exact artifact pin that F1-3b kept as the
 * last condition of every recording path is gone, so a build that carries a path's markers records on that path
 * with the fixed provider id.</p>
 *
 * <p>Build B motion-01 (Owner, received by 11:03 AM ET 2026-11-14): Motion 0.10.38.395 (the linked-storage holder
 * build, on Storage 1.6 and Core 1.5) records UNLINKED carts and boats on the CURRENT path, with the same row as the
 * older build (the entity's position, ENTITY_BOUND, the same label rule). A linked cart or boat is refused: nothing in
 * it is recorded, the storage opens normally for the player, and one warning per server start says so
 * ({@link SophisticatedLinkedStorage}). Mixed markers stay OFF, never an older path. r2: the same refusal, with its
 * own reason, when the opened storage is not the cart's own ({@code ownStorageRefused}); and, until Sophisticated
 * part 1's upgrade policy lands, CURRENT records only an iron chest with no upgrade, as LINKED_CORE
 * ({@code kindRefused}). r3: on every path, a per-object check that refuses a capture is counted with one warning per
 * server start per reason ({@code OBJECT_SITE}); it still gives no row. motion-02: LINKED_CORE's own restriction
 * (an unlinked plain iron chest minecart only) is counted and named too, with the kind reason
 * ({@code currentCoreHostSupported}); it was silent at match.</p>
 */
public final class StorageInMotionMenuAdapter implements MenuTransactionAdapter {
    /** Provider id is row data, byte for byte: Motion 0.10.34.358 (on Core 1.4.90 and 1.5.1). */
    static final String ADAPTER_ID = "storagemotion-0.10.34.358";
    /** Provider id is row data, byte for byte: Motion 0.10.38.395 (CURRENT; unit-gated on Storage 1.6.16 / Core 1.5.32 and 1.6.15 / 1.5.25). */
    static final String CURRENT_ADAPTER_ID = "storagemotion-0.10.38.395";
    /** The build part of the row's internal key on the older paths (unchanged from HEAD). */
    static final String KEY_VERSION = "0.10.34.358";
    /** The build part of the row's internal key on CURRENT. */
    static final String CURRENT_KEY_VERSION = "0.10.38.395";
    /** Never in a row: an OFF path matches nothing it could record. Keeps the loader's id prefix. */
    static final String OFF_ADAPTER_ID = "storagemotion-off";
    static final String PATH_SITE = "adapter:StorageInMotionMenuAdapter.path";
    /**
     * Build B F1-4: where the admitted LINKED_CORE cart is refused when it is linked (SophisticatedLinkedStorage).
     * Build B motion-01: also where a linked CURRENT cart or boat is refused.
     */
    static final String LINKED_SITE = "adapter:StorageInMotionMenuAdapter.linked";
    /** Build B motion-01: where a CURRENT per-object read that failed is reported. */
    static final String CURRENT_SITE = "adapter:StorageInMotionMenuAdapter.current";
    /** Build B motion-01 r2: where a CURRENT open whose storage is not the cart's own is refused. */
    static final String OWN_STORAGE_SITE = "adapter:StorageInMotionMenuAdapter.ownStorage";
    /** The warning's reason: the storage opened in the cart or boat changed, or is not the cart's or boat's own. */
    static final String OWN_STORAGE_REASON = "cart-storage-changed-or-not-its-own";
    /**
     * Build B motion-01 r2: where a CURRENT storage kind or upgrade set that is not recorded yet is refused. motion-02:
     * also where LINKED_CORE refuses anything but an unlinked plain iron chest minecart, and where its read that failed
     * is reported.
     */
    static final String KIND_SITE = "adapter:StorageInMotionMenuAdapter.kind";
    /**
     * The warning's reason: only an iron chest with no upgrades is recorded on CURRENT for now; on LINKED_CORE only an
     * unlinked plain iron chest minecart (motion-02).
     */
    static final String KIND_REASON = "storage-kind-or-upgrades-not-recorded-yet";
    /**
     * Build B motion-01 r3 (lead decision on review-01 MINOR-3, under the Owner's never-silent rule): where a per-object
     * check refuses a capture, on CURRENT and on the older paths alike. Each reason below writes one warning per server
     * start; every refusal is counted. Nothing in that capture is recorded, as before.
     */
    static final String OBJECT_SITE = "adapter:StorageInMotionMenuAdapter.perObject";
    /** The menu's own player is not the acting player, or that player is no longer live in its level. */
    static final String PLAYER_REASON = "menu-player-not-the-acting-player";
    /**
     * The cart or boat is gone, in another level, or not found by id and UUID; or the menu is no longer valid for the
     * player (Motion's own stillValid: out of reach, packed, or its opened storage changed).
     */
    static final String CART_REASON = "cart-gone-or-out-of-reach";
    /** The cart's or boat's position is not finite, or it is not at rest in an ordinary world frame. */
    static final String POSITION_REASON = "cart-position-unusable";
    /** The holder, menu, opened and own wrappers are not one finite Storage wrapper, it is packed, or extra slots. */
    static final String HOLDER_REASON = "cart-holder-or-wrapper-mismatch";
    /** The installed and wrapped storage stacks, their item, contents id or barrel kind disagree. */
    static final String STACK_REASON = "cart-storage-stack-mismatch";
    /** No valid motion state for the cart or boat (the entity epoch EventTrail keeps). */
    static final String EPOCH_REASON = "cart-motion-state-unknown";
    /** CURRENT marker: Motion's own linked-storage holder getter (0.10.38.395; absent from 0.10.34.358). */
    static final String NEWER_MARKER = "EntityStorageHolder.getLinkedStorageEndpointData()";
    /** Older-path marker: the 0.10.34.358 inventory-contents request's UUID-only record constructor. */
    static final String OLDER_MARKER = "RequestMovingStorageInventoryContentsPayload(UUID)";
    static final String CONCEPT_LOOKED_FOR = OLDER_MARKER + " (Motion 0.10.34) without " + NEWER_MARKER;
    /** Mixed shapes: the CURRENT marker without the rest of its generation (at most 160 characters). */
    static final String CURRENT_UNPAIRED_LOOKED_FOR = NEWER_MARKER + " with " + SophisticatedStorageMarkers.LINKED_STORAGE_FIELD
            + ", without the Motion 0.10.34 marker or the Core pre-linked factory";
    /** The CURRENT per-object members ({@link CurrentContract}); missing ones switch the key OFF (at most 160). */
    static final String CURRENT_LOOKED_FOR = "EntityStorageHolder.getLinkedStorageEndpointData()/getInstalledStorageItem(),"
            + " menu openedWrapper/getLinkedStorageGroupId(), Storage/Core linked-storage members";
    static final String STORAGE_UNPAIRED_LOOKED_FOR = SophisticatedStorageMarkers.STORAGE_1_5_91_MARKER + " without "
            + SophisticatedStorageMarkers.LINKED_STORAGE_FIELD + " (Storage 1.5.91) under Motion 0.10.34; that Storage is untested";
    static final String CORE_UNPAIRED_LOOKED_FOR = SophisticatedCoreMarkers.PRE_LINKED_REPOSITORY
            + " (Core 1.4.90 or 1.5.1) under Motion 0.10.34; that Core is untested";
    /** Used only when building a selection failed itself (for example memory ran out); never cached. */
    private static final Selection UNPROBED = new Selection(Path.CONCEPT_UNUSABLE, OFF_ADAPTER_ID,
            SophisticatedCoreMarkers.PROBE_FAILED + "unknown", null, false);
    private static volatile Selection selected;
    private static final String CURRENT_IRON_CHEST = "sophisticatedstorage:iron_chest";
    private static final Field MENU_PLAYER = nativePlayerField();
    private final Predicate<Entity> physicalFrame;
    private final Function<Entity, MovingStorageEpoch> motion;
    private final Supplier<Selection> paths;

    /**
     * The behaviour path, chosen by what is present (build B R1d/Row A). CURRENT, LINKED_CORE and LEGACY record; the
     * other two switch the key OFF at first use with one warning naming what was looked for.
     */
    enum Path {
        /**
         * Build B motion-01: Motion 0.10.38 (its linked-storage holder, no older marker) with Storage 1.6 (Storage's
         * own linked-storage field) and a Core without the pre-linked factory. Unlinked carts and boats only; a
         * linked one is refused with one warning per server start.
         */
        CURRENT,
        /**
         * Motion 0.10.34 (older marker, no linked-storage holder) with Storage 1.5.91 and a Core that has the
         * linked-storage endpoint interface, the pre-linked factory and the reviewed-linked-Core marker (Core 1.5.1;
         * {@link #reviewedLinkedCoreOnly}). Core 1.5.5, 1.5.12 and 1.5.14 have the first two only and are OFF.
         */
        LINKED_CORE,
        /** Motion 0.10.34 with Storage 1.5.91 and a Core with the pre-linked factory and no linked storage. */
        LEGACY,
        /**
         * Present but unusable: Motion's linked-storage holder without the rest of its generation (mixed shapes), the
         * older marker with an untested Storage or Core, or a probe that failed. Never a fall-back to a laxer path.
         */
        CONCEPT_UNUSABLE,
        /** No Motion marker: the build cannot be placed. */
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
        static Selection recording(Path path) {
            return new Selection(path, path == Path.CURRENT ? CURRENT_ADAPTER_ID : ADAPTER_ID, null, null, true);
        }

        static Selection off(Path path, String offDetail) {
            return new Selection(path, OFF_ADAPTER_ID, offDetail, null, true);
        }
    }

    public StorageInMotionMenuAdapter() {
        this(StorageInMotionMenuAdapter::physicalFrame,
                entity -> entity instanceof MovingStorageMotion moving ? moving.eventtrail$movingStorageEpoch() : null,
                StorageInMotionMenuAdapter::selection);
    }

    /**
     * Isolated actual-class tests replace only world-frame/mixin infrastructure. This seam probes presence uncached,
     * the same rule as production, so it never writes the process-wide path cache.
     */
    StorageInMotionMenuAdapter(Predicate<Entity> physicalFrame, Function<Entity, MovingStorageEpoch> motion) {
        this(physicalFrame, motion, StorageInMotionMenuAdapter::probe);
    }

    /**
     * The path choice is given: production passes {@link #selection()}, the isolated seam above an uncached presence
     * probe, and the OFF-gate tests a fixed choice (Advisor M-1).
     */
    StorageInMotionMenuAdapter(Predicate<Entity> physicalFrame, Function<Entity, MovingStorageEpoch> motion,
            Supplier<Selection> paths) {
        this.physicalFrame = java.util.Objects.requireNonNull(physicalFrame);
        this.motion = java.util.Objects.requireNonNull(motion);
        this.paths = java.util.Objects.requireNonNull(paths);
    }

    /**
     * Path rule over the six probes, each read only when needed. Motion's linked-storage holder getter (0.10.38)
     * selects CURRENT only with the rest of its generation: the older marker absent, Storage's own linked-storage
     * field present (Storage 1.6) and the Core pre-linked factory absent (build B motion-01). With anything else it is
     * OFF (mixed shapes), never an older path; the Storage 1.5.91 marker and the Core linked-storage interface are not
     * read for it. The older paths need the older marker, the Storage 1.5.91 generation (its marker present,
     * Storage's own linked-storage field absent) and the Core pre-linked factory (present in Core 1.4.86, 1.4.90 and
     * 1.5.1, and also in the unreviewed 1.4.87, 1.5.5, 1.5.12 and 1.5.14); the Core linked-storage interface, which
     * the LINKED_CORE path itself checks, then picks LINKED_CORE over LEGACY, and {@link #probe()} keeps a LINKED_CORE
     * choice only with the reviewed-linked-Core marker ({@link #reviewedLinkedCoreOnly}). No artifact pin is read
     * (build B F1-4). A probe that throws is present-but-unusable: OFF naming the failure, cached unless it is a
     * {@link VirtualMachineError}.
     */
    static Selection choose(BooleanSupplier newerMarker, BooleanSupplier olderMarker,
            BooleanSupplier storageLinkedField, BooleanSupplier storage1591Marker, BooleanSupplier linkedCoreMarker,
            BooleanSupplier preLinkedCoreMarker) {
        try {
            if (newerMarker.getAsBoolean()) {
                return !olderMarker.getAsBoolean() && storageLinkedField.getAsBoolean()
                        && !preLinkedCoreMarker.getAsBoolean()
                        ? Selection.recording(Path.CURRENT)
                        : Selection.off(Path.CONCEPT_UNUSABLE, CURRENT_UNPAIRED_LOOKED_FOR);
            }
            if (!olderMarker.getAsBoolean()) return Selection.off(Path.CONCEPT_NOT_FOUND, CONCEPT_LOOKED_FOR);
            if (storageLinkedField.getAsBoolean() || !storage1591Marker.getAsBoolean()) {
                return Selection.off(Path.CONCEPT_UNUSABLE, STORAGE_UNPAIRED_LOOKED_FOR);
            }
            if (!preLinkedCoreMarker.getAsBoolean()) return Selection.off(Path.CONCEPT_UNUSABLE, CORE_UNPAIRED_LOOKED_FOR);
            return Selection.recording(linkedCoreMarker.getAsBoolean() ? Path.LINKED_CORE : Path.LEGACY);
        } catch (Throwable failed) {
            return new Selection(Path.CONCEPT_UNUSABLE, OFF_ADAPTER_ID, SophisticatedCoreMarkers.probeFailed(failed),
                    failed, !(failed instanceof VirtualMachineError));
        }
    }

    /** Probed at first use (presence only); cached unless the choice was a VM error. Never throws. */
    static Selection selection() {
        try {
            return cachedOr(selected, StorageInMotionMenuAdapter::probe, chosen -> selected = chosen);
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
     * LEGACY. Every other choice, CURRENT included, is returned as it is, and the marker is not read for it. A marker
     * probe that throws is OFF naming the failure, cached unless it is a {@link VirtualMachineError}.
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
        return reviewedLinkedCoreOnly(choose(StorageInMotionMenuAdapter::newerMarkerPresent,
                StorageInMotionMenuAdapter::olderMarkerPresent,
                SophisticatedStorageMarkers::linkedStorageFieldPresent,
                SophisticatedStorageMarkers::storage1591MarkerPresent,
                SophisticatedCoreMarkers::linkedEndpointProviderPresent,
                SophisticatedCoreMarkers::preLinkedRepositoryPresent),
                SophisticatedCoreMarkers::reviewedLinkedCoreMarkerPresent);
    }

    /**
     * A cached OFF choice switches the key OFF until restart with its detail. An uncached one (VM error) is
     * reported instead: the fail-safe's classifier sorts it (stack/memory errors are transient), so it never
     * locks the key OFF.
     */
    static Path switchOffUnlessRecording(Selection selection) {
        if (selection.path().records()) return selection.path();
        if (selection.cacheable()) {
            AdapterFailSafe.conceptNotFound(AdapterKey.STORAGE_IN_MOTION, PATH_SITE, selection.offDetail());
        } else {
            AdapterFailSafe.report(AdapterKey.STORAGE_IN_MOTION, PATH_SITE, selection.failure());
        }
        return selection.path();
    }

    /** This adapter's path for recording work; a non-recording path switches the key OFF (idempotent, one warning). */
    private Path recordingPath() {
        return switchOffUnlessRecording(paths.get());
    }

    /**
     * Motion's entity holder declares the linked-storage endpoint getter (0.10.38.395; absent from 0.10.34.358): the
     * CURRENT marker. Declared, not inherited. Only "not there" is false; any other failure propagates to the path
     * choice (OFF).
     */
    static boolean newerMarkerPresent() {
        try {
            return EntityStorageHolder.class.getDeclaredMethod("getLinkedStorageEndpointData") != null;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    /** The request payload declares the UUID-only record constructor (0.10.34.358; 0.10.38.395 adds an item reference). */
    static boolean olderMarkerPresent() {
        try {
            return RequestMovingStorageInventoryContentsPayload.class.getDeclaredConstructor(UUID.class) != null;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    @Override public String adapterId() { return paths.get().adapterId(); }
    @Override public boolean exact() { return true; }
    @Override public boolean usesHandlerBackedView() { return true; }
    @Override public boolean usesIndependentBackingTarget() { return true; }
    @Override public boolean usesMovingBackingTarget() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.ACTION_SCOPED_PHYSICAL; }

    @Override public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || context.menu() == null) return AdapterMatch.NO_MATCH;
        if (!(context.menu() instanceof MovingStorageContainerMenu<?> menu)) {
            return OptionalMenuAdapterLoader.isStorageInMotionMenu(context.menu().getClass())
                    ? AdapterMatch.AMBIGUOUS : AdapterMatch.NO_MATCH;
        }
        // An OFF path still owns its menus: blocked, never handed to the generic fallback.
        Path path = recordingPath();
        if (!path.records()) return AdapterMatch.AMBIGUOUS;
        if (path == Path.CURRENT) return matchCurrent(CurrentContract.production(), menu);
        return exactMenu(menu) && menu.getStorageEntity().filter(StorageInMotionMenuAdapter::exactEntity)
                .filter(entity -> currentCoreHostSupported(path, menu, entity)).isPresent()
                ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
    }

    /**
     * Build B motion-01: CURRENT's match. The exact menus and the exact cart and boat classes only. Refused (AMBIGUOUS,
     * so nothing in that open is recorded and the generic logger does not take it; the storage still opens normally
     * for the player), each with one warning per server start and every later refusal counted: a linked one
     * ({@link #linkedRefused}); one whose opened storage is not the cart's own ({@link #ownStorageRefused}, r2); and,
     * until Sophisticated part 1's upgrade policy lands, any storage kind or upgrade set but an iron chest with no
     * upgrades ({@link #kindRefused}, r2, the LINKED_CORE restriction). Read at every match, so a cart linked or
     * changed while its menu is open is refused at the next capture. Never throws.
     */
    static AdapterMatch matchCurrent(CurrentContract contract, MovingStorageContainerMenu<?> menu) {
        if (menu == null || !exactMenu(menu)) return AdapterMatch.AMBIGUOUS;
        Entity entity = menu.getStorageEntity().orElse(null);
        if (!exactEntity(entity)) return AdapterMatch.AMBIGUOUS;
        return linkedRefused(contract, menu, entity) || ownStorageRefused(contract, menu, entity)
                || kindRefused(menu, entity) ? AdapterMatch.AMBIGUOUS : AdapterMatch.SUPPORTED;
    }

    /**
     * Build B motion-01 r2: true when the menu's opened storage is not the cart's or boat's own wrapper (its storage
     * item was replaced while the menu was open, or the opened storage belongs to something else). Refused with its
     * own reason ({@link #OWN_STORAGE_REASON}), one warning per server start, every later refusal counted; the key
     * stays ON. A read that fails is reported and refuses that open. Reached only after {@link #linkedRefused} found
     * every member. Never throws.
     */
    static boolean ownStorageRefused(CurrentContract contract, MovingStorageContainerMenu<?> menu, Entity entity) {
        boolean foreign;
        try {
            foreign = contract != null && contract.openedNotOwn(menu, entity);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.STORAGE_IN_MOTION, OWN_STORAGE_SITE, failure);
            return true;
        }
        if (foreign) AdapterFailSafe.refusedOnce(AdapterKey.STORAGE_IN_MOTION, OWN_STORAGE_SITE, OWN_STORAGE_REASON);
        return foreign;
    }

    /**
     * Build B motion-01 r2 (lead's decision 4): CURRENT records only what LINKED_CORE records, an iron chest of 54 cells
     * and 2 upgrade slots with no upgrade installed, in the plain menu (carts and boats both: the Owner's decision names
     * both), until Sophisticated part 1's upgrade policy lands; Motion CURRENT and LEGACY then switch to part 1's
     * allowlist in a later integration step. Anything else is refused with its own reason ({@link #KIND_REASON}), one
     * warning per server start, every later refusal counted; never a row. A read that fails is reported and refuses
     * that open. Never throws.
     */
    static boolean kindRefused(MovingStorageContainerMenu<?> menu, Entity entity) {
        boolean admitted;
        try {
            if (!(entity instanceof IMovingStorageEntity moving)
                    || !(menu.getStorageWrapper() instanceof MovingStorageWrapper wrapper)) {
                // r3 (review-01 MINOR-3): not a Motion cart and wrapper is refused here, counted, with the per-object
                // holder reason (it was SUPPORTED with a silent empty anchor).
                AdapterFailSafe.refusedOnce(AdapterKey.STORAGE_IN_MOTION, OBJECT_SITE, HOLDER_REASON);
                return true;
            }
            ItemStack stack = moving.getStorageItem();
            var inventory = wrapper.getInventoryHandler();
            var upgrades = wrapper.getUpgradeHandler();
            boolean emptyUpgrades = upgrades != null;
            for (int slot = 0; emptyUpgrades && slot < upgrades.getSlots(); slot++) {
                emptyUpgrades = upgrades.getStackInSlot(slot).isEmpty();
            }
            admitted = stack != null && !stack.isEmpty() && stack.getItem().getClass() == ChestBlockItem.class
                    && inventory != null && upgrades != null
                    && currentKindAdmitted(menu.getClass(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                            inventory.getSlots(), upgrades.getSlots(), emptyUpgrades);
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.STORAGE_IN_MOTION, KIND_SITE, failure);
            return true;
        }
        if (!admitted) AdapterFailSafe.refusedOnce(AdapterKey.STORAGE_IN_MOTION, KIND_SITE, KIND_REASON);
        return !admitted;
    }

    /** The storage-kind and upgrade part of {@link #currentCoreSimpleIronCart}, for CURRENT carts and boats. Pure. */
    static boolean currentKindAdmitted(Class<?> menuClass, String itemId, int inventorySlots, int upgradeSlots,
            boolean emptyUpgrades) {
        return menuClass == MovingStorageContainerMenu.class && CURRENT_IRON_CHEST.equals(itemId)
                && inventorySlots == 54 && upgradeSlots == 2 && emptyUpgrades;
    }

    /**
     * Build B motion-01 (Owner, received by 11:03 AM ET 2026-11-14: block linked carts and boats with one warning per
     * server start): true when the CURRENT cart or boat is linked, or its link state cannot be read. LINKED and a
     * value of another shape refuse that open with the one warning ({@link AdapterFailSafe#refusedOnce}); the key stays
     * ON. A missing member switches the key OFF naming {@link #CURRENT_LOOKED_FOR}. Never throws.
     */
    static boolean linkedRefused(CurrentContract contract, MovingStorageContainerMenu<?> menu, Entity entity) {
        return SophisticatedLinkedStorage.refusedOpen(AdapterKey.STORAGE_IN_MOTION, LINKED_SITE, CURRENT_LOOKED_FOR,
                () -> contract == null ? SophisticatedLinkedStorage.Linkage.UNREADABLE : contract.linkage(menu, entity));
    }

    /**
     * The linked-storage Core's (LINKED_CORE) linked-host and upgrade-crafting paths have no Motion admission: only an
     * unlinked plain iron chest minecart (54 cells, 2 empty upgrade slots, Storage's own finite wrapper) is recorded.
     * Build B motion-02 (motion-01 re-check-01, named follow-up of check 5): every other exact Motion menu and cart or
     * boat is refused with the kind reason ({@link #KIND_REASON} at {@link #KIND_SITE}), one warning per server start,
     * every refusal counted, as CURRENT's {@link #kindRefused}: a boat, a limited barrel's menu, another storage kind, a
     * packed cart, another holder or wrapper, extra slots, another size, an installed upgrade. A linked plain iron cart
     * keeps its own linked line instead (F1-4). Read at every match, so a cart that stops being admitted while its menu
     * is open (an upgrade goes in) is refused, no longer silently, from the next capture on. A read that fails is
     * reported ({@link AdapterFailSafe#report} at {@link #KIND_SITE}) and refuses. A null menu or entity is not a
     * refusal ({@link #match} never passes one). The key stays ON and the player's menu stays open. Never throws for a
     * non-VM failure.
     */
    static boolean currentCoreHostSupported(Path path, MovingStorageContainerMenu<?> menu, Entity entity) {
        if (path == Path.LEGACY) return true; // Existing legacy match/current checks remain authoritative.
        if (path != Path.LINKED_CORE) return false;
        if (menu == null || entity == null) return false;
        try {
            if (menu.getClass() != MovingStorageContainerMenu.class || entity.getClass() != StorageMinecart.class) {
                return linkedCoreKindRefused();
            }
            var moving = (IMovingStorageEntity) entity;
            var holder = moving.getStorageHolder();
            ItemStack stack = moving.getStorageItem();
            if (holder == null || holder.getClass() != EntityStorageHolder.class || holder.isPacked()
                    || !supportedItem(stack) || stack.getItem().getClass() != ChestBlockItem.class
                    || !(menu.getStorageWrapper() instanceof MovingStorageWrapper wrapper)
                    || !wrapper.getClass().getName().equals("net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper$1")
                    || holder.getStorageWrapper() != wrapper || !menu.getExtraSlots().isEmpty()) {
                return linkedCoreKindRefused();
            }
            var inventory = wrapper.getInventoryHandler();
            var upgrades = wrapper.getUpgradeHandler();
            if (inventory == null || upgrades == null || inventory.getSlots() != 54 || upgrades.getSlots() != 2) {
                return linkedCoreKindRefused();
            }
            boolean emptyUpgrades = upgrades.getStackInSlot(0).isEmpty() && upgrades.getStackInSlot(1).isEmpty();
            boolean linked = Class.forName("net.p3pp3rf1y.sophisticatedcore.linkedstorage.ILinkedStorageEndpointProvider",
                    false, wrapper.getClass().getClassLoader()).isInstance(wrapper);
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (linked && currentCoreSimpleIronCart(path, menu.getClass(), entity.getClass(), itemId,
                    inventory.getSlots(), upgrades.getSlots(), emptyUpgrades, false)) {
                // Build B F1-4 (SophisticatedLinkedStorage; temporary until Owner option B): the admitted cart, but
                // linked. Still not taken, as before (nothing recorded; the cart's storage still opens for the
                // player), now with one warning per server start; the key stays ON.
                SophisticatedLinkedStorage.refused(AdapterKey.STORAGE_IN_MOTION, LINKED_SITE,
                        SophisticatedLinkedStorage.Linkage.LINKED, null);
                return false;
            }
            return currentCoreSimpleIronCart(path, menu.getClass(), entity.getClass(), itemId, inventory.getSlots(),
                    upgrades.getSlots(), emptyUpgrades, linked) || linkedCoreKindRefused();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) {
            // motion-02: reported (a missing member switches the key OFF naming it; anything else is a counted skip).
            AdapterFailSafe.report(AdapterKey.STORAGE_IN_MOTION, KIND_SITE, invalid);
            return false;
        }
    }

    /**
     * Build B motion-02: LINKED_CORE's kind refusal ({@link #KIND_REASON} at {@link #KIND_SITE}), one warning per server
     * start, every refusal counted; the key stays ON. Always false. Never throws.
     */
    private static boolean linkedCoreKindRefused() {
        AdapterFailSafe.refusedOnce(AdapterKey.STORAGE_IN_MOTION, KIND_SITE, KIND_REASON);
        return false;
    }

    static boolean currentCoreSimpleIronCart(Path path, Class<?> menuClass, Class<?> entityClass,
            String itemId, int inventorySlots, int upgradeSlots, boolean emptyUpgrades, boolean linked) {
        return path == Path.LINKED_CORE
                && menuClass == MovingStorageContainerMenu.class && entityClass == StorageMinecart.class
                && CURRENT_IRON_CHEST.equals(itemId) && inventorySlots == 54 && upgradeSlots == 2
                && emptyUpgrades && !linked;
    }

    static boolean exactEntity(Entity entity) {
        return entity != null && (entity.getClass() == StorageMinecart.class || entity.getClass() == StorageBoat.class);
    }

    private static boolean exactMenu(MovingStorageContainerMenu<?> menu) {
        return menu.getClass() == MovingStorageContainerMenu.class || menu.getClass() == MovingLimitedBarrelContainerMenu.class;
    }

    /**
     * The capture's anchor, or empty. Build B motion-01 r3 (lead decision on review-01 MINOR-3): every per-object check
     * that refuses is a counted refusal with one warning per server start per reason ({@link #refusedObject}), on CURRENT
     * and the older paths alike; no row changes. Not refusals, so still silent: no context or player; a fake player
     * (never recorded); a menu that is no longer the player's open menu (it has closed); and a match that is not
     * SUPPORTED. motion-02 (re-check-01 R-NIT-1): such a match has counted and named its own refusal (CURRENT's linked,
     * own-storage and kind refusals; LINKED_CORE's linked and kind refusals), or the path is OFF with its own warning,
     * except for a menu or entity that is not of the exact Motion classes, or a menu whose cart or boat it no longer
     * holds (Motion keeps it in a weak reference), which match leaves uncounted on every path (at open the tracker's one
     * line per family per server start covers it; later in an open nothing does).
     */
    private Optional<Anchor> current(MenuAdapterContext context) {
        if (context == null || context.player() == null || EventTrailEventHandler.isFakePlayer(context.player())
                || context.player().containerMenu != context.menu() || match(context) != AdapterMatch.SUPPORTED) return Optional.empty();
        var player = context.player();
        var menu = (MovingStorageContainerMenu<?>) context.menu();
        if (nativePlayer(menu) != player || !(player.level() instanceof ServerLevel level)
                || player.isRemoved() || !player.isAlive() || level.getEntity(player.getUUID()) != player) {
            return refusedObject(PLAYER_REASON);
        }
        Entity entity = menu.getStorageEntity().orElse(null);
        if (!exactEntity(entity) || !entity.isAlive() || entity.isRemoved() || entity.level() != level
                || level.getEntity(entity.getId()) != entity || level.getEntity(entity.getUUID()) != entity
                || !menu.stillValid(player)) {
            return refusedObject(CART_REASON);
        }
        if (!finitePosition(entity) || !physicalFrame.test(entity)) return refusedObject(POSITION_REASON);
        if (paths.get().path() == Path.CURRENT) return currentAnchor(CurrentContract.production(), menu, entity, level);
        var moving = (IMovingStorageEntity) entity;
        var holder = moving.getStorageHolder();
        if (holder == null || holder.getClass() != EntityStorageHolder.class || holder.isPacked()
                || !(menu.getStorageWrapper() instanceof MovingStorageWrapper wrapper)
                || !wrapper.getClass().getName().equals("net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper$1")
                || holder.getStorageWrapper() != wrapper || !menu.getExtraSlots().isEmpty()) return refusedObject(HOLDER_REASON);
        ItemStack entityStack = moving.getStorageItem();
        ItemStack wrapperStack = wrapper.getWrappedStorageStack();
        if (!supportedItem(entityStack) || wrapperStack == null || wrapperStack.isEmpty()
                || entityStack.getItem() != wrapperStack.getItem()) return refusedObject(STACK_REASON);
        UUID contents = entityStack.get(ModCoreDataComponents.STORAGE_UUID);
        if (contents == null || !contents.equals(wrapperStack.get(ModCoreDataComponents.STORAGE_UUID))
                || wrapper.getContentsUuid().filter(contents::equals).isEmpty()
                || (menu.getClass() == MovingLimitedBarrelContainerMenu.class) != MovingStorageWrapper.isLimitedBarrel(entityStack)) {
            return refusedObject(STACK_REASON);
        }
        // Native synchronization copies the stack: object equality between these stacks is invalid.
        MovingStorageEpoch epoch = motion.apply(entity);
        if (epoch == null || !epoch.valid()) return refusedObject(EPOCH_REASON);
        return Optional.of(new Anchor(entity, holder, wrapper, contents, entityStack.getItem(), epoch,
                MovingStorageEpoch.runtimeGeneration(), level, KEY_VERSION));
    }

    /**
     * Build B motion-01: CURRENT's per-object checks, after {@link #current}'s player, world and entity checks and
     * after {@link #matchCurrent} found the cart unlinked. The holder, menu, opened and own wrappers are one finite
     * Storage wrapper; the holder's installed item is the entity's stack; the stacks' item and contents id agree (as
     * on the older paths). Members that the compile classpath lacks are read through {@link CurrentContract}. A check
     * that refuses is a counted refusal with one warning per server start per reason (r3, {@link #refusedObject}); a read
     * that fails is reported (a missing member switches the key OFF). Either gives no row. Never throws.
     */
    private Optional<Anchor> currentAnchor(CurrentContract contract, MovingStorageContainerMenu<?> menu, Entity entity,
            ServerLevel level) {
        try {
            if (contract == null || !contract.complete() || !(entity instanceof IMovingStorageEntity moving)) {
                return Optional.empty();
            }
            var holder = moving.getStorageHolder();
            if (holder == null || holder.getClass() != EntityStorageHolder.class || holder.isPacked()
                    || !(menu.getStorageWrapper() instanceof MovingStorageWrapper wrapper)
                    || wrapper.getClass() != contract.finiteWrapper()
                    || contract.ownWrapper().get(holder) != wrapper || contract.openedWrapper().get(menu) != wrapper
                    || contract.holderWrapper().invoke(holder) != wrapper || !menu.getExtraSlots().isEmpty()) {
                return refusedObject(HOLDER_REASON);
            }
            ItemStack entityStack = moving.getStorageItem();
            ItemStack wrapperStack = wrapper.getWrappedStorageStack();
            if (contract.installedItem().invoke(holder) != entityStack || !supportedItem(entityStack)
                    || wrapperStack == null || wrapperStack.isEmpty() || entityStack.getItem() != wrapperStack.getItem()) {
                return refusedObject(STACK_REASON);
            }
            if (!(contract.uuidComponent().get() instanceof DataComponentType<?> uuidType)) {
                // motion-02 (re-check-01 R-NIT-5): Core's STORAGE_UUID of another shape is a build-shape problem, not
                // a per-cart stack mismatch: reported (a counted skip with its own warning), no row, as before.
                AdapterFailSafe.report(AdapterKey.STORAGE_IN_MOTION, CURRENT_SITE,
                        new IllegalStateException("Core STORAGE_UUID is not a DataComponentType"));
                return Optional.empty();
            }
            if (!(entityStack.get(uuidType) instanceof UUID contents) || !contents.equals(wrapperStack.get(uuidType))
                    || wrapper.getContentsUuid().filter(contents::equals).isEmpty()
                    || (menu.getClass() == MovingLimitedBarrelContainerMenu.class) != MovingStorageWrapper.isLimitedBarrel(entityStack)) {
                return refusedObject(STACK_REASON);
            }
            MovingStorageEpoch epoch = motion.apply(entity);
            if (epoch == null || !epoch.valid()) return refusedObject(EPOCH_REASON);
            return Optional.of(new Anchor(entity, holder, wrapper, contents, entityStack.getItem(), epoch,
                    MovingStorageEpoch.runtimeGeneration(), level, CURRENT_KEY_VERSION));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            AdapterFailSafe.report(AdapterKey.STORAGE_IN_MOTION, CURRENT_SITE, unavailable);
            return Optional.empty();
        }
    }

    /**
     * Build B motion-01 r3: a per-object refusal ({@link #OBJECT_SITE}), one warning per server start per reason, every
     * refusal counted (several times per action: each capture reads the anchor more than once); the key stays ON.
     * Always empty. Never throws.
     */
    private static Optional<Anchor> refusedObject(String reason) {
        AdapterFailSafe.refusedOnce(AdapterKey.STORAGE_IN_MOTION, OBJECT_SITE, reason);
        return Optional.empty();
    }

    private static boolean supportedItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() != 1) return false;
        Class<?> item = stack.getItem().getClass();
        return item == ChestBlockItem.class || item == BarrelBlockItem.class || item == ShulkerBoxItem.class;
    }

    @Override public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        return current(context).flatMap(anchor -> SophisticatedMenuSlots.describe((MovingStorageContainerMenu<?>) context.menu(),
                context.player().getInventory(), anchor.entity(), List.of(anchor.entity(), anchor.holder(), anchor.wrapper()), true));
    }

    @Override public Optional<ContainerTarget> resolveMenuBackingTarget(MenuAdapterContext context) {
        return current(context).map(anchor -> target(anchor));
    }

    @Override public Optional<MenuAccessTarget> resolveContainerAccessTarget(MenuAdapterContext context) {
        return current(context).map(anchor -> new MenuAccessTarget(target(anchor), technicalId(anchor.entity())));
    }

    @Override public Optional<BooleanSupplier> captureMovingAccessGuard(MenuAdapterContext context) {
        return current(context).map(opening -> {
            MovingStorageEpoch.Stamp stamp = opening.epoch().snapshot();
            BlockPos position = opening.entity().blockPosition().immutable();
            boolean[] valid = {true};
            return () -> {
                if (!valid[0]) return false;
                try {
                    valid[0] = motionHooksRunning() && opening.epoch().unchanged(stamp) && current(context)
                            .filter(opening::sameOwner).filter(now -> position.equals(now.entity().blockPosition())).isPresent();
                } catch (RuntimeException | LinkageError invalid) {
                    // Closed for good (the latch), and reported: a missing member switches the key off until restart.
                    valid[0] = false;
                    AdapterFailSafe.report(AdapterKey.STORAGE_IN_MOTION, "adapter:StorageInMotionMenuAdapter.movingAccessGuard", invalid);
                }
                return valid[0];
            };
        });
    }

    /**
     * The cart and boat epoch is kept current by the guarded entity setter hooks, which run only while the
     * STORAGE_IN_MOTION key is active. A moving access guard therefore stays open only while that key is active, so a
     * stale epoch can never keep it open. OFF lasts until restart, so a closed guard stays closed.
     */
    static boolean motionHooksRunning() {
        try {
            return AdapterFailSafe.active(AdapterKey.STORAGE_IN_MOTION);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    @Override public InventorySlotRole classifySlot(MenuAdapterContext context, int index, Slot slot) {
        return context != null && context.player() != null && context.menu() instanceof MovingStorageContainerMenu<?> menu
                ? SophisticatedMenuSlots.classify(menu, context.player().getInventory(), slot) : InventorySlotRole.UNKNOWN;
    }

    @Override public List<MenuObservedSlot> additionalObservedSlots(MenuAdapterContext context) {
        return context != null && context.menu() instanceof MovingStorageContainerMenu<?> menu
                ? SophisticatedMenuSlots.additional(menu) : List.of();
    }

    private static ContainerTarget target(Anchor anchor) {
        String dimension = anchor.level().dimension().location().toString();
        String key = "storagemotion|" + anchor.keyVersion() + '|' + dimension + '|' + anchor.entity().getUUID() + '|'
                + anchor.contents() + '|' + anchor.generation();
        return ContainerTarget.block(dimension, anchor.entity().blockPosition().immutable(), label(anchor), BackingContextKind.ENTITY_BOUND, key);
    }

    private static String technicalId(Entity entity) {
        return entity.getClass() == StorageMinecart.class ? "sophisticatedstorageinmotion:storage_minecart"
                : "sophisticatedstorageinmotion:storage_boat";
    }

    private static String label(Anchor anchor) {
        try {
            var custom = anchor.entity().getCustomName();
            String name = custom == null ? null : EventTrailTextBounds.literalDisplayText(custom.getString());
            if (name != null) return name;
            String storage = DisplayNameResolver.itemContainerLabel(anchor.wrapper().getWrappedStorageStack());
            if (storage != null && !storage.isBlank()) return storage;
        } catch (RuntimeException | LinkageError ignored) { }
        return technicalId(anchor.entity());
    }

    private static boolean finitePosition(Entity entity) {
        var point = entity.position();
        return point != null && anchorCoordinate(point.x) && anchorCoordinate(point.y) && anchorCoordinate(point.z)
                && BlockPos.containing(point).equals(entity.blockPosition());
    }

    private static boolean anchorCoordinate(double coordinate) {
        return Double.isFinite(coordinate) && coordinate >= -2_147_483_648.0 && coordinate < 2_147_483_648.0;
    }

    private static boolean physicalFrame(Entity entity) {
        return AuditBlockLocations.capture(entity.level(), entity.blockPosition())
                .filter(frame -> !frame.moving() && frame.isCurrent())
                .flatMap(AuditBlockLocations.Frame::currentLocation).isPresent();
    }

    private static Field nativePlayerField() {
        try {
            Field player = StorageContainerMenuBase.class.getDeclaredField("player");
            if (player.getType() != Player.class) throw new IllegalStateException("Unexpected native menu owner contract");
            player.setAccessible(true);
            return player;
        } catch (ReflectiveOperationException invalid) { throw new IllegalStateException("Native menu owner unavailable", invalid); }
    }

    private static Player nativePlayer(StorageContainerMenuBase<?> menu) {
        try { return (Player) MENU_PLAYER.get(menu); }
        catch (IllegalAccessException invalid) { throw new IllegalStateException("Native menu owner unavailable", invalid); }
    }

    /**
     * Build B motion-01: the CURRENT path's members of Motion 0.10.38, Storage 1.6 and Core 1.5, looked up by name.
     * The compile classpath (Motion 0.10.34.358, Storage 1.5.91, Core 1.4.90) lacks them, so none is called directly.
     * A lookup that fails is null, and one null makes every read UNREADABLE: the key goes OFF with one warning naming
     * {@link StorageInMotionMenuAdapter#CURRENT_LOOKED_FOR}. Fields are opened with {@code trySetAccessible}; one that cannot be opened is null.
     */
    record CurrentContract(Method endpointData, Method installedItem, Method linkedFlag, Method holderWrapper,
            Method groupId, Field openedWrapper, Field ownWrapper, Field clientHost, Field clientEndpoint,
            Supplier<?> endpointComponent, Supplier<?> uuidComponent, Class<?> virtualHost, Class<?> finiteWrapper) {
        static final String VIRTUAL_HOST_CLASS = "net.p3pp3rf1y.sophisticatedcore.linkedstorage.ILinkedStorageVirtualHost";
        static final String FINITE_WRAPPER_CLASS = "net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper$1";

        /** Resolved once, at the first CURRENT use (never on the older paths). */
        static CurrentContract production() {
            return Production.CONTRACT;
        }

        /** Every lookup on the running classpath; never throws. */
        static CurrentContract resolve() {
            return new CurrentContract(method(EntityStorageHolder.class, "getLinkedStorageEndpointData"),
                    method(EntityStorageHolder.class, "getInstalledStorageItem"),
                    method(StorageHolderBase.class, "isLinkedStorage"),
                    method(StorageHolderBase.class, "getStorageWrapper"),
                    method(MovingStorageContainerMenu.class, "getLinkedStorageGroupId"),
                    field(MovingStorageContainerMenu.class, "openedWrapper"),
                    field(StorageHolderBase.class, "storageWrapper"),
                    field(StorageHolderBase.class, "clientLinkedStorageHost"),
                    field(StorageHolderBase.class, "clientLinkedStorageEndpoint"),
                    component("LINKED_STORAGE_ENDPOINT"), component("STORAGE_UUID"),
                    type(VIRTUAL_HOST_CLASS, StorageContainerMenuBase.class), type(FINITE_WRAPPER_CLASS, MovingStorageWrapper.class));
        }

        boolean complete() {
            return endpointData != null && installedItem != null && linkedFlag != null && holderWrapper != null
                    && groupId != null && openedWrapper != null && ownWrapper != null && clientHost != null
                    && clientEndpoint != null && endpointComponent != null && uuidComponent != null && virtualHost != null
                    && finiteWrapper != null;
        }

        /**
         * One open's link state, endpoint absence first (no getter that could resolve a remote link host is called):
         * the entity's stack carries Core's endpoint component; the holder's endpoint data, linked flag, client link
         * host or client endpoint; the menu's opened link group; or the opened or menu wrapper is a virtual link host.
         * Any of them is LINKED (an opened wrapper that is not the holder's own is r2's {@link #openedNotOwn}, refused
         * with its own reason). A flag or group id of another shape is STATE_UNREADABLE (that open only). A
         * missing member is UNREADABLE. An object that is not a Motion cart or boat is UNLINKED here; the per-object
         * checks refuse it. Throws what a read throws ({@link SophisticatedLinkedStorage#refusedOpen} sorts it).
         */
        SophisticatedLinkedStorage.Linkage linkage(MovingStorageContainerMenu<?> menu, Entity entity)
                throws ReflectiveOperationException {
            if (!complete() || !(endpointComponent.get() instanceof DataComponentType<?> endpoint)) {
                return SophisticatedLinkedStorage.Linkage.UNREADABLE;
            }
            if (menu == null || !(entity instanceof IMovingStorageEntity moving)) return SophisticatedLinkedStorage.Linkage.UNLINKED;
            ItemStack stack = moving.getStorageItem();
            if (stack != null && stack.get(endpoint) != null) return SophisticatedLinkedStorage.Linkage.LINKED;
            Object holder = moving.getStorageHolder();
            if (holder != null) {
                if (endpointData.invoke(holder) != null) return SophisticatedLinkedStorage.Linkage.LINKED;
                if (!(linkedFlag.invoke(holder) instanceof Boolean linked)) return SophisticatedLinkedStorage.Linkage.STATE_UNREADABLE;
                if (linked || clientHost.get(holder) != null || clientEndpoint.get(holder) != null) {
                    return SophisticatedLinkedStorage.Linkage.LINKED;
                }
            }
            if (!(groupId.invoke(menu) instanceof Optional<?> group)) return SophisticatedLinkedStorage.Linkage.STATE_UNREADABLE;
            if (group.isPresent()) return SophisticatedLinkedStorage.Linkage.LINKED;
            Object opened = openedWrapper.get(menu);
            if (virtualHost.isInstance(opened) || virtualHost.isInstance(menu.getStorageWrapper())) {
                return SophisticatedLinkedStorage.Linkage.LINKED;
            }
            return SophisticatedLinkedStorage.Linkage.UNLINKED;
        }

        /**
         * Build B motion-01 r2: the menu's opened storage is not the cart's or boat's own wrapper (Noop excepted: a
         * packed cart, which the per-object checks refuse). Refused with its own reason, not as linked. False for an
         * incomplete contract (the linked read already switched the key OFF) or an object that is not a Motion cart.
         * Throws what a read throws.
         */
        boolean openedNotOwn(MovingStorageContainerMenu<?> menu, Entity entity) throws ReflectiveOperationException {
            if (!complete() || menu == null || !(entity instanceof IMovingStorageEntity moving)) return false;
            Object holder = moving.getStorageHolder();
            Object opened = openedWrapper.get(menu);
            return holder != null && opened != null && opened != NoopStorageWrapper.INSTANCE && opened != ownWrapper.get(holder);
        }

        private static Method method(Class<?> type, String name) {
            try { return type.getMethod(name); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }

        private static Field field(Class<?> type, String name) {
            try {
                Field field = type.getDeclaredField(name);
                return field.trySetAccessible() ? field : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }

        private static Supplier<?> component(String name) {
            try {
                return ModCoreDataComponents.class.getField(name).get(null) instanceof Supplier<?> supplier ? supplier : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }

        private static Class<?> type(String name, Class<?> loaderOf) {
            try { return Class.forName(name, false, loaderOf.getClassLoader()); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
        }

        private static final class Production {
            static final CurrentContract CONTRACT = resolve();
        }
    }

    private record Anchor(Entity entity, EntityStorageHolder<?> holder, MovingStorageWrapper wrapper, UUID contents,
                          Item item, MovingStorageEpoch epoch, long generation, ServerLevel level, String keyVersion) {
        boolean sameOwner(Anchor current) {
            return entity == current.entity && holder == current.holder && wrapper == current.wrapper && item == current.item
                    && contents.equals(current.contents) && epoch == current.epoch && generation == current.generation && level == current.level;
        }
    }
}
