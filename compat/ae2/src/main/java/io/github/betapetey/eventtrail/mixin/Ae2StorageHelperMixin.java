package io.github.betapetey.eventtrail.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.hooks.HookGuard;
import io.github.betapetey.eventtrail.compat.hooks.HookSite;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/**
 * Captures only positive AE2 item quantities after a committed call. AE2's
 * storage call runs exactly once through {@link HookGuard}, whatever
 * EventTrail's recording does.
 */
@Pseudo
@Mixin(targets = "appeng.api.storage.StorageHelper", remap = false)
abstract class Ae2StorageHelperMixin {
    @Unique
    private static final String INSERT = "poweredInsert("
            + "Lappeng/api/networking/energy/IEnergySource;"
            + "Lappeng/api/storage/MEStorage;"
            + "Lappeng/api/stacks/AEKey;J"
            + "Lappeng/api/networking/security/IActionSource;"
            + "Lappeng/api/config/Actionable;)J";
    @Unique
    private static final String EXTRACT = "poweredExtraction("
            + "Lappeng/api/networking/energy/IEnergySource;"
            + "Lappeng/api/storage/MEStorage;"
            + "Lappeng/api/stacks/AEKey;J"
            + "Lappeng/api/networking/security/IActionSource;"
            + "Lappeng/api/config/Actionable;)J";

    @WrapMethod(
            method = INSERT,
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static long eventtrail$recordCommittedAe2Insert(
            IEnergySource energy,
            MEStorage storage,
            AEKey key,
            long requested,
            IActionSource source,
            Actionable mode,
            Operation<Long> original) {
        return HookGuard.wrap(AdapterKey.AE2, "hook:Ae2StorageHelperMixin.recordCommittedAe2Insert", original, once -> {
            long committed = once.call();
            eventtrail$record(key, source, mode, committed, TransactionDirection.DEPOSIT);
        }, energy, storage, key, requested, source, mode);
    }

    @WrapMethod(
            method = EXTRACT,
            remap = false,
            require = 0,
            expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static long eventtrail$recordCommittedAe2Extraction(
            IEnergySource energy,
            MEStorage storage,
            AEKey key,
            long requested,
            IActionSource source,
            Actionable mode,
            Operation<Long> original) {
        return HookGuard.wrap(AdapterKey.AE2, "hook:Ae2StorageHelperMixin.recordCommittedAe2Extraction", original, once -> {
            long committed = once.call();
            eventtrail$record(key, source, mode, committed, TransactionDirection.WITHDRAWAL);
        }, energy, storage, key, requested, source, mode);
    }

    private static void eventtrail$record(
            AEKey key,
            IActionSource source,
            Actionable mode,
            long committed,
            TransactionDirection direction) {
        if (mode != Actionable.MODULATE
                || committed <= 0L
                || !(key instanceof AEItemKey itemKey)
                || source == null) {
            return;
        }
        source.player()
                .filter(ServerPlayer.class::isInstance)
                .map(ServerPlayer.class::cast)
                .ifPresent(player -> UniversalContainerTracker.recordAuthoritativeTransfer(
                        player, itemKey.toStack(1), direction, committed));
    }
}
