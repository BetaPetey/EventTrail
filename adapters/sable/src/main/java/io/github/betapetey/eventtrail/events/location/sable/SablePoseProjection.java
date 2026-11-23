package io.github.betapetey.eventtrail.events.location.sable;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.util.Optional;

/** Sable's own {@code Pose3d} convention for server poses (tested with Sable 2.0.5 / companion 1.6.0). */
public final class SablePoseProjection {
    private SablePoseProjection() { }

    public static Optional<BlockPos> blockCenter(Pose3dc pose, BlockPos nativePosition) {
        return nativePosition == null ? Optional.empty() : point(pose, Vec3.atCenterOf(nativePosition));
    }

    public static Optional<BlockPos> point(Pose3dc pose, Vec3 nativePoint) {
        if (pose == null || nativePoint == null || !finite(nativePoint)) return Optional.empty();
        Pose3d snapshot = new Pose3d(pose);
        if (!valid(snapshot)) return Optional.empty();
        Vec3 physical = snapshot.transformPosition(nativePoint);
        if (!finite(physical) || physical.x < Integer.MIN_VALUE || physical.x >= (double) Integer.MAX_VALUE + 1
                || physical.y < Integer.MIN_VALUE || physical.y >= (double) Integer.MAX_VALUE + 1
                || physical.z < Integer.MIN_VALUE || physical.z >= (double) Integer.MAX_VALUE + 1) return Optional.empty();
        return Optional.of(BlockPos.containing(physical));
    }

    public static boolean valid(Pose3dc pose) {
        if (pose == null || !finite(pose.position()) || !finite(pose.rotationPoint()) || !finite(pose.scale())) return false;
        Quaterniondc q = pose.orientation();
        if (!Double.isFinite(q.x()) || !Double.isFinite(q.y()) || !Double.isFinite(q.z()) || !Double.isFinite(q.w())) return false;
        double norm = q.x() * q.x() + q.y() * q.y() + q.z() * q.z() + q.w() * q.w();
        return Double.isFinite(norm) && Math.abs(norm - 1.0) <= 1.0e-6
                && pose.scale().x() > 0 && pose.scale().y() > 0 && pose.scale().z() > 0;
    }

    private static boolean finite(Vector3dc v) { return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z()); }
    private static boolean finite(Vec3 v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
}
