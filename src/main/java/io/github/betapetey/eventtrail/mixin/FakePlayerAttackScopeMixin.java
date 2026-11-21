package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.FakePlayerAttackGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Fix 4a: marks "a FakePlayer attack is in progress" for the synchronous extent of a FakePlayer's own vanilla
 * {@code Player.attack}. Presence only: no attacker, target or source is kept. A real player's attack opens no scope.
 */
@Mixin(Player.class)
abstract class FakePlayerAttackScopeMixin {
    @WrapMethod(
            method = "attack(Lnet/minecraft/world/entity/Entity;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$scopeFakePlayerAttack(Entity target, Operation<Void> original) {
        if (!FakePlayerAttackGuard.enter((Player) (Object) this)) {
            original.call(target);
            return;
        }
        try {
            original.call(target);
        } finally {
            FakePlayerAttackGuard.exit();
        }
    }
}
