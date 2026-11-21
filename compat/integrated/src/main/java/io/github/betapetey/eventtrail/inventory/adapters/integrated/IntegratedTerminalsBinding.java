package io.github.betapetey.eventtrail.inventory.adapters.integrated;

import io.github.betapetey.eventtrail.events.EventTrailEventHandler;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.cyclops.commoncapabilities.api.ingredient.IngredientComponent;
import org.cyclops.integrateddynamics.api.network.INetwork;
import org.cyclops.integrateddynamics.api.part.IPartContainer;
import org.cyclops.integrateddynamics.api.part.PartPos;
import org.cyclops.integrateddynamics.core.helper.NetworkHelpers;
import org.cyclops.integrateddynamics.core.helper.PartHelpers;
import org.cyclops.integrateddynamics.core.network.Network;
import org.cyclops.integratedterminals.inventory.container.ContainerTerminalStoragePart;
import org.cyclops.integratedterminals.part.PartTypeTerminalStorage;
import org.cyclops.integratedtunnels.core.network.ItemNetwork;
import java.util.Optional;
import java.util.UUID;

/** Exact original menu/access/network authority; contains no network inventory mirror. */
public final class IntegratedTerminalsBinding {
    final ContainerTerminalStoragePart menu;
    final ServerPlayer player;
    final ServerLevel level;
    final INetwork network;
    final ItemNetwork items;
    private final IPartContainer partContainer;
    private final PartTypeTerminalStorage.State partState;
    private final PartPos access;
    private final BlockPos position;
    private final BlockEntity block;
    private final long generation;
    private final int partId;
    private UUID networkIdentity;
    private boolean valid = true;

    private IntegratedTerminalsBinding(ContainerTerminalStoragePart menu, ServerPlayer player,
            INetwork network, ItemNetwork items, IPartContainer partContainer, PartTypeTerminalStorage.State partState) {
        this.menu = menu; this.player = player; this.level = player.serverLevel(); this.network = network; this.items = items;
        this.partContainer = partContainer; this.partState = partState; this.partId = partState.getId();
        this.access = menu.getPartTarget().getCenter(); this.position = access.getPos().getBlockPos().immutable();
        this.block = level.getBlockEntity(position); this.generation = UniversalContainerTracker.runtimeGeneration();
    }

    /** Called only from native placed-menu construction, before vanilla installs the menu. */
    public static IntegratedTerminalsBinding capture(ContainerTerminalStoragePart menu) {
        try {
            if (menu == null || menu.getClass() != ContainerTerminalStoragePart.class
                    || !(menu.getPlayerIInventory().player instanceof ServerPlayer player) || !human(player)) return null;
            INetwork network = menu.getNetwork().orElse(null);
            Object items = NetworkHelpers.getIngredientNetwork(Optional.ofNullable(network), IngredientComponent.ITEMSTACK).orElse(null);
            if (network == null || network.getClass() != Network.class || !(network instanceof IntegratedNetworkCarrier)
                    || items == null || items.getClass() != ItemNetwork.class || menu.getPartContainer().isEmpty()
                    || menu.getPartState().isEmpty()) return null;
            var binding = new IntegratedTerminalsBinding(menu, player, network, (ItemNetwork) items,
                    menu.getPartContainer().orElseThrow(), menu.getPartState().orElseThrow());
            return binding.current(false) ? binding : null;
        } catch (RuntimeException | LinkageError failure) { IntegratedTerminalsOperations.observationFailed("adapter:IntegratedTerminalsBinding.capture", failure); return null; }
    }

    static boolean human(ServerPlayer player) {
        return player != null && player.getServer() != null && player.getServer().isSameThread()
                && !player.isRemoved() && !player.isSpectator() && !player.isCreative() && !EventTrailEventHandler.isFakePlayer(player)
                && player.serverLevel().getEntity(player.getUUID()) == player;
    }

    public boolean current() { return current(true); }
    private boolean current(boolean active) {
        if (!valid) return false;
        try {
            valid = !IntegratedTerminalsOperations.disabled() && human(player) && player.level() == level && (!active || player.containerMenu == menu)
                    && generation == UniversalContainerTracker.runtimeGeneration() && block != null && !block.isRemoved()
                    && level.hasChunkAt(position) && level.getBlockEntity(position) == block
                    && access.getPos().getLevel(true) == level && position.equals(access.getPos().getBlockPos())
                    && menu.getWorld() == level && menu.getNetwork().orElse(null) == network
                    && network.isInitialized() && !network.isKilled() && !network.isCrashed()
                    && nativeNetworkOwns(network, items)
                    && NetworkHelpers.getIngredientNetwork(Optional.of(network), IngredientComponent.ITEMSTACK).orElse(null) == items
                    && NetworkHelpers.getNetwork(access).orElse(null) == network
                    && PartHelpers.getPartContainer(access.getPos(), access.getSide()).orElse(null) == partContainer
                    && menu.getPartContainer().orElse(null) == partContainer
                    && menu.getPartType() == org.cyclops.integratedterminals.part.PartTypes.TERMINAL_STORAGE
                    && partContainer.getPart(access.getSide()) == menu.getPartType()
                    && partContainer.getPartState(access.getSide()) == partState && menu.getPartState().orElse(null) == partState
                    && partState.getId() == partId && partId >= 0;
            var frame = valid ? AuditBlockLocations.capture(level, position).orElse(null) : null;
            valid = valid && frame != null && !frame.moving() && frame.isCurrent()
                    && frame.currentLocation().isPresent();
        } catch (RuntimeException | LinkageError failure) { valid = false; IntegratedTerminalsOperations.observationFailed("adapter:IntegratedTerminalsBinding.current", failure); }
        return valid;
    }

    public Optional<ContainerTarget> target(int channel) {
        if (channel < -1 || !current()) return Optional.empty();
        if (networkIdentity == null) networkIdentity = ((IntegratedNetworkCarrier) network).eventtrail$integratedNetworkIdentity();
        return Optional.of(new ContainerTarget(level.dimension().location().toString(), position.getX(), position.getY(), position.getZ(),
                "Integrated Storage Terminal", AccessKind.WIRED_NETWORK, BackingContextKind.NETWORK,
                networkKey(generation, networkIdentity, channel)));
    }

    static String networkKey(long generation, UUID identity, int channel) {
        if (identity == null || channel < -1) throw new IllegalArgumentException("Invalid transient network authority");
        return "integratedterminals:runtime:" + generation + ':' + identity + ":channel:" + channel;
    }
    /** Tunnels registers this forward capability; its inherited reverse-network field is unused and null. */
    static boolean nativeNetworkOwns(INetwork network, ItemNetwork items) {
        return network != null && network.getClass() == Network.class && items != null && items.getClass() == ItemNetwork.class
                && network.getCapability(org.cyclops.integratedtunnels.Capabilities.ItemNetwork.NETWORK).orElse(null) == items;
    }
    public void invalidate() { valid = false; }
}
