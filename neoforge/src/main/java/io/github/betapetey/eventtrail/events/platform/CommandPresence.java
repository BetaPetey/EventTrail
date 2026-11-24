package io.github.betapetey.eventtrail.events.platform;

/**
 * Whether a command is running on this thread (graves design r6, 1.7; rule 1: "EventTrail records no rows for anything
 * a command does"). Presence only, like {@link CommandCreditGuard}: it keeps no source, command text, entity, target or
 * time, only a per-thread depth.
 *
 * <p>{@link #inCommand()} is true when either the guard's depth around the server's command execution is open, or a
 * grave mod's own command method is running, marked by that mod's presence wrap through {@link #enterModCommand()} and
 * {@link #exitModCommand()}. A failure to read answers true, so an unreadable state records nothing.</p>
 */
public final class CommandPresence {

    /** Grave-mod command methods open on this thread; {@code null} outside every one. */
    private static final ThreadLocal<int[]> MOD_COMMAND_DEPTH = new ThreadLocal<>();

    private CommandPresence() {
    }

    /** True inside any command: the guard's scope, or a grave mod's own command method. */
    public static boolean inCommand() {
        try {
            if (CommandCreditGuard.active()) return true;
            int[] depth = MOD_COMMAND_DEPTH.get();
            return depth != null && depth[0] > 0;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return true;
        }
    }

    /**
     * Opens one mod-command depth on this thread. True only when it opened one; the wrap must then call
     * {@link #exitModCommand()} in a {@code finally}, and must not call it after a false return.
     */
    public static boolean enterModCommand() {
        try {
            int[] depth = MOD_COMMAND_DEPTH.get();
            if (depth == null) {
                depth = new int[1];
                MOD_COMMAND_DEPTH.set(depth);
            }
            depth[0]++;
            return true;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The mod's command still runs unchanged.
            return false;
        }
    }

    /** Closes one mod-command depth; the last close leaves nothing on the thread. A stray close cannot go negative. */
    public static void exitModCommand() {
        try {
            int[] depth = MOD_COMMAND_DEPTH.get();
            if (depth == null) return;
            depth[0]--;
            if (depth[0] <= 0) MOD_COMMAND_DEPTH.remove();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Closing never interrupts the command's own result or exception.
        }
    }
}
