package io.github.betapetey.eventtrail.events.parts;

import appeng.api.implementations.items.IMemoryCard;
import appeng.api.inventories.InternalInventory;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.core.definitions.AEItems;
import appeng.items.contents.NetworkToolMenuHost;
import appeng.items.tools.NetworkToolItem;
import appeng.parts.AEBasePart;
import appeng.recipes.game.CraftingUnitTransformRecipe;
import appeng.util.SettingsFrom;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The AE2-typed driver of the AE2 in-place hook family (parts readers design-03 section 5), shared by the six
 * {@code mixin.ae2inplace} hooks, and the bridge's AE2 side of the N-2 catch-all ({@link
 * PartRemovalAuditBridge.InPlaceSide}). Loaded only where AE2 is installed: by the hooks (applied only then) and by
 * the bridge by class name when the "ae2" mod is loaded. Every rule that needs no AE2 type is in
 * {@link InPlaceMoveAudit}.
 *
 * <p>Every hook body runs inside {@link HookGuard#wrap}: the mod's call runs exactly once, through
 * {@code once.call()}; a mod failure of a live player's action is counted ({@code in-place-original-threw}, once per
 * action with a frame, with or without one: review-02 MAJOR-1) and goes on as the same object; an EventTrail
 * failure marks the frame (or, before {@link #run}, the player's open action: review-02 NIT-3) reported and is
 * reported by the guard. The decision after the call is {@link InPlaceMoveAudit#afterCall}, on plain values.</p>
 */
public final class Ae2InPlaceMoves implements PartRemovalAuditBridge.InPlaceSide {
    /** Built by the bridge by class name (public, no arguments). */
    public Ae2InPlaceMoves() {
    }

    @Override
    public boolean memoryCard(Item item) {
        return item instanceof IMemoryCard;
    }

    @Override
    public Map<InventoryItemIdentity, Long> playerTotals(ServerPlayer player) {
        // N-2: a copy or a clear rewrites the card's own data, which must not count.
        return InPlaceMoveAudit.totals(playerStacks(player), stack -> stack.getItem() instanceof IMemoryCard);
    }

    // ---------------------------------------------------------------------------------------------------------
    // The three moves
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Design-03 5.2: the upgrade cards of a memory-card paste, at {@code MemoryCardItem.importGenericSettings}.
     * Snapshot only when {@code importTo} is an {@code IUpgradeableObject} and the player is known (otherwise the
     * upgrade restore cannot run).
     */
    public static void memoryCardPaste(AdapterKey key, String site, HookGuard.Original<?> once, Object importTo,
                                       Player player) {
        if (!(importTo instanceof IUpgradeableObject upgradeable) || player == null) {
            once.call();
            return;
        }
        Target target;
        try {
            if (importTo instanceof AEBasePart part) {
                target = Target.of(part.getBlockEntity(), partLabel(part));
            } else if (importTo instanceof BlockEntity blockEntity) {
                target = Target.of(blockEntity, null);
            } else {
                target = new Target(null, null, null);
            }
        } catch (Throwable failure) {
            failedBeforeRun(player);
            throw failure;
        }
        run(key, site, once, player, target, true, InventorySlotRole.REAL_UPGRADE, false,
                new Side<Map<InventoryItemIdentity, Long>>() {
                    @Override
                    public Map<InventoryItemIdentity, Long> read() {
                        return InPlaceMoveAudit.totals(slots(upgradeable.getUpgrades()));
                    }

                    @Override
                    public boolean changed(Map<InventoryItemIdentity, Long> before, Map<InventoryItemIdentity, Long> after) {
                        return !InPlaceMoveAudit.change(before, after).isEmpty();
                    }

                    @Override
                    public boolean mirrors(Map<InventoryItemIdentity, Long> before, Map<InventoryItemIdentity, Long> after,
                                           Map<InventoryItemIdentity, Long> observed) {
                        return InPlaceMoveAudit.mirrors(
                                InPlaceMoveAudit.opposite(InPlaceMoveAudit.change(before, after)), observed);
                    }
                });
    }

    /**
     * Design-03 5.3: the patterns of a memory-card paste. {@code host} is the provider's host (a part host gives
     * the part item's label), {@code blockEntity} the block entity that holds it (null: position unknown).
     * {@code everyStackIsBlank}: Applied Pneumatics' rule (every old stack comes back as Blank Patterns); else
     * AE2's rule (the five pattern kinds come back as Blank Patterns, anything else as itself).
     */
    public static void patternPaste(AdapterKey key, String site, HookGuard.Original<?> once, Object host,
                                    BlockEntity blockEntity, InternalInventory patterns, Player player,
                                    boolean everyStackIsBlank) {
        Target target;
        try {
            target = Target.of(blockEntity, host instanceof AEBasePart part ? partLabel(part) : null);
        } catch (Throwable failure) {
            failedBeforeRun(player);
            throw failure;
        }
        Predicate<ItemStack> returnsAsBlank = everyStackIsBlank ? stack -> true : Ae2InPlaceMoves::aPatternKind;
        run(key, site, once, player, target, true, InventorySlotRole.STORAGE, false, new Side<List<ItemStack>>() {
            @Override
            public List<ItemStack> read() {
                List<ItemStack> copies = new ArrayList<>();
                for (ItemStack stack : slots(patterns)) copies.add(stack.copy());
                return copies;
            }

            @Override
            public boolean changed(List<ItemStack> before, List<ItemStack> after) {
                return !InPlaceMoveAudit.change(InPlaceMoveAudit.totals(before), InPlaceMoveAudit.totals(after))
                        .isEmpty();
            }

            @Override
            public boolean mirrors(List<ItemStack> before, List<ItemStack> after,
                                   Map<InventoryItemIdentity, Long> observed) {
                return InPlaceMoveAudit.mirrors(InPlaceMoveAudit.patternExpected(before, after, returnsAsBlank,
                        AEItems.BLANK_PATTERN.stack()), observed);
            }
        });
    }

    /**
     * Design-03 5.4: a crafting-unit upgrade added, taken out or swapped, at
     * {@code AbstractCraftingUnitBlock.upgrade} and {@code useWithoutItem}. No creative rule of its own: the
     * mirror decides (a creative add consumes nothing, so it is counted as {@code in-place-creative}).
     */
    public static void craftingChange(AdapterKey key, String site, HookGuard.Original<?> once, Level level,
                                      BlockPos position, Player player) {
        Target target = new Target(level, position == null ? null : position.immutable(), null);
        run(key, site, once, player, target, false, InventorySlotRole.REAL_UPGRADE, true, new Side<Block>() {
            @Override
            public Block read() {
                return level.getBlockState(position).getBlock();
            }

            @Override
            public boolean changed(Block before, Block after) {
                return before != after;
            }

            @Override
            public boolean mirrors(Block before, Block after, Map<InventoryItemIdentity, Long> observed) {
                return InPlaceMoveAudit.mirrorsByType(InPlaceMoveAudit.craftingExpected(
                        CraftingUnitTransformRecipe.getRemovedUpgrade(level, before),
                        CraftingUnitTransformRecipe.getRemovedUpgrade(level, after)), observed);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------
    // The shared order (design-03 5.1)
    // ---------------------------------------------------------------------------------------------------------

    /** One hook's machine side: read before and after, whether it changed, and its mirror rule. */
    interface Side<S> {
        S read();

        boolean changed(S before, S after);

        boolean mirrors(S before, S after, Map<InventoryItemIdentity, Long> observed);
    }

    /** The target's level, block position (null: unknown) and part label (null: the host block's name). */
    record Target(Level level, BlockPos position, String label) {
        static Target of(BlockEntity blockEntity, String label) {
            if (blockEntity == null) return new Target(null, null, label);
            return new Target(blockEntity.getLevel(), blockEntity.getBlockPos().immutable(), label);
        }
    }

    /**
     * The order of every hook body (design-03 5.1): client side or off the server thread, the original only; the
     * frame and its window; the snapshots and the mark; the original once; then the diff, the scope rules, the
     * mirror and the report in {@link InPlaceMoveAudit#afterCall}.
     */
    static <S> void run(AdapterKey key, String site, HookGuard.Original<?> once, Player player, Target target,
                        boolean paste, InventorySlotRole role, boolean handles, Side<S> side) {
        Level level = target.level() != null ? target.level() : player == null ? null : player.level();
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) {
            once.call();
            return;
        }
        ServerPlayer live = player instanceof ServerPlayer serverPlayer && PartRemovalAuditBridge.liveActor(serverPlayer)
                ? serverPlayer : null;
        PartRemovalAuditBridge.InPlaceFrame frame = live == null || target.position() == null
                ? null : PartRemovalAuditBridge.inPlaceFrame(live, target.position());
        if (frame != null && !frame.openWindow()) {
            InPlaceMoveAudit.count(key, site, InPlaceMoveAudit.NESTED, frame);
            once.call();
            return;
        }
        try {
            S before = side.read();
            Map<InventoryItemIdentity, Long> playerBefore =
                    live == null ? Map.of() : InPlaceMoveAudit.totals(playerStacks(live));
            int mark = frame == null ? 0 : frame.collectedMark();
            try {
                once.call();
            } catch (Throwable modFailure) {
                // Review-02 MAJOR-1: a live player's action is counted with or without a frame; re-check 01
                // MINOR-1: automation's throw is counted as not a player.
                InPlaceMoveAudit.originalThrew(key, site, live != null, frame);
                throw modFailure;
            }
            S after = side.read();
            InPlaceMoveAudit.afterCall(key, site, side.changed(before, after), paste, handles, live != null,
                    live != null && live.getAbilities().instabuild, target.position() != null, frame,
                    hand -> hand instanceof IMemoryCard,
                    () -> {
                        List<ItemStack> now = playerStacks(live);
                        now.addAll(frame.collectedSince(mark));
                        return InPlaceMoveAudit.change(playerBefore, InPlaceMoveAudit.totals(now));
                    },
                    observed -> side.mirrors(before, after, observed), role, target.label());
        } catch (Throwable failure) {
            // An EventTrail failure: the guard reports it (and calls the mod if it has not run); the frame writes
            // no family row for this action and the bridge adds no second line.
            if (frame != null) frame.markReported();
            throw failure;
        } finally {
            if (frame != null) frame.closeWindow();
        }
    }

    /**
     * Review-02 NIT-3: an EventTrail failure in a hook's own reads before {@link #run} (a target or host lookup). The
     * guard reports the failure and calls the mod; the player's open use action of this tick writes no family row
     * and gets no second line, so no partial group is written. Never throws.
     */
    public static void failedBeforeRun(Player player) {
        try {
            if (player instanceof ServerPlayer serverPlayer) PartRemovalAuditBridge.inPlaceLookupFailed(serverPlayer);
        } catch (Throwable ignored) {
            // The guard reports the original failure; this must never replace it.
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------------------------

    /**
     * The player side (design-03 5.1, review A.6): every inventory slot except Network Tool stacks, plus the
     * Network Tool's inner inventory.
     */
    static List<ItemStack> playerStacks(Player player) {
        List<ItemStack> stacks = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getItem() instanceof NetworkToolItem) continue;
            stacks.add(stack);
        }
        NetworkToolMenuHost<?> tool = NetworkToolItem.findNetworkToolInv(player);
        if (tool != null) stacks.addAll(slots(tool.getInventory()));
        return stacks;
    }

    /** The non-empty stacks of an AE2 inventory, slot by slot (not copied). */
    static List<ItemStack> slots(InternalInventory inventory) {
        List<ItemStack> stacks = new ArrayList<>();
        if (inventory == null) return stacks;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack != null && !stack.isEmpty()) stacks.add(stack);
        }
        return stacks;
    }

    /** The slots of an upgrade inventory (an {@code InternalInventory}). */
    static List<ItemStack> slots(IUpgradeInventory upgrades) {
        return slots((InternalInventory) upgrades);
    }

    /** AE2's rule (clearPatternInventory): the four encoded kinds and the blank come back as Blank Patterns. */
    static boolean aPatternKind(ItemStack stack) {
        return stack.is(AEItems.CRAFTING_PATTERN.asItem())
                || stack.is(AEItems.PROCESSING_PATTERN.asItem())
                || stack.is(AEItems.SMITHING_TABLE_PATTERN.asItem())
                || stack.is(AEItems.STONECUTTING_PATTERN.asItem())
                || stack.is(AEItems.BLANK_PATTERN.asItem());
    }

    /**
     * A part target's label: the part item's own name, through the row writer's item-name call (review m-7), with
     * the part's custom name when it has one (review-02 MINOR-2): read as the AE2 parts reader reads it, from AE2's
     * own {@code exportSettings(DISMANTLE_ITEM)} export, keeping only its {@code CUSTOM_NAME}.
     */
    static String partLabel(AEBasePart part) {
        DataComponentMap.Builder exported = DataComponentMap.builder();
        part.exportSettings(SettingsFrom.DISMANTLE_ITEM, exported);
        return InPlaceMoveAudit.partLabel(part.getPartItem(), exported.build());
    }
}
