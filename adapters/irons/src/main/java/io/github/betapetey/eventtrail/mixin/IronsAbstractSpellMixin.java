package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AbstractSpell.class, remap = false)
abstract class IronsAbstractSpellMixin {
    @WrapMethod(method = "castSpell", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$castBody(Level level, int spellLevel, ServerPlayer player, CastSource source,
            boolean cooldown, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.IRONS, "hook:IronsAbstractSpellMixin.castBody", original,
                once -> IronsCastAuditBridge.castBody((AbstractSpell) (Object) this, player, once::call),
                level, spellLevel, player, source, cooldown);
    }
    @WrapMethod(method = "attemptInitiateCast", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$attempt(ItemStack stack, int spellLevel, Level level, Player player,
            CastSource source, boolean cooldown, String slot, Operation<Boolean> original) {
        // Two exclusive paths in one guarded body; each calls the mod once (work map section 1 item 3).
        return HookGuard.wrap(AdapterKey.IRONS, "hook:IronsAbstractSpellMixin.attempt", original,
                once -> {
                    if (!(player instanceof ServerPlayer serverPlayer)) {
                        once.call();
                        return;
                    }
                    IronsCastAuditBridge.attempt((AbstractSpell) (Object) this, serverPlayer,
                            IronsCastAuditBridge.data(serverPlayer), spellLevel, source, slot, once::call);
                }, stack, spellLevel, level, player, source, cooldown, slot);
    }
    @WrapOperation(method = "attemptInitiateCast", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/magic/MagicData;initiateCast(Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;IILio/redspace/ironsspellbooks/api/spells/CastSource;Ljava/lang/String;)V"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$accepted(MagicData data, AbstractSpell spell, int level, int duration,
            CastSource source, String slot, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.IRONS, "hook:IronsAbstractSpellMixin.accepted", original,
                once -> {
                    once.call();
                    IronsCastAuditBridge.accepted(data, spell, level, duration, source, slot);
                }, data, spell, level, duration, source, slot);
    }
    @WrapOperation(method = "castSpell", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;onCast(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V"),
            require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$callback(AbstractSpell spell, Level world, int spellLevel, LivingEntity player,
            CastSource source, MagicData data, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.IRONS, "hook:IronsAbstractSpellMixin.callback", original,
                once -> IronsCastAuditBridge.callback(spell, world, spellLevel, player, source, data, once::call),
                spell, world, spellLevel, player, source, data);
    }
}
