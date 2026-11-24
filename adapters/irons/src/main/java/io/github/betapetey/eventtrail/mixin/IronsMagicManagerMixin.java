package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.spells.IronsCastAuditBridge;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MagicManager.class, remap = false)
abstract class IronsMagicManagerMixin {
    @WrapOperation(method = "lambda$tick$0", at = @At(value = "INVOKE",
            target = "Lio/redspace/ironsspellbooks/api/spells/AbstractSpell;castSpell(Lnet/minecraft/world/level/Level;ILnet/minecraft/server/level/ServerPlayer;Lio/redspace/ironsspellbooks/api/spells/CastSource;Z)V"),
            require = 0, expect = 0)
    @HookSite(require = 3, expect = 3, allow = 3)
    private void eventtrail$execute(AbstractSpell spell, Level level, int spellLevel, ServerPlayer player,
            CastSource source, boolean cooldown, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.IRONS, "hook:IronsMagicManagerMixin.execute", original,
                once -> IronsCastAuditBridge.execute(spell, level, spellLevel, player, source, once::call),
                spell, level, spellLevel, player, source, cooldown);
    }
}
