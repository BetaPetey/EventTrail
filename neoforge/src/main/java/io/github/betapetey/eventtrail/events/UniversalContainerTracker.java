package io.github.betapetey.eventtrail.events;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.audit.ActionRecordBatch;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterFamilyMisses;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.ItemsNotTrackedOpens;
import io.github.betapetey.eventtrail.adapters.RecordingGaps;
import io.github.betapetey.eventtrail.adapters.hooks.OpenActionAbort;
import io.github.betapetey.eventtrail.inventory.adapters.StandardPhysicalMenuAdapter;
import io.github.betapetey.eventtrail.inventory.adapters.VanillaEnderChestMenuAdapter;
import io.github.betapetey.eventtrail.inventory.action.AuthoritativeTransactionCollector;
import io.github.betapetey.eventtrail.inventory.action.PhysicalInventoryActionCorrelator;
import io.github.betapetey.eventtrail.inventory.action.PhysicalInventoryActionRolePolicy;
import io.github.betapetey.eventtrail.inventory.action.PhysicalInventoryRoleResolver;
import io.github.betapetey.eventtrail.inventory.access.AccessPointTargets;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPayloadCodec;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessTargetSnapshot;
import io.github.betapetey.eventtrail.inventory.access.neoforge.NeoForgeContainerAccessAuditBridge;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.EvidenceKind;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionEvidence;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionNormalizer;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.inventory.core.WholeMenuPhysicalTargetResolver;
import io.github.betapetey.eventtrail.inventory.core.PhysicalContainerLocationBinding;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageEpoch;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility;
import io.github.betapetey.eventtrail.inventory.optional.OptionalMenuAdapterLoader;
import io.github.betapetey.eventtrail.inventory.spi.AdapterMatch;
import io.github.betapetey.eventtrail.inventory.spi.GuardedMenuAdapter;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.spi.MenuAccessTarget;
import io.github.betapetey.eventtrail.inventory.spi.MenuPhysicalBlockTarget;
import io.github.betapetey.eventtrail.inventory.spi.MenuPhysicalBacking;
import io.github.betapetey.eventtrail.inventory.spi.MenuObservedSlot;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapter;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapterRegistry;
import io.github.betapetey.eventtrail.inventory.spi.ObservationStrategy;
import io.github.betapetey.eventtrail.inventory.view.BoundedMenuInventoryView;
import static io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits.MAX_MENU_DESCRIPTORS;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import net.neoforged.neoforge.items.wrapper.EntityArmorInvWrapper;
import net.neoforged.neoforge.items.wrapper.EntityEquipmentInvWrapper;
import net.neoforged.neoforge.items.wrapper.EntityHandsInvWrapper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerArmorInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import net.neoforged.neoforge.items.wrapper.PlayerOffhandInvWrapper;
import net.neoforged.neoforge.items.wrapper.RangedWrapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Unified player-driven container transaction tracker.
 *
 * <p>For normal physical containers, one bounded whole-menu resolver proves a
 * single live backing identity before this tracker owns the session. Ordinary
 * server menu actions are then bracketed while device, player inventory, and
 * carried cursor custody are compared synchronously.</p>
 *
 * <p>Exact virtual-storage adapters own their menu session and contribute only
 * committed operation results inside one bounded server-thread action scope.
 * Unsupported nonphysical menus fail closed instead of receiving speculative
 * inventory-delta attribution.</p>
 */
@EventBusSubscriber(modid = EventTrailIds.MOD_ID)
public final class UniversalContainerTracker {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    /** A2-44: at most one "container action not recorded" WARN per minute, with the count suppressed since. */
    private static final RateLimitedDiagnosticGate ACTION_NOT_RECORDED = new RateLimitedDiagnosticGate(60_000);
    /** R2-menu: at most one "tracker entry point failed" WARN per minute, with the count suppressed since. */
    private static final RateLimitedDiagnosticGate ENTRY_FAILED = new RateLimitedDiagnosticGate(60_000);
    /**
     * R2-menu (review-01 MAJOR-1): at most one "cursor provenance lost" WARN per minute, with the count suppressed
     * since, for a session whose adapter has no fail-safe key (EventTrail's own vanilla adapters).
     */
    private static final RateLimitedDiagnosticGate CURSOR_PROVENANCE_LOST = new RateLimitedDiagnosticGate(60_000);
    private static final String CURSOR_PROVENANCE_LOST_REASON = "cursor-provenance-lost";
    /** Catch-all (review-01 m-1): at most one "could not check or count" WARN per minute, with the suppressed count. */
    private static final RateLimitedDiagnosticGate CATCH_ALL_FAILED = new RateLimitedDiagnosticGate(60_000);
    // GF-WARNINGS (B47): the registry's frozen UNSUPPORTED_VERSION split by its three causes.
    static final String CATCH_ALL_ADAPTER_OFF = "adapter off (UNSUPPORTED_VERSION)";
    static final String CATCH_ALL_ADAPTER_NOT_BUILT = "adapter not built (UNSUPPORTED_VERSION)";
    static final String CATCH_ALL_MENU_NOT_SUPPORTED = "this menu not supported by the adapter (UNSUPPORTED_VERSION)";
    /** r3 (review-01 NIT-10): the published status could not be read, so none of the three causes is proven. */
    static final String CATCH_ALL_CAUSE_NOT_KNOWN = "cause not known (UNSUPPORTED_VERSION)";
    /** GF-WARNINGS r2: the ACTION_CONTEXT_CHANGED detail of a click whose adapter action guard was not current. */
    static final String ACTION_GUARD_DETAIL = "action-guard";
    /** Own-slot "opened" line: the prefix of its access target's backing key (only the key's SHA-256 is stored). */
    private static final String ITEMS_NOT_TRACKED_KEY = "eventtrail.items-not-tracked:";
    // R2-menu: fail-safe sites of the tracker's own adapter-failure and integrity reports (a bounded set).
    private static final String SITE_OPEN_TARGET = "menu open target";
    private static final String SITE_OPEN_BACKING = "menu open backing";
    private static final String SITE_OPEN_ACCESS_TARGET = "menu open access target";
    private static final String SITE_OPEN_ACCESS_GUARD = "menu open access guard";
    private static final String SITE_SLOT_VIEW = "menu slot view";
    private static final String SITE_ACTION_CAPTURE = "menu action capture";
    private static final String SITE_ACTION_ROLE = "menu action role";
    private static final String SITE_OBSERVED_SLOTS = "menu observed slots";
    private static final String SITE_ACTION_RECONCILE = "menu action reconcile";
    private static final String SITE_ACTION_GUARD = "menu action guard";
    private static final String SITE_SESSION_GUARD = "menu session guard";
    private static final String SITE_BACKING_REFRESH = "menu backing refresh";
    private static final String SITE_AUTH_BEGIN = "menu authoritative begin";
    private static final String SITE_ENTRY_POINT = "menu tracker entry point";
    private static final long TARGET_TTL_MS = 5_000L;
    private static final Map<UUID, RecentTarget> RECENT_TARGETS = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ThreadLocal<ActionToken> ACTIVE_AUTHORITATIVE_ACTION = new ThreadLocal<>();
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();
    private static volatile long runtimeGeneration;
    private static volatile MenuTransactionAdapterRegistry ADAPTERS =
            createAdapterRegistry(List.of());

    // Last step of class initialisation (lead decision L15 G1): a guarded hook skip discards the calling thread's
    // open exact action. No action can be open before this class is initialised, so a skip before this runs has
    // nothing to discard.
    static {
        OpenActionAbort.register(UniversalContainerTracker::abortCurrentAuthoritativeAction);
    }

    private UniversalContainerTracker() {}

    /** Session authority is process-local and revoked whenever runtime state is cleared. */
    public static long runtimeGeneration() { return runtimeGeneration; }

    private static MenuTransactionAdapterRegistry createAdapterRegistry(
            List<? extends MenuTransactionAdapter> optionalAdapters) {
        List<MenuTransactionAdapter> exact = new ArrayList<>(optionalAdapters);
        exact.add(new VanillaEnderChestMenuAdapter());
        return new MenuTransactionAdapterRegistry(
                exact,
                new StandardPhysicalMenuAdapter(),
                OptionalMenuAdapterLoader::isRuntimeAdapterDisabled,
                OptionalMenuAdapterLoader::runtimeDisabledAdapterOwnsContext,
                OptionalMenuAdapterLoader::reportMatchFailure);
    }

    /**
     * Resolves optional integrations once during the server-start lifecycle. The adapter fail-safe starts
     * a fresh OFF table first, so an adapter switched OFF in an earlier server run of this JVM is tried
     * again, and OFF lasts until the next server start.
     */
    public static void startRuntimeCompatibility() {
        NeoForgeContainerAccessAuditBridge.resetRuntimeState();
        AdapterFamilyMisses.reset();
        ItemsNotTrackedOpens.reset();
        try {
            AdapterFailSafe.resetAtServerStart();
            OptionalMenuAdapterLoader.LoadResult result =
                    OptionalMenuAdapterLoader.loadInstalled();
            ADAPTERS = createAdapterRegistry(result.adapters());
            OptionalMenuAdapterLoader.activate(result);
        } catch (Throwable failure) {
            // Optional compatibility must never prevent vanilla EventTrail startup.
            ADAPTERS = createAdapterRegistry(List.of());
            LOGGER.error("[EVENTTRAIL_COMPATIBILITY] Optional inventory compatibility "
                            + "initialization failed; optional adapters are disabled ({})",
                    EventTrailDiagnostics.safeFailureSummary(failure));
        }
    }

    /**
     * Own-slot "opened" line (review-01 MAJOR-1): HIGHEST, so it runs before every other mod's handler for this click
     * (either hand). From here on a screen may be opened by another mod's handler for this click, by the off-hand item
     * or by the clicked block, so the player's remembered earlier click no longer proves its own block's screen. The
     * LOWEST main-hand record below then remembers this click, unmarked and not armed; the start of the block's own use
     * arms it (r5, {@link #onUseItemOnBlock}), so the block's own screen is still admitted.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onRightClickBlockFirst(PlayerInteractEvent.RightClickBlock event) {
        markClickBeforeOtherHandlers(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        // Own-slot "opened" line: an off-hand click was already marked by onRightClickBlockFirst (HIGHEST).
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!preparePlayerTracking(player)) return;
        if (!anyContainerTrackingEnabled()) {
            RECENT_TARGETS.remove(player.getUUID());
            return;
        }
        if (!shouldRememberInteraction(event.isCanceled(), event.getUseBlock().isFalse())) {
            RECENT_TARGETS.remove(player.getUUID());
            return;
        }

        String label;
        try {
            label = blockDisplayName(event.getLevel().getBlockState(event.getPos()).getBlock());
        } catch (Throwable ignored) {
            label = null;
        }
        // Own-slot "opened" line (Owner decision A, 11-14): the click's server tick and the clicked block, read here so
        // the open can prove it is this click's screen. A failure to read them is remembered as unread (the open then
        // writes no "opened" line); the click is remembered as before. r5 (Advisor M-1): the click is stored NOT armed;
        // only the start of the block's own use arms it (onUseItemOnBlock, BLOCK phase). A spectator's click is armed
        // here: vanilla opens the block's menu for a spectator without the block's own use (and without that event).
        long tick = RecentTarget.NO_TICK;
        String blockId = RecentTarget.CLICK_UNREAD;
        boolean armed = false;
        try {
            tick = currentTick(player);
            String clicked = canonicalBlockId(player, event.getPos());
            if (clicked != null) blockId = clicked;
            armed = player.isSpectator();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            tick = RecentTarget.NO_TICK;
            armed = false;
        }
        rememberTarget(player, event.getLevel().dimension(), event.getPos(), label, tick, blockId, false, armed);
    }

    /**
     * Own-slot "opened" line: a used item (either hand) can open a screen itself, a remote link for one, and so can
     * another mod's handler for this item use. HIGHEST (review-01 MAJOR-1), so the mark is in place before any of
     * those runs: a screen opened after it is not proven to be the clicked block's. Only the click's own-slot proof
     * changes; the remembered target the other paths use is unchanged.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        markClickBeforeOtherHandlers(event);
    }

    /**
     * Own-slot "opened" line (r3, the same rule as MAJOR-1 for an entity use-click): another mod's handler for this
     * entity click can open a screen before the LOWEST record below replaces the player's remembered block click, so
     * that click is marked first. The entity's own screen opens after the record, which never proves a block screen.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityInteractFirst(PlayerInteractEvent.EntityInteract event) {
        markClickBeforeOtherHandlers(event);
    }

    /** Own-slot "opened" line: as {@link #onEntityInteractFirst}, for the click at a point on the entity. */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onEntityInteractSpecificFirst(PlayerInteractEvent.EntityInteractSpecific event) {
        markClickBeforeOtherHandlers(event);
    }

