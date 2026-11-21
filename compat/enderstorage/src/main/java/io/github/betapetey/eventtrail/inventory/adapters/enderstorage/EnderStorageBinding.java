package io.github.betapetey.eventtrail.inventory.adapters.enderstorage;

import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.container.ContainerEnderItemStorage;
import codechicken.enderstorage.init.EnderStorageModContent;
import codechicken.enderstorage.manager.EnderStorageManager;
import codechicken.enderstorage.storage.EnderItemStorage;
import codechicken.enderstorage.tile.TileEnderChest;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.MissingAdapterMemberException;
import io.github.betapetey.eventtrail.compat.RecordingGaps;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** One menu's live native access owner and shared storage. Channel key never supplies an actor. */
public final class EnderStorageBinding {
    private static final Field SERVER_MANAGER = field(EnderStorageManager.class, "serverManager");
    private static final Field STORAGE_MAP = field(EnderStorageManager.class, "storageMap");
    final ServerPlayer player;
    final ContainerEnderItemStorage menu;
    final EnderItemStorage storage;
    final EnderStorageManager manager;
    final TileEnderChest chest;
    final ItemStack pouch;
    /** Part 1 SCM-ENDER: the contraption Ender chest's anchor; null for a chest block or a pouch. */
    final EnderMountedAnchor mounted;
    final Frequency frequency;
    private final ServerLevel level;
    private final Inventory inventory;
    private final BlockPos chestPosition;
    private final long generation;
    private final long frequencyEpoch;
    private final String nativeKey;
    private boolean valid = true;

    private EnderStorageBinding(EnderOpeningScope.Opening opening, ContainerEnderItemStorage menu, EnderItemStorage storage) {
        this.player = opening.player(); this.menu = menu; this.storage = storage; this.manager = storage.manager;
        this.chest = opening.chest(); this.pouch = opening.pouch(); this.mounted = opening.mounted(); this.frequency = opening.frequency();
        this.level = player.serverLevel(); this.inventory = player.getInventory(); this.generation = opening.generation();
        this.chestPosition = chest == null ? null : chest.getBlockPos().immutable();
        this.frequencyEpoch = chest instanceof EnderFrequencyCarrier carrier ? carrier.eventtrail$enderFrequencyEpoch() : -1;
        this.nativeKey = nativeKey(frequency);
    }
    static Optional<EnderStorageBinding> capture(EnderOpeningScope.Opening opening, ContainerEnderItemStorage menu, EnderItemStorage storage) {
        if (storage instanceof EnderStorageCarrier) return proven(opening, menu, storage);
        shapeFailed(menu);
        return Optional.empty();
    }
    /** GF-WARNINGS (B48): only a menu the adapter answers SUPPORTED for (exact class, carrier) is warned about. */
    private static void shapeFailed(ContainerEnderItemStorage menu) {
        if (menu != null && menu.getClass() == ContainerEnderItemStorage.class && menu instanceof EnderMenuCarrier) {
            RecordingGaps.bindingShapeFailed(AdapterKey.ENDER_STORAGE, "adapter:EnderStorageBinding.capture");
        }
    }
    /**
     * capture's proofs past the applied epoch mixin's carrier (a unit test can reach them; Part 1 SCM-ENDER seam). The
     * opening is exactly one of a chest block, a pouch or a contraption Ender chest. A contraption Ender chest binds
     * only a menu whose channel has the mounted storage's own frequency (review-01 m-2 b); otherwise a menu built for
     * another reason inside the call would bind to the wrong channel.
     */
    static Optional<EnderStorageBinding> proven(EnderOpeningScope.Opening opening, ContainerEnderItemStorage menu, EnderItemStorage storage) {
        if (storage == null || storage.getClass() != EnderItemStorage.class || menu.getClass() != ContainerEnderItemStorage.class
                || menu.chestInv != storage || !wellFormed(opening.frequency()) || !wellFormed(storage.freq)
                || (opening.chest() != null ? 1 : 0) + (opening.pouch() != null ? 1 : 0) + (opening.mounted() != null ? 1 : 0) != 1
                || opening.mounted() != null && !opening.frequency().equals(storage.freq)) {
            shapeFailed(menu);
            return Optional.empty();
        }
        var binding = new EnderStorageBinding(opening, menu, storage);
        // During the native constructor player.containerMenu still refers to the previous menu.
        return binding.currentOwner() ? Optional.of(binding) : Optional.empty();
    }
    static boolean wellFormed(Frequency frequency) {
        return frequency != null && frequency.left() != null && frequency.middle() != null && frequency.right() != null
                && frequency.owner() != null && frequency.ownerName() != null
                && frequency.owner().isPresent() == frequency.ownerName().isPresent();
    }
    static String nativeKey(Frequency frequency) {
        if (!wellFormed(frequency)) throw new IllegalArgumentException("Unproven native frequency");
        return frequency + ",type=item";
    }
    static String backingKey(Frequency frequency) { return "enderstorage:2.13.0.191|" + nativeKey(frequency); }

