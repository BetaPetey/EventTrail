package io.github.betapetey.eventtrail.events.platform;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Kill credit B (design-05c 3.2.5 rule 4): at {@code ServerTickEvent.Pre}, on the main thread, no kill-credit scope is
 * legitimately open (each one closes inside one handler, task or entity tick), so every per-thread stack still holding
 * an entry is cleared and counted SCOPE_LEAK, once per stack; the first clear in a run writes one warning line. Later
 * parts add their stacks here.
 *
 * <p>Only the server thread's stacks are swept (review-01 NIT 8). A leak on another thread, possible only when a mod
 * calls {@code hurt} or {@code doPostAttack} off the server thread, is cleared by that thread's next push with a
 * different tick stamp (3.2.5 rule 3). Until then it withholds, the safe direction, and its entry keeps a reference to
 * the entity it was pushed for.</p>
 */
public final class ScopeLeakSweeper {
    private ScopeLeakSweeper() {
    }

    /** Registered on the NeoForge bus by EventTrailMod. */
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        sweep();
    }

    /** One sweep of this thread's stacks; never throws into the tick. */
    static void sweep() {
        try {
            RetaliationScope.sweepThisThread();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The sweep never interrupts the server tick.
        }
    }
}
