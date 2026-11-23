package io.github.betapetey.eventtrail.mixin.integrated;

import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedCountingStorageAccess;
import org.cyclops.commoncapabilities.api.ingredient.storage.IIngredientComponentStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "org.cyclops.cyclopscore.ingredient.storage.IngredientComponentStorageWrapped", remap = false)
public interface IntegratedCountingStorageAccessor extends IntegratedCountingStorageAccess {
    @Override @Accessor("ingredientComponentStorage") IIngredientComponentStorage<?, ?> eventtrail$integratedDelegate();
    @Override @Accessor("read") boolean eventtrail$integratedRead();
    @Override @Accessor("insert") boolean eventtrail$integratedInsert();
    @Override @Accessor("extract") boolean eventtrail$integratedExtract();
}
