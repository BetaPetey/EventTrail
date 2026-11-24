package io.github.betapetey.eventtrail.mixin.ae2inplace;

import appeng.api.inventories.InternalInventory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.MissingAdapterMemberException;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.parts.Ae2InPlaceMoves;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Parts readers design-03 5.3: the patterns of a memory-card paste on an AdvancedAE pattern provider (block or
 * part). {@code AdvPatternProviderLogic} is not a {@code PatternProviderLogic} subclass, so it has its own hook,
 * under its own key: a pack without AdvancedAE leaves it out quietly. The host's type is AdvancedAE's and is not on
 * EventTrail's build path, so the private {@code host} field and its {@code getBlockEntity()} are read through the
 * target's own lookup; a missing member is reported as missing (this key OFF until restart). AdvancedAE's call
 * runs exactly once through {@link HookGuard}.
 */
@Pseudo
@Mixin(targets = "net.pedroksl.advanced_ae.common.logic.AdvPatternProviderLogic", remap = false)
public abstract class AdvAePatternPasteMixin {
    @Unique
    private static final String SITE = "hook:AdvAePatternPasteMixin.advPatternPaste";
    @Unique
    private static final String OWNER = "net.pedroksl.advanced_ae.common.logic.AdvPatternProviderLogic";

    @Shadow
    public abstract InternalInventory getPatternInv();

    @WrapMethod(
            method = "importSettings(Lnet/minecraft/core/component/DataComponentMap;"
                    + "Lnet/minecraft/world/entity/player/Player;)V",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$advPatternPaste(DataComponentMap input, Player player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.PARTS_ADVANCED_AE_INPLACE, SITE, original, once -> {
            Object host;
            BlockEntity blockEntity;
            InternalInventory patterns;
            try {
                host = eventtrail$host();
                blockEntity = eventtrail$blockEntity(host);
                patterns = getPatternInv();
            } catch (Throwable failure) {
                // Review-02 NIT-3: the guard reports a failed lookup; the player's open action writes no family row.
                Ae2InPlaceMoves.failedBeforeRun(player);
                throw failure;
            }
            Ae2InPlaceMoves.patternPaste(AdapterKey.PARTS_ADVANCED_AE_INPLACE, SITE, once, host, blockEntity,
                    patterns, player, false);
        }, input, player);
    }

    /** The private {@code host} field, through this class's own lookup (the target's, once applied). */
    @Unique
    private Object eventtrail$host() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Field field = lookup.lookupClass().getDeclaredField("host");
            return lookup.unreflectGetter(field).invoke((Object) this);
        } catch (Throwable failure) {
            throw MissingAdapterMemberException.missing(OWNER, "host", null, failure);
        }
    }

    /** {@code AdvPatternProviderLogicHost.getBlockEntity()} on the host, or null for a null host. */
    @Unique
    private static BlockEntity eventtrail$blockEntity(Object host) {
        if (host == null) return null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> hostType = lookup.lookupClass().getDeclaredField("host").getType();
            Object blockEntity = lookup.findVirtual(hostType, "getBlockEntity", MethodType.methodType(BlockEntity.class))
                    .invoke(host);
            return (BlockEntity) blockEntity;
        } catch (Throwable failure) {
            throw MissingAdapterMemberException.missing(OWNER + "Host", "getBlockEntity",
                    "()Lnet/minecraft/world/level/block/entity/BlockEntity;", failure);
        }
    }
}
