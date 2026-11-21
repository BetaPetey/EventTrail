package io.github.betapetey.eventtrail.inventory.core;

import io.github.betapetey.eventtrail.mixin.CompoundContainerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves one physical inventory from the complete bounded menu instead of
 * guessing from its first non-player slot.
 *
 * <p>Every device slot must name the same backing container. The clicked block
 * must be one of that container's live block-entity members. Empty, mixed,
 * conflicting, inaccessible, or partially described backing state is rejected.
 * Compound members are sorted before their context key is built so either half
 * of a double chest resolves to one immutable backing identity.</p>
 */
public final class WholeMenuPhysicalTargetResolver {
    private static final Comparator<BlockPos> POSITION_ORDER =
            Comparator.comparingInt((BlockPos position) -> position.getX())
                    .thenComparingInt(position -> position.getY())
                    .thenComparingInt(position -> position.getZ());

    private WholeMenuPhysicalTargetResolver() {}

    public static Optional<ResolvedTarget> resolve(
            AbstractContainerMenu menu,
            Inventory playerInventory,
            Level level,
            BlockPos interactedPosition,
            int maximumSlots) {
        return resolve(menu, playerInventory, level, interactedPosition, maximumSlots,
                WholeMenuPhysicalTargetResolver::compoundMembers);
    }

    static Optional<ResolvedTarget> resolve(
            AbstractContainerMenu menu,
            Inventory playerInventory,
            Level level,
            BlockPos interactedPosition,
            int maximumSlots,
            CompoundMemberResolver compoundMemberResolver) {
        if (menu == null || playerInventory == null || level == null
                || interactedPosition == null || maximumSlots < 1
                || menu.slots.size() > maximumSlots
                || menu.slots.size() > MenuInventoryLimits.MAX_MENU_DESCRIPTORS) {
            return Optional.empty();
        }

        Optional<Container> selected = selectDeviceBacking(menu, playerInventory, maximumSlots);
        if (selected.isEmpty()) return Optional.empty();
        Container backing = selected.orElseThrow();
        List<BlockEntity> members = members(backing, compoundMemberResolver);
        if (members.isEmpty()) return Optional.empty();

        List<BlockPos> positions = new ArrayList<>(members.size());
        BlockEntity interacted = level.getBlockEntity(interactedPosition);
        boolean containsInteraction = false;
        for (BlockEntity member : members) {
            if (member == null) return Optional.empty();
            BlockPos position = member.getBlockPos().immutable();
            if (!isCurrentMember(member, level, position, level.getBlockEntity(position))) {
                return Optional.empty();
            }
            if (positions.contains(position)) return Optional.empty();
            positions.add(position);
            containsInteraction |= member == interacted && position.equals(interactedPosition);
        }
        if (!containsInteraction) return Optional.empty();
        positions.sort(POSITION_ORDER);

        ResourceKey<Level> dimension = level.dimension();
        StringBuilder key = new StringBuilder(dimension.location().toString());
        for (BlockPos position : positions) {
            key.append('|').append(position.getX())
                    .append(',').append(position.getY())
                    .append(',').append(position.getZ());
        }
        return Optional.of(new ResolvedTarget(
                backing,
                List.copyOf(members),
                List.copyOf(positions),
                interactedPosition.immutable(),
                key.toString(),
                positions.size() == 1
                        ? BackingContextKind.WORLD_BLOCK
                        : BackingContextKind.COMPOUND_BLOCK));
    }

    static boolean isCurrentMember(
            BlockEntity describedMember,
            Level expectedLevel,
            BlockPos describedPosition,
            BlockEntity liveMember) {
        return describedMember != null
                && describedPosition != null
                && !describedMember.isRemoved()
                && describedMember.getLevel() == expectedLevel
                && describedMember.getBlockPos().equals(describedPosition)
                && liveMember == describedMember;
    }

    static Optional<Container> selectDeviceBacking(
            AbstractContainerMenu menu, Container playerInventory, int maximumSlots) {
        if (menu == null || playerInventory == null || maximumSlots < 1
                || menu.slots.size() > maximumSlots
                || menu.slots.size() > MenuInventoryLimits.MAX_MENU_DESCRIPTORS) {
            return Optional.empty();
        }
        Map<Container, Boolean> deviceContainers = new IdentityHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot == null) return Optional.empty();
            Container candidate = slot.container;
            if (candidate == null) return Optional.empty();
            if (candidate == playerInventory || candidate instanceof Inventory
                    || OpeningPlayerSlots.isOpeningPlayerSlot(slot, playerInventory)) continue;
            deviceContainers.put(candidate, Boolean.TRUE);
            if (deviceContainers.size() > 1) return Optional.empty();
        }
        if (deviceContainers.size() != 1) return Optional.empty();
        return Optional.of(deviceContainers.keySet().iterator().next());
    }

    private static List<BlockEntity> members(
            Container container, CompoundMemberResolver compoundMemberResolver) {
        if (container instanceof BlockEntity blockEntity) return List.of(blockEntity);
        if (container instanceof CompoundContainer compound) {
            List<Container> parts;
            try {
                parts = compoundMemberResolver.members(compound);
            } catch (RuntimeException | LinkageError failure) {
                return List.of();
            }
            if (parts == null || parts.size() != 2 || parts.get(0) == parts.get(1)) {
                return List.of();
            }
            List<BlockEntity> blockEntities = new ArrayList<>(2);
            for (Container part : parts) {
                if (!(part instanceof BlockEntity blockEntity)) return List.of();
                blockEntities.add(blockEntity);
            }
            return List.copyOf(blockEntities);
        }
        return List.of();
    }

    private static List<Container> compoundMembers(CompoundContainer compound) {
        CompoundContainerAccessor accessor = (CompoundContainerAccessor) (Object) compound;
        return List.of(accessor.eventtrail$firstContainer(), accessor.eventtrail$secondContainer());
    }

    @FunctionalInterface
    interface CompoundMemberResolver {
        List<Container> members(CompoundContainer compound);
    }

    public record ResolvedTarget(
            Container backingContainer,
            List<BlockEntity> ownershipMembers,
            List<BlockPos> canonicalBackingPositions,
            BlockPos accessPosition,
            String stableContextKey,
            BackingContextKind backingContextKind) {
        public ResolvedTarget {
            backingContainer = Objects.requireNonNull(backingContainer, "backingContainer");
            ownershipMembers = List.copyOf(ownershipMembers);
            canonicalBackingPositions = List.copyOf(canonicalBackingPositions);
            if (canonicalBackingPositions.isEmpty()) {
                throw new IllegalArgumentException("canonicalBackingPositions must not be empty");
            }
            accessPosition = Objects.requireNonNull(accessPosition, "accessPosition").immutable();
            if (stableContextKey == null || stableContextKey.isBlank()) {
                throw new IllegalArgumentException("stableContextKey must not be blank");
            }
            backingContextKind = Objects.requireNonNull(backingContextKind, "backingContextKind");
        }
    }
}
