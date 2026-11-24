package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.spi.*;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedbackpacks.Config;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;
import net.p3pp3rf1y.sophisticatedbackpacks.settings.BackpackMainSettingsCategory;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents;
import net.p3pp3rf1y.sophisticatedcore.settings.SettingsManager;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Exact item/placed Backpack contexts feeding the existing Core custody mechanism. */
public final class SophisticatedBackpacksMenuAdapter implements MenuTransactionAdapter {
    /** Provider ids are row data: one fixed id per path, byte for byte as before build B. */
    static final String CURRENT_ADAPTER_ID = "sophisticatedbackpacks-3.26.3.2158";
    static final String LEGACY_ADAPTER_ID = "sophisticatedbackpacks-3.25.78.2107";
    /** Never in a row: an OFF path matches nothing it could record. Keeps the loader's id prefix. */
    static final String OFF_ADAPTER_ID = "sophisticatedbackpacks-off";
    static final String PATH_SITE = "adapter:SophisticatedBackpacksMenuAdapter.path";
    /** At most 160 characters (the fail-safe's member-text cap), so the warning is never cut (build B F1-4 r2, B-1). */
    static final String CONCEPT_LOOKED_FOR = SophisticatedCoreMarkers.LINKED_ENDPOINT_COMPONENT
            + " (linked Core) or " + SophisticatedCoreMarkers.PRE_LINKED_REPOSITORY + " (older Core)";
    /** Build B F1-4: where a linked backpack is refused ({@link #linkedRefused}). */
    static final String LINKED_SITE = "adapter:SophisticatedBackpacksMenuAdapter.linked";
    static final String LINKED_LOOKED_FOR = SophisticatedCoreMarkers.LINKED_ENDPOINT_COMPONENT
            + " (a DataComponentType supplier), to tell a linked backpack";
    /** Backpacks-side legacy marker (3.25.78.2107 only; 3.26.3+ take a Level first): NOTES-f1-3a section 2. */
    static final String LEGACY_BACKPACKS_MARKER = "BackpackItem.hasEverlastingUpgrade(ItemStack)";
    static final String LEGACY_UNPAIRED_LOOKED_FOR = LEGACY_BACKPACKS_MARKER
            + " (Backpacks 3.25.78) with a Core before linked storage; that Core has an untested Backpacks";
    /** Used only when building a selection failed itself (for example memory ran out); never cached. */
    private static final Selection UNPROBED = new Selection(Path.CONCEPT_UNUSABLE,
            SophisticatedCoreMarkers.PROBE_FAILED + "unknown", null, false);
    private static volatile Selection selected;
    // Anchor discovery is a separate scan, not the opened menu's custody capture.
    private static final int MAX_PARENT_ANCHOR_CELLS = 300;
    private static final int MAX_PLAYER_PROVIDER_ANCHOR_CELLS = 300;
    private static final Set<Class<?>> CONTEXTS = Set.of(BackpackContext.Item.class,
            BackpackContext.Block.class, BackpackContext.AnotherPlayer.class,
            BackpackContext.ItemSubBackpack.class, BackpackContext.BlockSubBackpack.class,
            BackpackContext.AnotherPlayerSubBackpack.class);
    /** Backpacks' Curios handler name ({@code CuriosCompat.addPlayerInventoryHandlers}, read in the bytes). */
    static final String CURIOS_INVENTORY = "curios";
    /**
     * The player inventory handlers a backpack may be opened from and recorded. soph-p3: on CURRENT also the places an
     * {@code Item} or {@code AnotherPlayer} context may name; any other handler name (for example another accessory
     * mod's) is refused with a warning.
     */
    static final List<String> PROVIDERS = List.of(PlayerInventoryProvider.MAIN_INVENTORY,
            PlayerInventoryProvider.OFFHAND_INVENTORY, PlayerInventoryProvider.ARMOR_INVENTORY, CURIOS_INVENTORY);
    /** soph-p3: the nested contexts; CURRENT refuses them (they ride on the inception upgrade, which stays refused). */
    private static final Set<Class<?>> SUB_BACKPACK_CONTEXTS = Set.of(BackpackContext.ItemSubBackpack.class,
            BackpackContext.BlockSubBackpack.class, BackpackContext.AnotherPlayerSubBackpack.class);
    /** soph-p3 (part 3a): where an installed upgrade is checked ({@link SophisticatedUpgradePolicy}). */
    static final String UPGRADE_SITE = "adapter:SophisticatedBackpacksMenuAdapter.upgrades";
    /** soph-p3 (part 3b): where a backpack opened from a place CURRENT does not record is refused. */
    static final String CONTEXT_SITE = "adapter:SophisticatedBackpacksMenuAdapter.context";
    /** A sub-backpack (a backpack opened inside another through the inception upgrade). */
    static final String SUB_BACKPACK_REASON = "sub-backpack-not-recorded";
    /** Any other context class, or an item context naming a handler outside {@link #PROVIDERS}. */
    static final String CONTEXT_REASON = "backpack-context-not-recorded";
    /**
     * soph-p3 r2 (review-01 MINOR-1): an admitted context whose backpack stack is not where the context says, or not the
     * menu's own backpack, while the native menu still works. Written at {@link #CONTEXT_SITE}; never where the native
     * rule is false too (owner gone, block out of reach or gone), since there no item moves.
     */
    static final String SOURCE_REASON = "backpack-source-not-proven";
    /** soph-p3 r2 (review-01 NIT-2): a menu that is a subclass of {@code BackpackContainer}, at {@link #CONTEXT_SITE}. */
    static final String MENU_REASON = "backpack-menu-subclass-not-recorded";

