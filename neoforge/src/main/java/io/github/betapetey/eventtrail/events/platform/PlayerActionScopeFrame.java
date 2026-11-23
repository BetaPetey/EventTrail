package io.github.betapetey.eventtrail.events.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * GF-SCOPE: one open player-action scope on the server thread (plain state, no world access, so it can be tested).
 * It keeps the first unmasked preimage of every position changed while it is open, the positions the native place
 * paths own, the cells produce was dropped in and the masks; at close {@link #plan} says what each position writes.
 */
final class PlayerActionScopeFrame {
    /** Positions checked per scope; further first touches are counted, never silently dropped. */
    static final int MAX_TOUCHES = 4096;
    /** Item-entity cells kept per scope (the part-removal collector's bound). */
    static final int MAX_DROP_CELLS = 256;

    /** One position in one level ({@code level} is the level object itself, compared by identity). */
    record Key(Object level, BlockPos position) {
        Key {
            Objects.requireNonNull(level, "level");
            position = Objects.requireNonNull(position, "position").immutable();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.level == level && key.position.equals(position);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(level) * 31 + position.hashCode();
        }
    }

    /**
     * The first unmasked touch of a position: its state before, and what the glue captured with it (the location
     * frame, the block-entity preimage and stored contents, the script detail). The glue's fields are opaque here.
     */
    static final class Touch {
        final Key key;
        final BlockState before;
        Object locationFrame;
        Object preimage;
        List<?> contents = List.of();
        String script;
        /**
         * Review-01 MAJOR-1: the state his own writes left when masked work (an explosion, a command, a suspended
         * neighbour callback or chunk task, bone meal, another player's nested action) first rewrote this position;
         * null while no masked write came. His row is planned from this state, never from the masked result.
         */
        BlockState partAfter;
        /** He wrote the position again after the masked write: his part cannot be told apart (no row, counted). */
        boolean mixed;
        /**
         * R2-3: the position was written while a real player's destroyBlock of that very position ran (the break
         * session's target, inside the BreakEvent a mod may cancel).
         */
        boolean destroyTarget;

        Touch(Key key, BlockState before) {
            this.key = key;
            this.before = before;
        }

        boolean tainted() {
            return partAfter != null;
        }
    }

    /**
     * What one position writes at close. {@code partOnly}: {@code after} is his part before a masked write, not the
     * position's state at close (so no block entity is read for it from the level).
     */
    record Planned(Touch touch, PlayerActionScopeRules.Outcome outcome, BlockState after, boolean partOnly) {
        Planned(Touch touch, PlayerActionScopeRules.Outcome outcome, BlockState after) {
            this(touch, outcome, after, false);
        }
    }

    /**
     * The plan of one scope: rows in first-touch order, the right-click harvests refused by a guard, and the changed
     * positions refused because they cannot be proven his (a masked write mixed with his own, his part under a masked
     * write that needs a block entity, an unproven conversion). {@code liquidLevel} is the part of {@code unproven}
     * that is a liquid block's own level change (review-02 NIT 4: counted the same, logged at debug level).
     */
    record Plan(List<Planned> rows, int harvestRefused, int unproven, int liquidLevel) {
    }

    final PlayerActionScopeRules.Kind kind;
    final Object clickedLevel;
    final BlockPos clicked;
    final Thread thread;
    private final Map<Key, Touch> touches = new LinkedHashMap<>();
    private final Set<Key> owned = new HashSet<>();
    private final Set<Key> dropCells = new HashSet<>();
    /** R2-3: the positions a real player's destroyBlock is breaking now, with their nesting depth. */
    private final Map<Key, Integer> destroying = new HashMap<>();
    private int suspended;
    private int foreign;
    private int foreignWrites;
    private boolean boneMeal;
    private boolean breakSession;
    private int touchesNotChecked;
    private int dropCellsNotKept;

    PlayerActionScopeFrame(PlayerActionScopeRules.Kind kind, Object clickedLevel, BlockPos clicked, Thread thread) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.clickedLevel = clickedLevel;
        this.clicked = clicked == null ? null : clicked.immutable();
        this.thread = thread;
        // A destroy scope is one destroyBlock call of its clicked position, open for the scope's whole life.
        if (kind == PlayerActionScopeRules.Kind.DESTROY && clickedLevel != null && this.clicked != null) {
            destroying.put(new Key(clickedLevel, this.clicked), 1);
        }
    }

    // ----- masks ----------------------------------------------------------------------------------------------------

    /**
     * A neighbour-changed callback of a block other than the clicked one, or a chunk task: no touch is kept. Shape
     * updates are never masked (a survival break removes a door's or bed's partner half through one).
     */
    void suspend() {
        suspended++;
    }

    void resume() {
        if (suspended > 0) suspended--;
    }

    boolean suspended() {
        return suspended > 0;
    }

    /** Whether a neighbour-changed callback at {@code position} must suspend the observer (not the clicked block). */
    boolean suspendsNeighbourUpdateAt(Object level, BlockPos position) {
        return clicked == null || level != clickedLevel || !clicked.equals(position);
    }

    /**
     * Review-01 MINOR-8: another real player's action runs nested inside this scope. It never joins: while it runs
     * no touch is kept, a position he already touched is tainted, and every block change it makes is counted.
     */
    void foreignBegin() {
        foreign++;
    }

    void foreignEnd() {
        if (foreign > 0) foreign--;
    }

    boolean foreign() {
        return foreign > 0;
    }

    /** One block change made by a nested other player's action: refused, counted at close. */
    void foreignWrite() {
        foreignWrites++;
    }

    int foreignWrites() {
        return foreignWrites;
    }

    /** Bone meal was used in this scope: growth after it writes no row (Owner, R2-1). */
    void boneMealUsed() {
        boneMeal = true;
    }

    boolean boneMealSeen() {
        return boneMeal;
    }

    /** A player break session (destroyBlock) ran inside this scope: its gains are GF-DROPPICK's, never withdrawals. */
    void breakSessionRan() {
        breakSession = true;
    }

    boolean hadBreakSession() {
        return breakSession;
    }

    /** R2-3: a real player's destroyBlock of {@code key} joined this scope; writes there are its target's. */
    void destroyBegin(Key key) {
        destroying.merge(key, 1, Integer::sum);
    }

    void destroyEnd(Key key) {
        destroying.computeIfPresent(key, (k, depth) -> depth > 1 ? depth - 1 : null);
    }

    boolean destroying(Key key) {
        return destroying.containsKey(key);
    }

    // ----- touches --------------------------------------------------------------------------------------------------

    /** True when a first touch of this position may be kept now (not masked, not seen, room left). */
    boolean wantsTouch(Key key) {
        if (suspended > 0 || foreign > 0 || boneMeal || touches.containsKey(key)) return false;
        if (touches.size() >= MAX_TOUCHES) {
            touchesNotChecked++;
            return false;
        }
        return true;
    }

    /** Whether this position already has his first touch. */
    boolean touched(Key key) {
        return touches.containsKey(key);
    }

    /**
     * Review-01 MAJOR-1: a later write that changes a position he already touched ({@code current} is the state
     * just before it). A masked write ({@code externallyMasked}: a command or an explosion removing blocks in that
     * level; or this frame's own masks: suspended, another player's nested action, bone meal) taints the touch and
     * keeps the state his own writes left; a write of his after that mixes the two.
     */
    void rewrite(Key key, boolean externallyMasked, BlockState current) {
        Touch touch = touches.get(key);
        if (touch == null || current == null) return;
        if (destroying.containsKey(key)) touch.destroyTarget = true;
        boolean masked = externallyMasked || suspended > 0 || foreign > 0 || boneMeal;
        if (!touch.tainted()) {
            if (masked) touch.partAfter = current;
        } else if (!masked) {
            touch.mixed = true;
        }
    }

    Touch touch(Key key, BlockState before) {
        Touch touch = new Touch(key, before);
        touch.destroyTarget = destroying.containsKey(key);
        touches.put(key, touch);
        return touch;
    }

    int touchesNotChecked() {
        return touchesNotChecked;
    }

    int touchCount() {
        return touches.size();
    }

    /** The placement boundary captured this position (a real player's place event inside the boundary). */
    void own(Key key) {
        owned.add(key);
    }

    /** The clicked position belongs to an exact bridge (the part-removal readers, p2gf-4 #7). */
    void ownClicked() {
        if (clicked != null && clickedLevel != null) owned.add(new Key(clickedLevel, clicked));
    }

    boolean owns(Key key) {
        return owned.contains(key);
    }

    /** An item entity joined the level in this cell while the scope was open. */
    void dropAt(Key cell) {
        if (dropCells.contains(cell)) return;
        if (dropCells.size() >= MAX_DROP_CELLS) {
            dropCellsNotKept++;
            return;
        }
        dropCells.add(cell);
    }

    boolean droppedAt(Key cell) {
        return dropCells.contains(cell);
    }

    int dropCellsNotKept() {
        return dropCellsNotKept;
    }

    boolean isClicked(Key key) {
        return clicked != null && key.level() == clickedLevel && clicked.equals(key.position());
    }

    // ----- close ----------------------------------------------------------------------------------------------------

    /**
     * What every touched position writes, in first-touch order. Positions the placement boundary captured are skipped
     * (they are the boundary's, whatever the multi-place setting). A position {@code alreadyWritten} (another
     * EventTrail writer wrote a block row there while the scope was open) is skipped, except that a harvest there
     * still writes the reset crop's place row. Refused harvests are counted, never written.
     *
     * <p>Review-01 MAJOR-1: a tainted position (masked work rewrote it after his touch) is planned from the state his
     * own writes left, so the masked result (an explosion's fire on a bed's cell) is never his. When his part cannot
     * be told apart (he wrote again after the masked write) or would need a block entity the level no longer holds,
     * the position writes no row and is counted in {@link Plan#unproven}; so is an unproven conversion.</p>
     *
     * @param finalState the state of a position at close
     * @param handedOver whether the player's inventory gained items during the call (produce handed over; it
     *                   counts only for the clicked position)
     */
    Plan plan(Function<Key, BlockState> finalState, Predicate<Touch> alreadyWritten, boolean handedOver) {
        List<Planned> rows = new ArrayList<>();
        int refused = 0;
        int unproven = 0;
        int liquidLevel = 0;
        for (Touch touch : touches.values()) {
            if (owned.contains(touch.key)) continue;
            if (touch.mixed) {
                unproven++;
                continue;
            }
            boolean partOnly = touch.tainted();
            BlockState after = partOnly ? touch.partAfter : finalState.apply(touch.key);
            boolean produce = droppedAt(touch.key) || (handedOver && isClicked(touch.key));
            PlayerActionScopeRules.Outcome outcome =
                    PlayerActionScopeRules.classify(touch.before, after, kind, produce, touch.destroyTarget);
            if (outcome == PlayerActionScopeRules.Outcome.NONE) continue;
            if (outcome == PlayerActionScopeRules.Outcome.HARVEST_REFUSED) {
                refused++;
                continue;
            }
            if (outcome == PlayerActionScopeRules.Outcome.UNPROVEN
                    || (partOnly && after.hasBlockEntity() && writesAfterState(outcome))) {
                unproven++;
                if (outcome == PlayerActionScopeRules.Outcome.UNPROVEN
                        && PlayerActionScopeRules.liquidLevelChange(touch.before, after)) liquidLevel++;
                continue;
            }
            if (alreadyWritten.test(touch)) {
                // A harvest whose ripe crop another writer broke (a mod's destroyBlock): only the reset crop is ours.
                if (outcome == PlayerActionScopeRules.Outcome.HARVEST) {
                    rows.add(new Planned(touch, PlayerActionScopeRules.Outcome.PLACE, after, partOnly));
                }
                continue;
            }
            rows.add(new Planned(touch, outcome, after, partOnly));
        }
        return new Plan(List.copyOf(rows), refused, unproven, liquidLevel);
    }

    /** Outcomes whose rows carry the state after (and its block entity): place, swap and the harvest's place. */
    private static boolean writesAfterState(PlayerActionScopeRules.Outcome outcome) {
        return outcome == PlayerActionScopeRules.Outcome.PLACE || outcome == PlayerActionScopeRules.Outcome.SWAP
                || outcome == PlayerActionScopeRules.Outcome.HARVEST;
    }
}
