package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.ExtractableStorage;
import com.refinedmods.refinedstorage.api.storage.InsertableStorage;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.refinedstorage.RefinedStorageTransactionBridge;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Captures the positive quantity returned by RS's committed transfer. The
 * transfer runs exactly once through {@link HookGuard}, whatever EventTrail's
 * recording does.
 */
@Pseudo
@Mixin(
        targets = "com.refinedmods.refinedstorage.api.network.impl.node.grid.GridOperationsImpl",
        remap = false)
abstract class RefinedStorageGridOperationsMixin {
    @Unique
    private static final String TRANSFER =
            "Lcom/refinedmods/refinedstorage/api/storage/TransferHelper;transfer("
                    + "Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;J"
                    + "Lcom/refinedmods/refinedstorage/api/storage/Actor;"
                    + "Lcom/refinedmods/refinedstorage/api/storage/ExtractableStorage;"
                    + "Lcom/refinedmods/refinedstorage/api/storage/InsertableStorage;"
                    + "Lcom/refinedmods/refinedstorage/api/storage/InsertableStorage;)J";

    @WrapOperation(
            method = "insert("
                    + "Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;"
                    + "Lcom/refinedmods/refinedstorage/api/network/node/grid/GridInsertMode;"
                    + "Lcom/refinedmods/refinedstorage/api/storage/ExtractableStorage;)Z",
            at = @At(value = "INVOKE", target = TRANSFER, remap = false),
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private long eventtrail$recordCommittedInsert(
            ResourceKey resource,
            long requested,
            Actor actor,
            ExtractableStorage source,
            InsertableStorage destination,
            InsertableStorage fallback,
            Operation<Long> original) {
        return HookGuard.wrap(AdapterKey.REFINED_STORAGE, "hook:RefinedStorageGridOperationsMixin.recordCommittedInsert", original,
                once -> {
                    long committed = once.call();
                    RefinedStorageTransactionBridge.recordCommittedTransfer(
                            resource, actor, committed, TransactionDirection.DEPOSIT);
                }, resource, requested, actor, source, destination, fallback);
    }

    @WrapOperation(
            method = "extract("
                    + "Lcom/refinedmods/refinedstorage/api/resource/ResourceKey;"
                    + "Lcom/refinedmods/refinedstorage/api/network/node/grid/GridExtractMode;"
                    + "Lcom/refinedmods/refinedstorage/api/storage/InsertableStorage;)Z",
            at = @At(value = "INVOKE", target = TRANSFER, remap = false),
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private long eventtrail$recordCommittedExtraction(
            ResourceKey resource,
            long requested,
            Actor actor,
            ExtractableStorage source,
            InsertableStorage destination,
            InsertableStorage fallback,
            Operation<Long> original) {
        return HookGuard.wrap(AdapterKey.REFINED_STORAGE, "hook:RefinedStorageGridOperationsMixin.recordCommittedExtraction", original,
                once -> {
                    long committed = once.call();
                    RefinedStorageTransactionBridge.recordCommittedTransfer(
                            resource, actor, committed, TransactionDirection.WITHDRAWAL);
                }, resource, requested, actor, source, destination, fallback);
    }
}
