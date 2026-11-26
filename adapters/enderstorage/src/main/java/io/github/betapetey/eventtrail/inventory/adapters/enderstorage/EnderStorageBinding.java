package io.github.betapetey.eventtrail.inventory.adapters.enderstorage;

import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.container.ContainerEnderItemStorage;
import codechicken.enderstorage.init.EnderStorageModContent;
import codechicken.enderstorage.manager.EnderStorageManager;
import codechicken.enderstorage.storage.EnderItemStorage;
import codechicken.enderstorage.tile.TileEnderChest;
import codechicken.lib.colour.EnumColour;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.MissingAdapterMemberException;
import io.github.betapetey.eventtrail.adapters.RecordingGaps;
import io.github.betapetey.eventtrail.events.UniversalContainerTracker;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

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
        return proven(opening, menu, storage, EnderStorageBinding::nativeKey);
    }
    /** As {@link #proven(EnderOpeningScope.Opening, ContainerEnderItemStorage, EnderItemStorage)}, with the key text the guard checks (test seam). */
    static Optional<EnderStorageBinding> proven(EnderOpeningScope.Opening opening, ContainerEnderItemStorage menu, EnderItemStorage storage,
            Function<Frequency, String> keyText) {
        if (storage == null || storage.getClass() != EnderItemStorage.class || menu.getClass() != ContainerEnderItemStorage.class
                || menu.chestInv != storage || !wellFormed(opening.frequency()) || !wellFormed(storage.freq)
                || (opening.chest() != null ? 1 : 0) + (opening.pouch() != null ? 1 : 0) + (opening.mounted() != null ? 1 : 0) != 1
                || opening.mounted() != null && !opening.frequency().equals(storage.freq)) {
            shapeFailed(menu);
            return Optional.empty();
        }
        // KL-50 key guard: a build whose key text leaves out a channel field would merge two channels under one key.
        if (!keyTextHoldsEveryField(opening.frequency(), keyText) || !keyTextHoldsEveryField(storage.freq, keyText)) {
            RecordingGaps.keyTextFailed(AdapterKey.ENDER_STORAGE, KEY_TEXT_SITE);
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
    /** KL-50 key guard: the fail-safe site of a bind refused because the key text leaves out a channel field (DRAFT TEXT for the Owner). */
    static final String KEY_TEXT_SITE = "adapter:EnderStorageBinding.keyText";
    /**
     * KL-50 key guard (Owner Q381: "Build the KL-50 key guard for Ender."): true only when {@code keyText} gives the
     * well-formed {@code frequency} a key that changes whenever a field {@link #wellFormed} reads changes: each of the
     * three colours, and the owner (another owner, or no owner, for an owned channel; an owner for a public one). The
     * owner's display name is read by wellFormed only for its presence, which must match the owner's, so the owner
     * covers it; its text names no channel (the native manager keeps a renamed owner's channel). A key text that is
     * null or throws fails. Otherwise a changed build's key text could merge two channels' history under one key.
     */
    static boolean keyTextHoldsEveryField(Frequency frequency, Function<Frequency, String> keyText) {
        if (!wellFormed(frequency) || keyText == null) return false;
        try {
            String key = keyText.apply(frequency);
            if (key == null) return false;
            for (Frequency changed : oneFieldChanged(frequency)) if (key.equals(keyText.apply(changed))) return false;
            return true;
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
    }
    /** KL-50: {@code frequency} with exactly one channel field changed, once for each field wellFormed reads. */
    private static List<Frequency> oneFieldChanged(Frequency frequency) {
        EnumColour left = frequency.left(), middle = frequency.middle(), right = frequency.right();
        Optional<UUID> owner = frequency.owner(); Optional<Component> name = frequency.ownerName();
        List<Frequency> changed = new ArrayList<>(5);
        changed.add(new Frequency(next(left), middle, right, owner, name));
        changed.add(new Frequency(left, next(middle), right, owner, name));
        changed.add(new Frequency(left, middle, next(right), owner, name));
        if (owner.isPresent()) {
            UUID id = owner.get();
            changed.add(new Frequency(left, middle, right, Optional.of(new UUID(id.getMostSignificantBits(), ~id.getLeastSignificantBits())), name));
            changed.add(new Frequency(left, middle, right, Optional.empty(), Optional.empty()));
        } else {
            changed.add(new Frequency(left, middle, right, Optional.of(new UUID(0L, 0L)), Optional.of(Component.empty())));
        }
        return changed;
    }
    private static EnumColour next(EnumColour colour) {
        EnumColour[] all = EnumColour.values();
        return all[(colour.ordinal() + 1) % all.length];
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
