package io.github.betapetey.eventtrail.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Typed access to the two containers that form a vanilla compound inventory. */
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {
    @Accessor("container1")
    Container eventtrail$firstContainer();

    @Accessor("container2")
    Container eventtrail$secondContainer();
}
