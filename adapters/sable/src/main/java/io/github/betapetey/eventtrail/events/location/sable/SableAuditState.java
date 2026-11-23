package io.github.betapetey.eventtrail.events.location.sable;

import dev.ryanhcode.sable.companion.math.Pose3dc;

/**
 * No UUID index or movement history: one lazily activated pose and monotonic
 * change epoch on the native owner. Only server-thread poses are observed.
 *
 * <p>One process-wide frame epoch backs the per-owner context epoch: a
 * source-frame invalidation that fails before its target sub-level is known
 * moves it, so every frame captured before the failure stops being current.
 * Same primitive and threads as {@link #contextEpoch()}: written by the
 * assembly hook that also moves the context epoch, read by frame checks only
 * after they have confirmed the server thread.</p>
 */
public final class SableAuditState {
    private static long frameEpoch;
    private static boolean framesExhausted;

    private double[] observed;
    private long epoch;
    private long contextEpoch;
    private volatile boolean tainted;

    public void taint() { tainted = true; }
    public boolean tainted() { return tainted; }
    public long epoch() { return epoch; }
    public long contextEpoch() { return contextEpoch; }

    public void invalidateContext() {
        if (contextEpoch == Long.MAX_VALUE) taint();
        else contextEpoch++;
    }

    static long frameEpoch() { return frameEpoch; }

    static boolean frameCurrent(long capturedFrameEpoch) {
        return !framesExhausted && frameEpoch == capturedFrameEpoch;
    }

    /** Makes every frame captured so far non-current; an exhausted epoch fails closed rather than wrapping. */
    static void invalidateEveryFrame() {
        if (frameEpoch == Long.MAX_VALUE) framesExhausted = true;
        else frameEpoch++;
    }

    public void activate(Pose3dc pose) {
        if (observed == null) {
            observed = new double[13];
            copy(pose, observed);
        } else {
            observe(pose);
        }
    }

    /**
     * This state stays allocation-free for ordinary native reads before the first audit. The guarded hook
     * that calls it is not: it may allocate a capturing lambda per read (accepted design cost).
     */
    public void observe(Pose3dc pose) {
        if (observed == null || tainted) return;
        if (!same(pose, observed)) {
            if (epoch == Long.MAX_VALUE) {
                taint();
                return;
            }
            epoch++;
            copy(pose, observed);
        }
    }

    private static boolean same(Pose3dc p, double[] d) {
        return d[0] == p.position().x() && d[1] == p.position().y() && d[2] == p.position().z()
                && d[3] == p.orientation().x() && d[4] == p.orientation().y()
                && d[5] == p.orientation().z() && d[6] == p.orientation().w()
                && d[7] == p.rotationPoint().x() && d[8] == p.rotationPoint().y() && d[9] == p.rotationPoint().z()
                && d[10] == p.scale().x() && d[11] == p.scale().y() && d[12] == p.scale().z();
    }

    private static void copy(Pose3dc p, double[] d) {
        d[0] = p.position().x(); d[1] = p.position().y(); d[2] = p.position().z();
        d[3] = p.orientation().x(); d[4] = p.orientation().y(); d[5] = p.orientation().z(); d[6] = p.orientation().w();
        d[7] = p.rotationPoint().x(); d[8] = p.rotationPoint().y(); d[9] = p.rotationPoint().z();
        d[10] = p.scale().x(); d[11] = p.scale().y(); d[12] = p.scale().z();
    }
}