    /**
     * Own-slot "opened" line (r4, re-check MINOR-A): NeoForge posts this event from the held item's {@code useOn}
     * (ITEM_AFTER_BLOCK), after EventTrail's LOWEST record and only once the block's own use passed, so the block's own
     * screen has already opened or never will. Another mod's handler of it (or the item's {@code useOn}) can open a
     * screen, so the click is marked first. ITEM_BEFORE_BLOCK ({@code onItemUseFirst}) and BLOCK run before the block's
     * own use and are never marked: that would refuse every own-slot block screen. r5 (Advisor M-1): the BLOCK phase
     * arms the click instead, in {@link #onUseItemOnBlock}.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onUseItemOnBlockFirst(UseItemOnBlockEvent event) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK) return;
        markClickBefore(event::getPlayer);
    }

    /**
     * Own-slot "opened" line (r5, Advisor M-1): the click proves the clicked block's screen only from the moment the
     * block's own use starts. NeoForge posts this event's BLOCK phase from {@code BlockStateBase.useItemOn}, which
     * starts the block's own use with or without an item in hand (and before {@code useWithoutItem}). OS-C1: this
     * handler also receives a canceled BLOCK phase. The cancellation skips {@code Block.useItemOn}, and
     * {@code BlockStateBase.useItemOn} returns the cancellation result; only for PASS_TO_DEFAULT_BLOCK_INTERACTION
     * (NeoForge's default, a plain {@code setCanceled(true)}) on the main hand does
     * {@code ServerPlayerGameMode.useItemOn} still run the block's own {@code useWithoutItem}, so only then does a
     * canceled phase arm the click. A canceled phase with any other result never arms it: NeoForge skips the block's
     * own use, and a screen opened then is refused as NO_BLOCK_USE (a row lost and counted, never a wrong row). A
     * screen the canceling handler opens inside its handler opens before the click is armed and is refused the same
     * way; that open uses the click up. A screen it opens later in the same tick (for example through the server's task
     * queue) opens after the arming, and if the block's own use opened nothing it is written for the clicked block (a
     * known limitation). A BLOCK-phase handler at LOWEST registered after EventTrail's runs after the arming, canceled
     * or not (also a known limitation). LOWEST, so another mod's BLOCK-phase handler above LOWEST, the held item's
     * {@code onItemUseFirst} (ITEM_BEFORE_BLOCK) and any click handler that runs after EventTrail's record all run
     * before the click is armed: a screen opened there finds it not armed and is refused. Arms only this player's
     * main-hand use, on a server-side level, of the recorded position in the click's own dimension on the click's own
     * server tick; it never clears a mark (an armed and marked click is still refused). A failure once the player is
     * known sets the click aside as unreadable (CHECK_FAILED at the next open); nothing reaches the event short of a
     * {@link VirtualMachineError}.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        ServerPlayer actor = null;
        try {
            if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.BLOCK) return;
            if (event.getHand() != InteractionHand.MAIN_HAND) return;
            if (event.isCanceled()
                    && event.getCancellationResult() != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                return;
            }
            if (!(event.getPlayer() instanceof ServerPlayer player)) return;
            actor = player;
            Level level = event.getLevel();
            if (level == null || level.isClientSide() || !preparePlayerTracking(player)) return;
            ResourceKey<Level> dimension = level.dimension();
            BlockPos pos = event.getPos();
            long tick = currentTick(player);
            RECENT_TARGETS.computeIfPresent(player.getUUID(), (id, target) -> target.armedBy(dimension, pos, tick));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            setAsideClick(actor);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!preparePlayerTracking(player)) return;
        if (!anyContainerTrackingEnabled()) {
            RECENT_TARGETS.remove(player.getUUID());
            return;
        }
        if (!shouldRememberInteraction(event.isCanceled(), false)) {
            RECENT_TARGETS.remove(player.getUUID());
            return;
        }

        String label;
        try {
            label = event.getTarget().getName().getString();
        } catch (Throwable ignored) {
            label = null;
        }
        rememberTarget(player, event.getLevel().dimension(), event.getPos(), label);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!preparePlayerTracking(player)) return;
        if (!anyContainerTrackingEnabled()) {
            RECENT_TARGETS.remove(player.getUUID());
            return;
        }
        if (!shouldRememberInteraction(event.isCanceled(), false)) {
            RECENT_TARGETS.remove(player.getUUID());
            return;
        }

        String label;
        try {
            label = event.getTarget().getName().getString();
        } catch (Throwable ignored) {
            label = null;
        }
        rememberTarget(player, event.getLevel().dimension(), event.getPos(), label);
    }

    /**
     * LOWEST is intentional: the interaction boundary records the clicked block
     * first, then this menu boundary proves one whole-menu backing target and takes
     * exclusive ownership of the transaction/access session.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        // R2-menu: the outermost catch of this entry point; nothing EventTrail or an adapter raises reaches the open.
        try {
            openContainer(event);
        } catch (Throwable failure) {
            entryFailed("container open", eventPlayer(event), failure);
        }
    }

    private static void openContainer(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        AbstractContainerMenu menu = event.getContainer();
        if (menu == null) return;
        // fix-3: an untracked synthetic actor can carry a real player's UUID (Create deployers use the owner's), and
        // every step below reads or changes state keyed by that UUID, so it returns before any of them.
        if (!preparePlayerTracking(player)) return;
        if (NeoForgeContainerAccessAuditBridge.suppressDuplicateOpen(player, menu)) return;
        NeoForgeContainerAccessAuditBridge.abandonSupersededSession(player, menu);

        UUID id = player.getUUID();
        SESSIONS.remove(id);
        RecentTarget recent = consumeRecentTarget(
                RECENT_TARGETS, id, player.level().dimension(), System.currentTimeMillis());

        // af-2 (Owner R-M1 b): one counted gate per container open. Transaction tracking is on exactly while
        // EventTrail is ready, so a not-ready open returned here before as well (it skips both sessions).
        if (!EventTrailManager.getInstance().admitCapture()) return;
        // af-3 (re-check n1): the counted gate has just admitted, so transaction tracking is on. Re-reading readiness
        // here could return without a count if it flipped in between; the later action gates are counted.
        boolean transactionTracking = true;
        boolean accessTracking = NeoForgeContainerAccessAuditBridge.observesOpen(player, menu)
                && EventTrailManager.getInstance().admitCaptureWithWriteCapacity();
        if (!transactionTracking && !accessTracking) return;

        Optional<WholeMenuPhysicalTargetResolver.ResolvedTarget> physical =
                recent == null
                        ? Optional.empty()
                        : WholeMenuPhysicalTargetResolver.resolve(
                                menu,
                                player.getInventory(),
                                player.level(),
                                recent.pos,
                                MAX_MENU_DESCRIPTORS);

        // Catch-all (Owner decision A, 11-14): the family whose selected adapter must yield a session, its mark, and
        // whether the open itself failed (the catch below).
        AdapterKey family = null;
        String familyAdapterId = null;
        long familyMark = 0L;
        boolean openFailed = false;
        // GF-WARNINGS (B47): the step of the open last entered, named in the family's miss (null before the first).
        String familyStep = null;
        try {
            MenuTransactionAdapterRegistry.Selection selection = ADAPTERS.select(
                    new MenuAdapterContext(menu, player, physical.isPresent()));
            if (selection.outcome() == MenuTransactionAdapterRegistry.Outcome.BLOCKED) {
                // R2-menu: an adapter's match failure was reported to the fail-safe where it happened (the guard, or
                // the registry for an unwrapped adapter); a failure no longer switches the adapter off here.
                LOGGER.debug(
                        "Container tracking declined adapter {} for {} ({}); no audit fact will be attributed",
                        EventTrailDiagnostics.sanitizeLogValue(selection.adapterId(), 96),
                        EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                        EventTrailDiagnostics.sanitizeLogValue(selection.reason(), 96));
                if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                familySelectionRefused(selection);
                return;
            }
            if (!selection.selected()) {
                // Own-slot "opened" line (Owner decision A, 11-14): no adapter and no vanilla handling takes this
                // screen (NO_MATCH). Only a screen proven to be the clicked block's gets OPEN/CLOSE rows marked "items
                // not tracked"; any other gets no row and a count, as an unresolved target before.
                if (accessTracking && !itemsNotTrackedOpen(player, menu, recent)) {
                    NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                }
                return;
            }
            family = adapterKey(selection.adapter());
            familyAdapterId = selection.adapterId();
            familyMark = GuardedMenuAdapter.failuresOnThisThread();
            if (OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(selection.adapterId())) {
                if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                return;
            }
            // R2-menu (design 2.4, OPEN_*): an adapter failure from here on means this open is not tracked.
            long adapterFailures = GuardedMenuAdapter.failuresOnThisThread();

            ObservationStrategy strategy = selection.adapter().observationStrategy();
            familyStep = "adapter checks";
            if (selection.adapter().usesMovingBackingTarget() && !isExactMovingBackingAdapter(selection.adapter())) {
                if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                return;
            }
            if (selection.adapter().usesSharedChannelBacking()
                    && !MenuPhysicalBacking.isExactSharedChannelProducer(selection.adapter())) {
                if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                return;
            }
            Optional<MenuPhysicalBlockTarget> adapterPhysicalTarget = Optional.empty();
            Optional<ContainerTarget> authoritativeTarget = Optional.empty();
            Optional<MenuAccessTarget> authoritativeAccessTarget = Optional.empty();
            Optional<MenuPhysicalBacking> handlerBacking = Optional.empty();
            Optional<ContainerTarget> independentTarget = Optional.empty();
            familyStep = "target";
            if (strategy == ObservationStrategy.ACTION_SCOPED_PHYSICAL
                    && selection.adapter().usesHandlerBackedView()) {
                try {
                    if (selection.adapter().usesIndependentBackingTarget()) {
                        independentTarget = selection.adapter().resolveMenuBackingTarget(
                                new MenuAdapterContext(menu, player, false));
                    } else {
                        adapterPhysicalTarget = selection.adapter().resolvePhysicalBlockTarget(
                                new MenuAdapterContext(menu, player, false));
                    }
                } catch (RuntimeException | LinkageError failure) {
                    adapterFailed(selection.adapter(), SITE_OPEN_TARGET, failure);
                    if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                    return;
                }
            } else if (strategy == ObservationStrategy.AUTHORITATIVE_OPERATION
                    && transactionTracking) {
                try {
                    authoritativeTarget = selection.adapter().resolveAuthoritativeTarget(
                            new MenuAdapterContext(menu, player, false));
                } catch (RuntimeException | LinkageError failure) {
                    adapterFailed(selection.adapter(), SITE_OPEN_TARGET, failure);
                    if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                    return;
                }
            }

            familyStep = "backing";
            if (strategy == ObservationStrategy.ACTION_SCOPED_PHYSICAL
                    && selection.adapter().usesHandlerBackedView()
                    && selection.adapter().usesIndependentBackingTarget()
                    && independentTarget.isPresent()) {
                MenuAdapterContext context = new MenuAdapterContext(menu, player, false);
                ContainerTarget expected = independentTarget.orElseThrow();
                Supplier<Optional<MenuPhysicalBacking.Description>> producer = () -> {
                            try {
                                return selection.adapter().resolveMenuBackingTarget(context)
                                        .filter(next -> sameBackingTarget(expected, next, selection.adapter())
                                                && independentAccess(player, selection.adapter(), next))
                                        .flatMap(ignored -> selection.adapter().describePhysicalBacking(context));
                            } catch (RuntimeException | LinkageError failure) {
                                adapterFailed(selection.adapter(), SITE_OPEN_BACKING, failure);
                                return Optional.empty();
                            }
                        };
                handlerBacking = expected.backingContextKind() == BackingContextKind.SHARED_CHANNEL
                        ? MenuPhysicalBacking.resolveExactSharedChannel(menu, player.getInventory(),
                                expected.stableContextKey(), selection.adapter(), producer)
                        : MenuPhysicalBacking.resolve(menu, player.getInventory(), expected.stableContextKey(),
                                expected.backingContextKind(), producer);
            }

            if (strategy == ObservationStrategy.ACTION_SCOPED_PHYSICAL
                    && selection.adapter().usesHandlerBackedView()
                    && !selection.adapter().usesIndependentBackingTarget()
                    && matchesResolvedPhysicalTarget(recent, adapterPhysicalTarget)) {
                MenuAdapterContext context = new MenuAdapterContext(menu, player, false);
                MenuPhysicalBlockTarget expectedTarget = adapterPhysicalTarget.orElseThrow();
                handlerBacking = MenuPhysicalBacking.resolve(menu, player.getInventory(),
                        selection.adapterId() + ":" + physicalContextKey(player.level().dimension(), recent.pos),
                        () -> {
                            try {
                                return selection.adapter().resolvePhysicalBlockTarget(context)
                                        .filter(expectedTarget::equals)
                                        .flatMap(ignored -> selection.adapter().describePhysicalBacking(context));
                            } catch (RuntimeException | LinkageError failure) {
                                adapterFailed(selection.adapter(), SITE_OPEN_BACKING, failure);
                                return Optional.empty();
                            }
                        });
            }
            // SOPH-LABELS (review-01 MAJOR-1): the adapter's own name for the block this open admitted, read once.
            // A naming failure is never an adapter failure, so it never skips the open; empty keeps today's label.
            Optional<String> adapterLabel = handlerBacking.isPresent()
                    && matchesResolvedPhysicalTarget(recent, adapterPhysicalTarget)
                    ? adapterLabel(selection.adapter(), new MenuAdapterContext(menu, player, false)) : Optional.empty();

            familyStep = "access target";
            if ((strategy == ObservationStrategy.AUTHORITATIVE_OPERATION
                    || (selection.adapter().usesIndependentBackingTarget() && handlerBacking.isPresent()))
                    && accessTracking) {
                try {
                    authoritativeAccessTarget =
                            selection.adapter().resolveContainerAccessTarget(
                                    new MenuAdapterContext(menu, player, false));
                } catch (RuntimeException | LinkageError failure) {
                    adapterFailed(selection.adapter(), SITE_OPEN_ACCESS_TARGET, failure);
                    NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                    return;
                }
            }

            Optional<PhysicalContainerLocationBinding> physicalLocation = Optional.empty();
            familyStep = "location";
            if (strategy == ObservationStrategy.ACTION_SCOPED_PHYSICAL) {
                if (selection.adapter().usesIndependentBackingTarget()) {
                    if (independentTarget.isPresent()
                            && !independentAccess(player, selection.adapter(), independentTarget.orElseThrow())) {
                        if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                        return;
                    }
                } else if (selection.adapter() instanceof VanillaEnderChestMenuAdapter) {
                    if (recent != null && !stationaryNativeAccess(player.level(), recent.pos)) {
                        if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                        return;
                    }
                } else {
                    Level originalLevel = player.level();
                    if (selection.adapter().usesHandlerBackedView()) {
                        if (matchesResolvedPhysicalTarget(recent, adapterPhysicalTarget) && handlerBacking.isPresent()) {
                            MenuPhysicalBacking backing = handlerBacking.orElseThrow();
                            ContainerTarget nativeTarget = ContainerTarget.block(originalLevel.dimension().location().toString(),
                                    recent.pos, chosenLabel(adapterLabel,
                                            () -> physicalContainerLabel(player, recent.pos, recent.label)),
                                    backing.backingContextKind(), backing.targetIdentity());
                            physicalLocation = PhysicalContainerLocationBinding.capture(originalLevel, nativeTarget,
                                    backing.ownershipMembers(), () -> player.level() == originalLevel
                                            && player.serverLevel().getEntity(player.getUUID()) == player
                                            && player.containerMenu == menu && backing.isCurrent());
                        }
                    } else if (physical.isPresent()) {
                        WholeMenuPhysicalTargetResolver.ResolvedTarget resolved = physical.orElseThrow();
                        ContainerTarget nativeTarget = ContainerTarget.block(originalLevel.dimension().location().toString(),
                                resolved.accessPosition(), physicalContainerLabel(player, resolved.accessPosition(), recent.label),
                                resolved.backingContextKind(), resolved.stableContextKey());
                        List<Slot> originalSlots = List.copyOf(menu.slots);
                        physicalLocation = PhysicalContainerLocationBinding.capture(originalLevel, nativeTarget,
                                resolved.ownershipMembers(), () -> currentPhysicalMenu(player, menu, originalLevel,
                                        resolved, originalSlots));
                    }
                    if (physicalLocation.isEmpty()) {
                        if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                        return;
                    }
                }
            }

            familyStep = "access row";
            if (adapterFailedSince(adapterFailures)) {
                if (accessTracking) NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                return;
            }
            if (accessTracking) {
                Optional<ContainerAccessTargetSnapshot> accessTarget = resolveAccessTarget(
                        player,
                        menu,
                        recent,
                        physical,
                        selection,
                        strategy,
                        adapterPhysicalTarget,
                        authoritativeAccessTarget,
                        handlerBacking,
                        physicalLocation,
                        adapterLabel);
                if (accessTarget.isPresent()) {
                    if (selection.adapter().usesCapturedAccessAuthority()) {
                        Optional<BooleanSupplier> guard = captureExactAccessGuard(player, menu, selection.adapter());
                        if (guard.isPresent()) {
                            NeoForgeContainerAccessAuditBridge.observeOpen(player, menu,
                                    accessTarget.orElseThrow(), guard.orElseThrow());
                        } else {
                            NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                        }
                    } else if (selection.adapter().usesMovingBackingTarget()) {
                        Optional<BooleanSupplier> guard = captureMovingAccessGuard(player, menu, selection.adapter(),
                                handlerBacking, authoritativeAccessTarget.map(MenuAccessTarget::target));
                        if (guard.isPresent()) {
                            NeoForgeContainerAccessAuditBridge.observeOpen(player, menu,
                                    accessTarget.orElseThrow(), guard.orElseThrow());
                        } else {
                            // Motion history is an access requirement; current committed
                            // custody can remain provable without an OPEN/CLOSE witness.
                            NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                        }
                    } else if (physicalLocation.isPresent()) {
                        NeoForgeContainerAccessAuditBridge.observeOpen(player, menu,
                                accessTarget.orElseThrow(), physicalLocation.orElseThrow().accessGuard());
                    } else if (OptionalMenuAdapterLoader.isLootrMenu(new MenuAdapterContext(menu, player, false))) {
                        // A private inventory may be refreshed/replaced without replacing its menu.
                        // Retain the exact original owner binding even when transactions are disabled.
                        if (handlerBacking.isEmpty()) return;
                        NeoForgeContainerAccessAuditBridge.observeOpen(
                                player, menu, accessTarget.orElseThrow(), handlerBacking.orElseThrow()::isCurrent);
                    } else if (authoritativeAccessTarget.map(MenuAccessTarget::accessPoint).orElse(false)) {
                        // Access rows B65/B66 (Owner decision 4 A, 11-14; design 4.3): an access point is recorded
                        // only with its close guard, whatever the strategy (it never takes the unguarded path below).
                        // A failure in here never stops item tracking for this open: the failures it counted are
                        // added to this open's baseline.
                        adapterFailures += observeAccessPointOpen(player, menu, selection.adapter(),
                                accessTarget.orElseThrow(), authoritativeAccessTarget.orElseThrow());
                    } else {
                        NeoForgeContainerAccessAuditBridge.observeOpen(
                                player, menu, accessTarget.orElseThrow());
                    }
                } else {
                    NeoForgeContainerAccessAuditBridge.unresolvedTarget();
                }
            }

            familyStep = "session";
            if (!transactionTracking) return;
            if (adapterFailedSince(adapterFailures)) return;
            if (OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(selection.adapterId())) return;
            if (strategy == ObservationStrategy.ACTION_SCOPED_PHYSICAL) {
                if (selection.adapter() instanceof VanillaEnderChestMenuAdapter) {
                    if (recent == null
                            || !player.level().getBlockState(recent.pos).is(Blocks.ENDER_CHEST)) {
                        return;
                    }
                    Container container = player.getEnderChestInventory();
                    ContainerTarget target = ContainerTarget.block(
                            recent.dimension.location().toString(),
                            recent.pos,
                            physicalContainerLabel(player, recent.pos, recent.label),
                            BackingContextKind.PLAYER_BOUND,
                            "minecraft:ender_chest:" + player.getUUID());
                    BoundedMenuInventoryView view = resolveContainerView(
                            menu, player, selection.adapter(), container, target.stableContextKey());
                    if (view == null) return;
                    openPhysicalSession(id, new Session(
                            menu, target, container, view, strategy, selection.adapter()));
                    return;
                }

                if (selection.adapter().usesHandlerBackedView()) {
                    if (selection.adapter().usesIndependentBackingTarget()) {
                        if (independentTarget.isEmpty() || handlerBacking.isEmpty()) return;
                        openPhysicalSession(id, new Session(menu, independentTarget.orElseThrow(), null, null,
                                strategy, selection.adapter(), handlerBacking.orElseThrow()));
                        return;
                    }
                    if (!matchesResolvedPhysicalTarget(recent, adapterPhysicalTarget)
                            || player.level().getBlockEntity(recent.pos) == null
                            || handlerBacking.isEmpty()) {
                        return;
                    }
                    BlockPos pos = recent.pos.immutable();
                    String label = chosenLabel(adapterLabel, () -> physicalContainerLabel(player, pos, recent.label));
                    Optional<ContainerTarget> current = physicalLocation.orElseThrow().currentTarget();
                    // R2-menu (review-01 NIT-3): an adapter failure behind the binding's target is a skipped open.
                    if (current.isEmpty() && adapterFailedSince(adapterFailures)) return;
                    ContainerTarget target = current.orElseThrow();
                    openPhysicalSession(id, new Session(
                            menu,
                            target,
                            null,
                            null,
                            strategy,
                            selection.adapter(),
                            handlerBacking.orElseThrow(),
                            physicalLocation.orElseThrow()));
                    LOGGER.debug("Using exact handler-backed adapter {} for {} at {} ({})",
                            EventTrailDiagnostics.sanitizeLogValue(selection.adapterId(), 96),
                            EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                            pos,
                            EventTrailDiagnostics.sanitizeLogValue(label, 160));
                    return;
                }

                if (physical.isEmpty()) return;
                WholeMenuPhysicalTargetResolver.ResolvedTarget resolved = physical.orElseThrow();
                BlockPos pos = resolved.accessPosition();
                String label = physicalContainerLabel(player, pos, recent == null ? null : recent.label);
                Optional<ContainerTarget> current = physicalLocation.orElseThrow().currentTarget();
                // R2-menu (review-01 NIT-3): an adapter failure behind the binding's target is a skipped open.
                if (current.isEmpty() && adapterFailedSince(adapterFailures)) return;
                ContainerTarget target = current.orElseThrow();
                BoundedMenuInventoryView view = resolveContainerView(
                        menu,
                        player,
                        selection.adapter(),
                        resolved.backingContainer(),
                        target.stableContextKey());
                if (view == null) return;
                openPhysicalSession(id, new Session(
                        menu,
                        target,
                        resolved.backingContainer(),
                        view,
                        strategy,
                        selection.adapter(),
                        null,
                        physicalLocation.orElseThrow()
                ));

                LOGGER.debug("Using action-scoped physical container tracking for {} at {} ({})",
                        EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64), pos,
                        EventTrailDiagnostics.sanitizeLogValue(label, 160));
                return;
            }

            if (strategy != ObservationStrategy.AUTHORITATIVE_OPERATION) return;
            if (authoritativeTarget.isEmpty()) {
                LOGGER.debug(
                        "Exact virtual adapter {} could not prove an access target for {}; tracking is disabled for this menu",
                        EventTrailDiagnostics.sanitizeLogValue(selection.adapterId(), 96),
                        EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64));
                return;
            }

            ContainerTarget resolvedTarget = authoritativeTarget.orElseThrow();
            SESSIONS.put(id, new Session(
                    menu,
                    resolvedTarget,
                    null,
                    null,
                    strategy,
                    selection.adapter()));

            LOGGER.debug("Using exact operation adapter {} for {} at {} ({})",
                    EventTrailDiagnostics.sanitizeLogValue(selection.adapterId(), 96),
                    EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                    resolvedTarget.accessPosition(),
                    EventTrailDiagnostics.sanitizeLogValue(resolvedTarget.label(), 160));
        } catch (Throwable t) {
            openFailed = true;
            discardSessionState(id);
            LOGGER.error("Failed to initialize unified container tracking for {} ({}); "
                            + "the session will not be attributed",
                    EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                    EventTrailDiagnostics.safeFailureSummary(t));
        } finally {
            if (family != null) familyOpenEnded(family, familyAdapterId, id, menu, familyMark, openFailed, familyStep);
        }
    }

    /**
     * Catch-all: a selection the registry refused, counted for each adapter family it names (a multiple match names
     * several). An id no family specification names (EventTrail's own vanilla adapters) is not a miss. Nothing it
     * throws reaches the open, short of a {@link VirtualMachineError}, which propagates unchanged (review-01 m-1).
     */
    private static void familySelectionRefused(MenuTransactionAdapterRegistry.Selection selection) {
        try {
            if (selection.adapterId() == null) return;
            // review-01 n-2, re-check m-3: the registry's frozen UNSUPPORTED_VERSION is never a game version. It comes
            // from an OFF or unbuilt guard, or from an adapter that is on but does not support this menu class (AE2).
            // GF-WARNINGS (B47): each family's miss names which of the three it is.
            boolean unsupportedVersion = AdapterMatch.UNSUPPORTED_VERSION.name().equals(selection.reason());
            EnumMap<AdapterKey, String> families = new EnumMap<>(AdapterKey.class);
            for (String adapterId : selection.adapterId().split(",")) {
                AdapterKey key = adapterKeyForId(adapterId);
                if (key != null && !families.containsKey(key)) {
                    families.put(key, unsupportedVersion ? unsupportedVersionCause(adapterId.trim(), key)
                            : selection.reason());
                }
            }
            families.forEach((key, reason) -> AdapterFamilyMisses.miss(key, AdapterFamilyMisses.Path.REFUSED, reason));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            catchAllFailed(failure);
        }
    }

    /**
     * Catch-all: a family's selected adapter took this open; no transaction session for this menu is one miss. Nothing
     * it throws reaches the open, short of a {@link VirtualMachineError}, which propagates unchanged (review-01 m-1).
     * A selected adapter found disabled at run time (the open's existing check after selection) is named as such
     * (re-check out-of-scope note). GF-WARNINGS (B47): {@code step} names the step of the open last entered (adapter
     * checks, target, backing, access target, location, access row, session), or is null before the first.
     */
    private static void familyOpenEnded(AdapterKey family, String adapterId, UUID id, AbstractContainerMenu menu,
            long mark, boolean openFailed, String step) {
        try {
            Session session = SESSIONS.get(id);
            if (session != null && isExactMenuIdentity(session.menu, menu)) return;
            String reason = openFailed ? "the open failed (see the error above)"
                    : adapterFailedSince(mark) ? "an adapter call failed during the open"
                    : OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(adapterId) ? "adapter disabled at run time"
                    : "target or backing not resolved";
            if (step != null) reason = reason + " (failed step: " + step + ")";
            AdapterFamilyMisses.miss(family, AdapterFamilyMisses.Path.UNRESOLVED, reason);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            catchAllFailed(failure);
        }
    }

    /**
     * GF-WARNINGS (B47): which of the three causes an UNSUPPORTED_VERSION refusal of {@code adapterId} (family
     * {@code key}) has. Reads the fail-safe and the published status only; changes nothing.
     */
    private static String unsupportedVersionCause(String adapterId, AdapterKey key) {
        OptionalIntegrationCompatibility.IntegrationState state;
        boolean unreadable = false;
        try {
            state = OptionalMenuAdapterLoader.compatibilitySnapshot().find(key);
        } catch (RuntimeException | LinkageError failure) {
            state = null;
            unreadable = true;
        }
        return unsupportedVersionCause(adapterId != null && adapterId.endsWith("compatibility-block"),
                AdapterFailSafe.switchedOff(key) || OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(adapterId), state,
                unreadable);
    }

    /** B47 decision (test seam) for a published status that was read; see the four-argument form. */
    static String unsupportedVersionCause(boolean blocker, boolean off,
            OptionalIntegrationCompatibility.IntegrationState state) {
        return unsupportedVersionCause(blocker, off, state, false);
    }

