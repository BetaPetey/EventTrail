package io.github.betapetey.eventtrail.inventory.graves.api;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.UUID;

/**
 * The scope part of a take's step 0b (graves design r6, 1.3 and 1.7, route B): "is this player acting at this grave
 * now?" One piece answers it from the current scope frame and installs its answer; the take accounting asks it and
 * combines it with its own listed-path marker. The two share no file, only this one.
 *
 * <p>Until an answer is installed, and whenever the answer throws or is given a null, the answer is false: the take
 * then writes no row and is counted as {@code grave-take-no-scope}. It never says yes by default.</p>
 */
public final class GraveTakerEvidence {

    /** One read-only question; {@code level} is the grave's level object, as the scope frame's key holds it. */
    @FunctionalInterface
    public interface Answer {
        boolean actingAt(UUID playerId, Object level, BlockPos position);
    }

    private static final Answer NONE = (playerId, level, position) -> false;

    private static volatile Answer answer = NONE;

    private GraveTakerEvidence() {
    }

    /** Installs the answer (once, at class initialisation of the piece that reads the scope frame). */
    public static void install(Answer installed) {
        answer = Objects.requireNonNull(installed, "installed");
    }

    /** True only when the installed answer says the player acts at this grave now; false on any failure. */
    public static boolean actingAt(UUID playerId, Object level, BlockPos position) {
        if (playerId == null || level == null || position == null) return false;
        try {
            return answer.actingAt(playerId, level, position);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** Test seam: the not-installed answer again. */
    static void resetForTests() {
        answer = NONE;
    }
}
