package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.audit.ActionRecordBatch;
import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.BlockAuditPreimage;
import io.github.betapetey.eventtrail.events.evidence.ScopeBlockEvidenceNormalizer;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransactionRecordingPolicy;
import io.github.betapetey.eventtrail.inventory.core.EvidenceKind;
import io.github.betapetey.eventtrail.inventory.core.InventoryItemIdentity;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionEvidence;
import io.github.betapetey.eventtrail.inventory.core.InventoryTransactionNormalizer;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.items.IItemHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * GF-SCOPE: the player-action scope with a committed block-change observer.
 *
 * <p>A scope opens on the server thread for a real (listed, non-FakePlayer) ServerPlayer around his use, use-on,
 * player-action (start, abort and stop destroy, release), entity-interact (never an attack: review-01 MAJOR-3, Owner
 * One Killer) and destroyBlock calls, and around the tick and the client-sent move (lead ruling 1,
 * {@code handleMoveVehicle}) of a non-living vehicle he steers (Q30; a living mount, pet, summon or dragon is never
 * his scope, P2-K2, P2-K4). Nested scopes of the same player (or of a FakePlayer) join the outermost one; another
 * real player's nested action never joins (review-01 MINOR-8). While it is open, {@code Level#setBlock} hands the
 * first unmasked preimage of every position to the scope; at close every position whose block changed writes a
 * break, place or swap row (GF-BLOCKIDENTITY rule, block families, Q12 fluids, Q21 swaps, R2-1 harvests), credited to
 * the player as an immediate-secondary actor with the held item, clicked block or steered vehicle as detail.</p>
 *
 * <p>Masks (no touch is kept): neighbour-changed callbacks of blocks other than the clicked one (never shape
 * updates, which remove a door's or bed's partner half in a survival break), chunk tasks run
 * inside the call, everything after bone meal is used (R2-1), any command execution (Q4), any explosion removing
 * blocks in that level (GF-EXPLOSIONSESSION's flag) and a Building or Mining Gadget in hand (that bridge owns the
 * action). Positions the placement boundary captured and positions another EventTrail writer wrote a block row at while
 * the scope was open are skipped. A FakePlayer's work inside the scope is credited to the scope player, because the
 * scope is the proof; with no scope there is no row. A masked write at a position he already touched never becomes
 * his: his row is planned from the state his own writes left, or the position writes no row and is counted
 * (review-01 MAJOR-1).</p>
 *
 * <p>EventTrail's own failures are caught as {@code HookGuard} does ({@link Throwable}), so the game's call and the
 * other writers' offers always run; every failure is counted and logged through its own rate gate.</p>
 */
public final class PlayerActionScopes {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final RateLimitedDiagnosticGate WARNINGS = new RateLimitedDiagnosticGate(60_000);
    /** Review-01 MINOR-2: failures have their own gate, so a warning never hides a failure. */
    private static final RateLimitedDiagnosticGate FAILURES = new RateLimitedDiagnosticGate(60_000);

    /** At most this many rows per scope; the rest is counted and warned (plan review, ActionRecordBatch cap). */
    static final int MAX_ROWS = ActionRecordBatch.MAX_ROWS_PER_ACTION - 1;
    /** Bound on the stacks read from one block's storage at its first touch. */
    static final int MAX_HELD_STACKS = 256;
    static final String PROVIDER_ID = "eventtrail.scope";

    private static final ThreadLocal<Open> ACTIVE = new ThreadLocal<>();
    /** Open scopes on every thread: the hot paths read this first and return at once when it is zero. */
    private static final AtomicInteger OPEN = new AtomicInteger();
    private static final AtomicLong NEXT_SESSION = new AtomicLong();

    private static final LongAdder RIGHT_CLICK_HARVESTS_REFUSED = new LongAdder();
    private static final LongAdder ROWS_OVER_CAP = new LongAdder();
    private static final LongAdder NOT_CHECKED = new LongAdder();
    private static final LongAdder LOCATION_WITHHELD = new LongAdder();

    private static final StackWalker WALKER = StackWalker.getInstance();

    /** An open scope; closing it writes its rows once. Never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final Scope NOOP = () -> { };

    private static final class Open implements Scope {
        final PlayerActionScopeFrame frame;
        final ServerPlayer player;
        final UUID playerId;
        final String playerName;
        final String detail;
        final List<ItemStack> inventoryBefore;
        final Set<AuditLocation> blockRowsWritten = new HashSet<>();
        final Set<AuditLocation> withdrawalsWritten = new HashSet<>();
        boolean closed;

        Open(PlayerActionScopeFrame frame, ServerPlayer player, String detail, List<ItemStack> inventoryBefore) {
            this.frame = frame;
            this.player = player;
            this.playerId = player.getUUID();
            this.playerName = player.getName().getString();
            this.detail = detail;
            this.inventoryBefore = inventoryBefore;
        }

        @Override
        public void close() {
            PlayerActionScopes.close(this);
        }
    }

    /**
     * Review-01 MINOR-8: another real player's action nested inside an open scope. It never joins: the outer frame
     * keeps no touch while it runs and counts its block changes; closing ends that once.
     */
    private static final class Foreign implements Scope {
        private final PlayerActionScopeFrame outer;
        private boolean closed;