    /**
     * B47 decision (test seam): a key switched OFF (or an adapter disabled at run time) is "adapter off"; a key whose
     * adapter could not be built (ADAPTER_UNAVAILABLE, the retry handle) is "adapter not built"; any other blocker
     * (the {@code <id>compatibility-block} form) or a status entry that is not in use is "adapter off"; an adapter
     * that is on and built answers it for a menu class it does not support. r3 (review-01 NIT-10): when the published
     * status could not be read ({@code unreadable}) and nothing above proves a cause, the cause is "not known" rather
     * than a guess of "menu not supported".
     */
    static String unsupportedVersionCause(boolean blocker, boolean off,
            OptionalIntegrationCompatibility.IntegrationState state, boolean unreadable) {
        if (off) return CATCH_ALL_ADAPTER_OFF;
        if (state != null && state.warningReason() == OptionalIntegrationCompatibility.WarningReason.ADAPTER_UNAVAILABLE) {
            return CATCH_ALL_ADAPTER_NOT_BUILT;
        }
        if (blocker || state != null && state.contractState() != OptionalIntegrationCompatibility.ContractState.ABSENT
                && !state.inUse()) {
            return CATCH_ALL_ADAPTER_OFF;
        }
        return unreadable ? CATCH_ALL_CAUSE_NOT_KNOWN : CATCH_ALL_MENU_NOT_SUPPORTED;
    }

    /**
     * Own-slot screens "opened" line (Owner decision A, 11-14), for a screen no adapter and no vanilla handling takes.
     * The OPEN row (its CLOSE is paired by the access bridge) is written only when the screen opened on the same server
     * tick as this player's use-click on that block, after that block's own use started (r5, Advisor M-1: the click is
     * armed at the BLOCK phase of NeoForge's item-on-block event, a spectator's at the click), no item use or later
     * click (whose handlers, another mod's included, run after the HIGHEST marks) could have opened it instead, the
     * clicked block and its block entity are still there, no slot of the screen shows another block's storage, and
     * either some slot is not provably the player's own inventory or the clicked block holds items the server can see
     * (Owner Q16 and decision 32 A: a screen with no slot, or only the player's own inventory, gets no row and is
     * counted NO_ITEM_SLOTS only when the clicked block also exposes no container and no item handler). The row names
     * the clicked block and carries the "items not tracked" provider; no item row is ever written for the screen. Any
     * other open gets no row and is counted (one WARN per reason per server start); it never blocks the menu. Returns
     * whether the OPEN was handed to the access bridge. Nothing it throws reaches the open short of a
     * {@link VirtualMachineError}, which propagates unchanged; a failed check is counted and writes nothing.
     */
    private static boolean itemsNotTrackedOpen(ServerPlayer player, AbstractContainerMenu menu, RecentTarget recent) {
        String clickedId = recent == null || RecentTarget.CLICK_UNREAD.equals(recent.blockId) ? null : recent.blockId;
        try {
            ItemsNotTrackedOpens.Refusal refusal = null;
            Level level = player.level();
            BlockEntity block = null;
            BlockItems blockItems = null;
            if (recent == null || recent.blockId == null) {
                refusal = ItemsNotTrackedOpens.Refusal.NO_BLOCK_CLICK;
            } else if (recent.tick == RecentTarget.NO_TICK || clickedId == null) {
                refusal = ItemsNotTrackedOpens.Refusal.CHECK_FAILED;
            } else if (recent.tick != currentTick(player)) {
                refusal = ItemsNotTrackedOpens.Refusal.LATER_TICK;
            } else if (!recent.armed) {
                refusal = ItemsNotTrackedOpens.Refusal.NO_BLOCK_USE;
            } else if (recent.itemMayOpen) {
                refusal = ItemsNotTrackedOpens.Refusal.ITEM_USE;
            } else if ((block = level.getBlockEntity(recent.pos)) == null) {
                refusal = ItemsNotTrackedOpens.Refusal.NO_BLOCK_ENTITY;
            } else if (!clickedId.equals(canonicalBlockId(player, recent.pos))) {
                refusal = ItemsNotTrackedOpens.Refusal.BLOCK_CHANGED;
            } else if (!slotsShowOnlyThisBlock(player, menu, level, recent.pos)) {
                refusal = ItemsNotTrackedOpens.Refusal.OTHER_BLOCK;
            } else if (!hasSlotThatMayHoldThisBlocksItems(player, menu)
                    && !(blockItems = clickedBlockItems(level, recent.pos, block)).held()) {
                // Owner Q16: a screen that stores no items writes no OPEN or CLOSE row. Decision 32 A: only when the
                // clicked block also holds no items the server can see (no container, no item handler).
                refusal = ItemsNotTrackedOpens.Refusal.NO_ITEM_SLOTS;
            }
            if (refusal == null) {
                BlockPos pos = recent.pos;
                BlockEntity opened = block;
                ContainerTarget nativeTarget = ContainerTarget.block(level.dimension().location().toString(), pos,
                        containerAccessDisplayLabel(player, pos, recent.label), BackingContextKind.WORLD_BLOCK,
                        ITEMS_NOT_TRACKED_KEY + physicalContextKey(level.dimension(), pos) + ":" + clickedId);
                // The CLOSE row's guard (review-01 NIT-2: with the player-entity check the two physical guards carry).
                Optional<PhysicalContainerLocationBinding> binding = PhysicalContainerLocationBinding.capture(level,
                        nativeTarget, List.of(opened), () -> player.level() == level
                                && player.serverLevel().getEntity(player.getUUID()) == player
                                && player.containerMenu == menu && level.getBlockEntity(pos) == opened);
                Optional<ContainerTarget> current = binding.flatMap(PhysicalContainerLocationBinding::currentTarget);
                if (binding.isEmpty() || current.isEmpty()) {
                    refusal = ItemsNotTrackedOpens.Refusal.NOT_BOUND;
                } else {
                    ContainerAccessTargetSnapshot target = ContainerAccessTargetSnapshot.from(current.orElseThrow(),
                            clickedId, ContainerAccessPayloadCodec.ITEMS_NOT_TRACKED_PROVIDER);
                    NeoForgeContainerAccessAuditBridge.observeOpen(player, menu, target,
                            binding.orElseThrow().accessGuard());
                    // review-01 NIT-1: counted once the bridge returned, so a failure there is never also "written".
                    ItemsNotTrackedOpens.written();
                    if (blockItems != null && blockItems.unreadable() != null) {
                        // Decision 32 A: kept because the clicked block's item handler could not be read.
                        ItemsNotTrackedOpens.blockItemsUnread(clickedId, screenId(menu), blockItems.unreadable());
                    }
                    return true;
                }
            }
            ItemsNotTrackedOpens.refused(refusal, clickedId, screenId(menu));
            return false;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            ItemsNotTrackedOpens.refused(ItemsNotTrackedOpens.Refusal.CHECK_FAILED, clickedId, null);
            return false;
        }
    }

    /**
     * Own-slot "opened" line: false when a slot of the menu visibly holds another block's storage (a block entity at
     * another position or in another level, directly, through a SlotItemHandler or its InvWrapper), a double
     * container, or a null slot, or when the menu has more slots than are ever checked. A slot whose storage shows no
     * block (the mod's own slot object) says nothing either way.
     */
    private static boolean slotsShowOnlyThisBlock(ServerPlayer player, AbstractContainerMenu menu, Level level,
            BlockPos pos) {
        List<Slot> slots = menu.slots;
        if (slots.size() > MAX_MENU_DESCRIPTORS) return false;
        for (Slot slot : slots) {
            if (slot == null) return false;
            Object storage = slot.container;
            if (slot instanceof SlotItemHandler handlerSlot) {
                storage = handlerSlot.getItemHandler();
                if (storage instanceof InvWrapper wrapper) storage = wrapper.getInv();
            }
            if (storage == player.getInventory()) continue;
            if (storage instanceof CompoundContainer) return false;
            if (storage instanceof BlockEntity other
                    && (other.getLevel() != level || !pos.equals(other.getBlockPos()))) return false;
        }
        return true;
    }

    /**
     * Owner Q16 ("Opening a screen that stores no items writes no OPEN or CLOSE row"): true when some slot of the menu
     * is not provably this player's own inventory, so its storage may be the clicked block's. False for a menu with no
     * slot at all, or one whose every slot is provably the player's own inventory (such a screen stores no items). A
     * slot whose storage proves nothing (the mod's own slot object) and a null slot count as possibly the block's.
     */
    private static boolean hasSlotThatMayHoldThisBlocksItems(ServerPlayer player, AbstractContainerMenu menu) {
        Inventory inventory = player.getInventory();
        WrapperFields fields = wrapperFields();
        for (Slot slot : menu.slots) {
            if (slot == null || !isPlayersOwnInventorySlot(slot, inventory, fields)) return true;
        }
        return false;
    }

