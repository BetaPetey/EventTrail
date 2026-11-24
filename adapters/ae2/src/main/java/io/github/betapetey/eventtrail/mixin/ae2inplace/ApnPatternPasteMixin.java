package io.github.betapetey.eventtrail.mixin.ae2inplace;

import appeng.util.inv.AppEngInternalInventory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
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
 * Parts readers design-03 5.3: the patterns of a memory-card paste on an Applied Pneumatics Amadron process
 * station (its private {@code importPatternsFromMemoryCard}; {@code clearPatternInventoryForMemoryCard} returns
 * every old stack as Blank Patterns). Applied Pneumatics' call runs exactly once through {@link HookGuard}.
 */
@Pseudo
@Mixin(targets = "com.wintercogs.appliedpneumatics.common.blocks.entitis.MEAmadronProcessStationBlockEntity",
        remap = false)
public abstract class ApnPatternPasteMixin {
    @Unique
    private static final String SITE = "hook:ApnPatternPasteMixin.amadronPatternPaste";

    @Shadow
    @Final
    private AppEngInternalInventory patternInventory;

    @WrapMethod(
            method = "importPatternsFromMemoryCard(Lnet/minecraft/core/component/DataComponentMap;"
                    + "Lnet/minecraft/world/entity/player/Player;)V",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$amadronPatternPaste(DataComponentMap input, Player player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.PARTS_APPLIED_PNEUMATICS_INPLACE, SITE, original, once -> {
            BlockEntity self = (BlockEntity) (Object) this;
            Ae2InPlaceMoves.patternPaste(AdapterKey.PARTS_APPLIED_PNEUMATICS_INPLACE, SITE, once, self, self,
                    patternInventory, player, true);
        }, input, player);
    }
}
