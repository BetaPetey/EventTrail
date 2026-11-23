package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.events.platform.CommandCreditGuard;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.execution.ExecutionContext;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Consumer;

/**
 * Command credit guard: marks "a command is executing" for the synchronous extent of
 * {@code Commands.executeCommandInContext}, the one method that runs a command queue (command blocks, functions,
 * /schedule, the console and typed commands all reach it; a nested call joins the running context inside it).
 * Presence only: no source, command text or entity is kept.
 */
@Mixin(Commands.class)
abstract class CommandExecutionScopeMixin {
    @WrapMethod(
            method = "executeCommandInContext(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/function/Consumer;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private static void eventtrail$scopeCommandExecution(
            CommandSourceStack source,
            Consumer<ExecutionContext<CommandSourceStack>> action,
            Operation<Void> original) {
        if (!CommandCreditGuard.enter()) {
            original.call(source, action);
            return;
        }
        try {
            original.call(source, action);
        } finally {
            CommandCreditGuard.exit();
        }
    }
}
