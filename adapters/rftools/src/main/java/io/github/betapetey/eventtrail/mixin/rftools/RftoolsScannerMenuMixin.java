package io.github.betapetey.eventtrail.mixin.rftools;

import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.rftools.RftoolsMenuCarrier;
import io.github.betapetey.eventtrail.inventory.adapters.rftools.RftoolsScannerBinding;
import io.github.betapetey.eventtrail.inventory.adapters.rftools.RftoolsScannerOperations;
import mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerContainer;
import mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerContainer", remap = false)
public abstract class RftoolsScannerMenuMixin implements RftoolsMenuCarrier {
    @Unique private RftoolsScannerBinding eventtrail$binding;
    public RftoolsScannerBinding eventtrail$rftoolsBinding() { return eventtrail$binding; }
    public void eventtrail$rftoolsBinding(RftoolsScannerBinding binding) { if (eventtrail$binding == null) eventtrail$binding = binding; }
    @Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$bind(MenuType<?> type, int id, BlockPos pos, StorageScannerTileEntity scanner, Player player, CallbackInfo ci) {
        HookGuard.observe(AdapterKey.RFTOOLS_STORAGE, "hook:RftoolsScannerMenuMixin.bind",
                () -> RftoolsScannerOperations.bind((StorageScannerContainer)(Object)this, pos, scanner, player));
    }
}
