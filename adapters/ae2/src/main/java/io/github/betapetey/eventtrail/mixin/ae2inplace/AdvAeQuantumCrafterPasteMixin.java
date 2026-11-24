package io.github.betapetey.eventtrail.mixin.ae2inplace;

import appeng.api.inventories.InternalInventory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.parts.Ae2InPlaceMoves;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Parts readers design-03 5.3: the patterns of a memory-card paste on an AdvancedAE Quantum Crafter (its private
 * {@code importPatterns}, called from its importSettings in MEMORY_CARD mode; the same clear rule as AE2's). A
 * class of its own beside {@link AdvAePatternPasteMixin}, because the pre-apply check holds every shadow and every
 * site count on each target. AdvancedAE's call runs exactly once through {@link HookGuard}.
 */
@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.entities.QuantumCrafterEntity", remap = false)
public abstract class AdvAeQuantumCrafterPasteMixin {
    @Unique
    private static final String SITE = "hook:AdvAeQuantumCrafterPasteMixin.quantumPatternPaste";

    @Shadow
    public abstract InternalInventory getPatternInventory();

    @WrapMethod(
            method = "importPatterns(Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/core/component/DataComponentMap;)V",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$quantumPatternPaste(Player player, DataComponentMap input, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.PARTS_ADVANCED_AE_INPLACE, SITE, original, once -> {
            BlockEntity self = (BlockEntity) (Object) this;
            Ae2InPlaceMoves.patternPaste(AdapterKey.PARTS_ADVANCED_AE_INPLACE, SITE, once, self, self,
                    getPatternInventory(), player, false);
        }, player, input);
    }
}
