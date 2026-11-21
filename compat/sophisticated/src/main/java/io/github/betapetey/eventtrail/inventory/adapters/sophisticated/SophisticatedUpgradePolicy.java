package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.api.ISlotChangeResponseUpgrade;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IExtractResponseUpgrade;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IInsertResponseUpgrade;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IOverflowResponseUpgrade;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Build B soph-p1 (Sophisticated part 1, Owner decision received by 12:15 PM ET 2026-11-14): which installed upgrades a
 * Sophisticated storage may carry and still be recorded.
 *
 * <p>An upgrade is admitted only when its wrapper class is on an exact class-name allowlist. Every allowlisted wrapper was
 * read in the bytes of Core 1.5.17 / 1.5.25 / 1.5.32 and Storage 1.6.8 / 1.6.15 / 1.6.16 (soph-p1 NOTES section 2): it
 * changes storage contents only on a tick or a world event (between clicks), never synchronously inside a player's
 * click; it overrides neither {@code onAdded} nor {@code onBeforeRemoved}; and its settings tab adds only filter
 * (ghost) slots. Its own moves between clicks are automation and are not credited to anyone, like a hopper's.</p>
 *
 * <p>Anything else is refused: a wrapper class not on the list (compacting, void, compression, infinity, cooking,
 * crafting, stonecutter, tank, battery, jukebox, an unknown or new upgrade), an allowlisted class that implements one of
 * Core's in-click response interfaces on some build, and an upgrade state that does not read consistently (a filled
 * upgrade cell without exactly one wrapper of the same item, a wrapper on an empty cell). A refusal records nothing for
 * that open or capture, with one warning per adapter key, reason and site after each server start
 * ({@link AdapterFailSafe#refusedOnce}); the storage opens and works normally for the player and the key stays ON.</p>
 *
 * <p>The adapters call it at every capture (the physical-backing producer runs before and after each action), so an
 * upgrade the click adds that is not admitted fails the after-check and that action records nothing. So does a click that
 * makes the menu rebuild an upgrade's settings slots (the descriptor no longer matches; Core 1.5.32 rebuilds whenever
 * an upgrade cell goes from empty to filled or filled to empty, and only a filter, magnet, pickup, feeding or hopper
 * upgrade has settings slots to rebuild). An admitted upgrade the click puts in or takes out, with no
 * such rebuild, passes both checks and is recorded as that item moving into or out of the upgrade cell.</p>
 */
public final class SophisticatedUpgradePolicy {
    /** The reason of an open refused because an installed upgrade is not admitted. */
    public static final String REASON = "upgrade-not-recorded";
    /** The reason of an open refused because the upgrade cells and wrappers did not read consistently. */
    public static final String STATE_REASON = "upgrade-state-unreadable";

    /**
     * Exact wrapper class names (byte-read, NOTES section 2). Basic and advanced tiers share one wrapper class each; the
     * stack tiers share {@code StackUpgradeItem$Wrapper}.
     */
    public static final Set<String> ADMITTED_WRAPPER_CLASSES = Set.of(
            "net.p3pp3rf1y.sophisticatedcore.upgrades.stack.StackUpgradeItem$Wrapper",
            "net.p3pp3rf1y.sophisticatedcore.upgrades.filter.FilterUpgradeWrapper",
            "net.p3pp3rf1y.sophisticatedcore.upgrades.magnet.MagnetUpgradeWrapper",
            "net.p3pp3rf1y.sophisticatedcore.upgrades.pickup.PickupUpgradeWrapper",
            "net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeWrapper",
            "net.p3pp3rf1y.sophisticatedcore.upgrades.pump.PumpUpgradeWrapper",
            "net.p3pp3rf1y.sophisticatedcore.upgrades.xppump.XpPumpUpgradeWrapper",
            "net.p3pp3rf1y.sophisticatedstorage.upgrades.hopper.HopperUpgradeWrapper");

    /**
     * Build B soph-p3 (part 3a): the Backpacks-only wrappers a backpack may also carry, by exact class name. Each was read
     * in the bytes of Backpacks 3.26.3 / 3.26.14 / 3.26.19 / 3.26.21 (identical on all four; soph-p3 NOTES section 2): it
     * implements none of Core's in-click response interfaces, overrides neither {@code onAdded} nor
     * {@code onBeforeRemoved}, its settings tab adds only Core filter (ghost) slots, and it moves items only outside a
     * menu click, never synchronously inside a player's click (a tick, which can also run between clicks while the
     * backpack's menu is open; a pick-block; a block or entity click; a sneak-right-click with the backpack on another
     * block's inventory, or the interaction key while wearing it); those moves are not rows (soph-p3 NOTES build items 3
     * to 5). As with the shared list, a refill, restock, deposit or advanced tool swapper upgrade put in or taken out while
     * the menu is open rebuilds its settings slots, and that click and the rest of that open are not recorded (NOTES
     * build item 2). Only the Backpacks adapter passes this set ({@link #admitted(AdapterKey, String,
     * UpgradeHandler, Set)}); Storage, the Create addon and Motion keep {@link #ADMITTED_WRAPPER_CLASSES} alone.
     */
    public static final Set<String> BACKPACKS_ADMITTED_WRAPPER_CLASSES = Set.of(
            "net.p3pp3rf1y.sophisticatedbackpacks.upgrades.everlasting.EverlastingUpgradeItem$Wrapper",
            "net.p3pp3rf1y.sophisticatedbackpacks.upgrades.refill.RefillUpgradeWrapper",
            "net.p3pp3rf1y.sophisticatedbackpacks.upgrades.restock.RestockUpgradeWrapper",
            "net.p3pp3rf1y.sophisticatedbackpacks.upgrades.deposit.DepositUpgradeWrapper",
            "net.p3pp3rf1y.sophisticatedbackpacks.upgrades.toolswapper.ToolSwapperUpgradeWrapper");

    /** What one read of a storage's upgrades found. */
    public enum Verdict {
        /** No upgrade, or only admitted upgrades, read consistently. */
        ADMITTED,
        /** An installed upgrade is not admitted. */
        REFUSED,
        /** The upgrade cells and wrappers do not read consistently. */
        STATE_UNREADABLE
    }

    private SophisticatedUpgradePolicy() {
    }

    /**
     * Applies the policy to one storage's upgrade handler: true when it may be recorded. A refusal writes the one
     * warning per server start and counts the open ({@link AdapterFailSafe#refusedOnce}). Throws what the handler's
     * reads throw; the caller reports it (as the adapters' existing upgrade checks did).
     */
    public static boolean admitted(AdapterKey key, String site, UpgradeHandler upgrades) {
        return admitted(key, site, upgrades, Set.of());
    }

    /**
     * soph-p3: as {@link #admitted(AdapterKey, String, UpgradeHandler)}, with one family's extra byte-read wrapper class
     * names also admitted (the Backpacks adapter passes {@link #BACKPACKS_ADMITTED_WRAPPER_CLASSES}). A null set reads as
     * an unreadable state (refused).
     */
    public static boolean admitted(AdapterKey key, String site, UpgradeHandler upgrades, Set<String> alsoAdmitted) {
        Verdict verdict = verdict(upgrades, alsoAdmitted);
        if (verdict == Verdict.ADMITTED) return true;
        AdapterFailSafe.refusedOnce(key, site, verdict == Verdict.REFUSED ? REASON : STATE_REASON);
        return false;
    }

    /** The policy over a live handler, read through the compile API only (cell count, cell stacks, slot wrappers). */
    public static Verdict verdict(UpgradeHandler upgrades) {
        return verdict(upgrades, Set.of());
    }

    /** soph-p3: the policy over a live handler with one family's extra admitted wrapper class names. */
    public static Verdict verdict(UpgradeHandler upgrades, Set<String> alsoAdmitted) {
        if (upgrades == null || alsoAdmitted == null) return Verdict.STATE_UNREADABLE;
        return verdict(upgrades.getSlots(), upgrades::getStackInSlot, upgrades.getSlotWrappers(),
                wrapper -> wrapper.getClass().getName(), alsoAdmitted);
    }

    /**
     * The rule, with the class-name read as a seam for tests: every filled cell has exactly one wrapper, keyed by its
     * cell, whose upgrade stack is of the cell's item; no wrapper sits on an empty cell or outside the cells; and every
     * wrapper's class is admitted and implements none of Core's in-click response interfaces.
     */
    static Verdict verdict(int slots, IntFunction<ItemStack> stackAt, Map<Integer, ? extends IUpgradeWrapper> wrappers,
            Function<Object, String> className) {
        return verdict(slots, stackAt, wrappers, className, Set.of());
    }

    /** soph-p3: the rule with one family's extra admitted class names ({@code alsoAdmitted}, never null here). */
    static Verdict verdict(int slots, IntFunction<ItemStack> stackAt, Map<Integer, ? extends IUpgradeWrapper> wrappers,
            Function<Object, String> className, Set<String> alsoAdmitted) {
        if (slots < 0 || slots > MenuInventoryLimits.MAX_DEVICE_CUSTODY_CELLS || stackAt == null || wrappers == null
                || className == null || alsoAdmitted == null) return Verdict.STATE_UNREADABLE;
        int filled = 0;
        boolean refused = false;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = stackAt.apply(slot);
            if (stack == null) return Verdict.STATE_UNREADABLE;
            IUpgradeWrapper wrapper = wrappers.get(slot);
            if (stack.isEmpty()) {
                if (wrapper != null || wrappers.containsKey(slot)) return Verdict.STATE_UNREADABLE;
                continue;
            }
            filled++;
            if (wrapper == null) return Verdict.STATE_UNREADABLE;
            ItemStack upgradeStack = wrapper.getUpgradeStack();
            if (upgradeStack == null || upgradeStack.isEmpty() || upgradeStack.getItem() != stack.getItem()) {
                return Verdict.STATE_UNREADABLE;
            }
            if (!admittedClass(wrapper, className, alsoAdmitted)) refused = true;
        }
        if (wrappers.size() != filled) return Verdict.STATE_UNREADABLE;
        return refused ? Verdict.REFUSED : Verdict.ADMITTED;
    }

    private static boolean admittedClass(IUpgradeWrapper wrapper, Function<Object, String> className,
            Set<String> alsoAdmitted) {
        String name = className.apply(wrapper);
        return name != null && (ADMITTED_WRAPPER_CLASSES.contains(name) || alsoAdmitted.contains(name))
                && !(wrapper instanceof ISlotChangeResponseUpgrade)
                && !(wrapper instanceof IInsertResponseUpgrade)
                && !(wrapper instanceof IExtractResponseUpgrade)
                && !(wrapper instanceof IOverflowResponseUpgrade);
    }
}
