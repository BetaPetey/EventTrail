package io.github.betapetey.eventtrail.mixin.ae2inplace;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.parts.Ae2InPlaceMoves;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;

/**
 * Parts readers design-03 5.2 (decision 21): the upgrade cards a memory-card paste moves between the player and
 * the machine. {@code MemoryCardItem.importGenericSettings} is the single meeting point of every paste route
 * (block or part, same type or {@code importGenericSettingsAndNotify}); AE2's call runs exactly once through
 * {@link HookGuard}, and the rows leave through the parts bridge's open use action
 * ({@link Ae2InPlaceMoves#memoryCardPaste}).
 */
@Pseudo
@Mixin(targets = "appeng.items.tools.MemoryCardItem", remap = false)
public abstract class Ae2MemoryCardPasteMixin {
    @Unique
    private static final String SITE = "hook:Ae2MemoryCardPasteMixin.memoryCardPaste";

    @WrapMethod(
            method = "importGenericSettings(Ljava/lang/Object;Lnet/minecraft/core/component/DataComponentMap;"
                    + "Lnet/minecraft/world/entity/player/Player;)Ljava/util/Set;",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static Set<?> eventtrail$memoryCardPaste(
            Object importTo, DataComponentMap input, Player player, Operation<Set<?>> original) {
        return HookGuard.wrap(AdapterKey.PARTS_AE2_INPLACE, SITE, original,
                once -> Ae2InPlaceMoves.memoryCardPaste(AdapterKey.PARTS_AE2_INPLACE, SITE, once, importTo, player),
                importTo, input, player);
    }
}
