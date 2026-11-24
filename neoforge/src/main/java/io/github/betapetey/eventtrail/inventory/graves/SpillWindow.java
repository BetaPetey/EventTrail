package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.events.platform.CommandPresence;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The join windows of graves design r6, 1.6.4 and 3.1, read by {@link OriginJoinListener} for each item entity that
 * joins on the server thread while one is open on that thread. Windows nest; the innermost wins; each closes in a
 * {@code finally}.
 *
 * <ul>
 *   <li><b>Death window</b> (3.1): opened around a real player's death-loot call; every item entity that joins inside
 *   it is that death's drop and gets one {@code DEATH_DROPS} bucket of its count. It opens also inside a command (Q228
 *   A, 1.7's "death exception"): a label is not a row.</li>
 *   <li><b>Identity window</b> (1.6.4 fallback, for a spill path whose helper cannot be wrapped): it tags an entity only
 *   if its stack has the window's identity and fits the window's remaining count, from the innermost open origin
 *   ({@link SpillTags}). It is not opened inside a command or with no origin open.</li>
 * </ul>
 *
 * <p>A cancelled join is counted ({@code origin-join-cancelled}) and uses up nothing. A pending spill open at the same
 * time wins (rule 4): the window does nothing and {@code origin-window-overlap} is counted.</p>
 */
public final class SpillWindow {
    private static final ThreadLocal<ArrayDeque<Window>> WINDOWS = ThreadLocal.withInitial(ArrayDeque::new);

    private SpillWindow() {
    }

    private sealed interface Window permits DeathWindow, IdentityWindow {
    }

    private record DeathWindow(UUID owner, String name, long deathEpochMillis) implements Window {
    }

    private static final class IdentityWindow implements Window {
        final SpillTags.Frame frame;
        final InventoryItemIdentity identity;
        long remaining;

        IdentityWindow(SpillTags.Frame frame, InventoryItemIdentity identity, long remaining) {
            this.frame = frame;
            this.identity = identity;
            this.remaining = remaining;
        }
    }

    /**
     * Runs {@code body} exactly once inside a death window of the dead player ({@code owner}, {@code name}) at
     * {@code deathEpochMillis}. Opens also inside a command (Q228 A).
     */
    public static void death(UUID owner, String name, long deathEpochMillis, Runnable body) {
        Objects.requireNonNull(body, "body");
        Window window = null;
        try {
            window = new DeathWindow(Objects.requireNonNull(owner, "owner"), name, deathEpochMillis);
            WINDOWS.get().push(window);
        } catch (Throwable unreadable) {
            window = null;
        }
        run(window, body);
    }

    /**
     * Runs {@code body} exactly once inside an identity window of {@code identity} and at most {@code maxUnits} units,
     * tagged from the innermost open origin. Not opened inside a command or with no origin open.
     */
    public static void identity(InventoryItemIdentity identity, long maxUnits, Runnable body) {
        Objects.requireNonNull(body, "body");
        Window window = null;
        try {
            SpillTags.Frame frame = SpillTags.currentFrame();
            if (identity != null && maxUnits > 0L && frame != null && !CommandPresence.inCommand()) {
                window = new IdentityWindow(frame, identity, maxUnits);
                WINDOWS.get().push(window);
            }
        } catch (Throwable unreadable) {
            window = null;
        }
        run(window, body);
    }

    /** Whether any window is open on this thread. */
    public static boolean open() {
        try {
            return !WINDOWS.get().isEmpty();
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /**
     * One item entity's join on this thread (the listener's core, test seam): {@code stack} is the entity's stack,
     * {@code persistentData} its persistent data, {@code cancelled} whether the join was cancelled.
     */
    static void onJoin(ItemStack stack, CompoundTag persistentData, HolderLookup.Provider registries,
            boolean cancelled) {
        Window window = WINDOWS.get().peek();
        if (window == null || stack == null || stack.isEmpty()) return;
        if (SpillTags.pendingOpen()) {
            GraveCounts.count(GraveCounts.Reason.ORIGIN_WINDOW_OVERLAP);
            return;
        }
        if (window instanceof DeathWindow death) {
            if (cancelled) {
                GraveCounts.count(GraveCounts.Reason.ORIGIN_JOIN_CANCELLED);
                return;
            }
            OriginLabelCodec.write(persistentData, OriginLabel.build(OriginLabel.itemId(stack),
                    OriginLabel.componentsSha256(stack, registries),
                    List.of(OriginLabel.deathBucket(death.owner(), death.name(), death.deathEpochMillis(),
                            stack.getCount())), 0, 0));
            return;
        }
        IdentityWindow identity = (IdentityWindow) window;
        if (stack.getCount() > identity.remaining || !identity.identity.equals(InventoryItemIdentity.of(stack))) return;
        if (cancelled) {
            GraveCounts.count(GraveCounts.Reason.ORIGIN_JOIN_CANCELLED);
            return;
        }
        SpillTags.Plan plan = SpillTags.plan(identity.frame, SpillTags.Kind.GRAVE, null, stack, registries);
        OriginLabelCodec.write(persistentData, plan.label());
        SpillTags.commit(plan);
        identity.remaining -= stack.getCount();
    }

    private static void run(Window window, Runnable body) {
        if (window == null) {
            body.run();
            return;
        }
        try {
            body.run();
        } finally {
            try {
                ArrayDeque<Window> windows = WINDOWS.get();
                if (windows.peek() == window) {
                    windows.pop();
                } else {
                    windows.removeFirstOccurrence(window);
                }
            } catch (Throwable ignored) {
                // Closing never interrupts the body's own result or exception.
            }
        }
    }
}