    /**
     * Owner decision 32 A: true when the clicked block holds items the server can see, so a screen whose slots show
     * only the player's own inventory (or that has no slot) keeps its "items not tracked" line. That is when its block
     * entity is a vanilla {@link Container}, or the block exposes NeoForge's item handler capability at its position,
     * on the null side or any side. A lookup that throws counts as holding items (the line is kept, the true row); the
     * caller counts it once the line is written, with one WARN per server start. A {@link VirtualMachineError}
     * propagates unchanged. Terminals are never told apart by slots, class names or mod ids.
     */
    private static BlockItems clickedBlockItems(Level level, BlockPos pos, BlockEntity block) {
        if (block instanceof Container) return BlockItems.HELD;
        try {
            BlockState state = level.getBlockState(pos);
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, block, null) != null) {
                return BlockItems.HELD;
            }
            for (Direction side : Direction.values()) {
                if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, block, side) != null) {
                    return BlockItems.HELD;
                }
            }
            return BlockItems.NONE;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return new BlockItems(true, unreadable);
        }
    }

    /**
     * Decision 32 A: whether the clicked block holds items the server can see; {@code unreadable} is the failure when
     * its item handler could not be read (it then counts as holding items, and is counted once the line is written).
     */
    private record BlockItems(boolean held, Throwable unreadable) {
        static final BlockItems HELD = new BlockItems(true, null);
        static final BlockItems NONE = new BlockItems(false, null);
    }

    /**
     * True only when {@code slot} provably shows {@code inventory}: its container is that inventory, or it is a
     * {@link SlotItemHandler} over a handler {@link #wrapsInventory} proves. Nothing else proves it. A slot whose
     * container is the player's inventory is trusted whatever the slot's class, as {@link #slotsShowOnlyThisBlock}
     * trusts it (review-01 NIT-3: vanilla's armor and offhand slots are such subclasses).
     */
    private static boolean isPlayersOwnInventorySlot(Slot slot, Inventory inventory, WrapperFields fields) {
        if (slot.container == inventory) return true;
        return slot instanceof SlotItemHandler handlerSlot
                && wrapsInventory(handlerSlot.getItemHandler(), inventory, fields, 0);
    }

    /** review-01 MINOR-1: how many range or combined wrappers may hold one another before nothing is proven. */
    private static final int MAX_WRAPPER_DEPTH = 2;

    /**
     * True only when {@code handler} provably shows {@code inventory}, by exact class (a subclass proves nothing):
     * {@link InvWrapper} over it; {@link PlayerMainInvWrapper} or {@link PlayerArmorInvWrapper} of it (their public
     * getter); {@link EntityHandsInvWrapper} or {@link EntityArmorInvWrapper} of its own player (their protected entity
     * field); and, while fewer than {@link #MAX_WRAPPER_DEPTH} wrappers hold it, {@link RangedWrapper} or
     * {@link PlayerOffhandInvWrapper} whose composed handler is proven (their private compose field), or
     * {@link CombinedInvWrapper} or {@link PlayerInvWrapper} with at least one part and every part proven (their
     * protected parts field). A field that could not be opened, or read, proves nothing, so that screen keeps its line.
     */
    private static boolean wrapsInventory(IItemHandler handler, Inventory inventory, WrapperFields fields, int depth) {
        if (handler == null) return false;
        Class<?> type = handler.getClass();
        if (type == InvWrapper.class) return ((InvWrapper) handler).getInv() == inventory;
        if (type == PlayerMainInvWrapper.class) return ((PlayerMainInvWrapper) handler).getInventoryPlayer() == inventory;
        if (type == PlayerArmorInvWrapper.class) {
            return ((PlayerArmorInvWrapper) handler).getInventoryPlayer() == inventory;
        }
        try {
            if (type == EntityHandsInvWrapper.class || type == EntityArmorInvWrapper.class) {
                Field entity = fields.equipmentEntity();
                return entity != null && inventory.player != null && entity.get(handler) == inventory.player;
            }
            if (depth >= MAX_WRAPPER_DEPTH) return false;
            if (type == RangedWrapper.class || type == PlayerOffhandInvWrapper.class) {
                Field compose = fields.rangedCompose();
                return compose != null && compose.get(handler) instanceof IItemHandler inner
                        && wrapsInventory(inner, inventory, fields, depth + 1);
            }
            if (type == CombinedInvWrapper.class || type == PlayerInvWrapper.class) {
                Field parts = fields.combinedParts();
                if (parts == null || !(parts.get(handler) instanceof IItemHandlerModifiable[] handlers)
                        || handlers.length == 0) {
                    return false;
                }
                for (IItemHandlerModifiable part : handlers) {
                    if (!wrapsInventory(part, inventory, fields, depth + 1)) return false;
                }
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return false;
        }
        return false;
    }

    /**
     * NeoForge's wrapper fields that have no getter: {@link CombinedInvWrapper}'s parts, {@link RangedWrapper}'s
     * composed handler and {@link EntityEquipmentInvWrapper}'s entity. Each is null when it could not be opened
     * (nothing is proven through it then).
     */
    record WrapperFields(Field combinedParts, Field rangedCompose, Field equipmentEntity) {
    }

    /** Test seam (review-01 MINOR-2): the fields the wrapper checks read instead of the opened ones; null restores. */
    private static volatile WrapperFields wrapperFieldsForTests;

    static void useWrapperFieldsForTests(WrapperFields fields) {
        wrapperFieldsForTests = fields;
    }

    private static WrapperFields wrapperFields() {
        WrapperFields forTests = wrapperFieldsForTests;
        return forTests != null ? forTests : NeoForgeWrapperFields.OPENED;
    }

    /** Opens each field once per JVM (this holder's initializer), so a field that failed warns once per JVM. */
    private static final class NeoForgeWrapperFields {
        static final WrapperFields OPENED = new WrapperFields(
                openWrapperField(CombinedInvWrapper.class, "itemHandler", LOGGER::warn),
                openWrapperField(RangedWrapper.class, "compose", LOGGER::warn),
                openWrapperField(EntityEquipmentInvWrapper.class, "entity", LOGGER::warn));

        private NeoForgeWrapperFields() {
        }
    }

    /**
     * {@code owner}'s field {@code name}, opened with trySetAccessible (the code's existing pattern), or null when it
     * cannot be opened; then one WARN naming the class and field goes to {@code warn} (review-01 MINOR-2). It never
     * throws short of a {@link VirtualMachineError}.
     */
    static Field openWrapperField(Class<?> owner, String name, Consumer<String> warn) {
        Field field = null;
        try {
            Field declared = owner.getDeclaredField(name);
            if (declared.trySetAccessible()) field = declared;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            field = null;
        }
        if (field == null) {
            try {
                warn.accept("EventTrail could not open the field " + name + " of " + owner.getName() + "; a screen"
                        + " whose slots show the player's own inventory through that NeoForge wrapper keeps its"
                        + " \"items not tracked\" opened line");
            } catch (RuntimeException | LinkageError ignored) {
                // Only a WARN; the holder's initializer never throws.
            }
        }
        return field;
    }

    /** The menu type's registry id, for a WARN only; null when the menu has none. */
    private static String screenId(AbstractContainerMenu menu) {
        try {
            var key = BuiltInRegistries.MENU.getKey(menu.getType());
            return key == null ? null : key.toString();
        } catch (RuntimeException | LinkageError unknown) {
            return null;
        }
    }

    /**
     * Catch-all (review-01 m-1): its own check or count failed; one rate-limited WARN, the open is unchanged. The text
     * claims nothing about the menu or its rows: the failure can come before the check knows either (re-check n-7).
     */
    private static void catchAllFailed(Throwable failure) {
        try {
            CATCH_ALL_FAILED.acquire().ifPresent(emission -> LOGGER.warn(
                    "EventTrail's unrecorded-menu catch-all could not check or count a container open ({}); the open "
                            + "is unchanged; suppressed {} such failure(s) since the last warning",
                    EventTrailDiagnostics.safeFailureSummary(failure), emission.suppressedSinceLastEmission()));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Never rethrown.
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        // R2-menu: the outermost catch of this entry point.
        try {
            closeContainer(event);
        } catch (Throwable failure) {
            entryFailed("container close", eventPlayer(event), failure);
        }
    }

    private static void closeContainer(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // fix-3: an untracked synthetic actor touches no state keyed by the (possibly shared) UUID it carries.
        // GF-FAKEPLAYER-ID: the close of an admitted session is type-only; it can run after PlayerList removal.
        if (!trackedActorType(player)) return;

        AbstractContainerMenu menu = event.getContainer();
        NeoForgeContainerAccessAuditBridge.observeClose(player, menu);

        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !isExactMenuIdentity(session.menu, menu)) return;
        if (!SESSIONS.remove(player.getUUID(), session)) return;
        clearActiveAuthoritativeForSession(session);
        if (OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(session.adapter.adapterId())) {
            // GF-WARNINGS (GF-QUICKMOVE carry): items held from the device at this close are counted, not dropped
            // silently.
            if (session.cursorEscrow.isPresent() || session.cursorRoleEscrow.isPresent()) {
                closeCursorUntraced(session, "adapter-disabled");
            }
            session.clearActionState();
            return;
        }

        if (session.strategy == ObservationStrategy.ACTION_SCOPED_PHYSICAL) {
            resolvePhysicalClose(player, session);
        }
    }

    /*
     * These are non-container changes to the player's inventory that can happen
     * while a menu is open. Resyncing after them prevents the fallback from
     * incorrectly calling them deposits/withdrawals.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) resync(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) resync(player);
    }

    /*
     * NeoForge removes the stack from inventory even when a toss is cancelled.
     * This handler writes no audit row; it resyncs that real possession change.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onItemToss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) resync(player);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) resync(player);
    }

    /**
     * Resynchronizes physical-menu evidence after another proven operation
     * changes the player's inventory. Some legitimate partial item pickups do
     * not emit NeoForge's pickup Post event, so the exact insertion boundary
     * calls this directly once it proves a real possession gain.
     */
    public static void onCommittedPlayerInventoryMutation(ServerPlayer player) {
        if (player != null) resync(player);
    }

    public static void onPlayerLogout(UUID playerId) {
        SESSIONS.remove(playerId);
        RECENT_TARGETS.remove(playerId);
        NeoForgeContainerAccessAuditBridge.onPlayerLogout(playerId);
        ActionToken active = ACTIVE_AUTHORITATIVE_ACTION.get();
        if (active != null && active.playerId.equals(playerId)) {
            active.abortAuthoritative();
            ACTIVE_AUTHORITATIVE_ACTION.remove();
        }
    }

    /**
     * Stop/restart cleanup. It never fabricates a close or a transfer. af-3 (Owner decision R-M1/R-m6 b, re-check
     * m4): each open transaction session and an in-flight authoritative action dropped here is counted once as a
     * skipped capture attempt; the container-access bridge counts its own sessions.
     */
    public static void clearRuntimeState() {
        int dropped = 0;
        for (UUID id : SESSIONS.keySet()) {
            if (SESSIONS.remove(id) != null) dropped++;
        }
        RECENT_TARGETS.clear();
        NeoForgeContainerAccessAuditBridge.clearRuntimeState();
        ActionToken active = ACTIVE_AUTHORITATIVE_ACTION.get();
        if (active != null) {
            active.abortAuthoritative();
            dropped++;
        }
        ACTIVE_AUTHORITATIVE_ACTION.remove();
        MovingStorageEpoch.resetRuntime();
        AdapterFamilyMisses.reset();
        ItemsNotTrackedOpens.reset();
        ItemClickOverrideProof.resetRuntime();
        runtimeGeneration = Math.incrementExact(runtimeGeneration);
        if (dropped > 0) EventTrailManager.getInstance().countDroppedCaptures(dropped);
    }

    static boolean trackingEnabled() {
        // Recording switches are applied only after custody/cursor state settles.
        // Retain observation even when both directions are disabled, including
        // config changes during an already-open menu.
        return EventTrailManager.getInstance().isReady();
    }

    private static boolean anyContainerTrackingEnabled() {
        return trackingEnabled() || NeoForgeContainerAccessAuditBridge.trackingEnabled();
    }

    static boolean isExactMenuIdentity(Object trackedMenu, Object observedMenu) {
        return trackedMenu != null && trackedMenu == observedMenu;
    }

    /** Captures immutable pre-state at the proven ordinary-menu packet boundary. */
    public static ActionToken beforeMenuAction(
            ServerPlayer player,
            AbstractContainerMenu menu,
            int clickedSlotIndex,
            ClickType clickType) {
        // R2-menu: the outermost catch of this entry point; a failure never stops the click itself.
        try {
            return captureMenuAction(player, menu, clickedSlotIndex, clickType);
        } catch (Throwable failure) {
            entryFailed("container action capture", player, failure);
            return null;
        }
    }

    private static ActionToken captureMenuAction(
            ServerPlayer player,
            AbstractContainerMenu menu,
            int clickedSlotIndex,
            ClickType clickType) {
        if (player == null || menu == null || !preparePlayerTracking(player)) return null;
        Session session = SESSIONS.get(player.getUUID());
        if (session != null
                && OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(session.adapter.adapterId())) {
            session.clearActionState();
            SESSIONS.remove(player.getUUID(), session);
            return null;
        }
        if (session == null || session.menu != menu) return null;
        if (session.actionInProgress) {
            // R2-menu (lead r3, Q2 site 1): a click seen while this session's action is open runs inside that
            // action (the flag is set only just before a token is returned and every end of that token clears it),
            // so the open action's before/after snapshots span this click: its escrow update, or its own skip exit
            // (which marks the session), already accounts for this click's cursor movement. No mark is needed.
            // P-1: this holds only while every caller ends its token on every exit. The vanilla click bracket once
            // left the token open when the drop boundary's begin or finish failed, and every later click of the
            // session then returned here unseen until close; the P-1 fix ends the token there too, for any
            // throwable. The AE2, Sophisticated Backpacks and Refined Storage brackets do the same (P-1 MINOR-3).
            return null;
        }
        if (!EventTrailManager.getInstance().admitCapture()) {
            // R2-menu (lead r3, Q2 site 1): the Owner's counted not-ready skip (admitCapture counts it) is unchanged.
            // The click is not observed, so it may move device items onto or off the cursor; the escrow is cleared
            // and the session is marked, so no later click turns those items into a deposit or a withdrawal.
            session.clearUnresolvedActionEvidence();
            session.cursorProvenanceLost = true;
            return null;
        }

        if (session.strategy == ObservationStrategy.AUTHORITATIVE_OPERATION) {
            return beginAuthoritativeAction(player, menu, session);
        }
        if (session.strategy != ObservationStrategy.ACTION_SCOPED_PHYSICAL) return null;

        long adapterFailures = GuardedMenuAdapter.failuresOnThisThread();
        try {
            Optional<BooleanSupplier> actionGuard = captureActionGuard(session.adapter,
                    new MenuAdapterContext(menu, player, false));
            if (!actionGuardCurrent(session.adapter, actionGuard)) {
                session.clearUnresolvedActionEvidence();
                session.cursorProvenanceLost = true;
                actionGuardRefused(session);
                return null;
            }
            ContainerTarget actionTarget = session.target;
            if (session.physicalLocation != null) {
                var current = currentPhysicalTarget(player, session);
                if (current.isEmpty()) {
                    endActionOrSession(player, session, adapterFailures, "native backing target");
                    return null;
                }
                actionTarget = current.orElseThrow();
            }
            if (session.adapter.usesIndependentBackingTarget()) {
                Optional<ContainerTarget> current = currentPhysicalTarget(player, session);
                if (current.isEmpty()) {
                    endActionOrSession(player, session, adapterFailures, "backing target");
                    return null;
                }
                actionTarget = current.orElseThrow();
            }
            CapturedActionState before = capturePhysicalAction(player, session);
            if (before == null) {
                endActionOrSession(player, session, adapterFailures, "pre-action");
                return null;
            }
            if (session.cursorProvenanceLost) {
                if (!before.custody().cursor().isEmpty()) {
                    // R2-menu (review-01 MAJOR-1): an earlier action of this session was not recorded, so the items
                    // on the cursor may have come from the device; returning them would read as a deposit. This
                    // action records nothing (a counted drop with a warning) until a click starts with an empty
                    // cursor, which proves the cursor's provenance again.
                    session.clearUnresolvedActionEvidence();
                    cursorProvenanceDrop(session);
                    return null;
                }
                session.cursorProvenanceLost = false;
            }
            session.actionInProgress = true;
            InventorySlotRole clickedRole = classifyClickedRole(
                    player, session, clickedSlotIndex);
            // GF-QUICKMOVE: the before-click half of the deposit role proof. It runs before the two checks below, so
            // a probe failure counted by the fail-safe skips this action like any other capture failure.
            Map<InventoryItemIdentity, Set<InventorySlotRole>> beforeAcceptingDepositRoles =
                    probeDepositRolesBeforeClick(player, session, clickedSlotIndex, clickType, clickedRole);
            if (OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(session.adapter.adapterId())) {
                session.clearActionState();
                SESSIONS.remove(player.getUUID(), session);
                return null;
            }
            if (adapterFailedSince(adapterFailures)) {
                // R2-menu (design 2.4): an adapter failed while this action was captured (for example a guard that
                // could not be produced): the action records nothing; the session stays, with no evidence to inherit.
                session.clearActionState();
                session.cursorProvenanceLost = true;
                return null;
            }
            return new ActionToken(
                    player,
                    session,
                    session.nextActionSequence(),
                    before,
                    clickType,
                    clickedRole,
                    clickedSlotIndex,
                    session.cursorRoleEscrow,
                    actionTarget,
                    actionGuard,
                    beforeAcceptingDepositRoles);
        } catch (RuntimeException | LinkageError failure) {
            endActionOrSession(player, session, adapterFailures, "pre-action");
            LOGGER.warn("Container action capture failed for {}; no transaction was attributed ({})",
                    EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                    EventTrailDiagnostics.safeFailureSummary(failure));
            return null;
        }
    }

    /**
     * GF-QUICKMOVE: before the click runs, probes every real-device-custody slot with {@link Slot#mayPlace} for the
     * stacks that this click can move into the device indirectly, and returns, per exact item, the roles with at least
     * one accepting slot. {@link #isTransferCompatibleWithAction} adds these roles to the roles that accept after the
     * click, for deposits only. A slot whose {@code mayPlace} turns false once the click has filled the device (a
     * shared capacity, for example) is then still proven by its answer from before the click.
     *
     * <p>Bound (per-click mod code): nothing is probed for a click on a device slot (the policy decides it by the
     * clicked role alone), and only two click types are probed: {@link ClickType#QUICK_MOVE} on a menu slot (the
     * clicked slot's stack, and the carried stack) and {@link ClickType#QUICK_CRAFT} outside the menu (the end of a
     * drag; the carried stack). Empty stacks are skipped, the two stacks share one probe when they are the same item,
     * and a role is probed only until one of its slots accepts the item, so a single-role menu stops at the first
     * accepting slot. Any failure (counted or not) discards every answer of this probe, so no role is accepted from it.
     * </p>
     */
    private static Map<InventoryItemIdentity, Set<InventorySlotRole>> probeDepositRolesBeforeClick(
            ServerPlayer player,
            Session session,
            int clickedSlotIndex,
            ClickType clickType,
            InventorySlotRole clickedRole) {
        if (clickedRole.isRealDeviceCustody()) return Map.of();
        boolean quickMove = clickType == ClickType.QUICK_MOVE && clickedSlotIndex >= 0;
        boolean dragEnd = clickType == ClickType.QUICK_CRAFT
                && clickedSlotIndex == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE;
        if (!quickMove && !dragEnd) return Map.of();
        MenuAdapterContext context = new MenuAdapterContext(
                session.menu, player, session.physicalContainer != null);
        long adapterFailures = GuardedMenuAdapter.failuresOnThisThread();
        try {
            List<MenuObservedSlot> observedSlots = observedHandlerSlots(
                    session.menu, session.adapter, context);
            if (observedSlots == null) return Map.of();
            List<ItemStack> candidates = new ArrayList<>(2);
            if (quickMove) {
                for (MenuObservedSlot observed : observedSlots) {
                    if (observed.menuSlotIndex() == clickedSlotIndex) {
                        candidates.add(observed.slot().getItem());
                        break;
                    }
                }
            }
            candidates.add(session.menu.getCarried());
            Map<InventoryItemIdentity, ItemStack> probes = new HashMap<>();
            for (ItemStack candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                InventoryItemIdentity item;
                try {
                    item = InventoryItemIdentity.of(candidate);
                } catch (IllegalArgumentException unidentified) {
                    continue; // no audit identity, so no transfer of it can be proven: nothing to probe
                }
                probes.putIfAbsent(item, item.stackWithCount(1));
            }
            if (probes.isEmpty()) return Map.of();
            Map<InventoryItemIdentity, Set<InventorySlotRole>> accepting = new HashMap<>();
            for (MenuObservedSlot observed : observedSlots) {
                Slot slot = observed.slot();
                InventorySlotRole role = session.adapter.classifySlot(
                        context, observed.menuSlotIndex(), slot);
                if (!role.isRealDeviceCustody()) continue;
                for (Map.Entry<InventoryItemIdentity, ItemStack> probe : probes.entrySet()) {
                    Set<InventorySlotRole> roles = accepting.computeIfAbsent(
                            probe.getKey(), ignored -> EnumSet.noneOf(InventorySlotRole.class));
                    if (!roles.contains(role) && slot.mayPlace(probe.getValue())) roles.add(role);
                }
            }
            // A role the guard answered UNKNOWN after a failure must not narrow or widen this proof silently.
            if (adapterFailedSince(adapterFailures)) return Map.of();
            Map<InventoryItemIdentity, Set<InventorySlotRole>> proven = new HashMap<>();
            accepting.forEach((item, roles) -> {
                if (!roles.isEmpty()) proven.put(item, java.util.Collections.unmodifiableSet(roles));
            });
            return Map.copyOf(proven);
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(session.adapter, SITE_ACTION_ROLE, failure);
            return Map.of();
        }
    }

    /** Begins one exact optional-mod action that does not use the vanilla click packet. */
    public static ActionToken beforeAuthoritativeMenuAction(
            ServerPlayer player, AbstractContainerMenu menu) {
        // R2-menu: the outermost catch of this entry point.
        try {
            return startAuthoritativeMenuAction(player, menu);
        } catch (Throwable failure) {
            entryFailed("exact container action start", player, failure);
            return null;
        }
    }

    private static ActionToken startAuthoritativeMenuAction(
            ServerPlayer player, AbstractContainerMenu menu) {
        if (player == null || menu == null || !preparePlayerTracking(player)) return null;
        Session session = SESSIONS.get(player.getUUID());
        if (session == null
                || session.menu != menu
                || session.strategy != ObservationStrategy.AUTHORITATIVE_OPERATION
                || session.actionInProgress
                || OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(session.adapter.adapterId())
                || !EventTrailManager.getInstance().admitCapture()) {
            return null;
        }
        return beginAuthoritativeAction(player, menu, session);
    }

    private static ActionToken beginAuthoritativeAction(
            ServerPlayer player, AbstractContainerMenu menu, Session session) {
        if (ACTIVE_AUTHORITATIVE_ACTION.get() != null) {
            invalidateSession(player, session, "nested authoritative action");
            return null;
        }
        long adapterFailures = GuardedMenuAdapter.failuresOnThisThread();
        try {
            Optional<ContainerTarget> resolved = session.adapter.resolveAuthoritativeTarget(
                    new MenuAdapterContext(menu, player, false));
            if (resolved.isEmpty()) {
                endActionOrSession(player, session, adapterFailures, "authoritative target");
                return null;
            }
            session.actionInProgress = true;
            ActionToken token = ActionToken.authoritative(
                    player,
                    session,
                    session.nextActionSequence(),
                    resolved.orElseThrow());
            ACTIVE_AUTHORITATIVE_ACTION.set(token);
            return token;
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(session.adapter, SITE_AUTH_BEGIN, failure);
            endActionOrSession(player, session, adapterFailures, "authoritative action");
            return null;
        }
    }

    /**
     * Accepts one exact committed item operation from an optional-mod boundary.
     * Calls outside the currently bracketed player/menu action are ignored.
     */
    public static void recordAuthoritativeTransfer(
            ServerPlayer actor,
            ItemStack stack,
            TransactionDirection direction,
            long quantity) {
        // R2-menu: the outermost catch of this entry point; the open action is discarded, never half-recorded.
        try {
            collectAuthoritativeTransfer(actor, stack, direction, quantity);
        } catch (Throwable failure) {
            try {
                abortCurrentAuthoritativeAction();
            } catch (Throwable ignored) {
                // entryFailed below still ends the session, which discards its open action.
            }
            entryFailed("exact container transfer", actor, failure);
        }
    }

    private static void collectAuthoritativeTransfer(
            ServerPlayer actor,
            ItemStack stack,
            TransactionDirection direction,
            long quantity) {
        if (actor == null || stack == null || stack.isEmpty() || quantity <= 0L) return;
        ActionToken token = ACTIVE_AUTHORITATIVE_ACTION.get();
        if (token == null || !token.authoritative()) return;
        Session session = token.session;
        // GF-WARNINGS (B46, KLL R5): a mismatch inside one exact action is named and counted (once per refused
        // transfer). The capture gate stays last and is not warned here: it counts its own refusals.
        String changed = actor.getServer() == null || !actor.getServer().isSameThread() ? "off-thread"
                : actor != token.player || !actor.getUUID().equals(token.playerId) ? "player"
                : actor.containerMenu != session.menu ? "menu"
                : SESSIONS.get(token.playerId) != session ? "session"
                : !session.actionInProgress ? "not-in-action"
                : OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(session.adapter.adapterId()) ? "adapter-disabled"
                : null;
        if (changed != null || !EventTrailManager.getInstance().admitCapture()) {
            if (changed != null) {
                RecordingGaps.drop(adapterKey(session.adapter), RecordingGaps.Gap.AUTHORITATIVE_CONTEXT_CHANGED, changed);
            }
            token.abortAuthoritative();
            return;
        }

        try {
            if (!token.authoritativeCollector.record(
                    direction, InventoryItemIdentity.of(stack), quantity)
                    && token.authoritativeCollector.invalid()
                    && !token.authoritativeOverflowReported) {
                token.authoritativeOverflowReported = true;
                LOGGER.warn(
                        "EventTrail discarded an over-sized exact container action from {}; the {}-transfer bound was exceeded",
                        EventTrailDiagnostics.sanitizeLogValue(actor.getName().getString(), 64),
                        AuthoritativeTransactionCollector.MAX_TRANSFERS_PER_ACTION);
            }
        } catch (RuntimeException | LinkageError failure) {
            token.abortAuthoritative();
            LOGGER.warn("Exact container evidence capture failed for {}; the action was discarded ({})",
                    EventTrailDiagnostics.sanitizeLogValue(actor.getName().getString(), 64),
                    EventTrailDiagnostics.safeFailureSummary(failure));
        }
    }

    /** Completes one successful Minecraft menu action without invoking menu behavior again. */
    public static void afterSuccessfulMenuAction(
            ServerPlayer player, AbstractContainerMenu menu, ActionToken token) {
        if (token == null) return;
        // R2-menu: the outermost catch of this entry point covers the whole method, the token and session
        // checks before the two branches included (review-01 NIT-1).
        try {
            Session session = token.session;
            // GF-NESTED: the item-click override proof of exactly this action; taking it clears it on every exit.
            Optional<ItemClickOverrideProof.Proof> overrideProof = ItemClickOverrideProof.take(token.player, menu,
                    new ItemClickOverrideProof.ActionKey(session.sessionId, token.actionSequence));
            if (token.authoritative()) {
                ActionToken active = ACTIVE_AUTHORITATIVE_ACTION.get();
                if (active != token) token.abortAuthoritative();
                ACTIVE_AUTHORITATIVE_ACTION.remove();
            }
            session.actionInProgress = false;
            // GF-WARNINGS (B46, KLL R5; GF-QUICKMOVE carry "the close inside a click"): the player, menu or session
            // changed before this action completed. It records nothing, as before, and is named and counted.
            String changed = player == null || player.getUUID() == null || player != token.player ? "player"
                    : menu == null || session.menu != menu ? "menu"
                    : SESSIONS.get(player.getUUID()) != session ? "session"
                    : player.containerMenu != menu ? "open-menu"
                    : null;
            if (changed != null) {
                token.abortAuthoritative();
                session.clearActionState();
                SESSIONS.remove(token.playerId, session);
                // r3 NIT-4: a session the tracker itself ended inside this click already has its WARN for it.
                if (!("session".equals(changed) && session.invalidatedWithWarning)) {
                    RecordingGaps.drop(adapterKey(session.adapter), RecordingGaps.Gap.ACTION_CONTEXT_CHANGED, changed);
                }
                return;
            }

            if (token.authoritative()) {
                // R2-menu: this branch is guarded too (design 2.2 G); a failure while the evidence is built hands
                // nothing to storage and ends the session, and nothing reaches the mod or packet handling.
                try {
                    List<AuthoritativeTransactionCollector.Transfer> transfers =
                            token.authoritativeCollector.commit();
                    // A2-44: every transfer's evidence is built first; the action is then published once, whole.
                    List<InventoryTransactionEvidence> action = new ArrayList<>(transfers.size());
                    for (AuthoritativeTransactionCollector.Transfer transfer : transfers) {
                        InventoryTransactionEvidence evidence = new InventoryTransactionEvidence(
                                System.currentTimeMillis(),
                                player.getUUID(),
                                player.getName().getString(),
                                EventTrailEventHandler.isFakePlayer(player),
                                session.sessionId,
                                token.actionSequence,
                                transfer.direction(),
                                transfer.item(),
                                transfer.quantity(),
                                token.authoritativeTarget,
                                session.adapter.adapterId(),
                                EvidenceKind.AUTHORITATIVE_MOD_OPERATION,
                                InventorySlotRole.STORAGE);
                        action.add(evidence);
                    }
                    publishTransfers(player, action);
                } catch (Throwable failure) {
                    entryFailed("exact container action completion", player, failure);
                }
                return;
            }

            long adapterFailures = GuardedMenuAdapter.failuresOnThisThread();
            try {
                if (!actionGuardCurrent(session.adapter, token.actionGuard)) {
                    session.clearUnresolvedActionEvidence();
                    session.cursorProvenanceLost = true;
                    actionGuardRefused(session);
                    return;
                }
                CapturedActionState after = capturePhysicalAction(player, session);
                if (after == null) {
                    endActionOrSession(player, session, adapterFailures, "post-action");
                    return;
                }
                if (!actionGuardCurrent(session.adapter, token.actionGuard)) {
                    session.clearUnresolvedActionEvidence();
                    session.cursorProvenanceLost = true;
                    actionGuardRefused(session);
                    return;
                }
                // Freeze the action's current physical location after committed custody
                // capture. The opening/pre-action pose never substitutes for this fact.
                Optional<ContainerTarget> currentTarget = currentPhysicalTarget(player, session)
                        .filter(target -> sameBackingTarget(token.physicalTarget, target, session.adapter));
                if (currentTarget.isEmpty()) {
                    endActionOrSession(player, session, adapterFailures, "committed target");
                    return;
                }

                PhysicalInventoryActionCorrelator.Result result =
                        PhysicalInventoryActionCorrelator.correlate(
                                token.before.custody(), after.custody(), session.cursorEscrow);
                // GF-NESTED: the second pass, only after the component-exact pass rejected a click that an item-click
                // override handled. Every row the first pass writes stays as it is.
                PhysicalInventoryRoleResolver.Result nestedRoles = null;
                if (!result.accepted() && overrideProof.isPresent()) {
                    NestedClickCustody.Outcome nested = NestedClickCustody.resolve(
                            overrideProof.orElseThrow(),
                            classifyClickedRole(player, session, overrideProof.orElseThrow().menuSlotIndex()),
                            token.clickType,
                            token.clickedRole,
                            token.clickedSlotIndex,
                            token.before.custody(),
                            after.custody(),
                            session.cursorEscrow,
                            roleSnapshot(token.before.device()),
                            roleSnapshot(after.device()),
                            session.cursorRoleEscrow);
                    if (nested.accepted()) {
                        result = nested.custody().orElseThrow();
                        nestedRoles = nested.roles().orElseThrow();
                    } else {
                        LOGGER.debug("Container action for {} was not attributable as a nested transfer ({})",
                                EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                                nested.reason());
                    }
                }
                if (!result.accepted()) {
                    // GF-NESTED (apotheosis#8): read before the escrow is cleared below.
                    Optional<PhysicalInventoryRoleResolver.RoleTransfer> escrowWithdrawal =
                            NestedClickCustody.escrowLeftTheCursor(
                                    token.clickType,
                                    token.clickedRole,
                                    token.before.custody(),
                                    after.custody(),
                                    session.cursorEscrow,
                                    session.cursorRoleEscrow);
                    session.clearUnresolvedActionEvidence();
                    session.cursorProvenanceLost = true;
                    LOGGER.debug("Container action for {} was not attributable ({})",
                            EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                            result.failureReason());
                    boolean escrowPublished = false;
                    if (escrowWithdrawal.isPresent()) {
                        if (isTransferCompatibleWithAction(player, token, escrowWithdrawal.orElseThrow())
                                && !adapterFailedSince(adapterFailures)) {
                            publishTransfers(player, List.of(physicalTransferEvidence(player, session,
                                    token.actionSequence, escrowWithdrawal.orElseThrow(),
                                    currentTarget.orElseThrow())));
                            escrowPublished = true;
                        } else {
                            LOGGER.debug("The escrowed withdrawal for {} was not written: the action's role check or "
                                            + "an adapter failure refused it",
                                    EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64));
                        }
                    }
                    // GF-WARNINGS (B45, r2 m-2): counted only when no row was written for this click: the first pass
                    // refused, the nested pass (if it ran) refused too, and the escrow rule published nothing. The
                    // detail is the first pass's reason only; the nested reason stays in the debug line above.
                    if (!escrowPublished) {
                        RecordingGaps.drop(adapterKey(session.adapter), RecordingGaps.Gap.ACTION_NOT_ATTRIBUTABLE,
                                String.valueOf(result.failureReason()));
                    }
                    return;
                }

                PhysicalInventoryRoleResolver.Result roleResult = nestedRoles != null
                        ? nestedRoles
                        : PhysicalInventoryRoleResolver.resolve(
                                result,
                                roleSnapshot(token.before.device()),
                                roleSnapshot(after.device()),
                                session.cursorRoleEscrow);
                if (!roleResult.accepted()) {
                    session.clearUnresolvedActionEvidence();
                    session.cursorProvenanceLost = true;
                    LOGGER.debug("Container action for {} had ambiguous slot-role evidence ({})",
                            EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                            roleResult.failureReason());
                    RecordingGaps.drop(adapterKey(session.adapter), RecordingGaps.Gap.ACTION_ROLE_AMBIGUOUS,
                            String.valueOf(roleResult.failureReason()));
                    return;
                }

                if (roleResult.transfers().stream().anyMatch(transfer ->
                        !isTransferCompatibleWithAction(player, token, transfer))) {
                    session.clearUnresolvedActionEvidence();
                    session.cursorProvenanceLost = true;
                    if (OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(session.adapter.adapterId())) {
                        SESSIONS.remove(token.playerId, session);
                    }
                    LOGGER.debug("Container action for {} changed a role outside the proven click path",
                            EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64));
                    RecordingGaps.drop(adapterKey(session.adapter), RecordingGaps.Gap.ACTION_OUTSIDE_CLICK_PATH, null);
                    return;
                }
                if (adapterFailedSince(adapterFailures)) {
                    // R2-menu (design 2.4): an adapter failed while this action was reconciled: it records nothing and
                    // leaves no escrow; the session stays.
                    session.clearUnresolvedActionEvidence();
                    session.cursorProvenanceLost = true;
                    return;
                }

                session.cursorEscrow = result.nextEscrow();
                session.cursorRoleEscrow = roleResult.nextEscrow();
                session.lastPlayerTotals = after.custody().player();
                // A2-44: every transfer's evidence is built first; the action is then published once, whole.
                List<InventoryTransactionEvidence> action = new ArrayList<>(roleResult.transfers().size());
                for (PhysicalInventoryRoleResolver.RoleTransfer transfer : roleResult.transfers()) {
                    action.add(physicalTransferEvidence(
                            player, session, token.actionSequence, transfer, currentTarget.orElseThrow()));
                }
                publishTransfers(player, action);
            } catch (RuntimeException | LinkageError failure) {
                endActionOrSession(player, session, adapterFailures, "post-action");
                LOGGER.warn("Container action reconciliation failed for {}; no transaction was attributed ({})",
                        EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                        EventTrailDiagnostics.safeFailureSummary(failure));
            } catch (Throwable failure) {
                // R2-menu: nothing reaches packet handling after the click has happened.
                entryFailed("container action completion", player, failure);
            }
        } catch (Throwable failure) {
            entryFailed("container action completion", player, failure);
        }
    }

    /**
     * GF-NESTED: the open action-scoped physical action of this player's session on exactly this menu, if one is open
     * (between {@link #beforeMenuAction} returning its token and the end of that token). The item-click override proof
     * is bound to it, so a proof can never be used by another action, player or menu.
     */
    static Optional<ItemClickOverrideProof.ActionKey> openPhysicalAction(
            ServerPlayer player, AbstractContainerMenu menu) {
        if (player == null || menu == null || player.getUUID() == null) return Optional.empty();
        Session session = SESSIONS.get(player.getUUID());
        if (session == null
                || session.menu != menu
                || !session.actionInProgress
                || session.strategy != ObservationStrategy.ACTION_SCOPED_PHYSICAL) {
            return Optional.empty();
        }
        return Optional.of(new ItemClickOverrideProof.ActionKey(session.sessionId, session.actionSequence));
    }

    /**
     * Clears unresolved evidence when the wrapped Minecraft action itself fails. GF-WARNINGS r3 (MINOR-3, lead ruling
     * 1): the action records nothing, so it is counted as {@link RecordingGaps.Gap#MENU_ACTION_FAILED} under the
     * session adapter's key (or the unkeyed WARN), unless an adapter failure or a recording gap was already counted on
     * this thread since the token was made (then that count is the record of this action).
     */
    public static void menuActionFailed(ActionToken token) {
        // GF-NESTED (review-01 n-4): before every exit below, the thrown click's item-click override recording (if
        // any) is dropped, so the thread keeps no player or menu reference. It never throws.
        ItemClickOverrideProof.clearThread();
        if (token == null) return;
        // R2-menu: the outermost catch; this runs while the mod's own failure is on its way out and must not
        // replace it.
        try {
            Session session = token.session;
            boolean alreadyCounted = alreadyCounted(token);
            token.abortAuthoritative();
            if (ACTIVE_AUTHORITATIVE_ACTION.get() == token) {
                ACTIVE_AUTHORITATIVE_ACTION.remove();
            }
            session.clearActionState();
            SESSIONS.remove(token.playerId, session);
            // r4 (review-02 NIT-2): a session the tracker itself ended inside this action already has its WARN for it.
            if (!alreadyCounted && !session.invalidatedWithWarning) {
                RecordingGaps.drop(adapterKey(session.adapter), RecordingGaps.Gap.MENU_ACTION_FAILED, null);
            }
        } catch (Throwable failure) {
            entryFailed("failed menu action", token.player, failure);
        }
    }

    /**
     * GF-WARNINGS r3: on the thread that made {@code token}, an adapter failure, skip or refusal, or a recording gap
     * was counted since then under the session adapter's own key (r4, review-02 MINOR-A: a count for another key
     * does not count this action), or that session adapter's guard counted a failure. On any other thread the marks
     * say nothing, so the answer is false (count it).
     */
    static boolean alreadyCounted(ActionToken token) {
        return token.madeOn == Thread.currentThread()
                && (AdapterFailSafe.countedOnThisThread(token.countedKey) != token.countedMark
                || GuardedMenuAdapter.failuresOnThisThread() != token.guardFailureMark);
    }

    /**
     * Discards the calling thread's open exact action, if one is open, so its
     * bracket commits no transfer (a guarded hook skipped part of it; lead
     * decision L15 G1). The action stays open, so {@link #afterSuccessfulMenuAction}
     * or {@link #menuActionFailed} still closes it. Touches only the calling
     * thread's state; with no open action it does nothing.
     */
    public static void abortCurrentAuthoritativeAction() {
        ActionToken active = ACTIVE_AUTHORITATIVE_ACTION.get();
        if (active != null) active.abortAuthoritative();
    }

    private static CapturedActionState capturePhysicalAction(
            ServerPlayer player, Session session) {
        if (session.physicalLocation != null && currentPhysicalTarget(player, session).isEmpty()) return null;
        if (session.deviceView == null && session.physicalBacking == null) return null;
        // R2-menu (review-01 MINOR-1): a guarded reader that failed has already counted and reported its own failure;
        // the integrity reason below is reported only when no adapter failure was counted during this capture.
        long readerFailures = GuardedMenuAdapter.failuresOnThisThread();
        BoundedMenuInventoryView.Capture device = session.physicalBacking == null
                ? session.deviceView.capture()
                : session.physicalBacking.capture().orElse(null);
        if (device == null) {
            if (session.physicalBacking != null && session.physicalBacking.readFailureObserved()
                    && !adapterFailedSince(readerFailures)) {
                adapterInvalid(session.adapter, SITE_ACTION_CAPTURE, "backing-read-failed");
            }
            return null;
        }
        if (!device.valid()) {
            if (!adapterFailedSince(readerFailures)) {
                adapterInvalid(session.adapter, SITE_ACTION_CAPTURE, "device-capture-invalid");
            }
            return null;
        }
        Snapshot playerInventory = snapshotPlayerInventory(player);
        Snapshot cursor = snapshotCursor(session.menu);
        if (!playerInventory.complete || !cursor.complete) return null;
        if (session.physicalLocation != null && currentPhysicalTarget(player, session).isEmpty()) return null;
        return new CapturedActionState(
                new PhysicalInventoryActionCorrelator.Snapshot(
                        device.totals(), playerInventory.totals, cursor.totals),
                device);
    }

    private static BoundedMenuInventoryView resolveContainerView(
            AbstractContainerMenu menu,
            ServerPlayer player,
            MenuTransactionAdapter adapter,
            Container backingContainer,
            String targetIdentity) {
        if (menu == null || player == null || adapter == null || backingContainer == null) return null;
        if (!isMenuSlotCountWithinObservationBound(menu.slots.size())) return null;
        MenuAdapterContext context = new MenuAdapterContext(menu, player, true);
        List<BoundedMenuInventoryView.SlotBinding> bindings = new ArrayList<>();
        // R2-menu: a guarded adapter answers a failed call with its "nothing" value; any failure here means no view.
        long adapterFailures = GuardedMenuAdapter.failuresOnThisThread();
        try {
            for (int menuSlotIndex = 0; menuSlotIndex < menu.slots.size(); menuSlotIndex++) {
                Slot slot = menu.slots.get(menuSlotIndex);
                if (slot.container != backingContainer) continue;
                InventorySlotRole role = adapter.classifySlot(context, menuSlotIndex, slot);
                if (adapterFailedSince(adapterFailures)) return null;
                if (!role.isRealDeviceCustody()) {
                    adapterInvalid(adapter, SITE_SLOT_VIEW, "slot-role-not-custody");
                    return null;
                }
                bindings.add(new BoundedMenuInventoryView.SlotBinding(
                        slot,
                        role,
                        targetIdentity,
                        adapter.resolveSlotAccess(context, menuSlotIndex, slot)));
                if (adapterFailedSince(adapterFailures)) return null;
            }
        } catch (RuntimeException | LinkageError failure) {
            if (!adapterFailed(adapter, SITE_SLOT_VIEW, failure)) {
                LOGGER.error("EventTrail container adapter {} failed while resolving physical slots ({})",
                        EventTrailDiagnostics.sanitizeLogValue(adapter.adapterId(), 96),
                        EventTrailDiagnostics.safeFailureSummary(failure));
            }
            return null;
        }
        BoundedMenuInventoryView.Resolution resolution = BoundedMenuInventoryView.resolve(bindings);
        if (!resolution.valid()) adapterInvalid(adapter, SITE_SLOT_VIEW, "slot-view-invalid");
        return resolution.view().orElse(null);
    }

    /**
     * R2-menu: an exception from adapter work reached the tracker (an adapter that is not wrapped in
     * {@link GuardedMenuAdapter}, or EventTrail code around adapter work). Sorted by the fail-safe: a missing member
     * switches that adapter's key OFF until restart; any other failure skips just this open or action with a
     * de-duplicated warning, and the adapter stays on (it is no longer switched off for every failure). Returns
     * whether the adapter has a key; an adapter without one (EventTrail's own vanilla adapters) is not reported.
     */
    private static boolean adapterFailed(MenuTransactionAdapter adapter, String site, Throwable failure) {
        AdapterKey key = adapterKey(adapter);
        if (key == null) return false;
        GuardedMenuAdapter.countFailureOnThisThread();
        AdapterFailSafe.report(key, site, failure);
        return true;
    }

    /**
     * R2-menu (design 2.5): an integrity failure that is not an exception. That open or action records nothing, one
     * de-duplicated warning, and the adapter stays on. Returns whether the adapter has a key.
     */
    private static boolean adapterInvalid(MenuTransactionAdapter adapter, String site, String reason) {
        AdapterKey key = adapterKey(adapter);
        if (key == null) return false;
        GuardedMenuAdapter.countFailureOnThisThread();
        AdapterFailSafe.invalid(key, site, reason);
        return true;
    }

    private static AdapterKey adapterKey(MenuTransactionAdapter adapter) {
        if (adapter == null) return null;
        AdapterKey key = GuardedMenuAdapter.keyOf(adapter);
        if (key != null) return key;
        try {
            return OptionalMenuAdapterLoader.keyForAdapter(adapter.adapterId());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Catch-all: the family key of an adapter id the registry named, or null when no specification names it. */
    private static AdapterKey adapterKeyForId(String adapterId) {
        return adapterKeyForId(adapterId, OptionalMenuAdapterLoader::keyForAdapter);
    }

    /**
     * As {@link #adapterKeyForId(String)} with the lookup given (test seam). Advisor NIT N-1 (catch-all r3): short of a
     * {@link VirtualMachineError}, which propagates unchanged like the other catch-all methods, a failed lookup is null.
     */
    static AdapterKey adapterKeyForId(String adapterId, Function<String, AdapterKey> lookup) {
        try {
            return lookup.apply(adapterId.trim());
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** R2-menu: an adapter failure was counted on this thread since {@code mark} was read. */
    private static boolean adapterFailedSince(long mark) {
        return GuardedMenuAdapter.failuresOnThisThread() != mark;
    }

    /**
     * R2-menu skip-not-invalidate (design 2.4): when an exact adapter failed during this action (counted on this
     * thread by the guard or by an integrity check; the guard turns an adapter exception into a "nothing" answer, so
     * it reaches every session end of the three action methods, not only their catch blocks), only the action is
     * skipped. It records nothing; the cursor escrow and the other unresolved evidence are cleared so no later action
     * inherits them; the session stays, and the next action proves everything again. Any other cause (an incomplete
     * snapshot, a target that moved, no failure at all) still ends the session.
     *
     * <p>Items picked up from the device may still be on the cursor after the skip (held before it, or moved by the
     * skipped click), and the cleared escrow no longer says so. The session is therefore marked: until a click starts
     * with an empty cursor, a click that starts with items on the cursor records nothing (review-01 MAJOR-1).</p>
     */
    private static void endActionOrSession(
            ServerPlayer player, Session session, long adapterFailures, String phase) {
        if (adapterFailedSince(adapterFailures)) {
            clearActiveAuthoritativeForSession(session);
            session.clearActionState();
            session.cursorProvenanceLost = true;
            return;
        }
        invalidateSession(player, session, phase);
    }

    /**
     * R2-menu (review-01 MAJOR-1): one click dropped because the cursor's provenance was lost. Counted like the other
     * skipped actions of this adapter (fail-safe ledger, de-duplicated warning). A session whose adapter has no key
     * (EventTrail's own vanilla adapters) has no ledger entry; it gets a rate-limited WARN with the count suppressed
     * since the last one.
     */
    private static void cursorProvenanceDrop(Session session) {
        if (adapterInvalid(session.adapter, SITE_ACTION_CAPTURE, CURSOR_PROVENANCE_LOST_REASON)) return;
        CURSOR_PROVENANCE_LOST.acquire().ifPresent(emission -> LOGGER.warn(
                "EventTrail did not record a container action: an earlier action in the same container was not "
                        + "recorded, so the items on the cursor could not be traced to where they came from; nothing "
                        + "is recorded until the cursor is empty; suppressed {} such action(s) since the last warning",
                emission.suppressedSinceLastEmission()));
    }

    /**
     * GF-WARNINGS (GF-QUICKMOVE carry): a close that could not prove where the device's items held on the cursor went.
     * It records nothing, as before; the drop is counted under the session adapter's key (never OFF) or, without a
     * key, under the vanilla limiter, with {@code detail} naming the failed check.
     */
    private static void closeCursorUntraced(Session session, String detail) {
        RecordingGaps.drop(adapterKey(session.adapter), RecordingGaps.Gap.CLOSE_CURSOR_UNTRACED, detail);
    }

    /**
     * GF-WARNINGS r2 (SCM-ENDER review, "outside the lane"): the session adapter's action guard was not current at the
     * start or end of one click (the storage behind the open menu changed, the adapter is disabled at run time, or
     * the guard threw, which {@link #actionGuardCurrent} has already reported). The click records nothing, as before,
     * and is now counted under ACTION_CONTEXT_CHANGED instead of being dropped silently.
     */
    private static void actionGuardRefused(Session session) {
        String detail = ACTION_GUARD_DETAIL;
        try {
            if (session.adapter == null
                    || OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(session.adapter.adapterId())) {
                detail = RecordingGaps.ADAPTER_DISABLED;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The detail stays "action-guard"; a warning never reaches the action it describes.
        }
        RecordingGaps.drop(adapterKey(session.adapter), RecordingGaps.Gap.ACTION_CONTEXT_CHANGED, detail);
    }

    /**
     * R2-menu (lead r3, Q2 site 2): opens an action-scoped physical session. Items already on the cursor when the
     * session opens (or a cursor that cannot be read) have no proven source, so the session starts marked: its first
     * click that starts with items on the cursor records nothing (a counted drop), and a click that starts with an
     * empty cursor clears the mark as usual.
     */
    private static void openPhysicalSession(UUID id, Session session) {
        Snapshot cursor = snapshotCursor(session.menu);
        if (!cursor.complete || !cursor.totals.isEmpty()) session.cursorProvenanceLost = true;
        SESSIONS.put(id, session);
    }

    /**
     * R2-menu: the outermost catch of a tracker entry point. Nothing is rethrown into Minecraft, NeoForge or a mod.
     * The tracked player's session ends, so nothing is attributed until the next open, and one rate-limited WARN is
     * written (stack trace at DEBUG). An untracked (synthetic) actor's state is never touched (fix-3).
     *
     * <p>review-01 MINOR-6: when a live session is discarded here and its adapter has a fail-safe key, the drop is
     * also counted in that key's skipped attempts (an integrity skip, so it never switches the adapter off). An open
     * or close that failed after the entry point had already removed the session is warned only.</p>
     */
    private static void entryFailed(String entry, ServerPlayer player, Throwable failure) {
        try {
            if (player != null && preparePlayerTracking(player)) {
                Session dropped = SESSIONS.get(player.getUUID());
                discardSessionState(player.getUUID());
                if (dropped != null) {
                    AdapterKey key = adapterKey(dropped.adapter);
                    if (key != null) AdapterFailSafe.invalid(key, SITE_ENTRY_POINT, "entry-failed");
                }
            }
        } catch (Throwable ignored) {
            // The warning below is still written.
        }
        try {
            ENTRY_FAILED.acquire().ifPresent(emission -> LOGGER.warn(
                    "EventTrail container tracking failed in {} ({}); that player's container session was ended and "
                            + "nothing was attributed; suppressed {} such failure(s) since the last warning",
                    entry, EventTrailDiagnostics.safeFailureSummary(failure),
                    emission.suppressedSinceLastEmission()));
            LOGGER.debug("Container tracking entry point {} failed", entry, failure);
        } catch (Throwable ignored) {
            // Never rethrown.
        }
    }

    private static ServerPlayer eventPlayer(PlayerEvent event) {
        try {
            return event != null && event.getEntity() instanceof ServerPlayer player ? player : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Snapshot snapshotPlayerInventory(ServerPlayer player) {
        try {
            return snapshotItems(
                    player.getInventory().getContainerSize(),
                    player.getInventory()::getItem,
                    null);
        } catch (RuntimeException | LinkageError ignored) {
            return Snapshot.invalid();
        }
    }

    private static Snapshot snapshotCursor(AbstractContainerMenu menu) {
        if (menu == null) return Snapshot.invalid();
        try {
            return snapshotItems(0, ignored -> ItemStack.EMPTY, menu::getCarried);
        } catch (RuntimeException | LinkageError ignored) {
            return Snapshot.invalid();
        }
    }

    private static PhysicalInventoryRoleResolver.RoleSnapshot roleSnapshot(
            BoundedMenuInventoryView.Capture capture) {
        Map<InventorySlotRole, Map<InventoryItemIdentity, Long>> totals =
                new java.util.EnumMap<>(InventorySlotRole.class);
        for (BoundedMenuInventoryView.CapturedSlot slot : capture.slots()) {
            if (slot.item().isEmpty()) continue;
            Map<InventoryItemIdentity, Long> roleTotals = totals.computeIfAbsent(
                    slot.role(), ignored -> new HashMap<>());
            roleTotals.merge(slot.item().orElseThrow(), (long) slot.count(), Math::addExact);
        }
        return new PhysicalInventoryRoleResolver.RoleSnapshot(totals);
    }

    private static InventorySlotRole classifyClickedRole(
            ServerPlayer player, Session session, int clickedSlotIndex) {
        try {
            MenuAdapterContext context = new MenuAdapterContext(
                    session.menu, player, session.physicalContainer != null);
            List<MenuObservedSlot> observedSlots = observedHandlerSlots(
                    session.menu, session.adapter, context);
            if (observedSlots == null) return InventorySlotRole.UNKNOWN;
            MenuObservedSlot clickedSlot = observedSlots.stream()
                    .filter(observed -> observed.menuSlotIndex() == clickedSlotIndex)
                    .findFirst()
                    .orElse(null);
            if (clickedSlot == null) return InventorySlotRole.UNKNOWN;
            return session.adapter.classifySlot(
                    context,
                    clickedSlotIndex,
                    clickedSlot.slot());
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(session.adapter, SITE_ACTION_ROLE, failure);
            return InventorySlotRole.UNKNOWN;
        }
    }

    /**
     * Builds one bounded, duplicate-free observation list. Exact adapters may
     * add proven virtual slots, but an index collision fails this action closed
     * (R2-menu: an integrity failure, reported to the fail-safe; the adapter stays
     * on) rather than letting two different backing cells share one player action.
     */
    static List<MenuObservedSlot> observedHandlerSlots(
            AbstractContainerMenu menu,
            MenuTransactionAdapter adapter,
            MenuAdapterContext context) {
        if (menu == null || adapter == null || context == null
                || !isMenuSlotCountWithinObservationBound(menu.slots.size())) {
            return null;
        }
        List<MenuObservedSlot> observed = new ArrayList<>();
        Set<Integer> indices = new HashSet<>();
        Set<Slot> slotIdentities = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            if (slot == null || !slotIdentities.add(slot)) {
                adapterInvalid(adapter, SITE_OBSERVED_SLOTS, "duplicate-slot-identity");
                return null;
            }
            observed.add(new MenuObservedSlot(index, slot));
            indices.add(index);
        }
        long adapterFailures = GuardedMenuAdapter.failuresOnThisThread();
        List<MenuObservedSlot> additionalSlots = adapter.additionalObservedSlots(context);
        // The guard already reported its own failure (it answers null then); no second report for it.
        if (adapterFailedSince(adapterFailures)) return null;
        if (additionalSlots == null
                || additionalSlots.size() > MAX_MENU_DESCRIPTORS - observed.size()
                || (!additionalSlots.isEmpty() && !adapter.exact())) {
            adapterInvalid(adapter, SITE_OBSERVED_SLOTS, "additional-slots-invalid");
            return null;
        }
        for (MenuObservedSlot additional : additionalSlots) {
            if (additional == null
                    || additional.menuSlotIndex() >= MAX_MENU_DESCRIPTORS
                    || additional.slot().index != additional.menuSlotIndex()
                    || !indices.add(additional.menuSlotIndex())
                    || !slotIdentities.add(additional.slot())) {
                adapterInvalid(adapter, SITE_OBSERVED_SLOTS, "additional-slot-invalid");
                return null;
            }
            observed.add(additional);
        }
        if (!isMenuSlotCountWithinObservationBound(observed.size())) {
            adapterInvalid(adapter, SITE_OBSERVED_SLOTS, "observed-slots-over-bound");
            return null;
        }
        return List.copyOf(observed);
    }

    /**
     * Confirms that resolved role evidence agrees with the actual clicked path.
     * This rejects synchronous processing that could otherwise make an input
     * deposit look like a deposit into a generated-output slot (or vice versa).
     */
    private static boolean isTransferCompatibleWithAction(
            ServerPlayer player,
            ActionToken token,
            PhysicalInventoryRoleResolver.RoleTransfer transfer) {
        ItemStack probe = transfer.item().stackWithCount(1);
        MenuAdapterContext context = new MenuAdapterContext(
                token.session.menu, player, token.session.physicalContainer != null);
        Set<InventorySlotRole> acceptingDepositRoles = EnumSet.noneOf(InventorySlotRole.class);
        Set<InventorySlotRole> extractableWithdrawalRoles = EnumSet.noneOf(InventorySlotRole.class);
        long adapterFailures = GuardedMenuAdapter.failuresOnThisThread();
        try {
            List<MenuObservedSlot> observedSlots = observedHandlerSlots(
                    token.session.menu, token.session.adapter, context);
            if (observedSlots == null) return false;
            for (MenuObservedSlot observed : observedSlots) {
                Slot slot = observed.slot();
                InventorySlotRole role = token.session.adapter.classifySlot(
                        context, observed.menuSlotIndex(), slot);
                if (!role.isRealDeviceCustody()) continue;
                if (slot.mayPlace(probe)) acceptingDepositRoles.add(role);
                if (slot.mayPickup(player)) extractableWithdrawalRoles.add(role);
            }
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(token.session.adapter, SITE_ACTION_RECONCILE, failure);
            return false;
        }
        // R2-menu: a role the guard answered UNKNOWN after a failure must not narrow this check silently.
        if (adapterFailedSince(adapterFailures)) return false;
        // GF-QUICKMOVE: for a deposit, a role is also proven when one of its real slots accepted this exact item
        // before the click (probeDepositRolesBeforeClick). A slot that refuses the item once the click has filled the
        // device no longer hides a true deposit. Only probe answers count, never the click type; the policy's two
        // earlier rules (no deposit into an output or byproduct role, and the direct-click rule) still run first.
        if (transfer.direction() == TransactionDirection.DEPOSIT) {
            acceptingDepositRoles.addAll(
                    token.beforeAcceptingDepositRoles.getOrDefault(transfer.item(), Set.of()));
        }
        return PhysicalInventoryActionRolePolicy.allows(
                transfer,
                token.clickedRole,
                token.clickType,
                token.priorRoleEscrow,
                acceptingDepositRoles,
                extractableWithdrawalRoles);
    }

    static boolean isMenuSlotCountWithinObservationBound(int slotCount) {
        return slotCount >= 0 && slotCount <= MAX_MENU_DESCRIPTORS;
    }

    /** The close-retention withdrawal: one transfer, published as its own action. */
    private static void logActionTransfer(ServerPlayer player, Session session, long actionSequence,
            PhysicalInventoryRoleResolver.RoleTransfer transfer, ContainerTarget target) {
        publishTransfers(player,
                List.of(physicalTransferEvidence(player, session, actionSequence, transfer, target)));
    }

    private static InventoryTransactionEvidence physicalTransferEvidence(ServerPlayer player, Session session,
            long actionSequence, PhysicalInventoryRoleResolver.RoleTransfer transfer, ContainerTarget target) {
        return new InventoryTransactionEvidence(
                System.currentTimeMillis(),
                player.getUUID(),
                player.getName().getString(),
                EventTrailEventHandler.isFakePlayer(player),
                session.sessionId,
                actionSequence,
                transfer.direction(),
                transfer.item(),
                transfer.quantity(),
                target,
                session.adapter.adapterId(),
                EvidenceKind.ACTION_SCOPED_PHYSICAL,
                transfer.role());
    }

    private static void resolvePhysicalClose(ServerPlayer player, Session session) {
        // GF-RESYNC r2 (review-01 MINOR-1, root fix): a close inside a click (a mod that closes the menu, or opens
        // another, from a slot callback) runs between that click's before and after captures. The escrow and the
        // player totals are the previous click's, and the inventory already holds this click's changes, so the
        // close cannot prove that the items on the cursor came from the device. It records nothing. When an escrow
        // was held (a close-retention withdrawal was possible), it is a counted cursor-provenance drop; the click
        // itself then records nothing either, because its after capture finds the session gone.
        if (session.actionInProgress) {
            if (session.cursorEscrow.isPresent() || session.cursorRoleEscrow.isPresent()) {
                cursorProvenanceDrop(session);
            }
            session.clearActionState();
            return;
        }
        // GF-WARNINGS (GF-QUICKMOVE carry): every refusal below while the device's items were held on the cursor is
        // named and counted, not dropped silently. With no escrow there was nothing to record; the capture gate counts
        // its own refusals.
        boolean held = session.cursorEscrow.isPresent() || session.cursorRoleEscrow.isPresent();
        String untraced = !held ? null
                : player.hasDisconnected() ? "disconnected"
                : session.cursorEscrow.isEmpty() || session.cursorRoleEscrow.isEmpty() ? "escrow-incomplete"
                : session.lastPlayerTotals == null ? "no-earlier-totals"
                : null;
        if (!held || untraced != null || !EventTrailManager.getInstance().admitCapture()) {
            if (untraced != null) closeCursorUntraced(session, untraced);
            session.clearActionState();
            return;
        }

        Snapshot current = snapshotPlayerInventory(player);
        if (!current.complete) {
            closeCursorUntraced(session, "inventory-unreadable");
            session.clearActionState();
            return;
        }
        PhysicalInventoryActionCorrelator.CursorEscrow escrow = session.cursorEscrow.orElseThrow();
        PhysicalInventoryRoleResolver.RoleEscrow roleEscrow =
                session.cursorRoleEscrow.orElseThrow();
        if (!roleEscrow.item().equals(escrow.item())
                || roleEscrow.quantity() != escrow.quantity()) {
            closeCursorUntraced(session, "escrow-mismatch");
            session.clearActionState();
            return;
        }
        if (!isExactCloseRetention(
                session.lastPlayerTotals, current.totals, escrow.item(), escrow.quantity())) {
            closeCursorUntraced(session, "not-retained");
            session.clearActionState();
            return;
        }
        long adapterFailures = GuardedMenuAdapter.failuresOnThisThread();
        Optional<ContainerTarget> target = currentPhysicalTarget(player, session);
        // R2-menu (design 2.4, CLOSE_RESOLVE): an adapter failure while the target is proven records nothing.
        if (target.isEmpty() || adapterFailedSince(adapterFailures)) {
            closeCursorUntraced(session, target.isEmpty() ? "target-lost" : "adapter-failed");
            session.clearActionState();
            return;
        }

        logActionTransfer(
                player,
                session,
                session.nextActionSequence(),
                new PhysicalInventoryRoleResolver.RoleTransfer(
                        TransactionDirection.WITHDRAWAL,
                        escrow.item(),
                        escrow.quantity(),
                        roleEscrow.role()),
                target.orElseThrow());
        session.clearActionState();
    }

    static boolean isExactCloseRetention(
            Map<InventoryItemIdentity, Long> before,
            Map<InventoryItemIdentity, Long> after,
            InventoryItemIdentity escrowItem,
            long escrowQuantity) {
        Set<InventoryItemIdentity> keys = new HashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (InventoryItemIdentity key : keys) {
            long delta;
            try {
                delta = Math.subtractExact(
                        after.getOrDefault(key, 0L), before.getOrDefault(key, 0L));
            } catch (ArithmeticException overflow) {
                return false;
            }
            long expected = key.equals(escrowItem) ? escrowQuantity : 0L;
            if (delta != expected) return false;
        }
        return true;
    }

    /**
     * Whether this actor's container state is tracked; it changes nothing (fix-3). An untracked actor is synthetic
     * (a fake player), and a synthetic actor can carry a real player's UUID (Create deployers use the owner's), so any
     * state found under that UUID is the real player's and must stay. Nothing is ever stored for an untracked actor:
     * every entry added to this tracker's or the access bridge's per-UUID state is added behind this check.
     * The name is historical: it no longer prepares or clears anything.
     *
     * <p>GF-FAKEPLAYER-ID (B51): this is the admission check (clicks, opens, menu actions), so it also asks the
     * PlayerList identity test: a ServerPlayer that is not a FakePlayer but is not the server's own object for its UUID
     * is untracked and counted. The close of an admitted session and a resync use {@link #trackedActorType}.</p>
     */
    private static boolean preparePlayerTracking(ServerPlayer player) {
        return trackedActorType(player) && EventTrailEventHandler.isAdmittedPlayer(player);
    }

    /**
     * The type-only check (fix-3), for the close of an admitted session and a resync (GF-FAKEPLAYER-ID); a resync also
     * skips another object than the listed one ({@link #resyncsAnotherPlayersState}, review-01 M3).
     */
    private static boolean trackedActorType(ServerPlayer player) {
        return shouldTrackContainerActor(EventTrailEventHandler.isFakePlayer(player));
    }

    static boolean shouldTrackContainerActor(boolean fakePlayer) {
        return EventTrailEventHandler.shouldTrackPlayer(fakePlayer);
    }

    static void discardSessionState(UUID playerId) {
        clearActiveAuthoritativeForSession(SESSIONS.remove(playerId));
    }

    private static void clearActiveAuthoritativeForSession(Session session) {
        ActionToken active = ACTIVE_AUTHORITATIVE_ACTION.get();
        if (active != null && (session == null || active.session == session)) {
            active.abortAuthoritative();
            ACTIVE_AUTHORITATIVE_ACTION.remove();
        }
    }

    /** An entity interaction: never a block click, so it never proves an own-slot screen. */
    private static void rememberTarget(
            ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos, String label) {
        rememberTarget(player, dimension, pos, label, RecentTarget.NO_TICK, null, true, false);
    }

    private static void rememberTarget(ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos, String label,
            long tick, String blockId, boolean itemMayOpen, boolean armed) {
        if (!preparePlayerTracking(player)) return;
        RECENT_TARGETS.put(player.getUUID(), new RecentTarget(
                dimension, pos.immutable(), label, System.currentTimeMillis(), tick, blockId, itemMayOpen, armed));
    }

    /** Own-slot "opened" line: the player's remembered click, if any, no longer proves the clicked block's screen. */
    private static void markItemUseAfterClick(ServerPlayer player) {
        RECENT_TARGETS.computeIfPresent(player.getUUID(), (id, target) -> target.afterItemUse());
    }

    /**
     * Own-slot "opened" line (review-01 MAJOR-1): the HIGHEST handlers' only work, behind the fake-player check (fix-3).
     * They run before every other mod's handler of the event, so nothing here may reach the event short of a
     * {@link VirtualMachineError}. A client-side event has no ServerPlayer and is skipped. If the mark fails once the
     * player is known (re-check NIT-A, r4), the click is set aside as unreadable: the next screen then gets no "opened"
     * line and is counted CHECK_FAILED (with that reason's WARN once per server start); an adapter keeps the target.
     */
    private static void markClickBeforeOtherHandlers(PlayerInteractEvent event) {
        markClickBefore(event::getEntity);
    }

    private static void markClickBefore(Supplier<?> clicker) {
        ServerPlayer actor = null;
        try {
            if (!(clicker.get() instanceof ServerPlayer player)) return;
            actor = player;
            if (player.level().isClientSide() || !preparePlayerTracking(player)) return;
            markItemUseAfterClick(player);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            setAsideClick(actor);
        }
    }

    /** E2 (re-check NIT-A): the click stays only as unreadable (CHECK_FAILED at the next open); else it is dropped. */
    private static void setAsideClick(ServerPlayer actor) {
        if (actor == null) return;
        try {
            RECENT_TARGETS.computeIfPresent(actor.getUUID(), (id, target) -> target.unreadable());
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            try {
                RECENT_TARGETS.remove(actor.getUUID());
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Nothing more can be done for this click; it never reaches the event.
            }
        }
    }

    /** The server's tick counter, or {@link RecentTarget#NO_TICK} when there is no server. */
    private static long currentTick(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server == null ? RecentTarget.NO_TICK : server.getTickCount();
    }

    static boolean shouldRememberInteraction(boolean canceled, boolean targetUseDenied) {
        return !canceled && !targetUseDenied;
    }

    static boolean matchesResolvedPhysicalTarget(
            RecentTarget recent, Optional<MenuPhysicalBlockTarget> adapterTarget) {
        return recent != null
                && adapterTarget != null
                && adapterTarget.isPresent()
                && recent.dimension.equals(adapterTarget.orElseThrow().dimension())
                && recent.pos.equals(adapterTarget.orElseThrow().position());
    }

    static RecentTarget consumeRecentTarget(
            Map<UUID, RecentTarget> targets,
            UUID playerId,
            ResourceKey<Level> currentDimension,
            long nowMs) {
        RecentTarget target = targets.remove(playerId);
        if (target == null) return null;
        if (!target.dimension.equals(currentDimension)) return null;
        if (nowMs < target.whenMs || nowMs - target.whenMs > TARGET_TTL_MS) return null;
        return target;
    }

    private static String physicalContextKey(ResourceKey<Level> dimension, BlockPos pos) {
        return dimension.location() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void resync(ServerPlayer player) {
        // GF-FAKEPLAYER-ID: no admission test. A resync only drops evidence, so it never writes a row; skipping one for
        // an admitted session could let the fallback call an inventory change a deposit or withdrawal. review-01 M3:
        // skipped only for an object the PlayerList does not hold while it holds another one, with its own inventory.
        if (!trackedActorType(player) || resyncsAnotherPlayersState(player)) return;
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) return;
        if (session.strategy == ObservationStrategy.ACTION_SCOPED_PHYSICAL) {
            // GF-RESYNC (Part 1): a mutation reported while this session's own click is in progress, on the menu
            // that click runs on, is not independent of the click. It happens between the click's before and
            // after captures (actionInProgress is set just before the token is returned, and every end of that
            // token clears it), so the after capture already counts it, and afterSuccessfulMenuAction correlates
            // it with the escrow as it stood when the click began. Clearing that escrow here dropped a correct
            // withdrawal: a furnace or Iron Furnaces output slot fires the smelt event from onTake inside the
            // click, so taking more output onto a held stack lost the stack's provenance. All five callers come
            // here (onCrafted, onSmelted, onItemToss, onItemPickup, onCommittedPlayerInventoryMutation); for
            // each, an event inside the click is inside that click's before/after pair. A click that does not
            // conserve (a THROW, a result that consumes ingredients, a listener that adds or removes items) is
            // still rejected by the correlator, which then clears the escrow. Outside a click, or with another
            // menu open, nothing changes: a pickup or a toss between two clicks still clears the escrow below.
            // r2 (review-01 MINOR-1): the previous click's player totals are still dropped. The click does not
            // need them (its after capture rewrites them, and every refusal clears them), but a close inside this
            // click must not use them; resolvePhysicalClose also refuses any close inside a click.
            if (session.actionInProgress && player.containerMenu == session.menu) {
                session.lastPlayerTotals = null; // a close inside this click must not use the previous click's totals
                return;
            }
            // An independently observed inventory mutation destroys cursor
            // provenance. Keep the menu session, but discard the unresolved fact.
            // R2-menu (review-01 MAJOR-1, pre-existing): device items held on the cursor are no longer known as
            // such, so the next click that starts with items on the cursor records nothing.
            if (session.cursorEscrow.isPresent() || session.cursorRoleEscrow.isPresent()) {
                session.cursorProvenanceLost = true;
            }
            session.cursorEscrow = Optional.empty();
            session.cursorRoleEscrow = Optional.empty();
            session.lastPlayerTotals = null;
        }
    }

    /**
     * GF-FAKEPLAYER-ID (review-01 M3): true when the server's PlayerList holds a different object under this player's
     * UUID (a mod's ServerPlayer carrying a real player's UUID) and that object does not share this one's inventory.
     * Such a toss or pickup changed only its own inventory, so it never touches the session kept under that UUID: a
     * session is opened only by the listed object (preparePlayerTracking). False, so the resync runs as before, when
     * the list holds this object or nothing (a real player before or after list removal), when the two share an
     * inventory (the change is the listed player's own) and when either is unreadable.
     */
    private static boolean resyncsAnotherPlayersState(ServerPlayer player) {
        try {
            ServerPlayer listed = EventTrailEventHandler.otherListedPlayer(player);
            return listed != null && !sharesInventory(player, listed);
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
    }

    private static boolean sharesInventory(ServerPlayer player, ServerPlayer other) {
        Inventory own = player.getInventory();
        Inventory theirs = other.getInventory();
        return own == null || theirs == null || own == theirs
                || own.items == theirs.items || own.armor == theirs.armor || own.offhand == theirs.offhand;
    }

    private static boolean stationaryIndependentAccess(ServerPlayer player, ContainerTarget target) {
        return target.accessKind() != io.github.betapetey.eventtrail.inventory.core.AccessKind.PHYSICAL
                || (target.hasAccessPosition() && target.dimensionId().equals(player.level().dimension().location().toString())
                    && stationaryNativeAccess(player.level(), target.accessPosition()));
    }

    private static boolean independentAccess(ServerPlayer player, MenuTransactionAdapter adapter, ContainerTarget target) {
        if (player == null || adapter == null || target == null) return false;
        if (target.backingContextKind() == BackingContextKind.SHARED_CHANNEL
                && !MenuPhysicalBacking.isExactSharedChannelProducer(adapter)) return false;
        if (adapter.usesMovingBackingTarget()) {
            // Exact moving adapters already supply a physical action-time anchor and
            // prove their native frame. Applying a block-frame transform here is wrong.
            return isMovingBackingTarget(adapter, target, player.level().dimension().location().toString());
        }
        if (target.backingContextKind() == BackingContextKind.MOVING_STORE
                || target.backingContextKind() == BackingContextKind.ENTITY_BOUND) return false;
        return stationaryIndependentAccess(player, target);
    }

    private static Optional<BooleanSupplier> captureExactAccessGuard(ServerPlayer player, AbstractContainerMenu menu,
            MenuTransactionAdapter adapter) {
        try {
            Optional<BooleanSupplier> nativeGuard = Objects.requireNonNull(adapter.captureAccessGuard(
                    new MenuAdapterContext(menu, player, false)), "native access guard");
            if (nativeGuard.isEmpty() || !adapter.exact()) return Optional.empty();
            Level originalLevel = player.level();
            BooleanSupplier result = capturedAccessGuard(adapter, nativeGuard.orElseThrow(),
                    () -> player.level() == originalLevel && player.containerMenu == menu
                            && !EventTrailEventHandler.isFakePlayer(player) && player.isAlive()
                            && player.serverLevel().getEntity(player.getUUID()) == player);
            return result.getAsBoolean() ? Optional.of(result) : Optional.empty();
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(adapter, SITE_OPEN_ACCESS_GUARD, failure);
            return Optional.empty();
        }
    }

    static BooleanSupplier capturedAccessGuard(MenuTransactionAdapter adapter,
            BooleanSupplier nativeAuthority, BooleanSupplier exactSession) {
        long generation = runtimeGeneration;
        boolean[] current = {adapter != null && adapter.exact() && adapter.usesCapturedAccessAuthority()
                && nativeAuthority != null && exactSession != null};
        return () -> {
            if (!current[0]) return false;
            try {
                current[0] = generation == runtimeGeneration
                        && !OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(adapter.adapterId())
                        && exactSession.getAsBoolean() && nativeAuthority.getAsBoolean();
            } catch (RuntimeException | LinkageError failure) {
                current[0] = false;
                adapterFailed(adapter, SITE_SESSION_GUARD, failure);
            }
            return current[0];
        };
    }

    /**
     * Access rows B65/B66 (design 4.3): the OPEN of an access-point target. It asks the adapter for the access-point
     * close guard, composes it into an {@link AccessPointGuard}, checks it once, and hands the OPEN with that guard and
     * its close-time callback to the access bridge. An empty guard, or one false at open, writes no row: it is counted
     * as an unresolved target and refused with {@code access-guard-missing} (the OPEN text, once per server start).
     * Returns the adapter failures counted on this thread meanwhile, which the caller adds to its baseline so that a
     * failure here never stops item tracking for this open (m8); each failure is still reported.
     */
    private static long observeAccessPointOpen(ServerPlayer player, AbstractContainerMenu menu,
            MenuTransactionAdapter adapter, ContainerAccessTargetSnapshot snapshot, MenuAccessTarget access) {
        long failuresBefore = GuardedMenuAdapter.failuresOnThisThread();
        AccessPointGuard guard = null;
        try {
            guard = captureAccessPointGuard(player, menu, adapter, access);
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(adapter, SITE_OPEN_ACCESS_GUARD, failure);
        }
        if (guard != null) {
            NeoForgeContainerAccessAuditBridge.observeOpen(player, menu, snapshot, guard, guard::reportLost);
        } else {
            NeoForgeContainerAccessAuditBridge.unresolvedTarget();
            AdapterFailSafe.accessRefusedOnce(adapterKey(adapter),
                    AccessPointTargets.site(GuardedMenuAdapter.implementationClassName(adapter), "accessPoint"),
                    AdapterFailSafe.AccessPhase.OPEN, AccessPointTargets.ACCESS_GUARD_MISSING);
        }
        long failuresAfter = GuardedMenuAdapter.failuresOnThisThread();
        return failuresBefore < 0L || failuresAfter < 0L ? 0L : failuresAfter - failuresBefore;
    }

    /**
     * The composed guard of an access-point OPEN, or null when the adapter gave none, it is false at open, or a
     * terminal's target names a world other than the player's (its frame half reads the player's world, so it could
     * not tell whether that terminal moves).
     */
    private static AccessPointGuard captureAccessPointGuard(ServerPlayer player, AbstractContainerMenu menu,
            MenuTransactionAdapter adapter, MenuAccessTarget access) {
        Optional<BooleanSupplier> nativeGuard = Objects.requireNonNull(adapter.captureAccessPointGuard(
                new MenuAdapterContext(menu, player, false)), "native access point guard");
        if (nativeGuard.isEmpty()) return null;
        ContainerTarget target = access.target();
        boolean portable = target.accessKind() == AccessKind.PORTABLE_MENU;
        Level originalLevel = player.level();
        BooleanSupplier frameCheck = null;
        if (!portable) {
            if (!originalLevel.dimension().location().toString().equals(target.dimensionId())) return null;
            BlockPos nativePosition = target.accessPosition();
            frameCheck = () -> AccessPointTargets.stationary(originalLevel, nativePosition);
        }
        AccessPointGuard guard = accessPointGuard(adapter, nativeGuard.orElseThrow(), frameCheck,
                () -> player.level() == originalLevel && player.containerMenu == menu
                        && !EventTrailEventHandler.isFakePlayer(player) && player.isAlive()
                        && player.serverLevel().getEntity(player.getUUID()) == player,
                portable ? AccessPointTargets.CELL_GONE : AccessPointTargets.TERMINAL_GONE);
        return guard.getAsBoolean() ? guard : null;
    }

    /**
     * Access rows B65/B66 (design 4.3, m3): the sibling of {@link #capturedAccessGuard} for access points. Its start
     * latch needs no {@link MenuTransactionAdapter#usesCapturedAccessAuthority()}.
     */
    static AccessPointGuard accessPointGuard(MenuTransactionAdapter adapter, BooleanSupplier nativeGuard,
            BooleanSupplier frameCheck, BooleanSupplier exactSession, String nativeReason) {
        AdapterKey key = adapterKey(adapter);
        String closeSite = AccessPointTargets.site(
                adapter == null ? null : GuardedMenuAdapter.implementationClassName(adapter), "accessClose");
        return new AccessPointGuard(adapter, runtimeGeneration, nativeGuard, frameCheck, exactSession,
                nativeReason, key, closeSite);
    }

    /**
     * One access-point OPEN's close guard. Each check tests, in order, and stops at the first false half, recording
     * it: (a) the run generation and the adapter's run-time switch ({@code adapter-off}); (b) the exact session, the
     * same live player in the same world with this menu ({@code session-lost}); (c) the adapter's native guard, that
     * the terminal or cell is still the object opened ({@code terminal-gone} or {@code cell-gone}); (d) for a
     * terminal, the stationary, current frame ({@code not-stationary}). A throw anywhere is reported, latches false
     * and records the native reason. Once false it stays false for the rest of the session. Server thread only.
     */
    static final class AccessPointGuard implements BooleanSupplier {
        private final MenuTransactionAdapter adapter;
        private final long generation;
        private final BooleanSupplier nativeGuard;
        private final BooleanSupplier frameCheck;
        private final BooleanSupplier exactSession;
        private final String nativeReason;
        private final AdapterKey key;
        private final String closeSite;
        private volatile boolean current;
        private volatile String lostReason;

        private AccessPointGuard(MenuTransactionAdapter adapter, long generation, BooleanSupplier nativeGuard,
                BooleanSupplier frameCheck, BooleanSupplier exactSession, String nativeReason, AdapterKey key,
                String closeSite) {
            this.adapter = adapter;
            this.generation = generation;
            this.nativeGuard = nativeGuard;
            this.frameCheck = frameCheck;
            this.exactSession = exactSession;
            this.nativeReason = nativeReason == null ? AccessPointTargets.TERMINAL_GONE : nativeReason;
            this.key = key;
            this.closeSite = closeSite;
            this.current = adapter != null && adapter.exact() && nativeGuard != null && exactSession != null;
            if (!current) lostReason = this.nativeReason;
        }

        @Override
        public boolean getAsBoolean() {
            if (!current) return false;
            try {
                if (generation != runtimeGeneration
                        || OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(adapter.adapterId())) {
                    return lose(AccessPointTargets.ADAPTER_OFF);
                }
                if (!exactSession.getAsBoolean()) return lose(AccessPointTargets.SESSION_LOST);
                if (!nativeGuard.getAsBoolean()) return lose(nativeReason);
                if (frameCheck != null && !frameCheck.getAsBoolean()) return lose(AccessPointTargets.NOT_STATIONARY);
                return true;
            } catch (RuntimeException | LinkageError failure) {
                lose(nativeReason);
                adapterFailed(adapter, SITE_SESSION_GUARD, failure);
                return false;
            }
        }

        private boolean lose(String reason) {
            lostReason = reason;
            current = false;
            return false;
        }

        /** Why the guard went false, or null while it holds. */
        String lostReason() {
            return lostReason;
        }

        /**
         * Run once by the session tracker when this session's close found the guard false (design 4.6): the close
         * text for {@code terminal-gone}, {@code cell-gone} and {@code not-stationary}; nothing for
         * {@code session-lost} and {@code adapter-off}, which the tracker's {@code abandoned} count already holds.
         */
        void reportLost() {
            String reason = lostReason;
            if (AccessPointTargets.TERMINAL_GONE.equals(reason) || AccessPointTargets.CELL_GONE.equals(reason)
                    || AccessPointTargets.NOT_STATIONARY.equals(reason)) {
                AdapterFailSafe.accessRefusedOnce(key, closeSite, AdapterFailSafe.AccessPhase.CLOSE, reason);
            }
        }
    }

    private static Optional<BooleanSupplier> captureActionGuard(MenuTransactionAdapter adapter, MenuAdapterContext context) {
        try {
            return Objects.requireNonNull(adapter.captureActionGuard(context), "native action guard");
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(adapter, SITE_ACTION_GUARD, failure);
            return Optional.empty();
        }
    }

    static boolean actionGuardCurrent(MenuTransactionAdapter adapter, Optional<BooleanSupplier> guard) {
        if (adapter == null || guard == null || OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(adapter.adapterId())) return false;
        if (guard.isEmpty()) return !adapter.usesSharedChannelBacking();
        try {
            return guard.orElseThrow().getAsBoolean();
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(adapter, SITE_ACTION_GUARD, failure);
            return false;
        }
    }

    static boolean isMovingBackingTarget(MenuTransactionAdapter adapter, ContainerTarget target, String dimension) {
        return isExactMovingBackingAdapter(adapter) && target != null
                && target.accessKind() == AccessKind.PHYSICAL && target.hasAccessPosition()
                && target.dimensionId().equals(dimension)
                && (target.backingContextKind() == BackingContextKind.MOVING_STORE
                    || target.backingContextKind() == BackingContextKind.ENTITY_BOUND);
    }

    private static boolean isExactMovingBackingAdapter(MenuTransactionAdapter adapter) {
        return adapter != null && adapter.exact() && adapter.usesHandlerBackedView()
                && adapter.usesIndependentBackingTarget() && adapter.usesMovingBackingTarget()
                && adapter.observationStrategy() == ObservationStrategy.ACTION_SCOPED_PHYSICAL;
    }

    private static Optional<BooleanSupplier> captureMovingAccessGuard(ServerPlayer player, AbstractContainerMenu menu,
            MenuTransactionAdapter adapter, Optional<MenuPhysicalBacking> backing, Optional<ContainerTarget> opening) {
        if (backing.isEmpty() || opening.isEmpty()
                || !isMovingBackingTarget(adapter, opening.orElseThrow(), player.level().dimension().location().toString())) {
            return Optional.empty();
        }
        try {
            MenuAdapterContext context = new MenuAdapterContext(menu, player, false);
            var nativeGuard = adapter.captureMovingAccessGuard(context);
            if (nativeGuard == null || nativeGuard.isEmpty()) return Optional.empty();
            Level originalLevel = player.level();
            MenuPhysicalBacking exactBacking = backing.orElseThrow();
            BooleanSupplier guard = movingAccessGuard(adapter, opening.orElseThrow(), nativeGuard.orElseThrow(),
                    () -> player.level() == originalLevel && player.containerMenu == menu
                            && player.serverLevel().getEntity(player.getUUID()) == player
                            && !EventTrailEventHandler.isFakePlayer(player) && exactBacking.isCurrent(),
                    () -> adapter.resolveMenuBackingTarget(context));
            return guard.getAsBoolean() ? Optional.of(guard) : Optional.empty();
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(adapter, SITE_OPEN_ACCESS_GUARD, failure);
            return Optional.empty();
        }
    }

    /** One OPEN's constant-size monotonic authority; it never relocates or recreates access facts. */
    static BooleanSupplier movingAccessGuard(MenuTransactionAdapter adapter, ContainerTarget opening,
            BooleanSupplier nativeMotionUnchanged, BooleanSupplier exactAuthority,
            Supplier<Optional<ContainerTarget>> currentTarget) {
        long generation = MovingStorageEpoch.runtimeGeneration();
        boolean[] valid = {isExactMovingBackingAdapter(adapter) && opening != null
                && isMovingBackingTarget(adapter, opening, opening.dimensionId())
                && nativeMotionUnchanged != null && exactAuthority != null && currentTarget != null};
        return () -> {
            if (!valid[0]) return false;
            try {
                valid[0] = generation == MovingStorageEpoch.runtimeGeneration()
                        && !OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(adapter.adapterId())
                        && nativeMotionUnchanged.getAsBoolean() && exactAuthority.getAsBoolean();
                if (valid[0]) {
                    var current = currentTarget.get();
                    valid[0] = current != null && current.filter(target ->
                            sameBackingTarget(opening, target, adapter)
                                    && opening.accessPosition().equals(target.accessPosition())).isPresent();
                }
            } catch (RuntimeException | LinkageError failure) {
                valid[0] = false;
                adapterFailed(adapter, SITE_SESSION_GUARD, failure);
            }
            return valid[0];
        };
    }

    private static boolean stationaryNativeAccess(Level level, BlockPos nativePosition) {
        return AuditBlockLocations.capture(level, nativePosition)
                .filter(frame -> !frame.moving() && frame.isCurrent())
                .flatMap(AuditBlockLocations.Frame::currentLocation).isPresent();
    }

    private static boolean currentPhysicalMenu(ServerPlayer player, AbstractContainerMenu menu, Level level,
            WholeMenuPhysicalTargetResolver.ResolvedTarget expected, List<Slot> originalSlots) {
        if (player.level() != level || player.serverLevel().getEntity(player.getUUID()) != player
                || player.containerMenu != menu || menu.slots.size() != originalSlots.size()) return false;
        for (int i = 0; i < originalSlots.size(); i++) if (menu.slots.get(i) != originalSlots.get(i)) return false;
        var current = WholeMenuPhysicalTargetResolver.resolve(menu, player.getInventory(), level,
                expected.accessPosition(), MAX_MENU_DESCRIPTORS);
        if (current.isEmpty()) return false;
        var target = current.orElseThrow();
        return target.backingContainer() == expected.backingContainer()
                && target.canonicalBackingPositions().equals(expected.canonicalBackingPositions())
                && target.ownershipMembers().size() == expected.ownershipMembers().size()
                && target.ownershipMembers().stream().allMatch(member ->
                    expected.ownershipMembers().stream().anyMatch(original -> original == member));
    }

    private static Optional<ContainerTarget> currentPhysicalTarget(ServerPlayer player, Session session) {
        if (player == null || player.containerMenu != session.menu) return Optional.empty();
        if (session.physicalLocation != null) {
            if (!session.physicalLocation.belongsTo(player.level())) return Optional.empty();
            return session.physicalLocation.currentTarget().filter(target -> sameBackingTarget(session.target, target));
        }
        if (session.adapter.usesIndependentBackingTarget()) {
            return refreshIndependentTarget(session.adapter, new MenuAdapterContext(session.menu, player, false))
                    .filter(target -> sameBackingTarget(session.target, target, session.adapter)
                            && independentAccess(player, session.adapter, target))
                    .map(target -> currentPortableAccess(target, player.level().dimension().location().toString(), player.blockPosition()));
        }
        if (!session.target.dimensionId().equals(player.level().dimension().location().toString())
                || !stationaryNativeAccess(player.level(), session.target.accessPosition())) return Optional.empty();
        return Optional.of(session.target);
    }

    /**
     * R2-menu: a missing member switches this exact adapter OFF (fail-safe; no cascade); any other failure skips
     * just this refresh, and the next refresh asks the adapter again.
     */
    static Optional<ContainerTarget> refreshIndependentTarget(MenuTransactionAdapter adapter, MenuAdapterContext context) {
        if (adapter == null || OptionalMenuAdapterLoader.isRuntimeAdapterDisabled(adapter.adapterId())) return Optional.empty();
        try {
            return Objects.requireNonNull(adapter.resolveMenuBackingTarget(context), "independent target result");
        } catch (RuntimeException | LinkageError failure) {
            adapterFailed(adapter, SITE_BACKING_REFRESH, failure);
            return Optional.empty();
        }
    }

    private static Optional<ContainerAccessTargetSnapshot> resolveAccessTarget(
            ServerPlayer player,
            AbstractContainerMenu menu,
            RecentTarget recent,
            Optional<WholeMenuPhysicalTargetResolver.ResolvedTarget> physical,
            MenuTransactionAdapterRegistry.Selection selection,
            ObservationStrategy strategy,
            Optional<MenuPhysicalBlockTarget> adapterPhysicalTarget,
            Optional<MenuAccessTarget> authoritativeAccessTarget,
            Optional<MenuPhysicalBacking> handlerBacking,
            Optional<PhysicalContainerLocationBinding> physicalLocation,
            Optional<String> adapterLabel) {
        try {
            if (strategy == ObservationStrategy.ACTION_SCOPED_PHYSICAL
                    && selection.adapter().usesIndependentBackingTarget()) {
                if (handlerBacking.isEmpty() || !handlerBacking.orElseThrow().durableAccessIdentity()
                        || !handlerBacking.orElseThrow().isCurrent() || authoritativeAccessTarget.isEmpty()) return Optional.empty();
                MenuAccessTarget access = authoritativeAccessTarget.orElseThrow();
                if (!access.target().stableContextKey().equals(handlerBacking.orElseThrow().targetIdentity())
                        || access.target().backingContextKind() != handlerBacking.orElseThrow().backingContextKind()
                        || !independentAccess(player, selection.adapter(), access.target())) return Optional.empty();
                return Optional.of(ContainerAccessTargetSnapshot.from(access.target(), access.technicalTargetId(), selection.adapterId()));
            }
            if (strategy == ObservationStrategy.AUTHORITATIVE_OPERATION) {
                if (authoritativeAccessTarget == null
                        || authoritativeAccessTarget.isEmpty()) {
                    return Optional.empty();
                }
                MenuAccessTarget resolved = authoritativeAccessTarget.orElseThrow();
                return Optional.of(ContainerAccessTargetSnapshot.from(
                        resolved.target(),
                        resolved.technicalTargetId(),
                        selection.adapterId()));
            }
            if (strategy != ObservationStrategy.ACTION_SCOPED_PHYSICAL || recent == null) {
                return Optional.empty();
            }

            BlockPos accessPos = recent.pos.immutable();
            BackingContextKind backing;
            String stableContextKey;
            if (selection.adapter() instanceof VanillaEnderChestMenuAdapter) {
                if (!player.level().getBlockState(accessPos).is(Blocks.ENDER_CHEST)) {
                    return Optional.empty();
                }
                backing = BackingContextKind.PLAYER_BOUND;
                stableContextKey = "minecraft:ender_chest:" + player.getUUID();
            } else if (selection.adapter().usesHandlerBackedView()) {
                if (!matchesResolvedPhysicalTarget(recent, adapterPhysicalTarget)
                        || player.level().getBlockEntity(accessPos) == null
                        || handlerBacking.isEmpty()
                        || !handlerBacking.orElseThrow().durableAccessIdentity()
                        || !handlerBacking.orElseThrow().isCurrent()) {
                    return Optional.empty();
                }
                backing = handlerBacking.orElseThrow().backingContextKind();
                stableContextKey = handlerBacking.orElseThrow().targetIdentity();
            } else {
                if (physical == null || physical.isEmpty()) {
                    return Optional.empty();
                }
                WholeMenuPhysicalTargetResolver.ResolvedTarget resolved = physical.orElseThrow();
                if (!resolved.accessPosition().equals(accessPos)
                        || !resolved.canonicalBackingPositions().contains(accessPos)) {
                    return Optional.empty();
                }
                backing = resolved.backingContextKind();
                stableContextKey = resolved.stableContextKey();
            }

            String technicalId = canonicalBlockId(player, accessPos);
            if (technicalId == null) return Optional.empty();
            ContainerTarget target = ContainerTarget.block(
                    recent.dimension.location().toString(),
                    accessPos,
                    chosenLabel(adapterLabel, () -> containerAccessDisplayLabel(player, accessPos, recent.label)),
                    backing,
                    stableContextKey);
            if (physicalLocation.isPresent()) {
                var current = physicalLocation.orElseThrow().currentTarget();
                if (current.isEmpty()) return Optional.empty();
                ContainerTarget projected = current.orElseThrow();
                target = new ContainerTarget(projected.dimensionId(), projected.x(), projected.y(), projected.z(),
                        target.label(), projected.accessKind(), projected.backingContextKind(), projected.stableContextKey());
            }
            return Optional.of(ContainerAccessTargetSnapshot.from(
                    target, technicalId, selection.adapterId()));
        } catch (RuntimeException | LinkageError failure) {
            if (strategy == ObservationStrategy.AUTHORITATIVE_OPERATION) {
                adapterFailed(selection.adapter(), SITE_OPEN_ACCESS_TARGET, failure);
            }
            return Optional.empty();
        }
    }

    /**
     * SOPH-LABELS (review-01 MAJOR-1): the selected adapter's own name for the block the open admitted, or empty for
     * today's label. Never an adapter failure (a name never skips an open): a throw, or a blank answer, gives empty; a
     * throw is counted as a name recorded with the block's own name (the guard already counts its own adapters').
     */
    private static Optional<String> adapterLabel(MenuTransactionAdapter adapter, MenuAdapterContext context) {
        try {
            Optional<String> label = adapter.physicalContainerLabel(context);
            return label == null || label.isEmpty() || label.orElseThrow().isBlank()
                    ? Optional.empty() : Optional.of(label.orElseThrow());
        } catch (Throwable failure) {
            // Fully qualified, with its site inline, to keep this lane's tracker hunks to the label sites.
            io.github.betapetey.eventtrail.adapters.ContainerLabelFallbacks.fellBack("container tracker",
                    "menu open label", failure);
            return Optional.empty();
        }
    }

    /** SOPH-LABELS: the adapter's name when it gave one, else today's label exactly ({@code todays}). */
    static String chosenLabel(Optional<String> adapterLabel, Supplier<String> todays) {
        return adapterLabel != null && adapterLabel.isPresent() ? adapterLabel.orElseThrow() : todays.get();
    }

    private static String canonicalBlockId(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return null;
        return BuiltInRegistries.BLOCK
                .getResourceKey(player.level().getBlockState(pos).getBlock())
                .map(key -> key.location().toString())
                .orElse(null);
    }

    private static String containerAccessDisplayLabel(
            ServerPlayer player, BlockPos pos, String recentLabel) {
        try {
            BlockEntity blockEntity = player.level().getBlockEntity(pos);
            if (blockEntity instanceof MenuProvider provider) {
                String customOrMenuName = provider.getDisplayName().getString();
                if (customOrMenuName != null && !customOrMenuName.isBlank()) {
                    return customOrMenuName;
                }
            }
        } catch (RuntimeException ignored) {}
        if (recentLabel != null && !recentLabel.isBlank()) return recentLabel;
        return physicalContainerLabel(player, pos, null);
    }

    private static String physicalContainerLabel(ServerPlayer player, BlockPos pos, String recentLabel) {
        try {
            if (pos != null) {
                // Prefer the precise block-item name. MenuProvider titles are often
                // intentionally generic (for example simply "Chest").
                Block block = player.level().getBlockState(pos).getBlock();
                String blockName = blockDisplayName(block);
                if (blockName != null && !blockName.isBlank()) return blockName;

                BlockEntity blockEntity = player.level().getBlockEntity(pos);
                if (blockEntity instanceof MenuProvider provider) {
                    String name = provider.getDisplayName().getString();
                    if (name != null && !name.isBlank()) return name;
                }
            }
        } catch (Throwable ignored) {}

        if (recentLabel != null && !recentLabel.isBlank()) return recentLabel;
        return "Container";
    }

    private static String blockDisplayName(Block block) {
        return DisplayNameResolver.blockDisplayName(block);
    }

    static Snapshot snapshotItems(
            int size, IntFunction<ItemStack> itemReader, Supplier<ItemStack> carriedReader) {
        Map<InventoryItemIdentity, Long> totals = new HashMap<>();
        try {
            for (int i = 0; i < size; i++) {
                add(totals, itemReader.apply(i));
            }
            // Cursor/carried item remains in the player's possession while dragging.
            if (carriedReader != null) add(totals, carriedReader.get());
            return Snapshot.complete(totals);
        } catch (Throwable ignored) {
            return Snapshot.invalid();
        }
    }

    private static void invalidateSession(ServerPlayer player, Session session, String phase) {
        clearActiveAuthoritativeForSession(session);
        session.clearActionState();
        SESSIONS.remove(player.getUUID(), session);
        session.invalidatedWithWarning = true;
        logInvalidSession(player, phase);
    }

    private static void logInvalidSession(ServerPlayer player, String phase) {
        LOGGER.warn("Container tracking for {} stopped because a complete {} snapshot "
                        + "could not be obtained; no transaction was attributed",
                EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64),
                EventTrailDiagnostics.sanitizeLogValue(phase, 48));
    }

    private static void add(Map<InventoryItemIdentity, Long> totals, ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() <= 0) return;

        int count = stack.getCount();
        totals.merge(InventoryItemIdentity.of(stack), (long) count, Long::sum);
    }

    /**
     * A2-44 (n6-2): publishes one container action (every transfer of one menu action, or the one close-retention
     * withdrawal) as one group through the storage's all-or-none admission, so it is stored whole or not at all.
     * Every adapter call (provider id, target) already happened while the evidence was built. A failure while the
     * rows are prepared discards the whole action with a WARN here (rate-limited: at most one a minute, with the
     * count suppressed since; stack trace at DEBUG). An action above
     * {@link ActionRecordBatch#MAX_ROWS_PER_ACTION} rows and a failing sink are refused whole and warned
     * (rate-limited) by {@link ActionRecordBatch}; a storage refusal is warned by the storage.
     */
    private static void publishTransfers(ServerPlayer player, List<InventoryTransactionEvidence> transfers) {
        if (transfers.isEmpty()) return;
        try {
            publishAction(
                    transfers,
                    shouldTrackContainerActor(EventTrailEventHandler.isFakePlayer(player)),
                    io.github.betapetey.eventtrail.inventory.core.ContainerTransactionRecordingPolicy::enabled,
                    EventTrailManager.getInstance().canAcceptAuditWrite(),
                    EventTrailManager.getInstance()::tryLogAll);
        } catch (Throwable t) {
            String actor = EventTrailDiagnostics.sanitizeLogValue(player.getName().getString(), 64);
            ACTION_NOT_RECORDED.acquire().ifPresent(emission -> LOGGER.warn(
                    "EventTrail did not record a container action of {} transfer(s) for {}: preparing its rows "
                            + "failed ({}); the whole action was discarded and none of its rows was written "
                            + "(stack trace at DEBUG); suppressed {} such failure(s) since the last warning",
                    transfers.size(), actor, EventTrailDiagnostics.safeFailureSummary(t),
                    emission.suppressedSinceLastEmission()));
            LOGGER.debug("Container action rows for {} could not be prepared", actor, t);
        }
    }

    /**
     * A2-44 (n6-2): builds every row of one container action, then hands them to {@code sink} once, as one group,
     * through {@link ActionRecordBatch}. The tracking policy applies per transfer before any row is built: an
     * untracked actor (a fake player) records nothing, and a transfer whose direction is not recorded is skipped
     * while the action's other transfers are still published. Rows keep the transfers' order, each transfer's
     * chunk rows in place. If a kept transfer cannot be built (for example a target without an access position),
     * the exception propagates and nothing is handed to {@code sink}. An action above
     * {@link ActionRecordBatch#MAX_ROWS_PER_ACTION} rows is refused whole before {@code sink} is called. Public only
     * for the writer's group tests.
     *
     * @return whether storage took the whole action; true, without calling {@code sink}, when the policy skipped
     *         every transfer
     */
    public static boolean publishAction(
            List<InventoryTransactionEvidence> transfers,
            boolean trackActor,
            Predicate<TransactionDirection> directionRecorded,
            boolean captureFullMetadata,
            Predicate<List<AuditRecord>> sink) {
        Objects.requireNonNull(transfers, "transfers");
        Objects.requireNonNull(directionRecorded, "directionRecorded");
        Objects.requireNonNull(sink, "sink");
        if (!trackActor) return true;
        return ActionRecordBatch.collect(sink, action -> {
            for (InventoryTransactionEvidence transfer : transfers) {
                if (!directionRecorded.test(transfer.direction())) continue;
                action.addAll(InventoryTransactionNormalizer.toAuditRecords(transfer, captureFullMetadata));
            }
        });
    }

    /**
     * The player's last interaction target. The own-slot "opened" line (Owner decision A, 11-14) adds the click's server
     * {@code tick}, the clicked {@code blockId} ({@code null} for an entity interaction, {@link #CLICK_UNREAD} when it
     * could not be read), {@code itemMayOpen} (an item use or a later click could open the next screen instead of
     * the block) and, r5 (Advisor M-1), {@code armed} (the block's own use started for this click, or the clicker is
     * a spectator; a click that is not armed never proves a screen). The four-value form is a target that never
     * proves an own-slot screen.
     */
    record RecentTarget(ResourceKey<Level> dimension, BlockPos pos, String label, long whenMs,
            long tick, String blockId, boolean itemMayOpen, boolean armed) {
        static final long NO_TICK = Long.MIN_VALUE;
        static final String CLICK_UNREAD = "?";

        RecentTarget(ResourceKey<Level> dimension, BlockPos pos, String label, long whenMs) {
            this(dimension, pos, label, whenMs, NO_TICK, null, true, false);
        }

        RecentTarget afterItemUse() {
            return itemMayOpen ? this : new RecentTarget(dimension, pos, label, whenMs, tick, blockId, true, armed);
        }

        /** r4 (re-check NIT-A): a click whose mark failed; it never proves a screen (CHECK_FAILED), the target stays. */
        RecentTarget unreadable() {
            return new RecentTarget(dimension, pos, label, whenMs, NO_TICK, blockId, true, armed);
        }

        /**
         * r5 (Advisor M-1): this click armed by the start of the block's own use at {@code usePos} in {@code useDimension}
         * on server tick {@code useTick}; unchanged unless that is this block click's position, dimension and own tick.
         * A mark already set stays set.
         */
        RecentTarget armedBy(ResourceKey<Level> useDimension, BlockPos usePos, long useTick) {
            if (armed || blockId == null || tick == NO_TICK || useTick != tick
                    || !dimension.equals(useDimension) || !pos.equals(usePos)) {
                return this;
            }
            return new RecentTarget(dimension, pos, label, whenMs, tick, blockId, itemMayOpen, true);
        }
    }

    static final class Snapshot {
        private final Map<InventoryItemIdentity, Long> totals;
        private final boolean complete;

        private Snapshot(Map<InventoryItemIdentity, Long> totals, boolean complete) {
            this.totals = totals;
            this.complete = complete;
        }

        private static Snapshot complete(Map<InventoryItemIdentity, Long> totals) {
            return new Snapshot(Map.copyOf(totals), true);
        }

        private static Snapshot invalid() {
            return new Snapshot(Map.of(), false);
        }

        boolean isComplete() {
            return complete;
        }

        int distinctItemCount() {
            return totals.size();
        }
    }

    private record CapturedActionState(
            PhysicalInventoryActionCorrelator.Snapshot custody,
            BoundedMenuInventoryView.Capture device) {}

    static boolean sameBackingTarget(ContainerTarget expected, ContainerTarget current) {
        if (expected == null || current == null || !expected.stableContextKey().equals(current.stableContextKey())
                || expected.backingContextKind() != current.backingContextKind()
                || expected.accessKind() != current.accessKind()) return false;
        // Portable access follows the actor; a placed backing cannot silently relocate.
        return expected.accessKind() == io.github.betapetey.eventtrail.inventory.core.AccessKind.PORTABLE_MENU
                || (expected.backingContextKind() == BackingContextKind.MOVING_STORE
                    && expected.dimensionId().equals(current.dimensionId()))
                || (expected.dimensionId().equals(current.dimensionId())
                && Objects.equals(expected.x(), current.x()) && Objects.equals(expected.y(), current.y())
                && Objects.equals(expected.z(), current.z()));
    }

    static boolean sameBackingTarget(ContainerTarget expected, ContainerTarget current, MenuTransactionAdapter adapter) {
        if (adapter != null && adapter.usesMovingBackingTarget()) {
            return expected != null && current != null
                    && isMovingBackingTarget(adapter, expected, expected.dimensionId())
                    && isMovingBackingTarget(adapter, current, expected.dimensionId())
                    && expected.backingContextKind() == current.backingContextKind()
                    && expected.stableContextKey().equals(current.stableContextKey());
        }
        return sameBackingTarget(expected, current);
    }

    static ContainerTarget currentPortableAccess(ContainerTarget target, String dimension, BlockPos position) {
        if (target.accessKind() != io.github.betapetey.eventtrail.inventory.core.AccessKind.PORTABLE_MENU) return target;
        return new ContainerTarget(dimension, position.getX(), position.getY(), position.getZ(), target.label(),
                target.accessKind(), target.backingContextKind(), target.stableContextKey());
    }

    /** Opaque pre-action token carried only across the single wrapped menu call. */
    public static final class ActionToken {
        private final UUID playerId;
        private final ServerPlayer player;
        private final Session session;
        private final long actionSequence;
        private final CapturedActionState before;
        private final ClickType clickType;
        private final InventorySlotRole clickedRole;
        /** GF-NESTED: the clicked menu slot index (-1 for an authoritative action), matched against the proof's slot. */
        private final int clickedSlotIndex;
        private final Optional<PhysicalInventoryRoleResolver.RoleEscrow> priorRoleEscrow;
        private final AuthoritativeTransactionCollector authoritativeCollector;
        private final ContainerTarget authoritativeTarget;
        private final ContainerTarget physicalTarget;
        private final Optional<BooleanSupplier> actionGuard;
        /** GF-QUICKMOVE: per exact item, the device roles with a slot that accepted it before the click. */
        private final Map<InventoryItemIdentity, Set<InventorySlotRole>> beforeAcceptingDepositRoles;
        private boolean authoritativeOverflowReported;
        /**
         * GF-WARNINGS r3 (MINOR-3): the thread that made this token and what it had counted then, read again by
         * {@link #menuActionFailed} so an action already counted by an adapter failure or a recording gap is not
         * counted a second time. r4 (review-02 MINOR-A): the mark is the one of the session adapter's key only (the
         * unkeyed slot for EventTrail's own vanilla adapters), so a count for another key or fact never hides it.
         */
        private final Thread madeOn = Thread.currentThread();
        private final AdapterKey countedKey;
        private final long countedMark;
        private final long guardFailureMark = GuardedMenuAdapter.failuresOnThisThread();

        private ActionToken(
                ServerPlayer player,
                Session session,
                long actionSequence,
                CapturedActionState before,
                ClickType clickType,
                InventorySlotRole clickedRole,
                int clickedSlotIndex,
                Optional<PhysicalInventoryRoleResolver.RoleEscrow> priorRoleEscrow,
                ContainerTarget physicalTarget,
                Optional<BooleanSupplier> actionGuard,
                Map<InventoryItemIdentity, Set<InventorySlotRole>> beforeAcceptingDepositRoles) {
            this.playerId = player.getUUID();
            this.player = player;
            this.session = session;
            this.actionSequence = actionSequence;
            this.before = before;
            this.clickType = Objects.requireNonNull(clickType, "clickType");
            this.clickedRole = Objects.requireNonNull(clickedRole, "clickedRole");
            this.clickedSlotIndex = clickedSlotIndex;
            this.priorRoleEscrow = Objects.requireNonNull(priorRoleEscrow, "priorRoleEscrow");
            this.authoritativeCollector = null;
            this.authoritativeTarget = null;
            this.physicalTarget = Objects.requireNonNull(physicalTarget, "physicalTarget");
            this.actionGuard = Objects.requireNonNull(actionGuard, "actionGuard");
            this.beforeAcceptingDepositRoles = Objects.requireNonNull(
                    beforeAcceptingDepositRoles, "beforeAcceptingDepositRoles");
            this.countedKey = sessionKey(session);
            this.countedMark = AdapterFailSafe.countedOnThisThread(countedKey);
        }

        private ActionToken(
                ServerPlayer player,
                Session session,
                long actionSequence,
                ContainerTarget authoritativeTarget) {
            this.player = Objects.requireNonNull(player, "player");
            this.playerId = player.getUUID();
            this.session = Objects.requireNonNull(session, "session");
            this.actionSequence = actionSequence;
            this.before = null;
            this.clickType = null;
            this.clickedRole = null;
            this.clickedSlotIndex = -1;
            this.priorRoleEscrow = Optional.empty();
            this.authoritativeCollector = new AuthoritativeTransactionCollector();
            this.authoritativeTarget = Objects.requireNonNull(
                    authoritativeTarget, "authoritativeTarget");
            this.physicalTarget = null;
            this.actionGuard = Optional.empty();
            this.beforeAcceptingDepositRoles = Map.of();
            this.countedKey = sessionKey(session);
            this.countedMark = AdapterFailSafe.countedOnThisThread(countedKey);
        }

        /** r4: the fail-safe key of {@code session}'s adapter, or null (vanilla adapters, or no session). */
        private static AdapterKey sessionKey(Session session) {
            return session == null ? null : adapterKey(session.adapter);
        }

        private static ActionToken authoritative(
                ServerPlayer player,
                Session session,
                long actionSequence,
                ContainerTarget target) {
            return new ActionToken(player, session, actionSequence, target);
        }

        private boolean authoritative() {
            return authoritativeCollector != null;
        }

        private void abortAuthoritative() {
            if (authoritativeCollector != null) authoritativeCollector.abort();
        }
    }

    private static final class Session {
        private final long sessionId;
        private final AbstractContainerMenu menu;
        private final ContainerTarget target;
        private final Container physicalContainer;
        private final BoundedMenuInventoryView deviceView;
        private final MenuPhysicalBacking physicalBacking;
        private final PhysicalContainerLocationBinding physicalLocation;
        private final ObservationStrategy strategy;
        private final MenuTransactionAdapter adapter;
        private boolean actionInProgress;
        private Optional<PhysicalInventoryActionCorrelator.CursorEscrow> cursorEscrow =
                Optional.empty();
        private Optional<PhysicalInventoryRoleResolver.RoleEscrow> cursorRoleEscrow =
                Optional.empty();
        private Map<InventoryItemIdentity, Long> lastPlayerTotals;
        /**
         * R2-menu (review-01 MAJOR-1): an action of this session was not recorded and its cursor evidence was cleared
         * while device items may be on the cursor. Set at every exit that keeps the session after such a skip, at the
         * counted not-ready skip, and at open when the cursor already holds items (r3); cleared by a click that starts
         * with an empty cursor. Never cleared by {@link #clearActionState()}.
         */
        private boolean cursorProvenanceLost;
        /**
         * GF-WARNINGS r3 (review-01 NIT-4): {@link #invalidateSession} ended this session and wrote its "tracking
         * stopped" WARN. A click still open on it is then not counted again as ACTION_CONTEXT_CHANGED "session".
         */
        private boolean invalidatedWithWarning;
        private long actionSequence;

        private Session(
                AbstractContainerMenu menu,
                ContainerTarget target,
                Container physicalContainer,
                BoundedMenuInventoryView deviceView,
                ObservationStrategy strategy,
                MenuTransactionAdapter adapter) {
            this(menu, target, physicalContainer, deviceView, strategy, adapter, null);
        }

        private Session(
                AbstractContainerMenu menu, ContainerTarget target, Container physicalContainer,
                BoundedMenuInventoryView deviceView, ObservationStrategy strategy,
                MenuTransactionAdapter adapter, MenuPhysicalBacking physicalBacking) {
            this(menu, target, physicalContainer, deviceView, strategy, adapter, physicalBacking, null);
        }

        private Session(
                AbstractContainerMenu menu, ContainerTarget target, Container physicalContainer,
                BoundedMenuInventoryView deviceView, ObservationStrategy strategy,
                MenuTransactionAdapter adapter, MenuPhysicalBacking physicalBacking,
                PhysicalContainerLocationBinding physicalLocation) {
            this.sessionId = NEXT_SESSION_ID.updateAndGet(previousId ->
                    previousId == Long.MAX_VALUE ? 1L : previousId + 1L);
            this.menu = menu;
            this.target = target;
            this.physicalContainer = physicalContainer;
            this.deviceView = deviceView;
            this.physicalBacking = physicalBacking;
            this.physicalLocation = physicalLocation;
            this.strategy = strategy;
            this.adapter = Objects.requireNonNull(adapter, "adapter");
        }

        private long nextActionSequence() {
            if (actionSequence == Long.MAX_VALUE) actionSequence = 0L;
            return ++actionSequence;
        }

        private void clearActionState() {
            actionInProgress = false;
            clearUnresolvedActionEvidence();
        }

        private void clearUnresolvedActionEvidence() {
            cursorEscrow = Optional.empty();
            cursorRoleEscrow = Optional.empty();
            lastPlayerTotals = null;
        }
    }
}
