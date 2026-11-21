package io.github.betapetey.eventtrail.inventory.adapters.create;

import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderMountedAnchor;
import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderOpeningScope;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Part 1 SCM-ENDER: the Create half of an Ender chest on a contraption (createenderstorage 1.1.1, SB4).
 *
 * <p>Its mounted storage overrides {@code MountedItemStorage#handleInteraction} and opens the shared Ender channel's
 * own menu, so the generic mounted capture never takes it. The one call site that opens it,
 * {@code MountedStorageManager#handlePlayerStorageInteraction}, is wrapped (CreateStorageCallSiteMixin). Inside a live
 * contraption packet scope for the same human player, entity and block, it proves the call's storage and block, reads
 * the storage's own frequency, and hands the Ender family an {@link EnderMountedAnchor} for the call's duration. The
 * Ender binding then binds a menu built inside that scope only for the same player, the same frequency and the native
 * channel of that frequency. The anchor is current only while the Create hooks run, the generic binding's checks hold
 * ({@link CreateMountedBinding#current()}) and the contraption has not moved since the call
 * ({@link CreateMountedBinding#guard}).</p>
 *
 * <p>createenderstorage is not a compile dependency, so its storage class is matched by exact name and exact
 * superclass, and its {@code getFrequency()} is read reflectively. Anything not proven gives no anchor: the Ender menu
 * then has no binding, and the tracker counts its open as an unresolved target.</p>
 */
public final class CreateEnderMountedAnchor implements EnderMountedAnchor {
    static final String ENDER_MOUNTED_STORAGE = "us.drullk.createenderstorage.contraption.EnderChestMountedStorage";
    static final String ENDER_FREQUENCY = "codechicken.enderstorage.api.Frequency";
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);

    private final CreateMountedBinding binding;
    private final Object frequency;
    private final BooleanSupplier stationary;

    private CreateEnderMountedAnchor(CreateMountedBinding binding, Object frequency) {
        this.binding = binding;
        this.frequency = frequency;
        this.stationary = binding.guard(() -> true);
    }

    /**
     * The call-site body: {@code original} (the storage's own handleInteraction) runs exactly once and its result is
     * returned unchanged, inside the Ender mounted scope when this call is proven, otherwise as it is.
     */
    public static boolean callSite(MountedStorageManager manager, MountedItemStorage storage, ServerPlayer player,
            Contraption contraption, StructureBlockInfo info, Supplier<Boolean> original) {
        CreateEnderMountedAnchor anchor = null;
        try {
            anchor = capture(manager, storage, player, contraption, info);
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.CREATE, "adapter:CreateEnderMountedAnchor.callSite", failure);
        }
        return anchor == null ? original.get() : EnderOpeningScope.mounted(anchor, original);
    }

    /** The anchor for this exact call, or null when any part of it is not proven. */
    static CreateEnderMountedAnchor capture(MountedStorageManager manager, MountedItemStorage storage,
            ServerPlayer player, Contraption contraption, StructureBlockInfo info) {
        if (storage == null || !mountedEnderStorage(storage.getClass())) return null;
        CreateOpeningScope.Opening opening = CreateOpeningScope.current(player);
        if (opening == null || manager == null || contraption == null || info == null || info.pos() == null
                || opening.entity().getContraption() != contraption || contraption.entity != opening.entity()
                || contraption.getStorage() != manager || !opening.local().equals(info.pos())
                || contraption.getBlocks().get(info.pos()) != info
                || manager.getAllItemStorages().get(info.pos()) != storage) return null;
        Object frequency = frequency(storage);
        if (frequency == null) return null;
        // The integration string below never reaches a row: the Ender target's backing key is the frequency key
        // (EnderStorageBinding.target), not this binding's mounted entity+position key.
        var binding = new CreateMountedBinding(opening,
                List.of(new CreateMountedBinding.Member(info.pos(), info.state(), storage)), null, null,
                "createenderstorage:1.1.1", "Ender Chest");
        if (binding.target().isEmpty()) return null;
        var anchor = new CreateEnderMountedAnchor(binding, frequency);
        return anchor.current() ? anchor : null;
    }

    /** createenderstorage's item storage: exact name and exact superclass (an unknown subclass is not admitted). */
    static boolean mountedEnderStorage(Class<?> type) {
        return type != null && ENDER_MOUNTED_STORAGE.equals(type.getName())
                && type.getSuperclass() == MountedItemStorage.class;
    }

    /** The storage's own frequency through its public {@code getFrequency()}; null when that member is not exact. */
    static Object frequency(MountedItemStorage storage) {
        Optional<Method> getter = FREQUENCY_GETTER.get(storage.getClass());
        if (getter.isEmpty()) return null;
        try {
            return getter.orElseThrow().invoke(storage);
        } catch (ReflectiveOperationException unreadable) {
            return null;
        }
    }

    private static final ClassValue<Optional<Method>> FREQUENCY_GETTER = new ClassValue<>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("getFrequency");
                if (method.getDeclaringClass() == type && !Modifier.isStatic(method.getModifiers())
                        && ENDER_FREQUENCY.equals(method.getReturnType().getName())) return Optional.of(method);
            } catch (NoSuchMethodException | SecurityException | LinkageError unreadable) {
                // falls through to the one warning below
            }
            // Once per class (ClassValue): a class with the mounted-storage name but another getter shape is not admitted.
            LOGGER.warn("EventTrail: {} has the createenderstorage mounted-storage name but no public non-static"
                    + " getFrequency() declared by the class and returning {}; its Ender chest opens on a contraption"
                    + " will not be recorded (createenderstorage may have changed)", type.getName(), ENDER_FREQUENCY);
            return Optional.empty();
        }
    };

    @Override public ServerPlayer player() { return binding.player; }
    @Override public Object mountedFrequency() { return frequency; }
    @Override public boolean current() { return binding.current() && stationary.getAsBoolean(); }
    @Override public Optional<BlockPos> accessPosition() {
        return current() ? binding.target().map(ContainerTarget::accessPosition) : Optional.empty();
    }
}
