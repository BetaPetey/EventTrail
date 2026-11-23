package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.RetaliationScope;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentTarget;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Kill credit B signal 1 (design-05c 2.2): opens a VICTIM frame for the synchronous extent of one enchantment's victim
 * pass of {@code Enchantment#doPostAttack} (the instance method that runs one enchantment's post-attack effects), so a
 * death inside it whose causing entity is the frame's owner is recognised as hit-back, whatever its damage type. The
 * frame holds the owner, the enchantment's id and its description text, in memory only. Other passes open nothing.
 */
@Mixin(Enchantment.class)
abstract class RetaliationScopeMixin {
    @WrapMethod(
            method = "doPostAttack(Lnet/minecraft/server/level/ServerLevel;ILnet/minecraft/world/item/enchantment/EnchantedItemInUse;Lnet/minecraft/world/item/enchantment/EnchantmentTarget;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$openVictimFrame(
            ServerLevel level,
            int enchantmentLevel,
            EnchantedItemInUse item,
            EnchantmentTarget target,
            Entity entity,
            DamageSource damageSource,
            Operation<Void> original) {
        RetaliationScope.Entry frame = target == EnchantmentTarget.VICTIM
                ? RetaliationScope.openVictimFrame(level, (Enchantment) (Object) this, item)
                : null;
        if (frame == null) {
            original.call(level, enchantmentLevel, item, target, entity, damageSource);
            return;
        }
        try {
            original.call(level, enchantmentLevel, item, target, entity, damageSource);
        } finally {
            frame.close();
        }
    }
}
