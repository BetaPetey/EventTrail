package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.PlayerAttackFrames;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Item 2 F4 option A (KL-3 4a): marks "this real player's own attack is running" for the synchronous extent of
 * {@code Player.attack}, so a source naming another real player inside it credits nobody. A separate class from
 * FakePlayerAttackScopeMixin, so that hook's pinned shape is unchanged. The frame holds the attacker only during the
 * call; nothing is stored on any entity.
 */
@Mixin(Player.class)
abstract class PlayerAttackFrameMixin {
    @WrapMethod(
            method = "attack(Lnet/minecraft/world/entity/Entity;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$frameOwnAttack(Entity target, Operation<Void> original) {
        PlayerAttackFrames.Frame frame = PlayerAttackFrames.frame((Player) (Object) this);
        try {
            frame.open();
            original.call(target);
        } finally {
            frame.close();
        }
    }
}
