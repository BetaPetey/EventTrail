package io.github.betapetey.eventtrail.mixin.ae2inplace;

import appeng.api.inventories.InternalInventory;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.parts.Ae2InPlaceMoves;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Parts readers design-03 5.3: the patterns of a memory-card paste on an AE2 pattern provider (block or part; the
 * ExtendedAE, MEGA Cells and ExpandedAE providers use this same logic). Rows are the Blank Patterns the player got
 * back or gave, net ({@link Ae2InPlaceMoves#patternPaste}); AE2's call runs exactly once through {@link HookGuard}.
 */
@Pseudo
@Mixin(targets = "appeng.helpers.patternprovider.PatternProviderLogic", remap = false)
public abstract class Ae2PatternPasteMixin {
    @Unique
    private static final String SITE = "hook:Ae2PatternPasteMixin.patternPaste";

    @Shadow
    @Final
    private PatternProviderLogicHost host;

    @Shadow
    public abstract InternalInventory getPatternInv();

    @WrapMethod(
            method = "importSettings(Lnet/minecraft/core/component/DataComponentMap;"
                    + "Lnet/minecraft/world/entity/player/Player;)V",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$patternPaste(DataComponentMap input, Player player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.PARTS_AE2_INPLACE, SITE, original, once -> {
            BlockEntity blockEntity;
            InternalInventory patterns;
            try {
                blockEntity = host == null ? null : host.getBlockEntity();
                patterns = getPatternInv();
            } catch (Throwable failure) {
                // Review-02 NIT-3: the guard reports a failed read; the player's open action writes no family row.
                Ae2InPlaceMoves.failedBeforeRun(player);
                throw failure;
            }
            Ae2InPlaceMoves.patternPaste(AdapterKey.PARTS_AE2_INPLACE, SITE, once, host, blockEntity,
                    patterns, player, false);
        }, input, player);
    }
}
