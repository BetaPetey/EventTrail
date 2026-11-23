package io.github.betapetey.eventtrail.mixin.integrated;
import io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedTabAccess;
import net.minecraft.server.level.ServerPlayer;
import org.cyclops.commoncapabilities.api.ingredient.IngredientComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
@Pseudo
@Mixin(targets = "org.cyclops.integratedterminals.core.terminalstorage.TerminalStorageTabIngredientComponentServer", remap = false)
public interface IntegratedTabAccessor extends IntegratedTabAccess {
    @Override @Accessor("ingredientComponent") IngredientComponent<?, ?> eventtrail$integratedComponent();
    @Override @Accessor("player") ServerPlayer eventtrail$integratedPlayer();
}
