package io.github.betapetey.eventtrail.events.spells;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;

/** A synchronous denial only: binds one native Windburst explosion object, never grants credit. */
public final class ArsNegativeExplosionScope {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private ArsNegativeExplosionScope() {}

    public static Boundary begin(Level level, Entity source) {
        Frame previous = CURRENT.get();
        Frame frame = new Frame(level, source, previous);
        frame.active = level instanceof ServerLevel serverLevel && serverLevel.getServer().isSameThread();
        // N-1: the boundary is built before CURRENT is set, so a failure while building it leaves nothing open.
        Boundary boundary = new Boundary(frame);
        CURRENT.set(frame);
        return boundary;
    }

    /** Called at the native full Explosion constructor's return, before callbacks can create nested blasts. */
    public static void constructed(Explosion explosion, Level level, Entity source) {
        Frame frame = CURRENT.get();
        if (frame != null && frame.active && frame.explosion == null && explosion != null
                && frame.level == level && frame.source == source && explosion.getClass() == Explosion.class) {
            frame.explosion = explosion;
        }
    }

    public static boolean denies(Explosion explosion) {
        Frame frame = CURRENT.get();
        return frame != null && frame.active && explosion != null && frame.explosion == explosion;
    }

    public static void clear() {
        Frame frame = CURRENT.get();
        if (frame != null) { frame.active = false; frame.explosion = null; }
        CURRENT.remove();
    }

    public static final class Boundary implements AutoCloseable {
        private final Frame frame;
        private boolean closed;
        private Boundary(Frame frame) { this.frame = frame; }
        @Override public void close() {
            if (closed) return;
            closed = true; frame.active = false; frame.explosion = null;
            if (CURRENT.get() != frame) { CURRENT.remove(); return; }
            if (frame.previous == null) CURRENT.remove(); else CURRENT.set(frame.previous);
        }
    }
    private static final class Frame {
        final Level level; final Entity source; final Frame previous;
        Explosion explosion; boolean active;
        Frame(Level level, Entity source, Frame previous) { this.level = level; this.source = source; this.previous = previous; }
    }
}