    /** Read only: observing a missing channel must never create one or reload the native manager. */
    static boolean nativeCurrent(EnderStorageManager manager, EnderItemStorage storage, String nativeKey) {
        try {
            return manager != null && !manager.client && SERVER_MANAGER.get(null) == manager
                    && storage != null && storage.manager == manager && storage.getClass() == EnderItemStorage.class
                    && wellFormed(storage.freq) && nativeKey.equals(nativeKey(storage.freq))
                    && ((Map<?, ?>) STORAGE_MAP.get(manager)).get(nativeKey) == storage;
        } catch (IllegalAccessException failure) { throw ownerUnavailable(failure); }
    }
    static boolean exactPouchPresent(Inventory inventory, ItemStack pouch) {
        if (inventory == null || pouch == null || pouch.isEmpty() || pouch.getCount() != 1
                || pouch.getItem() != EnderStorageModContent.ENDER_POUCH.get()
                || inventory.getContainerSize() < 1 || inventory.getContainerSize() > 41) return false;
        int matches = 0;
        for (int index = 0; index < inventory.getContainerSize(); index++) if (inventory.getItem(index) == pouch) matches++;
        return matches == 1;
    }
    /**
     * The Ender Storage hooks (frequency epoch, capability epoch, menu binding) run only while the ENDER_STORAGE key is
     * active. A binding is therefore current only while that key is active, so a skipped epoch bump can never keep an
     * access or action guard open. OFF lasts until restart, and a restart changes the runtime generation, so the
     * binding stays invalid.
     */
    static boolean enderHooksRunning() {
        try {
            return AdapterFailSafe.active(AdapterKey.ENDER_STORAGE);
        } catch (Throwable unavailable) {
            return false;
        }
    }
    private boolean currentOwner() {
        if (!valid) return false;
        boolean current = enderHooksRunning() && EnderOpeningScope.human(player) && player.level() == level && player.getInventory() == inventory
                && generation == UniversalContainerTracker.runtimeGeneration() && menu.chestInv == storage
                && nativeCurrent(manager, storage, nativeKey);
        if (current && chest != null) {
            current = chest.getClass() == TileEnderChest.class && chest instanceof EnderFrequencyCarrier carrier
                    && frequencyEpoch >= 0 && frequencyEpoch != Long.MAX_VALUE && carrier.eventtrail$enderFrequencyEpoch() == frequencyEpoch
                    && !chest.isRemoved() && chest.getLevel() == level && chest.getBlockPos().equals(chestPosition)
                    && level.getBlockEntity(chestPosition) == chest && chest.getBlockState().getBlock() == EnderStorageModContent.ENDER_CHEST_BLOCK.get()
                    && frequency.equals(chest.getFrequency()) && physical(level, chestPosition);
        } else if (current && mounted != null) {
            // Part 1 SCM-ENDER: the Create anchor (hooks running, same contraption, block and storage, unmoved since
            // the call) and the mounted storage's own frequency, which is still the channel's.
            // The mountedFrequency() check is belt and braces; the deciding ones are storage.freq and nativeCurrent.
            current = mounted.player() == player && mounted.current() && frequency.equals(mounted.mountedFrequency())
                    && frequency.equals(storage.freq);
        } else if (current) {
            current = exactPouchPresent(inventory, pouch) && frequency.equals(Frequency.readFromStack(pouch));
        }
        if (!current) valid = false;
        return current;
    }
    public boolean current() {
        if (!valid) return false;
        if (player.containerMenu != menu) { invalidate(); return false; }
        return currentOwner();
    }
    public Optional<ContainerTarget> target() {
        if (!current()) return Optional.empty();
        // A contraption Ender chest is reached at the world block its contraption block is at now (Part 1 SCM-ENDER).
        BlockPos position = chest != null ? chestPosition : mounted != null ? mounted.accessPosition().orElse(null) : player.blockPosition();
        if (position == null || !physical(level, position)) { invalidate(); return Optional.empty(); }
        boolean portable = chest == null && mounted == null;
        return Optional.of(new ContainerTarget(level.dimension().location().toString(), position.getX(), position.getY(), position.getZ(),
                portable ? "Ender Pouch" : "Ender Chest", portable ? AccessKind.PORTABLE_MENU : AccessKind.PHYSICAL,
                BackingContextKind.SHARED_CHANNEL, backingKey(frequency)));
    }
    public String targetId() { return chest == null && mounted == null ? "enderstorage:ender_pouch" : "enderstorage:ender_chest"; }
    public BooleanSupplier accessGuard() { return this::current; }
    public BooleanSupplier actionGuard() {
        if (!current() || !(storage instanceof EnderStorageCarrier carrier)) return () -> false;
        return actionGuard(this::current, carrier);
    }
    static BooleanSupplier actionGuard(BooleanSupplier current, EnderStorageCarrier carrier) {
        long external = carrier.eventtrail$externalCapabilityEpoch(); long action = carrier.eventtrail$beginManualAction();
        return () -> external != Long.MAX_VALUE && action != Long.MAX_VALUE && current.getAsBoolean()
                && carrier.eventtrail$externalCapabilityEpoch() == external && carrier.eventtrail$manualActionEpoch() == action;
    }
    void invalidate() { valid = false; }
    static boolean physical(ServerLevel level, BlockPos point) {
        return AuditBlockLocations.capture(level, point).filter(frame -> !frame.moving() && frame.isCurrent())
                .flatMap(AuditBlockLocations.Frame::currentLocation)
                .filter(location -> location.dimensionId().equals(level.dimension().location()) && location.position().equals(point)).isPresent();
    }
    static Field field(Class<?> type, String name) {
        try { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw nativeUnavailable("Ender native contract unavailable", missingField(type, name, failure)); }
    }
    /**
     * A reflective member of the native manager is missing (the field was not found) or present but unusable. Reports it under
     * ENDER_STORAGE with its exact member text (the key goes OFF until restart, one warning) and returns the fail-closed
     * throw, with the same type and message as before. The typed missing-member exception is the thrown error's cause, so a
     * caller's own report of it sorts the same way.
     */
    static IllegalStateException nativeUnavailable(String message, MissingAdapterMemberException missing) {
        AdapterFailSafe.report(AdapterKey.ENDER_STORAGE, "adapter:EnderStorageBinding.nativeMember", missing);
        return new IllegalStateException(message, missing);
    }
    static MissingAdapterMemberException missingField(Class<?> owner, String name, ReflectiveOperationException failure) {
        return failure instanceof NoSuchFieldException
                ? MissingAdapterMemberException.missing(owner.getName(), name, "", failure)
                : MissingAdapterMemberException.inaccessible(owner.getName(), name, "", failure);
    }
    /** The native manager's two static members are present but unusable (the failing one is not known to the caller). */
    static IllegalStateException ownerUnavailable(IllegalAccessException failure) {
        return nativeUnavailable("Ender native owner unavailable", MissingAdapterMemberException.inaccessible(
                EnderStorageManager.class.getName(), "serverManager,storageMap", "", failure));
    }
}
