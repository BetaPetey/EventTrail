package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.refinedmods.refinedstorage.api.network.node.grid.GridExtractMode;
import com.refinedmods.refinedstorage.api.network.node.grid.GridInsertMode;
import com.refinedmods.refinedstorage.common.api.grid.GridScrollMode;
import com.refinedmods.refinedstorage.common.api.support.resource.PlatformResourceKey;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.refinedstorage.RefinedStorageTransactionBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Brackets Refined Storage player grid actions. The grid action runs exactly
 * once through {@link HookGuard}, whatever EventTrail's bracketing does. Each
 * bracket catches every throwable, including an undeclared checked one (a
 * sneaky throw), so no failure of the grid action leaves its scope or
 * tracker token open.
 */
@Pseudo
@Mixin(targets = "com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu", remap = false)
abstract class RefinedStorageGridMenuMixin {
    @Shadow
    @Final
    protected Inventory playerInventory;

    @WrapMethod(
            method = "onInsert(Lcom/refinedmods/refinedstorage/api/network/node/grid/GridInsertMode;Z)Z",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$observeInsert(
            GridInsertMode mode,
            boolean tryAlternatives,
            Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.REFINED_STORAGE, "hook:RefinedStorageGridMenuMixin.observeInsert", original,
                once -> {
                    RefinedStorageTransactionBridge.Scope scope = eventtrail$beginAuthoritative();
                    try {
                        once.call();
                        RefinedStorageTransactionBridge.complete(scope);
                    } catch (Throwable failure) {
                        RefinedStorageTransactionBridge.fail(scope);
                        throw failure;
                    }
                }, mode, tryAlternatives);
    }

    @WrapMethod(
            method = "onExtract("
                    + "Lcom/refinedmods/refinedstorage/common/api/support/resource/PlatformResourceKey;"
                    + "Lcom/refinedmods/refinedstorage/api/network/node/grid/GridExtractMode;Z)Z",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$observeExtract(
            PlatformResourceKey resource,
            GridExtractMode mode,
            boolean tryAlternatives,
            Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.REFINED_STORAGE, "hook:RefinedStorageGridMenuMixin.observeExtract", original,
                once -> {
                    RefinedStorageTransactionBridge.Scope scope = eventtrail$beginAuthoritative();
                    try {
                        once.call();
                        RefinedStorageTransactionBridge.complete(scope);
                    } catch (Throwable failure) {
                        RefinedStorageTransactionBridge.fail(scope);
                        throw failure;
                    }
                }, resource, mode, tryAlternatives);
    }

    @WrapMethod(
            method = "onScroll("
                    + "Lcom/refinedmods/refinedstorage/common/api/support/resource/PlatformResourceKey;"
                    + "Lcom/refinedmods/refinedstorage/common/api/grid/GridScrollMode;I)Z",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private boolean eventtrail$observeScroll(
            PlatformResourceKey resource,
            GridScrollMode mode,
            int amount,
            Operation<Boolean> original) {
        return HookGuard.wrap(AdapterKey.REFINED_STORAGE, "hook:RefinedStorageGridMenuMixin.observeScroll", original,
                once -> {
                    RefinedStorageTransactionBridge.Scope scope = eventtrail$beginAuthoritative();
                    try {
                        once.call();
                        RefinedStorageTransactionBridge.complete(scope);
                    } catch (Throwable failure) {
                        RefinedStorageTransactionBridge.fail(scope);
                        throw failure;
                    }
                }, resource, mode, amount);
    }

    @WrapMethod(
            method = "quickMoveStack(Lnet/minecraft/world/entity/player/Player;I)"
                    + "Lnet/minecraft/world/item/ItemStack;",
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private ItemStack eventtrail$bindShiftClickActor(
            Player actor,
            int slot,
            Operation<ItemStack> original) {
        return HookGuard.wrap(AdapterKey.REFINED_STORAGE, "hook:RefinedStorageGridMenuMixin.bindShiftClickActor", original,
                once -> {
                    if (!(actor instanceof ServerPlayer player)) {
                        once.call();
                        return;
                    }
                    RefinedStorageTransactionBridge.Scope scope = RefinedStorageTransactionBridge.bindExisting(
                            player, (AbstractContainerMenu) (Object) this);
                    try {
                        once.call();
                        RefinedStorageTransactionBridge.complete(scope);
                    } catch (Throwable failure) {
                        RefinedStorageTransactionBridge.fail(scope);
                        throw failure;
                    }
                }, actor, slot);
    }

    private RefinedStorageTransactionBridge.Scope eventtrail$beginAuthoritative() {
        Player actor = playerInventory.player;
        if (!(actor instanceof ServerPlayer player)) return null;
        return RefinedStorageTransactionBridge.beginAuthoritative(
                player, (AbstractContainerMenu) (Object) this);
    }
}
