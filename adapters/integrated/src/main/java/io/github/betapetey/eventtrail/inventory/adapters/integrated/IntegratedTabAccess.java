package io.github.betapetey.eventtrail.inventory.adapters.integrated;
import net.minecraft.server.level.ServerPlayer;
import org.cyclops.commoncapabilities.api.ingredient.IngredientComponent;
public interface IntegratedTabAccess {
    IngredientComponent<?, ?> eventtrail$integratedComponent();
    ServerPlayer eventtrail$integratedPlayer();
}
