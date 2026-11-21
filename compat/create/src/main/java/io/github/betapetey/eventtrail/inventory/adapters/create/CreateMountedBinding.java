package io.github.betapetey.eventtrail.inventory.adapters.create;

import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.MountedStorageManager;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageEpoch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Exact current native members, independent of each immutable action anchor. */
public final class CreateMountedBinding {
    final ServerPlayer player;
    final AbstractContraptionEntity entity;
    final Contraption contraption;
    final MountedStorageManager manager;
    final List<Member> members;
    final Object handler;
    final Object detail;
    private final ServerLevel level;
    private final UUID uuid;
    private final BlockPos access;
    private final String key;
    private final String label;
    private final String targetId;
    private final long generation;
    private final long contextEpoch;
    /** Part 1 SCM-SABLE: true when the entity anchor sat in a moving (Sable ship plot) frame at binding time. */
    private final boolean shipFrame;
    /** The anchor frame's identity at binding time (the exact ship sub-level, or the level); null refuses. */
    private final Object frameIdentity;
    private boolean valid = true;

    CreateMountedBinding(CreateOpeningScope.Opening opening, List<Member> members, Object handler,
            Object detail, String integration, String label) {
        this.player = opening.player(); this.entity = opening.entity(); this.level = player.serverLevel();
        this.uuid = entity.getUUID(); this.contraption = entity.getContraption(); this.manager = contraption.getStorage();
        this.members = List.copyOf(members); this.handler = handler; this.detail = detail;
        this.access = opening.local(); this.generation = opening.generation(); this.label = label;
        this.contextEpoch = ((CreateEntityCarrier) entity).eventtrail$createContextEpoch();
        this.targetId = BuiltInRegistries.BLOCK.getKey(contraption.getBlocks().get(access).state().getBlock()).toString();
        this.key = backingKey(integration, level.dimension().location().toString(), uuid,
                members.stream().map(Member::local).toList());
        // SCM-SABLE: the frame is fixed when the binding is made. No current frame (none, or Sable's frame half OFF)
        // makes the binding invalid, so target() is empty and the caller counts the refused open.
        var frame = AuditBlockLocations.capture(level, entity.blockPosition()).filter(AuditBlockLocations.Frame::isCurrent);
        this.shipFrame = frame.map(AuditBlockLocations.Frame::moving).orElse(false);
        this.frameIdentity = frame.map(AuditBlockLocations.Frame::frameIdentity).orElse(null);
    }

