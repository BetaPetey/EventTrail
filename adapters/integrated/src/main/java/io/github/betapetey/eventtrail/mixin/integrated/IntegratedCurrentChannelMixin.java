package io.github.betapetey.eventtrail.mixin.integrated;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedTerminalsOperations;
import org.cyclops.commoncapabilities.api.ingredient.storage.IIngredientComponentStorage;
import org.cyclops.integrateddynamics.api.part.PartPos;
import org.cyclops.integrateddynamics.core.network.IngredientChannelAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "org.cyclops.integrateddynamics.core.network.IngredientChannelAdapter", remap = false)
public abstract class IntegratedCurrentChannelMixin {
    @WrapOperation(method = "insert(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;", at = @At(value = "INVOKE",
            target = "Lorg/cyclops/commoncapabilities/api/ingredient/storage/IIngredientComponentStorage;insert(Ljava/lang/Object;Z)Ljava/lang/Object;", remap = false),
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private Object eventtrail$positionedInsert(IIngredientComponentStorage<?, ?> storage, Object input, boolean simulate,
            Operation<Object> original, @Local PartPos position) {
        return HookGuard.wrap(AdapterKey.INTEGRATED_TERMINALS, "hook:IntegratedCurrentChannelMixin.positionedInsert", original,
                once -> IntegratedTerminalsOperations.positionedTransfer((IngredientChannelAdapter<?, ?>) (Object) this,
                        storage, position, input, null, simulate, true, once::call), storage, input, simulate);
    }
    @WrapOperation(method = "extract(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;", at = @At(value = "INVOKE",
            target = "Lorg/cyclops/commoncapabilities/api/ingredient/storage/IIngredientComponentStorage;extract(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;", remap = false),
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private Object eventtrail$positionedSimulation(IIngredientComponentStorage<?, ?> storage, Object input, Object match, boolean simulate,
            Operation<Object> original, @Local PartPos position) {
        return HookGuard.wrap(AdapterKey.INTEGRATED_TERMINALS, "hook:IntegratedCurrentChannelMixin.positionedSimulation", original,
                once -> IntegratedTerminalsOperations.positionedTransfer((IngredientChannelAdapter<?, ?>) (Object) this,
                        storage, position, input, match, simulate, false, once::call), storage, input, match, simulate);
    }
    @WrapOperation(method = "finalizeExtraction", at = @At(value = "INVOKE",
            target = "Lorg/cyclops/commoncapabilities/api/ingredient/storage/IIngredientComponentStorage;extract(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;", remap = false),
            remap = false, require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private Object eventtrail$positionedExtraction(IIngredientComponentStorage<?, ?> storage, Object input, Object match, boolean simulate,
            Operation<Object> original, @Local PartPos position) {
        return HookGuard.wrap(AdapterKey.INTEGRATED_TERMINALS, "hook:IntegratedCurrentChannelMixin.positionedExtraction", original,
                once -> IntegratedTerminalsOperations.positionedTransfer((IngredientChannelAdapter<?, ?>) (Object) this,
                        storage, position, input, match, simulate, false, once::call), storage, input, match, simulate);
    }
}
