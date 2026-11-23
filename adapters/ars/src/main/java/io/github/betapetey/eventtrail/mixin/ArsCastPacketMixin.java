package io.github.betapetey.eventtrail.mixin;

import com.hollingsworth.arsnouveau.api.spell.AbstractCaster;
import com.hollingsworth.arsnouveau.api.spell.Spell;
import com.hollingsworth.arsnouveau.common.network.AbstractPacket;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.ArsCastAuditBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.hollingsworth.arsnouveau.common.network.PacketCastSpell", remap = false)
abstract class ArsCastPacketMixin {
    @WrapOperation(method = "onServerReceived", at = @At(value = "INVOKE", target = "Lcom/hollingsworth/arsnouveau/api/spell/AbstractCaster;castSpell(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/network/chat/Component;Lcom/hollingsworth/arsnouveau/api/spell/Spell;)Lnet/minecraft/world/InteractionResultHolder;"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private InteractionResultHolder<ItemStack> eventtrail$selectedRecipe(AbstractCaster<?> caster, Level level, LivingEntity player,
            InteractionHand hand, Component message, Spell spell, Operation<InteractionResultHolder<ItemStack>> original) {
        return HookGuard.wrap(AdapterKey.ARS_CAST, "hook:ArsCastPacketMixin.selectedRecipe", original,
                once -> ArsCastAuditBridge.input((AbstractPacket) (Object) this, caster, level, player, hand, spell, once::call),
                caster, level, player, hand, message, spell);
    }
}
