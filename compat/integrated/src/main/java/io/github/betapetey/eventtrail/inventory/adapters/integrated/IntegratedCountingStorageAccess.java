package io.github.betapetey.eventtrail.inventory.adapters.integrated;

import org.cyclops.commoncapabilities.api.ingredient.storage.IIngredientComponentStorage;

/** Read-only access to the exact current family's native one-layer counting decorator. */
public interface IntegratedCountingStorageAccess {
    IIngredientComponentStorage<?, ?> eventtrail$integratedDelegate();
    boolean eventtrail$integratedRead();
    boolean eventtrail$integratedInsert();
    boolean eventtrail$integratedExtract();
}