    /**
     * The behaviour path, chosen by what is present (build B R1d/Row A; no version is read). Only CURRENT and
     * LEGACY record; the other two switch the key OFF at first use with one warning naming what was looked for.
     */
    enum Path {
        /** Core's linked-storage concept is present (no class-hash check since build B F1-4). */
        CURRENT,
        /** No linked-storage concept, the Core marker that predates it, and the Backpacks 3.25.78 marker. */
        LEGACY,
        /**
         * Present but unusable: the pre-linked Core without the Backpacks legacy marker, or a probe that failed.
         * Never a fall-back to LEGACY.
         */
        CONCEPT_UNUSABLE,
        /** Neither the concept nor the pre-concept marker: the build cannot be placed. */
        CONCEPT_NOT_FOUND;

        boolean records() {
            return this == CURRENT || this == LEGACY;
        }
    }

    /**
     * One path choice. {@code offDetail} is the OFF warning's detail (null on a recording path); {@code failure} is
     * the probe failure, if any; {@code cacheable} is false only for a VM error (or a choice that could not be
     * built), which is OFF for that call and probed again at the next.
     */
    record Selection(Path path, String offDetail, Throwable failure, boolean cacheable) {
        static Selection of(Path path, String offDetail) {
            return new Selection(path, offDetail, null, true);
        }
    }

    /**
     * Path rule over the three probes, each read only when needed: the concept alone selects CURRENT (build B F1-4
     * removed the class-hash pin that also had to hold), the Backpacks legacy marker is read only with the pre-linked
     * Core. A concept never leads to the laxer legacy path. A probe that throws is present-but-unusable: OFF naming
     * the failure, cached unless it is a {@link VirtualMachineError}.
     */
    static Selection choose(BooleanSupplier conceptMarker, BooleanSupplier coreLegacyMarker,
            BooleanSupplier backpacksLegacyMarker) {
        try {
            if (conceptMarker.getAsBoolean()) return Selection.of(Path.CURRENT, null);
            if (!coreLegacyMarker.getAsBoolean()) return Selection.of(Path.CONCEPT_NOT_FOUND, CONCEPT_LOOKED_FOR);
            return backpacksLegacyMarker.getAsBoolean() ? Selection.of(Path.LEGACY, null)
                    : Selection.of(Path.CONCEPT_UNUSABLE, LEGACY_UNPAIRED_LOOKED_FOR);
        } catch (Throwable failed) {
            return new Selection(Path.CONCEPT_UNUSABLE, SophisticatedCoreMarkers.probeFailed(failed), failed,
                    !(failed instanceof VirtualMachineError));
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

    /** The real probes wired into the rule, uncached (tests read it without touching the process-wide cache). */
    static Selection probe() {
        return choose(SophisticatedCoreMarkers::linkedEndpointComponentPresent,
                SophisticatedCoreMarkers::preLinkedRepositoryPresent,
                SophisticatedBackpacksMenuAdapter::legacyBackpacksMarkerPresent);
    }

    static Path path() {
        return selection().path();
    }

    /** The Backpacks 3.25.78 item helper without a Level; not-there only is false. */
    static boolean legacyBackpacksMarkerPresent() {
        try {
            return BackpackItem.class.getDeclaredMethod("hasEverlastingUpgrade", ItemStack.class) != null;
        } catch (NoSuchMethodException absent) {
            return false;
        }
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
            AdapterFailSafe.conceptNotFound(AdapterKey.SOPHISTICATED_BACKPACKS, PATH_SITE, selection.offDetail());
        } else {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_BACKPACKS, PATH_SITE, selection.failure());
        }
        return selection.path();
    }

    static boolean currentRuntime() {
        return path() == Path.CURRENT;
    }

