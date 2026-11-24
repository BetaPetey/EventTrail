package io.github.betapetey.eventtrail.mixin.graves;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.inventory.graves.SpillTags;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Graves design r6, 1.6.4: the {@code addFreshEntity} call inside vanilla's {@code Containers.dropItemStack}, which
 * splits one stack into several item entities. With a pending spill set on this thread ({@link SpillTags#atSpill}),
 * each entity is labelled before it is added and counted only if the add returns true; otherwise this is one
 * thread-local read. The add runs exactly once and its result is returned unchanged.
 */
@Mixin(Containers.class)
abstract class ContainersDropItemStackSpillMixin {
    @WrapOperation(
            method = "dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V",
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
