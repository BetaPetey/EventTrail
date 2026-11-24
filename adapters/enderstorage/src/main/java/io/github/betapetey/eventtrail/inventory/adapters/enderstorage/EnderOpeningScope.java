package io.github.betapetey.eventtrail.inventory.adapters.enderstorage;

import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.container.ContainerEnderItemStorage;
import codechicken.enderstorage.item.ItemEnderPouch;
import codechicken.enderstorage.storage.EnderItemStorage;
import codechicken.enderstorage.tile.TileEnderChest;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.MissingAdapterMemberException;
import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.function.Supplier;

/** Synchronous exact native opening only. Observation never consumes or repeats gameplay. */
public final class EnderOpeningScope {
    private static final ThreadLocal<Opening> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    private EnderOpeningScope() {}

    public static <T> T chest(TileEnderChest chest, Player player, Supplier<T> original) {
        return run(() -> player instanceof ServerPlayer server && human(server)
                && chest != null && chest.getClass() == TileEnderChest.class
                ? new Opening(server, chest, null, null, chest.getFrequency(), UniversalContainerTracker.runtimeGeneration()) : null, original);
    }
    public static <T> T pouch(ItemEnderPouch item, Player player, ItemStack stack, Supplier<T> original) {
        return run(() -> player instanceof ServerPlayer server && human(server) && !server.isCrouching()
                && stack != null && !stack.isEmpty() && stack.getCount() == 1 && stack.getItem() == item
                && item.getClass() == ItemEnderPouch.class
                ? new Opening(server, null, stack, null, Frequency.readFromStack(stack), UniversalContainerTracker.runtimeGeneration()) : null, original);
    }
    /**
     * Part 1 SCM-ENDER: a contraption Ender chest's own handleInteraction, which the Create family proved at its call
     * site. The opening frequency is the mounted storage's own; the binding later requires the menu's channel to be
     * exactly that frequency's native channel.
     */
    public static <T> T mounted(EnderMountedAnchor anchor, Supplier<T> original) {
        return run(() -> anchor != null && anchor.player() != null && human(anchor.player())
                && anchor.mountedFrequency() instanceof Frequency frequency && anchor.current()
                ? new Opening(anchor.player(), null, null, anchor, frequency, UniversalContainerTracker.runtimeGeneration()) : null, original);
    }
    private static <T> T run(Supplier<Opening> producer, Supplier<T> original) {
        Opening previous = CURRENT.get(); Boolean priorActive = ACTIVE.get(); Opening opening = null;
        try { if (priorActive == null) opening = producer.get(); }
        catch (RuntimeException | LinkageError failure) { observationFailed("adapter:EnderOpeningScope.run", failure); }
        ACTIVE.set(Boolean.TRUE);
        if (opening == null) CURRENT.remove(); else CURRENT.set(opening);
        try { return original.get(); }
        finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            if (priorActive == null) ACTIVE.remove(); else ACTIVE.set(priorActive);
        }
    }
    public static void bind(ContainerEnderItemStorage menu, Inventory inventory, EnderItemStorage storage) {
        try {
            Opening opening = opening(inventory);
            if (opening == null || !(menu instanceof EnderMenuCarrier carrier)) return;
            EnderStorageBinding.capture(opening, menu, storage).ifPresent(carrier::eventtrail$enderBinding);
        } catch (RuntimeException | LinkageError failure) { observationFailed("adapter:EnderOpeningScope.bind", failure); }
    }
    /** The live opening a menu built now for {@code inventory}'s player may bind to, or null (Part 1 SCM-ENDER seam). */
    static Opening opening(Inventory inventory) {
        Opening opening = CURRENT.get();
        return opening == null || inventory.player != opening.player() || !human(opening.player())
                || opening.generation() != UniversalContainerTracker.runtimeGeneration() ? null : opening;
    }
    public static <T> T retunePouch(ItemStack stack, Player player, Supplier<T> original) {
        Frequency before = null;
        try { if (stack != null) before = Frequency.readFromStack(stack); }
        catch (RuntimeException | LinkageError failure) { observationFailed("adapter:EnderOpeningScope.retunePouch", failure); }
        try { return original.get(); }
        finally {
            try {
                if (player != null && player.containerMenu instanceof EnderMenuCarrier carrier) {
                    var binding = carrier.eventtrail$enderBinding();
                    if (binding != null && binding.pouch == stack
                            && (before == null || !before.equals(Frequency.readFromStack(stack)))) binding.invalidate();
                }
            } catch (RuntimeException | LinkageError failure) { observationFailed("adapter:EnderOpeningScope.retunePouch", failure); }
        }
    }
    public static void capabilityMutation(net.minecraft.world.Container container, boolean simulate) {
        if (!simulate && container != null && container.getClass() == EnderItemStorage.class)
            externalMutation(container instanceof EnderStorageCarrier carrier ? carrier : null);
    }
    /**
     * The external capability epoch producer fails closed: on any failure the storage's epoch is invalidated before the
     * failure goes on, so no Ender action guard stays open across a change the epoch did not count. Anything that is
     * not runtime or linkage still reaches the hook guard, which reports it.
     */
    static void externalMutation(EnderStorageCarrier carrier) {
        try {
            if (carrier != null) carrier.eventtrail$externalCapabilityMutation();
            // No carrier: the epoch mixin is not on EnderItemStorage, which is a missing member (rule B: key OFF).
            else AdapterFailSafe.report(AdapterKey.ENDER_STORAGE, "adapter:EnderOpeningScope.externalMutation",
                    MissingAdapterMemberException.missing(EnderItemStorage.class.getName(),
                            "eventtrail$externalCapabilityMutation", "()V", null));
        } catch (RuntimeException | LinkageError failure) {
            failClosed(carrier);
            observationFailed("adapter:EnderOpeningScope.externalMutation", failure);
        } catch (Throwable failure) {
            failClosed(carrier);
            throw failure;
        }
    }
    private static void failClosed(EnderStorageCarrier carrier) {
        try {
            if (carrier != null) carrier.eventtrail$externalCapabilityInvalidated();
        } catch (Throwable ignored) {
            // Never replaces the failure being handled; the guard reports that one.
        }
    }
    static boolean human(ServerPlayer player) {
        return player != null && !EventTrailEventHandler.isFakePlayer(player) && !player.isSpectator()
                && !player.isRemoved() && player.getServer() != null && player.getServer().isSameThread()
                && player.serverLevel().getEntity(player.getUUID()) == player;
    }
    /** Reports to the fail-safe under ENDER_STORAGE: a missing member switches the key OFF, any other error skips this action. */
    public static void observationFailed(String site, Throwable failure) { AdapterFailSafe.report(AdapterKey.ENDER_STORAGE, site, failure); }
    public static int retainedScopes() { return ACTIVE.get() == null ? 0 : 1; }
    record Opening(ServerPlayer player, TileEnderChest chest, ItemStack pouch, EnderMountedAnchor mounted,
            Frequency frequency, long generation) {}
}
