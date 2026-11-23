package io.github.betapetey.eventtrail.events;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GF-NESTED: the per-click proof that vanilla's item-click override step handled a click (the step inside
 * {@code AbstractContainerMenu#doClick} that fires NeoForge's ItemStackedOnOtherEvent and then calls
 * {@code ItemStack#overrideStackedOnOther} and {@code #overrideOtherStackedOnMe}).
 *
 * <p>Recorded by {@code AbstractContainerMenuItemOverrideMixin} only for a {@link ServerPlayer} on the server thread
 * while that player's action-scoped physical action on this menu is open, and bound to that action. The tracker takes
 * it at the end of the same action ({@link #take}), which always clears it; a recording for another action, player or
 * menu is never returned, so nothing is carried across clicks or players. A second override step inside one action
 * (a nested or repeated call) poisons the proof, a step that returned false gives none, and a step whose original call
 * threw is never ended, so it gives none either; the click bracket's failure exit then drops it ({@link #clearThread}).</p>
 *
 * <p>Never throws into vanilla: every recording failure is caught, counted ({@link #recordingFailures()}) and logged
 * (one rate-limited WARN), and that click's proof is discarded, so no row comes from a half-recorded proof.</p>
 */
public final class ItemClickOverrideProof {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final long RECORDING_FAILED_INTERVAL_MILLIS = 60_000;
    /**
     * At most one "item-click override proof failed" WARN per minute, with the count suppressed since. A fresh gate at
     * every server start and stop ({@link #resetRuntime}).
     */
    private static volatile RateLimitedDiagnosticGate recordingFailed =
            new RateLimitedDiagnosticGate(RECORDING_FAILED_INTERVAL_MILLIS);
    private static final AtomicLong RECORDING_FAILURES = new AtomicLong();
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private ItemClickOverrideProof() {
    }

    /** The tracker's open action a proof is bound to. */
    public record ActionKey(long sessionId, long actionSequence) {
    }

    /**
     * What one handled override step saw: the clicked slot (its menu index, and whether it is a slot of the player's
     * own inventory), and copies of the clicked and carried stacks before and after the step.
     */
    public record Proof(
            int menuSlotIndex,
            boolean playerInventorySlot,
            ItemStack clickedBefore,
            ItemStack clickedAfter,
            ItemStack carriedBefore,
            ItemStack carriedAfter) {
        public Proof {
            clickedBefore = Objects.requireNonNull(clickedBefore, "clickedBefore");
            clickedAfter = Objects.requireNonNull(clickedAfter, "clickedAfter");
            carriedBefore = Objects.requireNonNull(carriedBefore, "carriedBefore");
            carriedAfter = Objects.requireNonNull(carriedAfter, "carriedAfter");
        }
    }

    /** One override step being recorded; {@code null} from {@link #begin} means nothing is recorded. */
    public static final class Recording {
        private final State state;
        private final Slot slot;
        private final int menuSlotIndex;
        private final boolean playerInventorySlot;
        private final ItemStack clickedBefore;
        private final ItemStack carriedBefore;

        private Recording(State state, Slot slot, int menuSlotIndex, boolean playerInventorySlot,
                ItemStack clickedBefore, ItemStack carriedBefore) {
            this.state = state;
            this.slot = slot;
            this.menuSlotIndex = menuSlotIndex;
            this.playerInventorySlot = playerInventorySlot;
            this.clickedBefore = clickedBefore;
            this.carriedBefore = carriedBefore;
        }
    }

    private static final class State {
        private final ServerPlayer player;
        private final AbstractContainerMenu menu;
        /** Null only when the action could not be read; such a state proves nothing and poisons its click. */
        private final ActionKey key;
        private Proof proof;
        private boolean poisoned;

        private State(ServerPlayer player, AbstractContainerMenu menu, ActionKey key) {
            this.player = player;
            this.menu = menu;
            this.key = key;
        }

        private boolean sameAction(ServerPlayer otherPlayer, AbstractContainerMenu otherMenu, ActionKey otherKey) {
            return player == otherPlayer && menu == otherMenu && (key == null || key.equals(otherKey));
        }

        private void poison() {
            poisoned = true;
            proof = null;
        }
    }

    /** Called by the hook before the override step runs. Never throws. */
    public static Recording begin(
            Player player, AbstractContainerMenu menu, Slot slot, ItemStack clicked, ItemStack carried) {
        State failedState = null;
        try {
            if (!(player instanceof ServerPlayer serverPlayer) || menu == null || slot == null) return null;
            MinecraftServer server = serverPlayer.getServer();
            if (server == null || !server.isSameThread()) return null;
            State prior = CURRENT.get();
            failedState = new State(serverPlayer, menu, null);
            Optional<ActionKey> key = UniversalContainerTracker.openPhysicalAction(serverPlayer, menu);
            if (key.isEmpty()) {
                // No tracked action: nothing to prove, and a leftover from an earlier click is dropped.
                CURRENT.remove();
                return null;
            }
            if (prior != null && prior.sameAction(serverPlayer, menu, key.orElseThrow())) {
                // A second override step in one action: the proof could not say which step moved what.
                prior.poison();
                return null;
            }
            State state = new State(serverPlayer, menu, key.orElseThrow());
            failedState = state;
            CURRENT.set(state);
            int menuSlotIndex = menuSlotIndex(menu, slot);
            if (menuSlotIndex < 0) {
                state.poison();
                return null;
            }
            boolean playerInventorySlot = slot.container instanceof Inventory inventory && inventory.player == player;
            return new Recording(state, slot, menuSlotIndex, playerInventorySlot,
                    copy(clicked), copy(carried));
        } catch (Throwable failure) {
            discard(failedState, failure);
            return null;
        }
    }

    /** Called by the hook after the override step returned normally. Never throws. */
    public static void end(Recording recording, boolean handled) {
        if (recording == null) return;
        State state = recording.state;
        try {
            if (CURRENT.get() != state || state.poisoned || !handled) return;
            if (state.proof != null) {
                state.poison();
                return;
            }
            state.proof = new Proof(
                    recording.menuSlotIndex,
                    recording.playerInventorySlot,
                    recording.clickedBefore,
                    copy(recording.slot.getItem()),
                    recording.carriedBefore,
                    copy(state.menu.getCarried()));
        } catch (Throwable failure) {
            discard(state, failure);
        }
    }

    /**
     * The tracker, at the end of an action: the proof recorded for exactly this action, if any. Always clears the
     * calling thread's recording.
     */
    static Optional<Proof> take(ServerPlayer player, AbstractContainerMenu menu, ActionKey key) {
        State state = CURRENT.get();
        CURRENT.remove();
        if (state == null || key == null || state.key == null || !state.sameAction(player, menu, key)) {
            return Optional.empty();
        }
        if (state.poisoned) {
            LOGGER.debug("An item-click override proof was discarded (a second override step or a failed recording)");
            return Optional.empty();
        }
        return Optional.ofNullable(state.proof);
    }

    /** Recording failures since the last server start or stop ({@link #resetRuntime}). */
    public static long recordingFailures() {
        return RECORDING_FAILURES.get();
    }

    /**
     * Server start and stop ({@code UniversalContainerTracker.clearRuntimeState}): the failure count restarts at zero,
     * the WARN gate is fresh, and the calling thread's recording is dropped.
     */
    static void resetRuntime() {
        RECORDING_FAILURES.set(0L);
        recordingFailed = new RateLimitedDiagnosticGate(RECORDING_FAILED_INTERVAL_MILLIS);
        CURRENT.remove();
    }

    /**
     * The tracker, when a click threw ({@code UniversalContainerTracker.menuActionFailed}): the step's recording is
     * dropped, so the thread keeps no player or menu reference. Never throws.
     */
    static void clearThread() {
        CURRENT.remove();
    }

    private static void discard(State state, Throwable failure) {
        try {
            if (state != null) {
                state.poison();
                CURRENT.set(state);
            } else {
                CURRENT.remove();
            }
            RECORDING_FAILURES.incrementAndGet();
            recordingFailed.acquire().ifPresent(emission -> LOGGER.warn(
                    "EventTrail could not record an item-click override step ({}); that click's nested transfer is "
                            + "not recorded; suppressed {} such failure(s) since the last warning",
                    EventTrailDiagnostics.safeFailureSummary(failure), emission.suppressedSinceLastEmission()));
        } catch (Throwable ignored) {
            // Never thrown into vanilla: the click's proof is already discarded (or there was none).
        }
    }

    private static int menuSlotIndex(AbstractContainerMenu menu, Slot slot) {
        for (int index = 0; index < menu.slots.size(); index++) {
            if (menu.slots.get(index) == slot) return index;
        }
        return -1;
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }
}
