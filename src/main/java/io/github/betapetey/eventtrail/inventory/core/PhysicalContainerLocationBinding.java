package io.github.betapetey.eventtrail.inventory.core;

import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/** Native backing authority kept separately from each immutable physical action location. */
public final class PhysicalContainerLocationBinding {
    private final Level level;
    private final ContainerTarget nativeTarget;
    private final List<Member> members;
    private final BooleanSupplier currentBacking;
    private final boolean moving;
    private final String stableContextKey;
    private boolean nativeAuthorityValid = true;

    private PhysicalContainerLocationBinding(Level level, ContainerTarget nativeTarget,
            List<Member> members, BooleanSupplier currentBacking, boolean moving, String stableContextKey) {
        this.level = level;
        this.nativeTarget = nativeTarget;
        this.members = List.copyOf(members);
        this.currentBacking = currentBacking;
        this.moving = moving;
        this.stableContextKey = stableContextKey;
    }

    public static Optional<PhysicalContainerLocationBinding> capture(Level level, ContainerTarget nativeTarget,
            List<?> ownershipMembers, BooleanSupplier currentBacking) {
        return capture(level, nativeTarget, ownershipMembers, currentBacking, AuditBlockLocations::capture);
    }

    static Optional<PhysicalContainerLocationBinding> capture(Level level, ContainerTarget nativeTarget,
            List<?> ownershipMembers, BooleanSupplier currentBacking,
            BiFunction<Level, BlockPos, Optional<AuditBlockLocations.Frame>> captureFrame) {
        if (level == null || nativeTarget == null || !nativeTarget.hasAccessPosition()
                || nativeTarget.accessKind() != AccessKind.PHYSICAL
                || (nativeTarget.backingContextKind() != BackingContextKind.WORLD_BLOCK
                    && nativeTarget.backingContextKind() != BackingContextKind.COMPOUND_BLOCK)
                || ownershipMembers == null || ownershipMembers.isEmpty()
                || ownershipMembers.size() > MenuInventoryLimits.MAX_OWNERSHIP_MEMBERS
                || currentBacking == null || captureFrame == null) return Optional.empty();
        try {
            if (!nativeTarget.dimensionId().equals(level.dimension().location().toString())) return Optional.empty();
            var identities = new IdentityHashMap<Object, Boolean>();
            var positions = new HashSet<BlockPos>();
            var members = new ArrayList<Member>();
            for (Object candidate : ownershipMembers) {
                if (!(candidate instanceof BlockEntity block) || identities.put(block, true) != null) return Optional.empty();
                BlockPos position = block.getBlockPos().immutable();
                if (!positions.add(position)) return Optional.empty();
                var resolved = captureFrame.apply(level, position);
                if (resolved == null || resolved.isEmpty()) return Optional.empty();
                var frame = resolved.orElseThrow();
                if (frame.level() != level || !position.equals(frame.nativePosition())
                        || frame.frameIdentity() == null || frame.stableContextKey() == null) return Optional.empty();
                members.add(new Member(block, position, frame, frame.frameIdentity(), frame.stableContextKey()));
            }
            if (!positions.contains(nativeTarget.accessPosition())) return Optional.empty();
            members.sort(Comparator.comparingInt((Member member) -> member.position().getX())
                    .thenComparingInt(member -> member.position().getY()).thenComparingInt(member -> member.position().getZ()));
            boolean moving = members.getFirst().frame().moving();
            Object frameIdentity = members.getFirst().frameIdentity();
            if (members.stream().anyMatch(member -> member.frame().moving() != moving
                    || member.frameIdentity() != frameIdentity)) return Optional.empty();
            String key = nativeTarget.stableContextKey();
            if (moving) {
                var context = new StringBuilder("sable|").append(key);
                for (Member member : members) {
                    if (member.frameKey().isBlank()) return Optional.empty();
                    context.append('|').append(member.frameKey().length()).append(':').append(member.frameKey());
                    if (context.length() > 4_096) return Optional.empty();
                }
                key = context.toString();
            }
            var result = new PhysicalContainerLocationBinding(level, nativeTarget, members, currentBacking, moving, key);
            return result.currentTarget().isPresent() ? Optional.of(result) : Optional.empty();
        } catch (RuntimeException | LinkageError invalid) {
            return Optional.empty();
        }
    }

    /** No live position escapes this call. Every member must still own its native address. */
    public Optional<ContainerTarget> currentTarget() {
        if (!nativeAuthorityValid) return Optional.empty();
        try {
            if (!currentBacking.getAsBoolean()) return invalidate();
            var anchors = new HashSet<BlockPos>();
            AuditLocation access = null;
            for (Member member : members) {
                BlockEntity block = member.block();
                var frame = member.frame();
                if (block.isRemoved() || block.getLevel() != level || !member.position().equals(block.getBlockPos())
                        || level.getBlockEntity(member.position()) != block || frame.level() != level
                        || !member.position().equals(frame.nativePosition()) || frame.moving() != moving
                        || frame.frameIdentity() != member.frameIdentity() || !member.frameKey().equals(frame.stableContextKey())
                        || !frame.isCurrent()) return invalidate();
                var current = frame.currentLocation();
                if (current.isEmpty()) return invalidate();
                AuditLocation location = current.orElseThrow();
                if (!location.dimensionId().toString().equals(nativeTarget.dimensionId())
                        || !anchors.add(location.position())) return invalidate();
                if (member.position().equals(nativeTarget.accessPosition())) access = location;
            }
            if (access == null) return invalidate();
            return Optional.of(ContainerTarget.block(nativeTarget.dimensionId(), access.position(), nativeTarget.label(),
                    moving ? BackingContextKind.MOVING_STORE : nativeTarget.backingContextKind(), stableContextKey));
        } catch (RuntimeException | LinkageError invalid) {
            return invalidate();
        }
    }

    private Optional<ContainerTarget> invalidate() {
        nativeAuthorityValid = false;
        return Optional.empty();
    }

    public boolean belongsTo(Level expectedLevel) { return level == expectedLevel; }

    /** Original access becomes permanently uncloseable after any observed authority/pose loss. */
    public BooleanSupplier accessGuard() {
        var opening = currentTarget();
        if (opening.isEmpty()) return () -> false;
        try {
            List<BooleanSupplier> guards = members.stream().map(member -> member.frame().accessGuard()).toList();
            boolean[] valid = {true};
            return () -> {
                if (!valid[0]) return false;
                try {
                    var current = currentTarget();
                    valid[0] = current.isPresent()
                            && opening.orElseThrow().accessPosition().equals(current.orElseThrow().accessPosition())
                            && guards.stream().allMatch(BooleanSupplier::getAsBoolean);
                } catch (RuntimeException | LinkageError invalid) { valid[0] = false; }
                return valid[0];
            };
        } catch (RuntimeException | LinkageError invalid) { return () -> false; }
    }

    private record Member(BlockEntity block, BlockPos position, AuditBlockLocations.Frame frame,
                          Object frameIdentity, String frameKey) { }
}