    /** Supplementary current backing identity: no physical location or runtime entity ID is used. */
    static String backingKey(String integration, String dimension, UUID uuid, List<BlockPos> members) {
        var identity = new StringBuilder(integration).append('|').append(dimension).append('|').append(uuid);
        members.stream().sorted(Comparator.comparingInt((BlockPos pos) -> pos.getX())
                        .thenComparingInt(pos -> pos.getY()).thenComparingInt(pos -> pos.getZ()))
                .forEach(pos -> identity.append('|').append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()));
        return identity.toString();
    }

    static boolean admittedEntity(AbstractContraptionEntity entity) {
        return entity != null && admittedEntityClass(entity.getClass()) && entity instanceof CreateEntityCarrier;
    }

    /**
     * The exact entity classes a mounted binding may name; unknown subclasses have no inherited admission. Each class
     * is admitted only with a hook on every writer of its rotation (Part 1 SA-create-mounted): the train carriage's
     * yaw and pitch come from {@code Carriage$DimensionalCarriageEntity#alignEntity} (CreateCarriageMotionMixin); the
     * Bits 'n' Bobs chain carriage writes its yaw inside its own {@code tickContraption}, which runs inside
     * {@code AbstractContraptionEntity#tick} (CreateEntityBindingMixin tick); the Bits 'n' Bobs flywheel-bearing
     * entity declares no motion member and writes no motion field (bits_n_bobs-2.3.5). Bits 'n' Bobs is not a compile
     * dependency, so its two classes are matched by exact name and exact superclass. The Offroad Borehead entity
     * (Part 1 SCM-OFFROAD) is matched the same way; it overrides only {@code getAngle(F)}, whose slowdown branch has no
     * hooked writer, so {@link #poseHooked} refuses it while that branch can run.
     */
    static boolean admittedEntityClass(Class<?> type) {
        if (type == null) return false;
        if (type == ControlledContraptionEntity.class || type == OrientedContraptionEntity.class
                || type == GantryContraptionEntity.class || type == CarriageContraptionEntity.class) return true;
        return BNB_FLYWHEEL_ENTITY.equals(type.getName()) && type.getSuperclass() == ControlledContraptionEntity.class
                || BNB_CHAIN_CARRIAGE_ENTITY.equals(type.getName()) && type.getSuperclass() == OrientedContraptionEntity.class
                || OFFROAD_BOREHEAD_ENTITY.equals(type.getName()) && type.getSuperclass() == ControlledContraptionEntity.class;
    }
    static final String BNB_FLYWHEEL_ENTITY =
            "com.kipti.bnb.content.kinetics.flywheel_bearing.contraption.InertControlledContraptionEntity";
    static final String BNB_CHAIN_CARRIAGE_ENTITY =
            "com.kipti.bnb.content.kinetics.cogwheel_carriage.contraption.CogwheelChainCarriageContraptionEntity";
    static final String OFFROAD_BOREHEAD_ENTITY = "dev.ryanhcode.offroad.content.entities.BoreheadContraptionEntity";
    static final String OFFROAD_BOREHEAD_BEARING =
            "dev.ryanhcode.offroad.content.blocks.borehead_bearing.BoreheadBearingBlockEntity";

    /**
     * Part 1 SCM-OFFROAD: whether {@code entity}'s rotation, as {@code toGlobalVector} reads it, is the entity's own
     * angle, whose writers the Create motion hooks observe. Only the Borehead entity changes that (offroad-neoforge
     * 1.21.1-1.3.2): its {@code getAngle(F)} returns the bearing's {@code getInterpolatedAngle} (the Simulated slowdown
     * controller's angle, which no hook observes) when {@code getController()} is a Borehead bearing (or a subclass)
     * whose {@code isSlowingDown()} is true, and otherwise (a bearing that is not slowing) Create's own angle/prevAngle body. This makes the same two
     * calls on the same server-thread state and refuses while that branch can run. It fails closed: an unresolved
     * controller, a controller that is not a Borehead bearing (or a subclass), a member that cannot be found or read,
     * or a read that throws refuses. A refusal makes {@link #current} false. A refusal at open is counted by the
     * tracker's catch-all as an unresolved mounted target; a refusal that starts after open stops the tracker's
     * session with the tracker's WARN (UniversalContainerTracker invalidateSession) and is not counted there.
     */
    static boolean poseHooked(AbstractContraptionEntity entity) {
        return poseHooked(entity, CONTROLLER);
    }
    static boolean poseHooked(AbstractContraptionEntity entity, Method controllerMember) {
        if (entity == null) return false;
        if (!OFFROAD_BOREHEAD_ENTITY.equals(entity.getClass().getName())) return true;
        if (controllerMember == null) return false;
        try {
            Object controller = controllerMember.invoke(entity);
            if (controller == null) return false;
            Object slowing = BOREHEAD_SLOWING.get(controller.getClass());
            if (slowing == NOT_BOREHEAD_BEARING) return false;
            return slowing instanceof Method member && member.invoke(controller) instanceof Boolean down && !down;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return false;
        }
    }
    private static final Method CONTROLLER = controllerMember();
    private static Method controllerMember() {
        try {
            Method member = ControlledContraptionEntity.class.getDeclaredMethod("getController");
            member.setAccessible(true);
            return member;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }
    private static final Object NOT_BOREHEAD_BEARING = new Object();
    private static final Object UNREADABLE = new Object();
    /** Per controller class: NOT_BOREHEAD_BEARING, its public instance {@code isSlowingDown()Z}, or UNREADABLE. */
    private static final ClassValue<Object> BOREHEAD_SLOWING = new ClassValue<>() {
        @Override protected Object computeValue(Class<?> type) {
            try {
                for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
                    if (!OFFROAD_BOREHEAD_BEARING.equals(owner.getName())) continue;
                    Method member = owner.getDeclaredMethod("isSlowingDown");
                    return member.getReturnType() == boolean.class && Modifier.isPublic(member.getModifiers())
                            && !Modifier.isStatic(member.getModifiers()) ? member : UNREADABLE;
                }
                return NOT_BOREHEAD_BEARING;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
                return UNREADABLE;
            }
        }
    };

    /**
     * The Create motion, context and removal hooks keep a contraption entity's moving-storage epoch and context epoch
     * current, and they run only while the CREATE key is active. A binding of either mounted family (Create or
     * Sophisticated mounted menus) is therefore current only while that key is active, so a stale epoch can never keep
     * a moving access open. OFF lasts until restart, so the binding stays invalid.
     */
    static boolean createHooksRunning() {
        try {
            return AdapterFailSafe.active(AdapterKey.CREATE);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    public boolean current() {
        if (!valid) return false;
        boolean current = createHooksRunning() && CreateOpeningScope.human(player) && player.level() == level
                && generation == MovingStorageEpoch.runtimeGeneration() && admittedEntity(entity)
                && ((CreateEntityCarrier)entity).eventtrail$movingStorageEpoch().valid()
                && entity.level() == level && entity.isAlive() && !entity.isRemoved()
                && level.getEntity(uuid) == entity && level.getEntity(entity.getId()) == entity
                && entity.getUUID().equals(uuid) && entity.getContraption() == contraption
                && contraption.entity == entity && contraption.getStorage() == manager
                && ((CreateEntityCarrier) entity).eventtrail$createContextEpoch() == contextEpoch
                && poseHooked(entity) && anchorCurrent();
        if (current) for (Member member : members) {
            var info = contraption.getBlocks().get(member.local());
            if (info == null || !member.state().equals(info.state())
                    || manager.getAllItemStorages().get(member.local()) != member.storage()) { current = false; break; }
        }
        if (!current) valid = false;
        return current;
    }

    public Optional<ContainerTarget> target() {
        // current() runs poseHooked before and after the point is computed, in this same server-thread call.
        if (!current()) return Optional.empty();
        Vec3 point = nativeAccessPoint();
        if (point == null) return lose();
        BlockPos anchor = shipFrame ? shipAnchor(point) : BlockPos.containing(point);
        if (anchor == null || !physical(level, anchor) || !current()) return lose();
        return Optional.of(ContainerTarget.block(level.dimension().location().toString(), anchor, label, BackingContextKind.MOVING_STORE, key));
    }
    private Optional<ContainerTarget> lose() { valid = false; return Optional.empty(); }
    void invalidate() { valid = false; }
    static boolean physical(ServerLevel level, BlockPos point) {
        return AuditBlockLocations.capture(level, point).filter(frame -> !frame.moving() && frame.isCurrent())
                .flatMap(AuditBlockLocations.Frame::currentLocation)
                .filter(location -> location.dimensionId().equals(level.dimension().location()) && location.position().equals(point)).isPresent();
    }

    /** The access block's center as the contraption places it (native coordinates), or null when not a finite cell. */
    private Vec3 nativeAccessPoint() {
        Vec3 point = entity.toGlobalVector(Vec3.atCenterOf(access), 1.0F);
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                || point.x < Integer.MIN_VALUE || point.x >= (double)Integer.MAX_VALUE + 1
                || point.y < Integer.MIN_VALUE || point.y >= (double)Integer.MAX_VALUE + 1
                || point.z < Integer.MIN_VALUE || point.z >= (double)Integer.MAX_VALUE + 1) return null;
        return point;
    }

    /**
     * SCM-SABLE: the entity anchor is still in the frame it was bound in. On land this is {@link #physical}, exactly as
     * before. On a ship the anchor's frame must be moving, current, the same live ship (identity), and projectable.
     */
    private boolean anchorCurrent() {
        if (frameIdentity == null) return false;
        if (!shipFrame) return physical(level, entity.blockPosition());
        return shipCell(entity.blockPosition()).flatMap(AuditBlockLocations.Frame::currentLocation).isPresent();
    }

    /** SCM-SABLE: the current frame of a native cell, only when it is a moving frame of the bound ship. */
    private Optional<AuditBlockLocations.Frame> shipCell(BlockPos nativeCell) {
        return AuditBlockLocations.capture(level, nativeCell)
                .filter(frame -> frame.moving() && frame.isCurrent() && frame.frameIdentity() == frameIdentity);
    }

    /** SCM-SABLE: the world cell of a native access point projected through the bound ship's frame, or null. */
    private BlockPos shipAnchor(Vec3 point) {
        return shipCell(BlockPos.containing(point)).flatMap(frame -> frame.currentPointLocation(point))
                .filter(location -> location.dimensionId().equals(level.dimension().location()))
                .map(AuditLocation::position).orElse(null);
    }

    /** SCM-SABLE: the ship frame's own sticky access guard at the access cell; any ship movement makes it false. */
    private BooleanSupplier shipFrameGuard() {
        Vec3 point = nativeAccessPoint();
        if (point == null) return () -> false;
        return shipCell(BlockPos.containing(point)).map(AuditBlockLocations.Frame::accessGuard).orElse(() -> false);
    }
    public String targetId() { return targetId; }

    /**
     * The OPEN's access guard: it holds while the backing holds, the contraption has not moved (Create epoch), the access
     * position is the opening's, and, on a Sable ship, the ship frame's own access guard holds (any pose change of the
     * ship ends it, as the Create epoch does). Once false it stays false.
     */
    public BooleanSupplier guard(BooleanSupplier backing) {
        Optional<ContainerTarget> opening = target();
        BooleanSupplier frameGuard = opening.isPresent() && shipFrame ? shipFrameGuard() : () -> opening.isPresent();
        MovingStorageEpoch epoch = ((CreateEntityCarrier) entity).eventtrail$movingStorageEpoch();
        var stamp = epoch.snapshot();
        return new BooleanSupplier() {
            private boolean stationary = opening.isPresent();
            public boolean getAsBoolean() {
                if (!stationary) return false;
                var now = target();
                stationary = backing.getAsBoolean() && now.isPresent() && epoch.unchanged(stamp)
                        && opening.orElseThrow().accessPosition().equals(now.orElseThrow().accessPosition())
                        && frameGuard.getAsBoolean();
                return stationary;
            }
        };
    }
    record Member(BlockPos local, BlockState state, MountedItemStorage storage) {
        Member { local = local.immutable(); }
    }
}
