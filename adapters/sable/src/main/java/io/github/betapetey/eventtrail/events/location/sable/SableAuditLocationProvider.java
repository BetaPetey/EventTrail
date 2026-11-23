package io.github.betapetey.eventtrail.events.location.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Exact native plot membership; no world scan and no raw-address fallback.
 *
 * <p>No Sable version and no Sable class bytes are checked (build B). Every
 * address is decided when it is captured, and every moving frame is checked
 * again whenever it is read:</p>
 * <ul>
 *   <li>The recognizer (in a plot or not) needs {@link AdapterKey#SABLE_RECOGNIZER}.
 *       A failure of the recognizer half in {@code capture} ({@code getContainer},
 *       {@code getLevel}, {@code inBounds}) answers empty and is reported under
 *       that key, so a missing member switches it OFF and every position is
 *       withheld until restart (Q2). A container that is not exactly Sable's
 *       server class switches it OFF too. (The same members read by the
 *       moving frame in {@code isCurrent} and {@code project} report under
 *       SABLE_FRAME.)</li>
 *   <li>Every moving frame also needs {@link AdapterKey#SABLE_FRAME}: while it is
 *       OFF (a frame-feeding hook did not pass the hook check, or a frame member
 *       is missing), no in-plot address is captured and no frame is current. A
 *       failure of the frame half escapes to {@link AuditBlockLocations}, which
 *       reports it under SABLE_FRAME. Addresses the recognizer proves outside
 *       every plot stay stationary.</li>
 *   <li>The container, plot and sub-level must be exactly Sable's server
 *       classes, the sub-level must carry the state the frame hook adds (an
 *       exact sub-level without it switches SABLE_FRAME OFF), and
 *       {@code isCurrent} re-checks thread, taint, both epochs and every
 *       identity link before any projection.</li>
 * </ul>
 */
public final class SableAuditLocationProvider implements AuditBlockLocations.Provider {
    static final String RECOGNIZER_SITE = "bridge:SableAuditLocationProvider.capture";
    static final String FRAME_SITE = "bridge:SableAuditLocationProvider.capture";
    /** Test seam for the recognizer half; null in production, where Sable's own lookup is called. */
    private final Function<ServerLevel, ServerSubLevelContainer> containers;

    public SableAuditLocationProvider() {
        this(null);
    }

    SableAuditLocationProvider(Function<ServerLevel, ServerSubLevelContainer> containers) {
        this.containers = containers;
    }

    @Override public Optional<AuditBlockLocations.Frame> capture(ServerLevel level, BlockPos nativePosition) {
        if (!keyActive(AdapterKey.SABLE_RECOGNIZER) || level == null || nativePosition == null
                || level.getServer() == null || !level.getServer().isSameThread()) return Optional.empty();
        ServerSubLevelContainer container;
        boolean inPlot;
        try {
            // Recognizer half: its failures are the recognizer's, never the frame stack's (f2-5 hand-off Q3).
            container = containers == null ? SubLevelContainer.getContainer(level) : containers.apply(level);
            // No container (or one for another level) is an ordinary state and stays silent. A container that is
            // present but not exactly Sable's server class is unusable: the recognizer goes OFF with a warning.
            if (container == null) return Optional.empty();
            if (container.getClass() != ServerSubLevelContainer.class) {
                AdapterFailSafe.conceptNotFound(AdapterKey.SABLE_RECOGNIZER, RECOGNIZER_SITE, "exact ServerSubLevelContainer");
                return Optional.empty();
            }
            if (container.getLevel() != level) return Optional.empty();
            inPlot = container.inBounds(nativePosition);
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.SABLE_RECOGNIZER, RECOGNIZER_SITE, failure);
            return Optional.empty();
        }
        if (!inPlot) return AuditBlockLocations.stationary(level, nativePosition);
        // Frame half: a failure from here on escapes to AuditBlockLocations, which reports it under SABLE_FRAME.
        if (!keyActive(AdapterKey.SABLE_FRAME)) return Optional.empty();
        LevelPlot plot = container.getPlot(nativePosition.getX() >> 4, nativePosition.getZ() >> 4);
        if (plot == null || plot.getClass() != ServerLevelPlot.class) return Optional.empty();
        SubLevel subLevel = plot.getSubLevel();
        if (subLevel == null || subLevel.getClass() != ServerSubLevel.class) return Optional.empty();
        SableAuditCarrier carrier = carrierOf(subLevel);
        if (carrier == null) return Optional.empty();
        UUID uuid = subLevel.getUniqueId();
        if (uuid == null) return Optional.empty();
        NativeFrame frame = new NativeFrame(level, nativePosition.immutable(), container, plot,
                (ServerSubLevel) subLevel, uuid, carrier.eventtrail$sableAuditState(), carrier.eventtrail$sableAuditState().contextEpoch(),
                SableAuditState.frameEpoch());
        return frame.currentLocation().isPresent() ? Optional.of(frame) : Optional.empty();
    }

    /**
     * The state holder of an exact {@code ServerSubLevel}. Such a sub-level that is not a {@link SableAuditCarrier} is
     * present but unusable (the Sable sub-level hook did not apply): SABLE_FRAME goes OFF with a warning and every
     * in-plot position stays withheld. Returns null in that case.
     */
    static SableAuditCarrier carrierOf(SubLevel subLevel) {
        if (subLevel instanceof SableAuditCarrier carrier) return carrier;
        AdapterFailSafe.conceptNotFound(AdapterKey.SABLE_FRAME, FRAME_SITE, "SableAuditCarrier (the Sable sub-level hook did not apply)");
        return null;
    }

    /** A fail-safe key gate; a check that cannot run reads as OFF, so locations are withheld. */
    static boolean keyActive(AdapterKey key) {
        try {
            return AdapterFailSafe.active(key);
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private record NativeFrame(ServerLevel level, BlockPos nativePosition, ServerSubLevelContainer container,
                               LevelPlot plot, ServerSubLevel subLevel, UUID uuid, SableAuditState state, long contextEpoch,
                               long frameEpoch)
            implements AuditBlockLocations.Frame {
        @Override public boolean moving() { return true; }
        @Override public Object frameIdentity() { return subLevel; }

        @Override public String stableContextKey() {
            // Native address distinguishes members; the UUID distinguishes reuse and split assemblies.
            // "sable:2.0.5:" is a fixed key prefix that reaches stored data, not a version check: kept byte for byte.
            return "sable:2.0.5:" + level.dimension().location() + ":" + uuid + ":"
                    + nativePosition.getX() + ":" + nativePosition.getY() + ":" + nativePosition.getZ();
        }

        @Override public boolean isCurrent() {
            // The key gate comes first: while SABLE_FRAME is OFF no Sable member is read here.
            return keyActive(AdapterKey.SABLE_FRAME)
                    && level.getServer() != null && level.getServer().isSameThread() && !state.tainted()
                    && state.contextEpoch() == contextEpoch && SableAuditState.frameCurrent(frameEpoch)
                    && SubLevelContainer.getContainer(level) == container && container.getLevel() == level
                    && !subLevel.isRemoved() && subLevel.getLevel() == level && subLevel.getUniqueId().equals(uuid)
                    && subLevel.getPlot() == plot && plot.getSubLevel() == subLevel
                    && container.inBounds(nativePosition)
                    && container.getPlot(nativePosition.getX() >> 4, nativePosition.getZ() >> 4) == plot
                    && container.getSubLevel(uuid) == subLevel;
        }

        @Override public Optional<AuditLocation> currentLocation() {
            return project(Vec3.atCenterOf(nativePosition));
        }

        @Override public Optional<AuditLocation> currentPointLocation(Vec3 nativePoint) {
            if (nativePoint == null || !Double.isFinite(nativePoint.x) || !Double.isFinite(nativePoint.y)
                    || !Double.isFinite(nativePoint.z) || !BlockPos.containing(nativePoint).equals(nativePosition)) return Optional.empty();
            return project(nativePoint);
        }

        private Optional<AuditLocation> project(Vec3 nativePoint) {
            if (!isCurrent()) return Optional.empty();
            Pose3d pose = new Pose3d(subLevel.logicalPose());
            state.activate(pose);
            Optional<BlockPos> physical = SablePoseProjection.point(pose, nativePoint);
            if (!isCurrent()) return Optional.empty();
            return physical.filter(position -> !container.inBounds(position))
                    .map(position -> new AuditLocation(level.dimension().location(), position));
        }

        @Override public BooleanSupplier accessGuard() {
            Optional<AuditLocation> opening = currentLocation();
            long openingEpoch = state.epoch();
            return new BooleanSupplier() {
                private boolean valid = opening.isPresent();
                @Override public boolean getAsBoolean() {
                    if (!valid) return false;
                    Optional<AuditLocation> now = currentLocation();
                    valid = now.isPresent() && state.epoch() == openingEpoch && now.equals(opening);
                    return valid;
                }
            };
        }
    }
}
