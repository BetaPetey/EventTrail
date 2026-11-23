package io.github.betapetey.eventtrail.events.parts;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/**
 * The one open part-removal action of a server (design r2 2.5, seam a): plain values only, no Minecraft object in
 * its decisions.
 *
 * <p>At most one action is open. A use action opens on a right-click; a break-path action opens inside the mod's
 * {@code onDestroyedByPlayer}. A removal at the open use action's position in the same level joins it (the use
 * action stays the one writer); any other nesting voids the open action and the nested one is a no-op, so neither
 * writes anything.</p>
 *
 * <p>Every action carries the server tick it opened in. An action lives inside one call, so an action still open
 * in a later tick was left by a failure that skipped its release: {@link #dropStale} drops it (review-01 M2), and
 * the scope warns and counts it once.</p>
 *
 * <p>Not thread-safe by design: owned by one {@code PartRemovalAuditBridge.ServerScope} and touched only on that
 * server's thread (every entry point checks {@code isSameThread} first). {@link #close} and {@link #abandon} always
 * release the slot, so nothing outlives the call that opened it.</p>
 */
final class PartActionSlot {
    /** Which entry point opened an action. */
    enum Path {
        USE,
        REMOVAL
    }

    /** What a break-path removal may do. */
    enum Entry {
        /** The slot is empty: the caller may open a break-path action if a reader claims the block. */
        OPEN,
        /** A use action is open at the same position in the same level: join it. */
        JOIN,
        /** Anything else: the open action was voided; the removal records nothing from the bridge. */
        NOOP
    }

    /** One open action, or a join handle on the open use action ({@link #outer} not null). */
    static final class Handle {
        final Path path;
        final BlockPos position;
        /** The action's level, compared by identity only (review-01 N3). */
        final Object level;
        /** The server tick the action opened in. */
        final long tick;
        final Handle outer;
        private boolean voided;
        private boolean ownedByExistingPath;
        private boolean released;

        private Handle(Path path, BlockPos position, Object level, long tick, Handle outer) {
            this.path = path;
            this.position = position;
            this.level = level;
            this.tick = tick;
            this.outer = outer;
        }

        boolean isJoin() {
            return outer != null;
        }

        /** The existing break path recorded a removal inside this use action: the action writes nothing. */
        boolean ownedByExistingPath() {
            return ownedByExistingPath;
        }

        boolean released() {
            return released;
        }
    }

    private Handle open;

    /** Opens a use action, or voids the open action and returns null when the slot is occupied. */
    Handle openUse(BlockPos position, Object level, long tick) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(level, "level");
        if (open != null) {
            open.voided = true;
            return null;
        }
        open = new Handle(Path.USE, position.immutable(), level, tick, null);
        return open;
    }

    /** Decides what a break-path removal at {@code position} in {@code level} may do; voids the open action on NOOP. */
    Entry enterRemoval(BlockPos position, Object level) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(level, "level");
        if (open == null) return Entry.OPEN;
        if (open.path == Path.USE && open.level == level && open.position.equals(position)) return Entry.JOIN;
        // A removal at another position or in another level (its drops would land in another cell), or nested
        // inside an open break-path action (a mod that breaks more blocks through the same player, n2): both write
        // nothing.
        open.voided = true;
        return Entry.NOOP;
    }

    /** Opens a break-path action after {@link Entry#OPEN}; null (and the open action voided) if occupied. */
    Handle openRemoval(BlockPos position, Object level, long tick) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(level, "level");
        if (open != null) {
            open.voided = true;
            return null;
        }
        open = new Handle(Path.REMOVAL, position.immutable(), level, tick, null);
        return open;
    }

    /** A join handle on the open use action after {@link Entry#JOIN}. */
    Handle join() {
        if (open == null || open.path != Path.USE) throw new IllegalStateException("No open use action to join");
        return new Handle(Path.REMOVAL, open.position, open.level, open.tick, open);
    }

    /**
     * Drops the open action when it opened in another tick than {@code tick} (it is voided and released) and returns
     * it; returns null when the slot is empty or its action belongs to this tick.
     */
    Handle dropStale(long tick) {
        Handle stale = open;
        if (stale == null || stale.tick == tick) return null;
        stale.voided = true;
        release(stale);
        return stale;
    }

    /**
     * The existing break path committed the removal of a joined use action: the outer action writes nothing
     * (one writer, never a second break row).
     */
    void ownedByExistingPath(Handle join) {
        if (join != null && join.isJoin()) join.outer.ownedByExistingPath = true;
    }

    /** The action ended normally. A join handle never empties the outer action's slot. */
    void close(Handle handle) {
        release(handle);
    }

    /**
     * The action's call threw or did not return normally: void it (a join voids its outer action) and release it.
     */
    void abandon(Handle handle) {
        if (handle == null) return;
        if (handle.isJoin()) handle.outer.voided = true;
        else handle.voided = true;
        release(handle);
    }

    /** Voids the open action (for example the collector's 257th item entity); it stays open until closed. */
    void voidOpen() {
        if (open != null) open.voided = true;
    }

    boolean isEmpty() {
        return open == null;
    }

    boolean isVoided(Handle handle) {
        return handle != null && (handle.voided || handle.isJoin() && handle.outer.voided);
    }

    /** The open action, or null. */
    Handle current() {
        return open;
    }

    private void release(Handle handle) {
        if (handle == null) return;
        handle.released = true;
        if (!handle.isJoin() && open == handle) open = null;
    }
}
