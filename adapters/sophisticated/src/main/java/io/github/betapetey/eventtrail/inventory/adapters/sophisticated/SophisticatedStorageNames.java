package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import io.github.betapetey.eventtrail.compat.ContainerLabelFallbacks;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.DisplayNameResolver.RenameState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageWrapper;
import net.p3pp3rf1y.sophisticatedstorage.block.WoodStorageBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.item.StorageBlockItem;
import net.p3pp3rf1y.sophisticatedstorage.item.WoodStorageBlockItem;

import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * SOPH-LABELS: the one name of a Sophisticated Storage chest or barrel (the block-name fix's Sophisticated detail
 * provider, BN 2.2 step 2, built early), the same placed (from its block entity) and mounted on a Create contraption
 * (from the stack the contraption holds), on both addon paths. The name is a function of the storage's own facts only:
 * wood type, main and accent colour, custom name.
 * <ul>
 *   <li>L, the look name: Sophisticated Storage's own item name for the storage's wood, or for no wood when it is
 *       fully tinted; a storage with no wood type that is not fully tinted reads as Acacia, the type it loads with
 *       (BN D-5).</li>
 *   <li>X, the second name: a fully tinted storage with a wood type also shows its window name, the wooden name
 *       (BN OQ-3b A): {@code Iron Chest (Oak Iron Chest)}. Left out when equal to L (BN D-7).</li>
 *   <li>The custom name alongside, never instead: {@code Oak Iron Chest (renamed "Bank")}
 *       ({@link DisplayNameResolver#formatName}).</li>
 * </ul>
 * Loaded only through the Sophisticated Storage adapter and the Create mounted adapter (both load only when the mod is
 * present). Never calls the mod's pick, menu title, rename or linked-storage code.
 *
 * <p>Fail-safe (review-01 MAJOR-1, MAJOR-2, MINOR-8; lead ruling Q4): every mod call sits in {@code catch (Throwable)}
 * and nothing here throws. A missing class or member ({@link LinkageError}) switches this helper off until the server
 * restarts; any other failure falls back for that one name. The fallback is the block's own name with the renamed part
 * (BN 2.2 step 8), so a row is never lost and never names anything else. Every fallback is counted, with one WARN per
 * kind, in {@link ContainerLabelFallbacks}. A storage that is not a wooden one (no wood facts to read) simply has the
 * block's own name: that is its name, not a fallback. Deliberately not the adapter fail-safe: its OFF and skip mean
 * "nothing recorded", which is false here.</p>
 */
public final class SophisticatedStorageNames {
    static final String OWNER = "Sophisticated Storage";
    static final String SITE = "SophisticatedStorageNames";
    /** No server run: the helper is on. */
    private static final long ON = Long.MIN_VALUE;
    /** The server run ({@link ContainerLabelFallbacks#serverRun()}) this helper was switched off in. */
    private static volatile long offInRun = ON;
    private static final BiFunction<String, WoodType, String> DISPLAY =
            (descriptionId, wood) -> WoodStorageBlockItem.getDisplayName(descriptionId, wood).getString();

    private SophisticatedStorageNames() {
    }

    /** The storage's own facts; {@code wood} is null for a storage with no wood type. */
    record Facts(String descriptionId, WoodType wood, boolean fullyTinted, RenameState rename) {
    }

    /** Mounted: the name of the storage {@code stack} a contraption holds, placed as {@code block}. Never throws. */
    public static String forStack(ItemStack stack, Block block) {
        RenameState rename = RenameState.ofStack(stack);
        Supplier<String> own = () -> DisplayNameResolver.blockDisplayName(block);
        if (switchedOff()) return offFallback(own, rename);
        Facts facts;
        try {
            if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof WoodStorageBlockItem)) {
                return fallback(own, rename);
            }
            facts = new Facts(stack.getItem().getDescriptionId(), WoodStorageBlockItem.getWoodType(stack).orElse(null),
                    fullyTinted(StorageBlockItem.getMainColorFromComponentHolder(stack).isPresent(),
                            StorageBlockItem.getAccentColorFromComponentHolder(stack).isPresent()),
                    rename);
        } catch (Throwable failure) {
            return failed(failure, own, rename);
        }
        return name(facts, DISPLAY, own);
    }

    /** Placed: the name of the storage block entity an open admitted. Never throws. */
    public static String forBlockEntity(StorageBlockEntity storage) {
        RenameState rename = storage == null ? RenameState.NONE : RenameState.ofComponent(() -> storage.getCustomName());
        Supplier<String> own = () -> storage == null ? null
                : DisplayNameResolver.blockDisplayName(storage.getBlockState().getBlock());
        if (switchedOff()) return offFallback(own, rename);
        Facts facts;
        try {
            if (!(storage instanceof WoodStorageBlockEntity wood)) return fallback(own, rename);
            StorageWrapper wrapper = wood.getStorageWrapper();
            facts = new Facts(wood.getBlockState().getBlock().getDescriptionId(), wood.getWoodType().orElse(null),
                    fullyTinted(wrapper.hasMainColor(), wrapper.hasAccentColor()), rename);
        } catch (Throwable failure) {
            return failed(failure, own, rename);
        }
        return name(facts, DISPLAY, own);
    }

    /**
     * The pure rule (unit-tested without the mod): L, X and the rename through the one formatter. {@code display} is
     * the mod's item name for a description id and a wood type (null: no wood); {@code own} the block's own name for
     * the fallback. Never throws.
     */
    static String name(Facts facts, BiFunction<String, WoodType, String> display, Supplier<String> own) {
        RenameState rename = facts == null || facts.rename() == null ? RenameState.NONE : facts.rename();
        if (switchedOff()) return offFallback(own, rename);
        try {
            if (facts == null || facts.descriptionId() == null || display == null) {
                ContainerLabelFallbacks.fellBack(OWNER, SITE, null);
                return fallback(own, rename);
            }
            WoodType lookWood = facts.fullyTinted() ? null : (facts.wood() == null ? WoodType.ACACIA : facts.wood());
            String look = display.apply(facts.descriptionId(), lookWood);
            String second = facts.fullyTinted() && facts.wood() != null
                    ? display.apply(facts.descriptionId(), facts.wood()) : null;
            if (!DisplayNameResolver.usableName(look)) {
                // review-01 MINOR-2: a blank look name falls back to the block's own name ("Container" if that is blank).
                ContainerLabelFallbacks.fellBack(OWNER, SITE, null);
                return fallback(own, rename);
            }
            return DisplayNameResolver.formatName(look, second, rename);
        } catch (Throwable failure) {
            return failed(failure, own, rename);
        }
    }

    /** Fully tinted: both a main and an accent colour (the mod's own test); one of them alone is not. */
    static boolean fullyTinted(boolean mainColor, boolean accentColor) {
        return mainColor && accentColor;
    }

    /** True while switched off in this server run (a later server start turns it on again). */
    static boolean switchedOff() {
        return offInRun == ContainerLabelFallbacks.serverRun();
    }

    /** Test seam: on again without a server start. */
    static void resetForTests() {
        offInRun = ON;
    }

    private static String failed(Throwable failure, Supplier<String> own, RenameState rename) {
        if (failure instanceof LinkageError) {
            offInRun = ContainerLabelFallbacks.serverRun();
            ContainerLabelFallbacks.switchedOff(OWNER, SITE, failure);
        } else {
            ContainerLabelFallbacks.fellBack(OWNER, SITE, failure);
        }
        return fallback(own, rename);
    }

    private static String offFallback(Supplier<String> own, RenameState rename) {
        ContainerLabelFallbacks.switchedOff(OWNER, SITE, null);
        return fallback(own, rename);
    }

    /** The block's own name with the renamed part (BN 2.2 step 8); "Container" when nothing is readable. */
    private static String fallback(Supplier<String> own, RenameState rename) {
        String name;
        try {
            name = own == null ? null : own.get();
        } catch (Throwable unreadable) {
            name = null;
        }
        return DisplayNameResolver.formatName(name, null, rename);
    }
}
