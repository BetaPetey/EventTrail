package io.github.betapetey.eventtrail.mixin.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateEnderMountedAnchor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The mounted storage interaction call site (Part 1 SCM-ENDER): {@code handlePlayerStorageInteraction} is the only
 * caller of {@code MountedItemStorage#handleInteraction} (create-1.21.1-6.0.10, offset 96). A storage that overrides
 * handleInteraction, such as createenderstorage's Ender chest, is not reached by the storage scope's WrapMethod on the
 * base body, so its opening is proven here, for the call's duration only. The call runs exactly once and its result
 * is returned unchanged.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.MountedStorageManager", remap = false)
public abstract class CreateStorageCallSiteMixin {
    @WrapOperation(method = "handlePlayerStorageInteraction", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/api/contraption/storage/item/MountedItemStorage;handleInteraction(Lnet/minecraft/server/level/ServerPlayer;Lcom/simibubi/create/content/contraptions/Contraption;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;)Z"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$callSite(MountedItemStorage storage, ServerPlayer player, Contraption contraption,
            StructureBlockInfo info, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.CREATE, "hook:CreateStorageCallSiteMixin.callSite", original,
                call -> CreateEnderMountedAnchor.callSite((MountedStorageManager)(Object)this, storage, player, contraption,
                        info, call::call),
                storage, player, contraption, info);
    }
}