        Foreign(PlayerActionScopeFrame outer) {
            this.outer = outer;
            outer.foreignBegin();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                outer.foreignEnd();
            } catch (Throwable failure) {
                report("foreign", failure);
            }
        }
    }

    /**
     * R2-3: a real player's destroyBlock joined to his open scope. While it runs, writes at its position are that
     * break's (a mod's single slab set inside the BreakEvent it cancels); closing ends that once.
     */
    private static final class Destroying implements Scope {
        private final PlayerActionScopeFrame outer;
        private final PlayerActionScopeFrame.Key key;
        private boolean closed;

        Destroying(PlayerActionScopeFrame outer, PlayerActionScopeFrame.Key key) {
            this.outer = outer;
            this.key = key;
            outer.destroyBegin(key);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                outer.destroyEnd(key);
            } catch (Throwable failure) {
                report("destroy", failure);
            }
        }
    }

    /** What a nested open does with the outer scope (review-01 MINOR-8). */
    enum Nesting {
        /** The same player, or a FakePlayer (the scope is the proof): the inner call joins the outer scope. */
        JOIN,
        /** Another real player: never joins; refused and counted. */
        FOREIGN
    }

    /** Whether a nested open of {@code inner} joins the scope of {@code outer} (both by UUID). */
    static Nesting nesting(UUID outer, UUID inner, boolean innerSynthetic) {
        if (innerSynthetic || inner == null || inner.equals(outer)) return Nesting.JOIN;
        return Nesting.FOREIGN;
    }

    private PlayerActionScopes() {
    }

    /**
     * Registers the scope's three listeners. Place ownership and dropped produce skip cancelled events (a cancelled
     * place is undone; a cancelled join dropped nothing). Bone meal hears cancelled events too: a cancelled use may be
     * a mod's own growth, and growth after bone meal is never credited.
     */
    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.LOWEST, false, BlockEvent.EntityPlaceEvent.class, PlayerActionScopes::onPlace);
        bus.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, PlayerActionScopes::onEntityJoin);
        bus.addListener(EventPriority.LOWEST, true, BonemealEvent.class, PlayerActionScopes::onBoneMeal);
    }

    // ----- opening --------------------------------------------------------------------------------------------------

    public static Scope openUseOn(ServerPlayer player, ServerboundUseItemOnPacket packet) {
        try {
            BlockPos clicked = packet.getHitResult().getBlockPos();
            return open(PlayerActionScopeRules.Kind.USE_ON, player, clicked, packet.getHand(), null);
        } catch (Throwable failure) {
            report("open", failure);
            return NOOP;
        }
    }

    public static Scope openUse(ServerPlayer player, ServerboundUseItemPacket packet) {
        try {
            return open(PlayerActionScopeRules.Kind.USE, player, null, packet.getHand(), null);
        } catch (Throwable failure) {
            report("open", failure);
            return NOOP;
        }
    }

    /** Start, abort and stop destroy carry their block; release and the other actions have none. */
    public static Scope openPlayerAction(ServerPlayer player, ServerboundPlayerActionPacket packet) {
        try {
            ServerboundPlayerActionPacket.Action action = packet.getAction();
            boolean destroy = action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                    || action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
                    || action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK;
            return open(PlayerActionScopeRules.Kind.PLAYER_ACTION, player, destroy ? packet.getPos() : null,
                    InteractionHand.MAIN_HAND, null);
        } catch (Throwable failure) {
            report("open", failure);
            return NOOP;
        }
    }

    /**
     * The destroyBlock break session (also the delayed break of a later tick). Inside an open scope of the same
     * player (or for a FakePlayer) it only marks that a player break session ran (its gains are GF-DROPPICK's) and,
     * for a real player, that its position is a break target while it runs (R2-3); another real player's break
     * never joins (review-01 MINOR-8).
     */
    public static Scope openDestroy(ServerPlayer player, BlockPos position) {
        try {
            if (OPEN.get() > 0) {
                Open outer = ACTIVE.get();
                if (outer != null && !outer.closed) {
                    if (nesting(outer.playerId, player == null ? null : player.getUUID(),
                            player != null && NeoForgePlayerProvenance.isSynthetic(player)) == Nesting.FOREIGN) {
                        return new Foreign(outer.frame);
                    }
                    outer.frame.breakSessionRan();
                    // R2-3: a real player's break of that position (not a FakePlayer's) marks it as the target.
                    if (player != null && position != null && !NeoForgePlayerProvenance.isSynthetic(player)) {
                        return new Destroying(outer.frame,
                                new PlayerActionScopeFrame.Key(player.serverLevel(), position));
                    }
                    return NOOP;
                }
            }
            return open(PlayerActionScopeRules.Kind.DESTROY, player, position, InteractionHand.MAIN_HAND, null);
        } catch (Throwable failure) {
            report("open", failure);
            return NOOP;
        }
    }

    /**
     * Review-01 MAJOR-3: only the two interaction handlers open a scope. An attack ({@code onAttack}) opens none, so
     * a death's block effects in that call are never credited to the attacker (Owner, One Killer).
     */
    public static Scope openInteract(ServerPlayer player, ServerboundInteractPacket packet) {
        try {
            if (player == null || player.server == null || !player.server.isSameThread()) return NOOP;
            InteractionHand hand = interactionHand(packet);
            if (hand == null) return NOOP;
            String fallback = null;
            Entity target = packet.getTarget(player.serverLevel());
            if (target != null) fallback = "clicked " + BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
            return open(PlayerActionScopeRules.Kind.INTERACT, player, null, hand, fallback);
        } catch (Throwable failure) {
            report("open", failure);
            return NOOP;
        }
    }

    /** The hand of an interaction packet ({@code onInteraction} with or without a location); null for an attack. */
    static InteractionHand interactionHand(ServerboundInteractPacket packet) {
        if (packet == null) return null;
        InteractionHand[] hand = new InteractionHand[1];
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(InteractionHand used) {
                hand[0] = used;
            }

            @Override
            public void onInteraction(InteractionHand used, net.minecraft.world.phys.Vec3 location) {
                hand[0] = used;
            }

            @Override
            public void onAttack() {
                hand[0] = null;
            }
        });
        return hand[0];
    }

    /**
     * Q30: the tick of a non-living vehicle whose controlling passenger is a real player who moved it this tick.
     * A living vehicle (a mount, pet, summon or dragon) never opens a scope (P2-K2, P2-K4).
     */
    public static Scope openSteered(Entity vehicle) {
        try {
            if (vehicle == null || vehicle instanceof LivingEntity || !vehicle.isVehicle()) return NOOP;
            LivingEntity controller = vehicle.getControllingPassenger();
            if (!(controller instanceof ServerPlayer player)) return NOOP;
            if (player.zza == 0.0F && player.xxa == 0.0F) return NOOP;
            return open(PlayerActionScopeRules.Kind.STEERED, player, null, null,
                    "steered " + BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()));
        } catch (Throwable failure) {
            report("open", failure);
            return NOOP;
        }
    }

    /**
     * Lead ruling 1: the one {@code Entity#move(MoverType, Vec3)} call of {@code handleMoveVehicle}, where a
     * client-steered vehicle (a boat) moves and {@code tryCheckInsideBlocks} runs (a boat breaks a lily pad there).
     * It opens a STEERED scope only for a player move of a non-living vehicle whose controlling passenger is this
     * player and which is his root vehicle, through the same guards as every scope (server thread, a nested scope
     * joins, never a FakePlayer, gadgets, admitted players).
     */
    public static Scope openSteeredMove(ServerPlayer player, Entity vehicle, MoverType type) {
        try {
            if (player == null || vehicle == null) return NOOP;
            if (!steersMove(type, vehicle.getClass(), vehicle, vehicle.getControllingPassenger(),
                    player.getRootVehicle(), player)) return NOOP;
            return open(PlayerActionScopeRules.Kind.STEERED, player, null, null,
                    "steered " + BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()));
        } catch (Throwable failure) {
            report("open", failure);
            return NOOP;
        }
    }

    /**
     * Lead ruling 1's vehicle guards: a {@link MoverType#PLAYER} move of a vehicle that is not a {@link LivingEntity}
     * (a living mount never opens), controlled by this player, and his root vehicle.
     */
    static boolean steersMove(MoverType type, Class<?> vehicleClass, Object vehicle, Object controller,
                              Object rootVehicle, Object player) {
        return type == MoverType.PLAYER && vehicle != null && vehicleClass != null
                && !LivingEntity.class.isAssignableFrom(vehicleClass) && player != null && vehicle != player
                && controller == player && rootVehicle == vehicle;
    }

    /** How {@link #open} answers, in its guard order. */
    enum Admission {
        OPEN,
        /** A scope is already open on this thread: the inner call joins it (or, for another player, is foreign). */
        NESTED,
        REFUSED
    }

    /**
     * The guards every scope passes, in order, each asked only when the ones before it passed (the admission check
     * counts its refusals, so it is asked last): the server thread, no open scope, not a FakePlayer, no gadget in
     * hand, an admitted player.
     */
    static Admission admission(BooleanSupplier sameThread, BooleanSupplier scopeOpen, BooleanSupplier synthetic,
                               BooleanSupplier gadget, BooleanSupplier admitted) {
        if (!sameThread.getAsBoolean()) return Admission.REFUSED;
        if (scopeOpen.getAsBoolean()) return Admission.NESTED;
        if (synthetic.getAsBoolean()) return Admission.REFUSED; // a FakePlayer scope never opens
        if (gadget.getAsBoolean()) return Admission.REFUSED; // the gadget bridge owns the action
        if (!admitted.getAsBoolean()) return Admission.REFUSED; // counted there
        return Admission.OPEN;
    }

    private static Scope open(PlayerActionScopeRules.Kind kind, ServerPlayer player, BlockPos clicked,
                              InteractionHand hand, String fallbackDetail) {
        if (player == null) return NOOP;
        MinecraftServer server = player.server;
        if (server == null) return NOOP;
        Admission admission = admission(server::isSameThread, () -> OPEN.get() > 0 && ACTIVE.get() != null,
                () -> NeoForgePlayerProvenance.isSynthetic(player), () -> gadgetInHand(player),
                () -> NeoForgePlayerProvenance.isAdmittedPlayer(player));
        if (admission == Admission.NESTED) {
            Open outer = ACTIVE.get();
            if (outer == null || outer.closed) return NOOP;
            return nesting(outer.playerId, player.getUUID(), NeoForgePlayerProvenance.isSynthetic(player))
                    == Nesting.FOREIGN ? new Foreign(outer.frame) : NOOP; // the same player joins the outermost
        }
        if (admission != Admission.OPEN) return NOOP;
        ServerLevel level = player.serverLevel();
        String detail = detail(player, level, clicked, hand, fallbackDetail);
        List<ItemStack> inventoryBefore = kind.isUse() ? inventory(player) : null;
        Open open = new Open(new PlayerActionScopeFrame(kind, level, clicked, Thread.currentThread()), player, detail,
                inventoryBefore);
        ACTIVE.set(open);
        OPEN.incrementAndGet();
        return open;
    }

    private static String detail(ServerPlayer player, Level level, BlockPos clicked, InteractionHand hand,
                                 String fallback) {
        if (hand != null) {
            ItemStack held = player.getItemInHand(hand);
            if (!held.isEmpty()) return "held " + BuiltInRegistries.ITEM.getKey(held.getItem());
        }
        if (fallback != null) return fallback;
        if (clicked != null) return "clicked " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(clicked).getBlock());
        return "player action";
    }

    private static boolean gadgetInHand(ServerPlayer player) {
        try {
            for (InteractionHand hand : InteractionHand.values()) {
                String namespace = BuiltInRegistries.ITEM.getKey(player.getItemInHand(hand).getItem()).getNamespace();
                if (AdapterKey.BUILDING_GADGETS.modId().equals(namespace)
                        || AdapterKey.MINING_GADGETS.modId().equals(namespace)) return true;
            }
            return false;
        } catch (Throwable unreadable) {
            return true; // cannot tell: the scope stays shut (the side that never credits)
        }
    }

    private static List<ItemStack> inventory(ServerPlayer player) {
        var inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) stacks.add(inventory.getItem(slot).copy());
        return stacks;
    }

    // ----- the observer and its masks -------------------------------------------------------------------------------

    /**
     * Level#setBlock(BlockPos, BlockState, int, int) at its head: the first unmasked touch of a position; a later
     * write at a position he already touched taints it when masked (review-01 MAJOR-1); a block change of another
     * player's nested action is counted (review-01 MINOR-8).
     */
    public static void beforeSetBlock(Level level, BlockPos position, BlockState newState) {
        if (OPEN.get() == 0) return;
        try {
            Open open = ACTIVE.get();
            if (open == null || open.closed) return;
            if (!(level instanceof ServerLevel) || position == null) return;
            PlayerActionScopeFrame.Key key = new PlayerActionScopeFrame.Key(level, position);
            boolean touched = open.frame.touched(key);
            boolean foreign = open.frame.foreign();
            if (touched || foreign) {
                BlockState current = level.getBlockState(position);
                if (current == newState) return;
                if (foreign) open.frame.foreignWrite();
                if (touched) open.frame.rewrite(key, foreign || externallyMasked(level), current);
                return;
            }
            if (!open.frame.wantsTouch(key)) return;
            if (externallyMasked(level)) return;
            BlockState before = level.getBlockState(position);
            if (before == newState) return;
            PlayerActionScopeFrame.Touch touch = open.frame.touch(key, before);
            touch.locationFrame = AuditBlockLocations.capture(level, position).orElse(null);
            touch.preimage = BlockAuditPreimage.capture(System.currentTimeMillis(), level, position, before,
                    EventTrailManager.getInstance().canAcceptAuditWrite());
            if (open.frame.kind.isUse() && before.hasBlockEntity()) touch.contents = heldStacks(level, position, before);
            touch.script = scriptDetail();
        } catch (Throwable failure) {
            report("touch", failure);
        }
    }

    /** Masks the frame cannot see itself: a command running (Q4), or an explosion removing blocks in that level. */
    private static boolean externallyMasked(Level level) {
        return CommandCreditGuard.active() || ExplosionRemovalAudit.removingBlocksNow(level);
    }

    /** A neighbour-changed callback at {@code position}: suspends the observer unless it is the clicked block. */
    public static boolean suspendForNeighbourUpdate(Level level, BlockPos position) {
        if (OPEN.get() == 0) return false;
        try {
            Open open = ACTIVE.get();
            if (open == null || open.closed || !open.frame.suspendsNeighbourUpdateAt(level, position)) return false;
            open.frame.suspend();
            return true;
        } catch (Throwable failure) {
            report("suspend", failure);
            return false;
        }
    }

    /** A chunk task run inside the call (a chunk loaded by the action): always suspended. */
    public static boolean suspendForChunkTask() {
        if (OPEN.get() == 0) return false;
        try {
            Open open = ACTIVE.get();
            if (open == null || open.closed) return false;
            open.frame.suspend();
            return true;
        } catch (Throwable failure) {
            report("suspend", failure);
            return false;
        }
    }

    /** Ends one suspension that returned true. */
    public static void resume() {
        try {
            Open open = ACTIVE.get();
            if (open != null) open.frame.resume();
        } catch (Throwable failure) {
            report("resume", failure);
        }
    }

    /**
     * Called by EventTrailManager for every row offered while a scope is open on this thread, before the offer.
     * Never throws (review-01 MINOR-1: any failure, a StackOverflowError too, is caught and counted, so the offer
     * always runs).
     */
    public static void noteWritten(AuditRecord record) {
        if (OPEN.get() == 0 || record == null) return;
        try {
            Open open = ACTIVE.get();
            if (open == null || open.closed) return;
            note(open, record);
        } catch (Throwable failure) {
            report("note", failure);
        }
    }

    /** {@link #noteWritten(AuditRecord)} for one action's rows. */
    public static void noteWritten(List<AuditRecord> records) {
        if (OPEN.get() == 0 || records == null) return;
        try {
            Open open = ACTIVE.get();
            if (open == null || open.closed) return;
            for (AuditRecord record : records) note(open, record);
        } catch (Throwable failure) {
            report("note", failure);
        }
    }

    private static void note(Open open, AuditRecord record) {
        try {
            if (record == null || record.location() == null) return;
            switch (record.action()) {
                case BLOCK_BREAK, BLOCK_PLACE, EXPLOSION -> open.blockRowsWritten.add(record.location());
                case CONTAINER_WITHDRAWAL -> open.withdrawalsWritten.add(record.location());
                default -> { }
            }
        } catch (Throwable failure) {
            report("note", failure);
        }
    }

    private static Open active() {
        if (OPEN.get() == 0) return null;
        Open open = ACTIVE.get();
        return open == null || open.closed ? null : open;
    }

    /**
     * Lead ruling (p2gf-5 #3): positions the placement boundary captured belong to the boundary. Every position of a
     * real player's place event inside the boundary is owned, whatever {@code recording.blocks.multiPlace.enabled}
     * says, so the observer never adds a second row and never records a partner the setting leaves out. A place
     * event outside the boundary (a hoe, an axe, a shovel, flint and steel) owns nothing: the observer records it.
     */
    static void onPlace(BlockEvent.EntityPlaceEvent event) {
        Open open = active();
        if (open == null) return;
        try {
            if (!NeoForgePlacementAuditBridge.capturing()) return;
            if (!(event.getEntity() instanceof ServerPlayer player) || NeoForgePlayerProvenance.isSynthetic(player)
                    || !NeoForgePlayerProvenance.isListedPlayer(player)) return; // FakePlayer work stays the scope's
            List<BlockPos> members = new ArrayList<>();
            if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
                for (BlockSnapshot snapshot : multi.getReplacedBlockSnapshots()) members.add(snapshot.getPos());
            }
            own(open.frame, event.getLevel(), event.getPos(), members);
        } catch (Throwable failure) {
            report("place", failure);
        }
    }

    /** Owns a captured place event's primary position and every member position. */
    static void own(PlayerActionScopeFrame frame, Object level, BlockPos primary, List<BlockPos> members) {
        for (BlockPos member : members) frame.own(new PlayerActionScopeFrame.Key(level, member));
        frame.own(new PlayerActionScopeFrame.Key(level, primary));
    }

    static void onEntityJoin(EntityJoinLevelEvent event) {
        Open open = active();
        if (open == null) return;
        try {
            Entity entity = event.getEntity();
            if (entity instanceof ItemEntity item) {
                joined(open.frame, event.getLevel(), false,
                        BlockPos.containing(item.getX(), item.getY(), item.getZ()));
            } else if (entity instanceof PrimedTnt tnt) {
                joined(open.frame, event.getLevel(), true, tnt.blockPosition());
            }
        } catch (Throwable failure) {
            report("drop", failure);
        }
    }

    /**
     * An entity joined inside the scope: an item entity marks its cell as dropped produce; primed TNT (p2gf-4 #6)
     * makes its cell the TNT shape's (the ignition and explosion rows), so the observer writes no break row there.
     */
    static void joined(PlayerActionScopeFrame frame, Object level, boolean primedTnt, BlockPos cell) {
        PlayerActionScopeFrame.Key key = new PlayerActionScopeFrame.Key(level, cell);
        if (primedTnt) frame.own(key);
        else frame.dropAt(key);
    }

    /**
     * p2gf-4 #7: a part-removal use action is open at the clicked position, so it is that bridge's (its AE2,
     * Integrated Dynamics and Ender IO readers write the rows); the observer writes none there.
     */
    public static void ownClickedForBridge() {
        Open open = active();
        if (open == null) return;
        try {
            open.frame.ownClicked();
        } catch (Throwable failure) {
            report("own", failure);
        }
    }

    static void onBoneMeal(BonemealEvent event) {
        try {
            Open open = active();
            if (open != null) open.frame.boneMealUsed();
        } catch (Throwable failure) {
            report("bone meal", failure);
        }
    }

    private static List<ItemStack> heldStacks(Level level, BlockPos position, BlockState state) {
        List<ItemStack> held = new ArrayList<>();
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (blockEntity instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize() && held.size() < MAX_HELD_STACKS; slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) held.add(stack.copy());
            }
            return held;
        }
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, position, state, blockEntity, null);
        if (handler == null) return held;
        for (int slot = 0; slot < handler.getSlots() && held.size() < MAX_HELD_STACKS; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) held.add(stack.copy());
        }
        return held;
    }

    /** Q5/Q7: a KubeJS script or a Lychee recipe on the call stack of the touch. */
    private static String scriptDetail() {
        return WALKER.walk(frames -> scriptDetail(frames.limit(256).map(StackWalker.StackFrame::getClassName)));
    }

    static String scriptDetail(Stream<String> classNames) {
        Iterator<String> names = classNames.iterator();
        while (names.hasNext()) {
            String name = names.next();
            if (name.startsWith("dev.latvian.mods.kubejs.") || name.startsWith("dev.latvian.mods.rhino.")) {
                return "script KubeJS";
            }
            if (name.startsWith("snownee.lychee.")) return "recipe Lychee";
        }
        return null;
    }

    // ----- closing --------------------------------------------------------------------------------------------------

    private static void close(Open open) {
        if (open.closed) return;
        open.closed = true;
        try {
            if (ACTIVE.get() == open) ACTIVE.remove();
        } catch (Throwable failure) {
            report("close", failure);
        }
        OPEN.decrementAndGet();
        try {
            finish(open);
        } catch (Throwable failure) {
            report("close", failure);
        }
    }

    private static void finish(Open open) {
        PlayerActionScopeFrame frame = open.frame;
        int notChecked = frame.touchesNotChecked() + frame.dropCellsNotKept();
        if (notChecked > 0) {
            NOT_CHECKED.add(notChecked);
            warn("{} block changes or drops in one player action were not checked (more than {}); not recorded",
                    notChecked, PlayerActionScopeFrame.MAX_TOUCHES);
        }
        if (frame.foreignWrites() > 0) {
            NOT_CHECKED.add(frame.foreignWrites());
            warn("{} block changes of another player's action nested in one player action were refused (never"
                    + " credited to the outer player); not recorded", frame.foreignWrites());
        }
        if (frame.touchCount() == 0) return;
        Map<InventoryItemIdentity, PlayerActionScopeGains.Gain> gains = open.inventoryBefore == null
                ? Map.of()
                : PlayerActionScopeGains.gains(open.inventoryBefore, inventory(open.player));
        PlayerActionScopeFrame.Plan plan = frame.plan(
                key -> ((Level) key.level()).getBlockState(key.position()),
                touch -> alreadyWritten(open, touch),
                !gains.isEmpty());
        if (plan.harvestRefused() > 0) {
            RIGHT_CLICK_HARVESTS_REFUSED.add(plan.harvestRefused());
            warn("right-click harvest refused at {} positions in one player action (a use scope, a lower age or"
                    + " picked berries, and produce for that position are all needed); not recorded",
                    plan.harvestRefused());
        }
        if (plan.unproven() > 0) {
            NOT_CHECKED.add(plan.unproven());
            int warned = plan.unproven() - plan.liquidLevel();
            if (warned > 0) {
                warn("{} block changes in one player action could not be proven his (masked work such as an"
                        + " explosion, a command or a neighbour update changed the same position, an unproven"
                        + " conversion, or a fluid change that is no source put in or taken out); not recorded",
                        warned);
            }
            // Review-02 NIT 4: a liquid block's own level change (a water bucket into flowing water) is common; it is
            // counted above like the rest, and logged at debug level only.
            if (plan.liquidLevel() > 0) {
                LOGGER.debug("[EVENTTRAIL_SCOPE] {} liquid level changes in one player action could not be proven his;"
                        + " counted, not recorded", plan.liquidLevel());
            }
        }
        if (plan.rows().isEmpty()) return;
        if (!EventTrailManager.getInstance().admitCapture()) return;

        long now = System.currentTimeMillis();
        // Review-01 MINOR-9: one group per planned position (a harvest's break and place rows together) and per
        // withdrawal, so the row cap keeps or drops each group whole.
        List<List<AuditRecord>> groups = new ArrayList<>();
        List<PlayerActionScopeGains.Vanished> vanished = new ArrayList<>();
        for (PlayerActionScopeFrame.Planned planned : plan.rows()) {
            PlayerActionScopeFrame.Touch touch = planned.touch();
            Optional<AuditLocation> location = location(touch);
            if (location.isEmpty() || !(touch.preimage instanceof BlockAuditPreimage preimage)) {
                LOCATION_WITHHELD.increment();
                continue;
            }
            Level level = (Level) touch.key.level();
            BlockPos position = touch.key.position();
            switch (planned.outcome()) {
                case BREAK -> {
                    groups.add(List.of(row(open, touch, AuditAction.BLOCK_BREAK, location.get(), touch.before,
                            preimage, null, now)));
                    if (withdrawalCandidate(open, touch)) {
                        vanished.add(new PlayerActionScopeGains.Vanished(touch, ownItem(touch.before),
                                stacks(touch.contents)));
                    }
                }
                case PLACE -> groups.add(List.of(row(open, touch, AuditAction.BLOCK_PLACE, location.get(),
                        planned.after(), afterEvidence(level, position, planned, now), null, now)));
                case SWAP -> groups.add(List.of(row(open, touch, AuditAction.BLOCK_PLACE, location.get(),
                        planned.after(), afterEvidence(level, position, planned, now),
                        "replaced " + BuiltInRegistries.BLOCK.getKey(touch.before.getBlock()), now)));
                case HARVEST -> groups.add(List.of(
                        row(open, touch, AuditAction.BLOCK_BREAK, location.get(), touch.before, preimage,
                                "harvest", now),
                        row(open, touch, AuditAction.BLOCK_PLACE, location.get(), planned.after(),
                                afterEvidence(level, position, planned, now), "harvest", now)));
                // p2gf-4 #5 (Q12), review-01 MINOR-4: a fluid source put into or taken out of a block is a place or
                // break row of the fluid source, carrying the block it is in.
                case FLUID_PLACE -> groups.add(List.of(fluidRow(open, touch, AuditAction.BLOCK_PLACE,
                        location.get(), planned.after(),
                        PlayerActionScopeRules.fluidHolder(touch.before, planned.after()), now)));
                case FLUID_BREAK -> groups.add(List.of(fluidRow(open, touch, AuditAction.BLOCK_BREAK,
                        location.get(), touch.before,
                        PlayerActionScopeRules.fluidHolder(touch.before, planned.after()), now)));
                // R2-3: one break row of the slab removed from the double slab, at that position, for him.
                case SLAB_HALF_BREAK -> groups.add(List.of(row(open, touch, AuditAction.BLOCK_BREAK, location.get(),
                        PlayerActionScopeRules.brokenSlab(touch.before, planned.after()), preimage, "half slab",
                        now)));
                default -> { }
            }
        }
        groups.addAll(withdrawals(open, vanished, gains, now));
        Capped<AuditRecord> capped = capWhole(groups, MAX_ROWS);
        if (capped.dropped() > 0) {
            ROWS_OVER_CAP.add(capped.dropped());
            warn("{} rows of one player action were over the {}-row limit and were not recorded (a harvest's"
                    + " break and place rows are kept or dropped together)", capped.dropped(), MAX_ROWS);
        }
        if (capped.kept().isEmpty()) return;
        EventTrailManager.getInstance().tryLogAll(capped.kept());
    }

    /** The rows a scope offers under the cap, and the rows dropped. */
    record Capped<T>(List<T> kept, int dropped) {
    }

    /**
     * Review-01 MINOR-9: keeps whole groups in order while they fit in {@code max} rows; the first group that does
     * not fit and every group after it are dropped and counted, so a harvest's break and place rows are both kept or
     * both dropped.
     */
    static <T> Capped<T> capWhole(List<List<T>> groups, int max) {
        List<T> kept = new ArrayList<>();
        int dropped = 0;
        boolean full = false;
        for (List<T> group : groups) {
            if (group.isEmpty()) continue;
            if (!full && kept.size() + group.size() <= max) {
                kept.addAll(group);
            } else {
                full = true;
                dropped += group.size();
            }
        }
        return new Capped<>(List.copyOf(kept), dropped);
    }

    /**
     * The block entity evidence of a row's state after: read from the level when that state is the position's state
     * at close; absent when it is only his part before a masked write (review-01 MAJOR-1; the frame refuses a part
     * that would need a block entity).
     */
    private static BlockEntityEvidence afterEvidence(Level level, BlockPos position,
                                                     PlayerActionScopeFrame.Planned planned, long now) {
        if (planned.partOnly()) return BlockEntityEvidence.absent();
        return after(level, position, planned.after(), now).blockEntityEvidence();
    }

    private static boolean alreadyWritten(Open open, PlayerActionScopeFrame.Touch touch) {
        Level level = (Level) touch.key.level();
        if (open.blockRowsWritten.contains(new AuditLocation(level.dimension().location(), touch.key.position()))) {
            return true;
        }
        return location(touch).map(open.blockRowsWritten::contains).orElse(false);
    }

    private static Optional<AuditLocation> location(PlayerActionScopeFrame.Touch touch) {
        return touch.locationFrame instanceof AuditBlockLocations.Frame frame ? frame.currentLocation()
                : Optional.empty();
    }

    private static BlockAuditPreimage after(Level level, BlockPos position, BlockState state, long now) {
        return BlockAuditPreimage.capture(now, level, position, state,
                EventTrailManager.getInstance().canAcceptAuditWrite());
    }

    private static AuditRecord row(Open open, PlayerActionScopeFrame.Touch touch, AuditAction action,
                                   AuditLocation location, BlockState state, BlockAuditPreimage metadata,
                                   String rowDetail, long now) {
        return row(open, touch, action, location, state, metadata.blockEntityEvidence(), rowDetail, now);
    }

    private static AuditRecord row(Open open, PlayerActionScopeFrame.Touch touch, AuditAction action,
                                   AuditLocation location, BlockState state, BlockEntityEvidence metadata,
                                   String rowDetail, long now) {
        StringBuilder detail = new StringBuilder(open.detail);
        if (touch.script != null) detail.append("; ").append(touch.script);
        if (rowDetail != null) detail.append("; ").append(rowDetail);
        ActorProvenance actor = ActorProvenance.forPlayerIdentity(open.playerId, open.playerName, false,
                ActorProvenance.Causality.IMMEDIATE_SECONDARY, detail.toString()).orElseThrow();
        return ScopeBlockEvidenceNormalizer.committed(now, action, actor, location, state, metadata);
    }

    /**
     * The fluid source a state holds, as its own block (water for a waterlogged block), with the holding block as
     * detail ("waterlogged" on the waterlogging path, "fluidlogged" for any other fluid, review-01 MINOR-4).
     */
    private static AuditRecord fluidRow(Open open, PlayerActionScopeFrame.Touch touch, AuditAction action,
                                        AuditLocation location, BlockState holder, String word, long now) {
        return row(open, touch, action, location, PlayerActionScopeRules.fluidSource(holder),
                BlockEntityEvidence.absent(),
                word + " " + BuiltInRegistries.BLOCK.getKey(holder.getBlock()), now);
    }

    private static boolean withdrawalCandidate(Open open, PlayerActionScopeFrame.Touch touch) {
        return withdrawalCandidate(open.frame.kind, open.frame.hadBreakSession(), open.inventoryBefore != null,
                touch.before);
    }

    /**
     * C11 (Q11): only in a use scope with no player break session, and never for a crop (its produce is a pickup).
     * Lead ruling 2 (review-01 risk 1): a crop is any state with an age or berries, so a cave-vine body (berries, no
     * age) is never a withdrawal.
     */
    static boolean withdrawalCandidate(PlayerActionScopeRules.Kind kind, boolean breakSession, boolean inventoryKnown,
                                       BlockState before) {
        return kind.isUse() && !breakSession && inventoryKnown && !PlayerActionScopeRules.crop(before);
    }

    private static Item ownItem(BlockState state) {
        Item item = state.getBlock().asItem();
        return item == null || item == Items.AIR ? null : item;
    }

    private static List<ItemStack> stacks(List<?> contents) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Object content : contents) if (content instanceof ItemStack stack) stacks.add(stack);
        return stacks;
    }

    /** One group of rows per granted withdrawal (review-01 MINOR-9: the cap keeps or drops each grant whole). */
    private static List<List<AuditRecord>> withdrawals(Open open, List<PlayerActionScopeGains.Vanished> vanished,
                                                       Map<InventoryItemIdentity, PlayerActionScopeGains.Gain> gains,
                                                       long now) {
        if (vanished.isEmpty() || gains.isEmpty() || !withdrawalsEnabled()) return List.of();
        List<PlayerActionScopeGains.Grant> grants = PlayerActionScopeGains.allocate(vanished, gains);
        if (grants.isEmpty()) return List.of();
        long sessionId = NEXT_SESSION.incrementAndGet();
        if (sessionId <= 0L) return List.of();
        List<List<AuditRecord>> rows = new ArrayList<>();
        for (PlayerActionScopeGains.Grant grant : grants) {
            Optional<AuditLocation> found = location(grant.touch());
            if (found.isEmpty() || open.withdrawalsWritten.contains(found.get())
                    || !(grant.touch().preimage instanceof BlockAuditPreimage preimage)) continue;
            AuditLocation location = found.get();
            BlockPos position = location.position();
            String dimension = location.dimensionId().toString();
            ContainerTarget target = ContainerTarget.block(dimension, position,
                    DisplayNameResolver.capturedBlockStateDisplayName(preimage.canonicalBlockState().storageText(),
                            preimage.blockEntityEvidence()),
                    BackingContextKind.WORLD_BLOCK,
                    dimension + "|" + position.getX() + "," + position.getY() + "," + position.getZ());
            InventoryTransactionEvidence evidence = new InventoryTransactionEvidence(now, open.playerId,
                    open.playerName, false, sessionId, 1L, TransactionDirection.WITHDRAWAL,
                    InventoryItemIdentity.of(grant.sample()), grant.count(), target, PROVIDER_ID,
                    EvidenceKind.AUTHORITATIVE_MOD_OPERATION, InventorySlotRole.STORAGE);
            rows.add(List.copyOf(InventoryTransactionNormalizer.toAuditRecords(evidence,
                    EventTrailManager.getInstance().canAcceptAuditWrite())));
        }
        return rows;
    }

    private static boolean withdrawalsEnabled() {
        try {
            return ContainerTransactionRecordingPolicy.enabled(TransactionDirection.WITHDRAWAL);
        } catch (Throwable unreadable) {
            return false;
        }
    }

    // ----- counters -------------------------------------------------------------------------------------------------

    /** R2-1: right-click harvests refused by a guard (no row), since this process started. */
    public static long rightClickHarvestsRefused() {
        return RIGHT_CLICK_HARVESTS_REFUSED.sum();
    }

    /** Rows over the per-scope cap, since this process started. */
    public static long rowsOverCap() {
        return ROWS_OVER_CAP.sum();
    }

    /**
     * Block changes and drops never recorded because they could not be checked or proven his, since this process
     * started: over the per-scope bounds; another player's nested action (review-01 MINOR-8); masked work mixed with
     * his own or an unproven conversion (review-01 MAJOR-1, MAJOR-2); and every scope failure (review-01 MINOR-2).
     */
    public static long changesNotChecked() {
        return NOT_CHECKED.sum();
    }

    /** Rows withheld because the position's location could not be proven at close, since this process started. */
    public static long locationsWithheld() {
        return LOCATION_WITHHELD.sum();
    }

    private static void warn(String message, Object... arguments) {
        WARNINGS.acquire().ifPresent(ignored -> LOGGER.warn("[EVENTTRAIL_SCOPE] " + message, arguments));
    }

    /**
     * Review-01 MINOR-2: every failure is counted (never silent) and logged under its own rate gate, so a burst of
     * warnings cannot hide it; the report itself never throws into the game.
     */
    private static void report(String site, Throwable failure) {
        try {
            NOT_CHECKED.increment();
            FAILURES.acquire().ifPresent(ignored -> LOGGER.error(
                    "[EVENTTRAIL_SCOPE] Player-action scope {} failed ({}); counted, not recorded",
                    site, EventTrailDiagnostics.safeFailureSummary(failure)));
        } catch (Throwable ignored) {
            // The count above runs first; a logging failure must not reach the game.
        }
    }
}
