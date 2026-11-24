package io.github.betapetey.eventtrail.inventory.adapters.create;

import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageEpoch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import java.util.function.Supplier;

/** Synchronous native opening provenance. It never authorizes a later item operation. */
public final class CreateOpeningScope {
    private static final ThreadLocal<Opening> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<NativeStorage> STORAGE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PACKET_ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> STORAGE_ACTIVE = new ThreadLocal<>();
    private CreateOpeningScope() {}

    public static void packet(ServerPlayer player, int entityId, BlockPos local, Runnable original) {
        Opening prior = CURRENT.get();
        Boolean priorActive = PACKET_ACTIVE.get();
        Opening opening = null;
        try {
            if (priorActive == null && human(player) && local != null
                    && player.serverLevel().getEntity(entityId) instanceof AbstractContraptionEntity entity
                    && CreateMountedBinding.admittedEntity(entity)) {
                opening = new Opening(player, entity, local.immutable(), MovingStorageEpoch.runtimeGeneration());
            }
        } catch (RuntimeException | LinkageError failure) { failed("adapter:CreateOpeningScope.packet", failure); }
        PACKET_ACTIVE.set(Boolean.TRUE);
        if (opening == null) CURRENT.remove(); else CURRENT.set(opening);
        try { original.run(); }
        finally {
            if (prior == null) CURRENT.remove(); else CURRENT.set(prior);
            if (priorActive == null) PACKET_ACTIVE.remove(); else PACKET_ACTIVE.set(priorActive);
        }
    }

    public static boolean storage(MountedItemStorage storage, ServerPlayer player, Contraption contraption,
            StructureBlockInfo info, Supplier<Boolean> original) {
        NativeStorage prior = STORAGE.get();
        Boolean priorActive = STORAGE_ACTIVE.get();
        NativeStorage scope = null;
        try {
            Opening opening = current(player);
            if (priorActive == null && opening != null && info != null && opening.entity().getContraption() == contraption
                    && opening.local().equals(info.pos()) && contraption.getBlocks().get(info.pos()) == info
                    && contraption.getStorage().getAllItemStorages().get(info.pos()) == storage) {
                scope = new NativeStorage(opening, storage, info);
            }
        } catch (RuntimeException | LinkageError failure) { failed("adapter:CreateOpeningScope.storage", failure); }
        STORAGE_ACTIVE.set(Boolean.TRUE);
        if (scope == null) STORAGE.remove(); else STORAGE.set(scope);
        try { return original.get(); }
        finally {
            if (prior == null) STORAGE.remove(); else STORAGE.set(prior);
            if (priorActive == null) STORAGE_ACTIVE.remove(); else STORAGE_ACTIVE.set(priorActive);
        }
    }

    public static void bindWrapper(Object wrapper, IItemHandlerModifiable handler) {
        try {
            NativeStorage nativeStorage = STORAGE.get();
            if (!(wrapper instanceof CreateStorageCarrier carrier) || nativeStorage == null
                    || current(nativeStorage.opening().player()) != nativeStorage.opening()) return;
            CreateMountedMenuAdapter.capture(nativeStorage.opening(), nativeStorage.storage(), handler)
                    .ifPresent(carrier::eventtrail$createBinding);
        } catch (RuntimeException | LinkageError failure) { failed("adapter:CreateOpeningScope.bindWrapper", failure); }
    }

    static Opening current(ServerPlayer player) {
        Opening scope = CURRENT.get();
        return scope != null && scope.player() == player && human(player)
                && scope.generation() == MovingStorageEpoch.runtimeGeneration() ? scope : null;
    }
    static boolean human(ServerPlayer player) {
        return player != null && !EventTrailEventHandler.isFakePlayer(player) && !player.isSpectator()
                && !player.isRemoved() && player.getServer() != null && player.getServer().isSameThread()
                && player.serverLevel().getEntity(player.getUUID()) == player;
    }
    /**
     * The opening, storage scope or binding is withheld (no provenance, so nothing is attributed to it) and the failure
     * goes to the fail-safe: a missing member switches CREATE off until restart; any other error skips only this one.
     */
    private static void failed(String site, Throwable failure) { AdapterFailSafe.report(AdapterKey.CREATE, site, failure); }
    public static int retainedScopes() { return (PACKET_ACTIVE.get() == null ? 0 : 1) + (STORAGE_ACTIVE.get() == null ? 0 : 1); }
    static boolean packetActive() { return PACKET_ACTIVE.get() != null; }
    record Opening(ServerPlayer player, AbstractContraptionEntity entity, BlockPos local, long generation) {}
    private record NativeStorage(Opening opening, MountedItemStorage storage, StructureBlockInfo info) {}
}
