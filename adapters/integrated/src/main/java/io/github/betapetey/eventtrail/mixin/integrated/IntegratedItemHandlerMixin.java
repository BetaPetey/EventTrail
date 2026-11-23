package io.github.betapetey.eventtrail.mixin.integrated;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedTerminalsOperations;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.cyclops.commoncapabilities.api.ingredient.storage.IIngredientComponentStorage;
import org.cyclops.integratedterminals.capability.ingredient.IngredientComponentTerminalStorageHandlerItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
@Pseudo
@Mixin(targets = "org.cyclops.integratedterminals.capability.ingredient.IngredientComponentTerminalStorageHandlerItemStack", remap = false)
public abstract class IntegratedItemHandlerMixin {
    @WrapOperation(method = {"extractActiveStackFromPlayerInventory", "extractMaxFromContainerSlot"}, at = @At(value = "INVOKE",
            target = "Lorg/cyclops/commoncapabilities/api/ingredient/storage/IIngredientComponentStorage;insert(Ljava/lang/Object;Z)Ljava/lang/Object;", remap = false),
            remap = false, require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private Object eventtrail$insert(IIngredientComponentStorage<?, ?> storage, Object input, boolean simulate,
            Operation<Object> original, @Local(argsOnly = true) AbstractContainerMenu menu) {
        return HookGuard.wrap(AdapterKey.INTEGRATED_TERMINALS, "hook:IntegratedItemHandlerMixin.insert", original,
                once -> IntegratedTerminalsOperations.channelTransfer((IngredientComponentTerminalStorageHandlerItemStack) (Object) this,
                        storage, menu, input, null, simulate, true, once::call), storage, input, simulate);
    }
    @WrapOperation(method = "insertIntoContainer", at = @At(value = "INVOKE",
            target = "Lorg/cyclops/commoncapabilities/api/ingredient/storage/IIngredientComponentStorage;extract(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;", remap = false),
            remap = false, require = 0, expect = 0)
    @HookSite(require = 2, expect = 2, allow = 2)
    private Object eventtrail$extract(IIngredientComponentStorage<?, ?> storage, Object input, Object match, boolean simulate,
            Operation<Object> original, @Local(argsOnly = true) AbstractContainerMenu menu) {
        return HookGuard.wrap(AdapterKey.INTEGRATED_TERMINALS, "hook:IntegratedItemHandlerMixin.extract", original,
                once -> IntegratedTerminalsOperations.channelTransfer((IngredientComponentTerminalStorageHandlerItemStack) (Object) this,
                        storage, menu, input, match, simulate, false, once::call), storage, input, match, simulate);
    }
}
