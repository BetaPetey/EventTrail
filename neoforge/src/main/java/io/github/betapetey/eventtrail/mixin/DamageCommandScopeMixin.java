package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.CommandCreditGuard;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.DamageCommand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Item 2 F4 option A (KL-175): the vanilla {@code /damage} body runs inside the command credit guard's scope even when
 * a mod runs the command straight through the dispatcher, outside {@code Commands.executeCommandInContext}. Inside the
 * queue runner the scope simply nests. Presence only: no source, target or command text is kept.
 */
@Mixin(DamageCommand.class)
abstract class DamageCommandScopeMixin {
    @WrapMethod(
            method = "damage(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/world/entity/Entity;F"
                    + "Lnet/minecraft/world/damagesource/DamageSource;)I",
            require = 1,
            expect = 1,
            allow = 1)
    private static int eventtrail$scopeDamageCommand(
            CommandSourceStack source,
            Entity target,
            float amount,
            DamageSource damageSource,
            Operation<Integer> original) {
        if (!CommandCreditGuard.enter()) {
            return original.call(source, target, amount, damageSource);
        }
        try {
            return original.call(source, target, amount, damageSource);
        } finally {
            CommandCreditGuard.exit();
        }
    }
}