    static String adapterId(Path path) {
        return switch (path) {
            case CURRENT -> CURRENT_ADAPTER_ID;
            case LEGACY -> LEGACY_ADAPTER_ID;
            case CONCEPT_UNUSABLE, CONCEPT_NOT_FOUND -> OFF_ADAPTER_ID;
        };
    }

    @Override public String adapterId() { return adapterId(path()); }
    @Override public boolean exact() { return true; }
    @Override public boolean usesHandlerBackedView() { return true; }
    @Override public boolean usesIndependentBackingTarget() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.ACTION_SCOPED_PHYSICAL; }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof BackpackContainer menu)) return AdapterMatch.NO_MATCH;
        Path path = recordingPath();
        // An OFF path still owns its menus: blocked, never handed to the generic fallback.
        if (!path.records()) return AdapterMatch.AMBIGUOUS;
        // soph-p3 r2 (Advisor F1-4 r3 N-1): the exact menu-class and context checks run before the linked check, as in
        // the Storage and Create-addon adapters, so the wrapper of a menu that is not exactly BackpackContainer is never
        // read. No row changes: each check only declines.
        if (menu.getClass() != BackpackContainer.class) {
            // review-01 NIT-2: on CURRENT a subclass stays declined, now with one warning per server start (LEGACY as before).
            if (path == Path.CURRENT) {
                AdapterFailSafe.refusedOnce(AdapterKey.SOPHISTICATED_BACKPACKS, CONTEXT_SITE, MENU_REASON);
            }
            return AdapterMatch.AMBIGUOUS;
        }
        if (!supportedContext(menu.getBackpackContext())) return AdapterMatch.AMBIGUOUS;
        if (path == Path.CURRENT && linkedRefused(menu)) return AdapterMatch.AMBIGUOUS;
        return path != Path.CURRENT || supportedCurrentWrapper(menu.getStorageWrapper())
                ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
    }

    /**
     * Build B F1-4 ({@link SophisticatedLinkedStorage}; temporary until the linked-group recording path, Owner option
     * B): on CURRENT a linked backpack (its stack carries Core's linked-storage endpoint) is not taken (nothing in that
     * open is recorded; the backpack still opens normally for the player), with one warning per server start; the key
     * stays ON. Before F1-4 {@link #supportedCurrentWrapper} already declined it here, without a line. LEGACY has no
     * linked storage. Only opens already declined are refused, so no row that was written before is lost.
     *
     * <p>r3 (re-check R-m1): the stack is read only from an exact {@link BackpackWrapper}, the one class the base
     * CURRENT checks read. A menu whose wrapper is one of Backpacks' linked wrapper classes ({@link
     * #LINKED_WRAPPER_CLASSES}, matched by name) is refused the same way without calling any of its methods; any other
     * wrapper class is left to {@link #supportedCurrentWrapper}, which declines it as before.</p>
     *
     * <p>Never throws for a non-VM failure: a failure to read the wrapper or the stack is reported or sorted by
     * {@link SophisticatedLinkedStorage#refusedOpen}; a {@link VirtualMachineError} (stack or memory) propagates.</p>
     */
    static boolean linkedRefused(BackpackContainer menu) {
        Object wrapper;
        try {
            wrapper = menu.getStorageWrapper();
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_BACKPACKS, LINKED_SITE, failure);
            return true;
        }
        // No backpack wrapper: no stack that could be linked (an unbuilt fixture only); the checks below decide.
        if (wrapper == null) return false;
        if (LINKED_WRAPPER_CLASSES.contains(wrapper.getClass().getName())) {
            return SophisticatedLinkedStorage.refused(AdapterKey.SOPHISTICATED_BACKPACKS, LINKED_SITE,
                    SophisticatedLinkedStorage.Linkage.LINKED, LINKED_LOOKED_FOR);
        }
        if (wrapper.getClass() != BackpackWrapper.class) return false;
        BackpackWrapper backpack = (BackpackWrapper) wrapper;
        return SophisticatedLinkedStorage.refusedOpen(AdapterKey.SOPHISTICATED_BACKPACKS, LINKED_SITE, LINKED_LOOKED_FOR,
                () -> SophisticatedLinkedStorage.stackLinkage(backpack.getBackpack()));
    }

    /**
     * r3 (re-check R-m1): Backpacks' linked wrapper classes, by name (the names SophisticatedBackpacksMenuAdapterTest
     * already checks). A menu carrying one is a linked backpack; none of its methods is called.
     */
    static final List<String> LINKED_WRAPPER_CLASSES = List.of(
            "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.LinkedStorageBackpackWrapper",
            "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackLinkedStorageHostWrapper");

    /**
     * Which contexts the path records. soph-p3 (part 3b): on CURRENT an exact {@code Item} or {@code AnotherPlayer}
     * context naming one of {@link #PROVIDERS} (main inventory, offhand, worn in the chest armour slot, a Curios slot)
     * and an exact {@code Block} context (a placed backpack). A sub-backpack context, any other context class and an item
     * context naming another handler are refused with one warning per server start ({@link AdapterFailSafe#refusedOnce});
     * the backpack still opens normally and nothing in that open is recorded. The source itself is proven later, before
     * the native resolver runs ({@link #resolveContext}).
     */
    static boolean supportedContext(BackpackContext context) {
        if (context == null) return false;
        Path path = path();
        if (path == Path.LEGACY) return CONTEXTS.contains(context.getClass());
        if (path != Path.CURRENT) return false;
        Class<?> type = context.getClass();
        if (type == BackpackContext.Block.class) return true;
        if (type != BackpackContext.Item.class && type != BackpackContext.AnotherPlayer.class) {
            AdapterFailSafe.refusedOnce(AdapterKey.SOPHISTICATED_BACKPACKS, CONTEXT_SITE,
                    SUB_BACKPACK_CONTEXTS.contains(type) ? SUB_BACKPACK_REASON : CONTEXT_REASON);
            return false;
        }
        Object handlerName;
        try {
            var name = BackpackContext.Item.class.getDeclaredField("handlerName");
            name.setAccessible(true);
            handlerName = name.get(context);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_BACKPACKS, "adapter:SophisticatedBackpacksMenuAdapter.supportedContext", unavailable);
            return false;
        }
        if (handlerName instanceof String name && PROVIDERS.contains(name)) return true;
        AdapterFailSafe.refusedOnce(AdapterKey.SOPHISTICATED_BACKPACKS, CONTEXT_SITE, CONTEXT_REASON);
        return false;
    }

    private Optional<Anchor> current(MenuAdapterContext context) {
        if (context == null || context.player() == null || context.player().containerMenu != context.menu()
                || match(context) != AdapterMatch.SUPPORTED) return Optional.empty();
        try {
            BackpackContainer menu = (BackpackContainer) context.menu();
            Optional<Anchor> anchor = resolveContext(menu.getBackpackContext(), context.player(), 0);
            if (anchor.isEmpty() || anchor.orElseThrow().wrapper() == menu.getStorageWrapper()) return anchor;
            // soph-p3 r2 (review-01 MINOR-1): the context resolves a backpack that is not the menu's own; the native menu
            // still works until its own check closes it, so CURRENT names it (LEGACY as before).
            return currentRuntime() ? sourceNotProven() : Optional.empty();
        } catch (RuntimeException invalidContext) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_BACKPACKS, "adapter:SophisticatedBackpacksMenuAdapter.current", invalidContext);
            return Optional.empty();
        }
    }

    @Override
    public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        return current(context).flatMap(anchor -> SophisticatedMenuSlots.describe(
                (BackpackContainer) context.menu(), context.player().getInventory(), anchor.stack(), anchor.members(), true));
    }

    @Override
    public Optional<ContainerTarget> resolveMenuBackingTarget(MenuAdapterContext context) {
        return current(context).map(anchor -> target(context.player(), anchor));
    }

    @Override
    public Optional<MenuAccessTarget> resolveContainerAccessTarget(MenuAdapterContext context) {
        return current(context).flatMap(anchor -> {
            String id = BuiltInRegistries.ITEM.getKey(anchor.stack().getItem()).toString();
            if (!id.startsWith("sophisticatedbackpacks:")) return Optional.empty();
            return Optional.of(new MenuAccessTarget(target(context.player(), anchor), id));
        });
    }

    private static ContainerTarget target(Player player, Anchor anchor) {
        BlockPos access = anchor.blockPosition().orElseGet(player::blockPosition);
        return new ContainerTarget(player.level().dimension().location().toString(), access.getX(), access.getY(), access.getZ(),
                DisplayNameResolver.itemContainerLabel(anchor.stack()),
                anchor.blockPosition().isPresent() ? AccessKind.PHYSICAL : AccessKind.PORTABLE_MENU,
                anchor.kind(), "sophisticatedbackpacks:contents:" + anchor.uuid());
    }

    @Override
    public InventorySlotRole classifySlot(MenuAdapterContext context, int index, Slot slot) {
        return context != null && context.player() != null && context.menu() instanceof BackpackContainer menu
                ? SophisticatedMenuSlots.classify(menu, context.player().getInventory(), slot) : InventorySlotRole.UNKNOWN;
    }

    @Override
    public List<MenuObservedSlot> additionalObservedSlots(MenuAdapterContext context) {
        return context != null && context.menu() instanceof BackpackContainer menu
                ? SophisticatedMenuSlots.additional(menu) : List.of();
    }

    /** Public context APIs are re-resolved; cached nested parent wrappers alone are insufficient. */
    static Optional<Anchor> resolveContext(BackpackContext context, Player player, int depth) {
        if (!supportedContext(context) || player == null || depth > 4 || !context.canInteractWith(player)) return Optional.empty();
        // supportedContext admits only CURRENT or LEGACY, so after the CURRENT branch only the LEGACY path remains.
        if (currentRuntime()) return depth == 0 ? resolveCurrent(context, player) : Optional.empty();
        boolean nested = context.getClass() == BackpackContext.ItemSubBackpack.class
                || context.getClass() == BackpackContext.BlockSubBackpack.class
                || context.getClass() == BackpackContext.AnotherPlayerSubBackpack.class;
        if (nested) {
            BackpackContext parentContext = context.getParentBackpackContext();
            Optional<Anchor> parent = resolveContext(parentContext, player, depth + 1);
            Optional<IStorageWrapper> cachedParent = context.getParentBackpackWrapper(player);
            if (parent.isEmpty() || cachedParent.isEmpty() || cachedParent.orElseThrow() != parent.orElseThrow().wrapper()) {
                return Optional.empty();
            }
            IBackpackWrapper child = context.getBackpackWrapper(player);
            Optional<UUID> uuid = existingIdentity(child);
            if (uuid.isEmpty() || child == parent.orElseThrow().wrapper()
                    || uuid.orElseThrow().equals(parent.orElseThrow().uuid())) return Optional.empty();
            var parentInventory = cachedParent.orElseThrow().getInventoryHandler();
            if (parentInventory == null || parentInventory.getSlots() < 1
                    || parentInventory.getSlots() > MAX_PARENT_ANCHOR_CELLS) return Optional.empty();
            int matches = 0;
            for (int i = 0; i < parentInventory.getSlots(); i++) {
                if (parentInventory.getStackInSlot(i) == child.getBackpack()) matches++;
            }
            if (matches != 1) return Optional.empty();
            List<Object> members = new ArrayList<>(parent.orElseThrow().members());
            members.add(parent.orElseThrow().wrapper());
            members.add(parent.orElseThrow().stack());
            members.add(parentInventory);
            members.add(child);
            members.add(child.getBackpack());
            return Optional.of(new Anchor(child, child.getBackpack(), uuid.orElseThrow(),
                    parent.orElseThrow().kind() == BackingContextKind.OTHER_PLAYER_BOUND
                            ? BackingContextKind.OTHER_PLAYER_BOUND : BackingContextKind.NESTED_ITEM,
                    distinctMembers(members), parent.orElseThrow().blockPosition()));
        }

        IBackpackWrapper wrapper = context.getBackpackWrapper(player);
        Optional<UUID> uuid = existingIdentity(wrapper);
        if (uuid.isEmpty()) return Optional.empty();
        ItemStack stack = wrapper.getBackpack();
        if (context.getClass() == BackpackContext.Block.class) {
            BlockPos pos = context.getBackpackPosition(player);
            if (!(player.level().getBlockEntity(pos) instanceof BackpackBlockEntity block)
                    || block.getBackpackWrapper() != wrapper
                    || MenuPhysicalBlockTarget.fromBlockEntity(block, player.level(), player.level().dimension()).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Anchor(wrapper, stack, uuid.orElseThrow(), BackingContextKind.PLACED_BLOCK,
                    List.of(block), Optional.of(pos.immutable())));
        }

        boolean other = context.getClass() == BackpackContext.AnotherPlayer.class;
        Player owner = player;
        if (other) {
            var entity = context.getOwnerPlayer(player).orElse(null);
            if (!(entity instanceof Player otherPlayer) || otherPlayer == player
                    || otherPlayer.level() != player.level() || !otherPlayer.isAlive()
                    || player.level().getPlayerByUUID(otherPlayer.getUUID()) != otherPlayer) return Optional.empty();
            owner = otherPlayer;
            if (!permissionAllowsOtherPlayer(owner, wrapper)) return Optional.empty();
        }
        Optional<Object> provider = findProviderAnchor(owner, stack, other);
        if (provider.isEmpty()) return Optional.empty();
        return Optional.of(new Anchor(wrapper, stack, uuid.orElseThrow(),
                other ? BackingContextKind.OTHER_PLAYER_BOUND : BackingContextKind.ITEM_BOUND,
                List.of(owner, provider.orElseThrow(), wrapper, stack), Optional.empty()));
    }

    static Optional<UUID> existingIdentity(IBackpackWrapper wrapper) {
        if (wrapper == null || wrapper instanceof IBackpackWrapper.Noop) return Optional.empty();
        Path path = path();
        if (!path.records() || path == Path.CURRENT && !supportedCurrentWrapper(wrapper)) return Optional.empty();
        ItemStack stack = wrapper.getBackpack();
        if (stack == null || stack.isEmpty() || stack.getCount() != 1 || !(stack.getItem() instanceof BackpackItem)) return Optional.empty();
        UUID uuid = stack.get(ModCoreDataComponents.STORAGE_UUID);
        return uuid != null && wrapper.getContentsUuid().filter(uuid::equals).isPresent() ? Optional.of(uuid) : Optional.empty();
    }

    /**
     * soph-p3 (part 3b): the CURRENT anchor for the three admitted context classes ({@link #supportedContext}). Current
     * native contexts may resolve linked facades, so each proves its direct, unlinked source before the native resolver
     * runs: an item context reads the named handler's own cell first, a placed backpack reads its block entity's own
     * wrapper first.
     */
    private static Optional<Anchor> resolveCurrent(BackpackContext context, Player player) {
        try {
            if (context.getClass() == BackpackContext.Block.class) return resolveCurrentBlock(context, player);
            if (context.getClass() == BackpackContext.AnotherPlayer.class) {
                var entity = context.getOwnerPlayer(player).orElse(null);
                if (!(entity instanceof Player owner) || !liveOtherPlayer(player, owner)) return Optional.empty();
                return resolveCurrentItem(context, player, owner, true);
            }
            if (context.getClass() != BackpackContext.Item.class) return Optional.empty();
            return resolveCurrentItem(context, player, player, false);
        } catch (RuntimeException | LinkageError invalidSource) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_BACKPACKS, "adapter:SophisticatedBackpacksMenuAdapter.resolveCurrent", invalidSource);
            return Optional.empty();
        }
    }

    /** Another player who is a live, distinct player in the opener's level (as the LEGACY branch requires). */
    private static boolean liveOtherPlayer(Player player, Player owner) {
        return owner != player && owner.level() == player.level() && owner.isAlive()
                && player.level().getPlayerByUUID(owner.getUUID()) == owner;
    }

    /**
     * Current native contexts may resolve linked facades. Prove the direct source before calling that resolver.
     * soph-p3: any handler in {@link #PROVIDERS}, read on the backpack's owner ({@code owner}: the opener, or for another
     * player's backpack that player); another player's backpack also needs a handler that native code lets another player
     * reach (worn: chest armour slot or Curios).
     *
     * <p>soph-p3 r2 (review-01 MAJOR-1): the server's and the owner's "another player can open" permission is not read
     * here. Native code reads it once, when the menu opens, and never again while it is open (the menu's own check is
     * only the owner being alive and within reach), so a permission switched off mid-session does not stop the native
     * menu; who acts (the menu's player), what moves (the menu's own cells) and which backpack (this exact stack in the
     * owner's named cell, and its contents UUID) are proven without it, so those moves stay recorded.</p>
     *
     * <p>r2 (review-01 MINOR-1): an exit where the source is not proven while the native menu would still work writes
     * {@link #SOURCE_REASON} once per server start (counted after that); a handler another player cannot reach writes
     * {@link #CONTEXT_REASON}. A refused upgrade writes the policy's own line only.</p>
     */
    private static Optional<Anchor> resolveCurrentItem(BackpackContext context, Player opener, Player owner, boolean other) {
        try {
            var handlerField = BackpackContext.Item.class.getDeclaredField("handlerName");
            var identifierField = BackpackContext.Item.class.getDeclaredField("identifier");
            handlerField.setAccessible(true);
            identifierField.setAccessible(true);
            String name = (String) handlerField.get(context);
            String identifier = (String) identifierField.get(context);
            // A name outside PROVIDERS was already refused with its line by supportedContext.
            if (name == null || !PROVIDERS.contains(name)) return Optional.empty();
            if (identifier == null) return sourceNotProven();
            var provider = PlayerInventoryProvider.get().getPlayerInventoryHandler(name);
            // No such handler registered: native finds no backpack either (its no-op wrapper), so nothing can move.
            if (provider.isEmpty()) return Optional.empty();
            var handler = provider.orElseThrow();
            if (other && !handler.isAccessibleByAnotherPlayer()) {
                // Only another mod opens this natively (Backpacks opens another player's backpack from armour or Curios).
                AdapterFailSafe.refusedOnce(AdapterKey.SOPHISTICATED_BACKPACKS, CONTEXT_SITE, CONTEXT_REASON);
                return Optional.empty();
            }
            var identifiers = handler.getIdentifiers(owner);
            int slot = context.getBackpackSlotIndex();
            int count = handler.getSlotCount(owner, identifier);
            if (identifiers == null || identifiers.size() > 64 || !identifiers.contains(identifier)
                    || slot < 0 || slot >= count || count > MAX_PLAYER_PROVIDER_ANCHOR_CELLS) return sourceNotProven();
            ItemStack stack = handler.getStackInSlot(owner, identifier, slot);
            if (!unlinkedCurrentStack(stack)) return sourceNotProven();
            // AnotherPlayer resolves on its own owner (native: Item.getBackpackWrapper(otherPlayer)).
            IBackpackWrapper wrapper = context.getBackpackWrapper(opener);
            if (wrapper == null || wrapper.getClass() != BackpackWrapper.class || wrapper.getBackpack() != stack) {
                return sourceNotProven();
            }
            // The stack is the unlinked one just read, so only the upgrade policy (its own line) or a reported read
            // failure can decline here.
            if (!supportedCurrentWrapper(wrapper)) return Optional.empty();
            Optional<UUID> uuid = existingIdentity(wrapper);
            Optional<Object> unique = findProviderAnchor(owner, stack, other);
            if (uuid.isEmpty() || unique.isEmpty() || unique.orElseThrow() != handler) return sourceNotProven();
            return Optional.of(new Anchor(wrapper, stack, uuid.orElseThrow(),
                    other ? BackingContextKind.OTHER_PLAYER_BOUND : BackingContextKind.ITEM_BOUND,
                    List.of(owner, handler, wrapper, stack), Optional.empty()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError invalidSource) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_BACKPACKS, "adapter:SophisticatedBackpacksMenuAdapter.resolveCurrentItem", invalidSource);
            return Optional.empty();
        }
    }

    /**
     * soph-p3: a placed backpack on CURRENT. The block entity's own wrapper is read first and must be an exact, unlinked
     * {@link BackpackWrapper} whose upgrades the policy admits; only then is the context's resolver asked, and it must
     * answer that same object (on a server it returns the block entity's wrapper; only a client resolves a linked one).
     * r2 (review-01 MINOR-1): a block entity whose own wrapper is not that exact, unlinked backpack, or a resolver that
     * answers another object, writes {@link #SOURCE_REASON}; no block entity there (the native rule is false too) writes
     * nothing.
     */
    private static Optional<Anchor> resolveCurrentBlock(BackpackContext context, Player player) {
        BlockPos pos = context.getBackpackPosition(player);
        if (pos == null || !(player.level().getBlockEntity(pos) instanceof BackpackBlockEntity block)) return Optional.empty();
        IBackpackWrapper direct = block.getBackpackWrapper();
        if (direct == null || direct.getClass() != BackpackWrapper.class
                || !unlinkedCurrentStack(direct.getBackpack())) return sourceNotProven();
        // Exact and unlinked: only the upgrade policy (its own line) or a reported read failure can decline here.
        if (!supportedCurrentWrapper(direct)) return Optional.empty();
        if (context.getBackpackWrapper(player) != direct) return sourceNotProven();
        Optional<UUID> uuid = existingIdentity(direct);
        if (uuid.isEmpty()) return sourceNotProven();
        // Cannot fail for the block entity just read from the player's own level (review-01 check 4).
        if (MenuPhysicalBlockTarget.fromBlockEntity(block, player.level(), player.level().dimension()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Anchor(direct, direct.getBackpack(), uuid.orElseThrow(), BackingContextKind.PLACED_BLOCK,
                List.of(block), Optional.of(pos.immutable())));
    }

    /**
     * An exact, unlinked {@link BackpackWrapper} whose installed upgrades the shared policy admits. soph-p3 (part 3a):
     * the old "no upgrade at all" loop is replaced by {@link SophisticatedUpgradePolicy} with the Backpacks-only
     * byte-read wrappers ({@link SophisticatedUpgradePolicy#BACKPACKS_ADMITTED_WRAPPER_CLASSES}); a refused or unreadable
     * upgrade state writes one warning per server start and is counted. Called at the match and at every capture (the
     * producer re-runs before and after each action), so an upgrade the click itself adds is checked by the after-read.
     */
    static boolean supportedCurrentWrapper(Object wrapper) {
        if (wrapper == null || wrapper.getClass() != BackpackWrapper.class
                || !unlinkedCurrentStack(((BackpackWrapper) wrapper).getBackpack())) return false;
        try {
            // A missing handler or an out-of-range cell count is the policy's unreadable state (one line, counted).
            return SophisticatedUpgradePolicy.admitted(AdapterKey.SOPHISTICATED_BACKPACKS, UPGRADE_SITE,
                    ((BackpackWrapper) wrapper).getUpgradeHandler(),
                    SophisticatedUpgradePolicy.BACKPACKS_ADMITTED_WRAPPER_CLASSES);
        } catch (RuntimeException | LinkageError unavailable) {
            AdapterFailSafe.report(AdapterKey.SOPHISTICATED_BACKPACKS, "adapter:SophisticatedBackpacksMenuAdapter.supportedCurrentWrapper", unavailable);
            return false;
        }
    }

    /**
     * soph-p3 r2 (review-01 MINOR-1): declines a CURRENT open whose backpack source is not proven while the native menu
     * still works, with one warning per server start ({@link AdapterFailSafe#refusedOnce}, counted, never throws); the
     * backpack stays open for the player.
     */
    private static Optional<Anchor> sourceNotProven() {
        AdapterFailSafe.refusedOnce(AdapterKey.SOPHISTICATED_BACKPACKS, CONTEXT_SITE, SOURCE_REASON);
        return Optional.empty();
    }

    private static boolean unlinkedCurrentStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() != 1 || !(stack.getItem() instanceof BackpackItem)
                || stack.get(ModCoreDataComponents.STORAGE_UUID) == null) return false;
        try {
            // Kept reflective so the historical optional compile API remains unchanged.
            // All current IBackpackWrappers implement ILinkedStorageEndpointProvider;
            // interface membership alone therefore cannot distinguish direct contents.
            Object supplier = ModCoreDataComponents.class.getField("LINKED_STORAGE_ENDPOINT").get(null);
            if (!(supplier instanceof java.util.function.Supplier<?> source)
                    || !(source.get() instanceof DataComponentType<?> endpoint)) return false;
            return !stack.has(endpoint);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** Match a source-resolved stack to one bounded registered live provider, never a nearby item. */
    static Optional<Object> findProviderAnchor(Player owner, ItemStack exactStack, boolean otherPlayer) {
        Object found = null;
        boolean accessible = false;
        int cells = 0;
        for (String name : PROVIDERS) {
            var candidate = PlayerInventoryProvider.get().getPlayerInventoryHandler(name);
            if (candidate.isEmpty()) continue;
            var handler = candidate.orElseThrow();
            var identifiers = handler.getIdentifiers(owner);
            if (identifiers == null || identifiers.size() > 64) return Optional.empty();
            for (String identifier : identifiers) {
                int count = handler.getSlotCount(owner, identifier);
                if (count < 0 || count > MAX_PLAYER_PROVIDER_ANCHOR_CELLS - cells) return Optional.empty();
                cells += count;
                for (int i = 0; i < count; i++) {
                    if (handler.getStackInSlot(owner, identifier, i) == exactStack) {
                        if (found != null) return Optional.empty();
                        found = handler;
                        accessible = !otherPlayer || handler.isAccessibleByAnotherPlayer();
                    }
                }
            }
        }
        return accessible ? Optional.ofNullable(found) : Optional.empty();
    }

    private static List<Object> distinctMembers(List<Object> members) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        List<Object> distinct = new ArrayList<>();
        for (Object member : members) if (seen.put(member, true) == null) distinct.add(member);
        return distinct;
    }

    /**
     * The native open-time rule (Backpacks' {@code AnotherPlayerBackpackOpenPayload}): the server config and the owner's
     * own "another player can open" setting. Read on LEGACY only; CURRENT does not re-read it (r2, review-01 MAJOR-1:
     * {@link #resolveCurrentItem}). soph-p3: read through Core's {@code IStorageWrapper.getSettingsHandler()} and
     * {@code SettingsHandler.getGlobalSettingsCategory()}. Backpacks 3.26.14 removed {@code IBackpackWrapper}'s covariant
     * {@code getSettingsHandler()} (bytecmp-02 F-1), which the old call bound to; 3.26.3 has that covariant getter and a
     * covariant {@code BackpackSettingsHandler.getGlobalSettingsCategory()}, with bridge methods, so the two Core
     * descriptors used here reach the same methods on both builds (review-01 NIT-1).
     */
    private static boolean permissionAllowsOtherPlayer(Player owner, IBackpackWrapper wrapper) {
        if (!Boolean.TRUE.equals(Config.SERVER.allowOpeningOtherPlayerBackpacks.get())) return false;
        IStorageWrapper storage = wrapper;
        var category = storage.getSettingsHandler().getGlobalSettingsCategory();
        return Boolean.TRUE.equals(SettingsManager.getSettingValue(owner, category.getPlayerSettingsTagName(),
                category, BackpackMainSettingsCategory.ANOTHER_PLAYER_CAN_OPEN));
    }

    record Anchor(IBackpackWrapper wrapper, ItemStack stack, UUID uuid, BackingContextKind kind,
            List<Object> members, Optional<BlockPos> blockPosition) {
        Anchor { members = List.copyOf(members); }
    }
}
