package io.github.betapetey.eventtrail.mixin.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.Contraption;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.create.CreateOpeningScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.simibubi.create.api.contraption.storage.item.MountedItemStorage", remap = false)
public abstract class CreateMountedItemInteractionMixin {
    @WrapMethod(method = "handleInteraction", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$storage(ServerPlayer player, Contraption contraption, StructureBlockInfo info, Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.CREATE, "hook:CreateMountedItemInteractionMixin.storage", original,
                call -> CreateOpeningScope.storage((MountedItemStorage)(Object)this, player, contraption, info, call::call),
                player, contraption, info);
    }
}
