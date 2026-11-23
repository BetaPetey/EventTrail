package io.github.betapetey.eventtrail.inventory.adapters.enderstorage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import java.util.Optional;

/**
 * Part 1 SCM-ENDER: an Ender chest mounted on a Create contraption (createenderstorage), as the Create family proved it
 * at the one call site that opens it ({@code MountedStorageManager#handlePlayerStorageInteraction}). It names no Create
 * and no Ender Storage type, so neither family loads the other's classes through it. It never supplies an actor: the
 * Ender binding still proves its own player, menu and native channel.
 */
public interface EnderMountedAnchor {
    /** The human player whose contraption interaction packet named this block. */
    ServerPlayer player();

    /** The mounted storage's own frequency (an Ender Storage {@code Frequency}), read once at the call site. */
    Object mountedFrequency();

    /**
     * Whether the contraption entity, its block and its mounted storage are still the ones proved at the call site, the
     * Create hooks are running, and the contraption has not moved since then. False stays false.
     */
    boolean current();

    /** The world block the contraption block is at, only while {@link #current()}. */
    Optional<BlockPos> accessPosition();
}
