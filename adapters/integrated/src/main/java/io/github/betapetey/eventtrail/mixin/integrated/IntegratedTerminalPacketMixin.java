package io.github.betapetey.eventtrail.mixin.integrated;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedTerminalsOperations;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.cyclops.integratedterminals.api.terminalstorage.TerminalClickType;
import org.cyclops.integratedterminals.core.terminalstorage.TerminalStorageTabIngredientComponentServer;
import org.cyclops.integratedterminals.network.packet.TerminalStorageIngredientSlotClickPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
@Pseudo
@Mixin(targets = "org.cyclops.integratedterminals.network.packet.TerminalStorageIngredientSlotClickPacket", remap = false)
public abstract class IntegratedTerminalPacketMixin {
    @WrapOperation(method = "actionServer", at = @At(value = "INVOKE", target =
            "Lorg/cyclops/integratedterminals/core/terminalstorage/TerminalStorageTabIngredientComponentServer;handleStorageSlotClick(Lnet/minecraft/world/inventory/AbstractContainerMenu;Lnet/minecraft/server/level/ServerPlayer;Lorg/cyclops/integratedterminals/api/terminalstorage/TerminalClickType;ILjava/lang/Object;IJLjava/lang/Object;Z)V", remap = false),
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$packet(TerminalStorageTabIngredientComponentServer<?, ?> tab, AbstractContainerMenu menu,
            ServerPlayer player, TerminalClickType click, int channel, Object hovering, int slot, long quantity,
            Object active, boolean selection, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.INTEGRATED_TERMINALS, "hook:IntegratedTerminalPacketMixin.packet", original,
                once -> IntegratedTerminalsOperations.packet((TerminalStorageIngredientSlotClickPacket<?>) (Object) this,
                        tab, menu, player, click, channel, slot, quantity, once::call),
                tab, menu, player, click, channel, hovering, slot, quantity, active, selection);
    }
}
