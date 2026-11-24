package io.github.betapetey.eventtrail.mixin.graves;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.inventory.graves.SpillTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Graves design r6, 1.6.4: the {@code addFreshEntity} call inside NeoForge's
 * {@code ItemHandlerHelper.giveItemToPlayer(Player, ItemStack, int)}, which inserts what fits and spawns the
 * remainder. With a pending spill set on this thread ({@link SpillTags#atSpill}), the remainder's entity is labelled
 * before it is added and counted only if the add returns true; the inserted part stays in the inventory and is counted
 * once, in the take's {@code net}. The add runs exactly once and its result is returned unchanged.
 */
@Mixin(value = ItemHandlerHelper.class, remap = false)
abstract class GiveItemToPlayerSpillMixin {
    @WrapOperation(
            method = "giveItemToPlayer(Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/item/ItemStack;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;addFreshEntity("
                            + "Lnet/minecraft/world/entity/Entity;)Z"),
            require = 1,
            expect = 1,
            allow = 1)
    private static boolean eventtrail$tagGraveSpill(Level level, Entity entity, Operation<Boolean> original) {
        return SpillTags.addFreshEntity(level, entity, () -> original.call(level, entity));
    }
}
