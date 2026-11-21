package io.github.betapetey.eventtrail.mixin.integrated;
import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedComponentStorageAccess;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
@Pseudo
@Mixin(targets = "org.cyclops.commoncapabilities.ingredient.storage.IngredientComponentStorageWrapperHandlerItemStack$ComponentStorageWrapper", remap = false)
public interface IntegratedComponentStorageAccessor extends IntegratedComponentStorageAccess {
    @Override @Accessor("storage") IItemHandler eventtrail$integratedItemHandler();
}
