package io.github.betapetey.eventtrail.events.gadgets;

import com.direwolf20.mininggadgets.common.tiles.RenderBlockTileEntity;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Exact Mining Gadgets 1.18.7 producer. Saved ownership never grants authority. */
public final class MiningGadgetAuditBridge {
    private static final int MAX_ACTIVE_USES = 256;
    private static final Map<ServerPlayer, UseGrant> USES = new IdentityHashMap<>();
    private static final ThreadLocal<TickBoundary> ACTIVE_TICK = new ThreadLocal<>();
    private static final ThreadLocal<RemovalBoundary> ACTIVE_REMOVAL = new ThreadLocal<>();

    static {
        GadgetAuditRuntime.registerLifecycle(AdapterKey.MINING_GADGETS, MiningGadgetAuditBridge::tick,
                MiningGadgetAuditBridge::clear, MiningGadgetAuditBridge::logout);
    }

    private MiningGadgetAuditBridge() {}

    public static UseBoundary beginUse(Level level, Player player, InteractionHand hand) {
        return observe(() -> beginUseObserved(level, player, hand));
    }

    private static UseBoundary beginUseObserved(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) return null;
        stopUsing(serverPlayer);
        if (GadgetAuditRuntime.currentInputActor() != serverPlayer
                || level != serverPlayer.level() || serverPlayer.isUsingItem()) return null;
        ItemStack stack = serverPlayer.getItemInHand(hand);
        return stack.isEmpty() ? null : new UseBoundary(serverPlayer, level, hand, stack);
    }

    public static void finishUse(UseBoundary boundary, boolean returnedNormally) {
        observeRun(() -> finishUseObserved(boundary, returnedNormally));
    }

    private static void finishUseObserved(UseBoundary boundary, boolean returnedNormally) {
        if (boundary == null || !returnedNormally
                || boundary.player.level() != boundary.level
                || !boundary.player.isUsingItem()
                || boundary.player.getUsedItemHand() != boundary.hand
                || boundary.player.getUseItem() != boundary.stack
                || boundary.player.getItemInHand(boundary.hand) != boundary.stack
                || USES.size() >= MAX_ACTIVE_USES) return;
        // The enclosing packet seals this provisional authority after it returns.
        var authority = GadgetAuditRuntime.captureInput(boundary.player, "mininggadgets");
        if (authority != null) USES.put(boundary.player,
                new UseGrant(authority, boundary.hand, boundary.stack));
    }

    /** Every ordinary stop invalidates prior members; precision finishes this tick. */
    public static void stopUsing(LivingEntity entity) {
        observeRun(() -> stopUsingObserved(entity));
    }

    private static void stopUsingObserved(LivingEntity entity) {
        if (!(entity instanceof ServerPlayer player)) return;
        UseGrant grant = USES.remove(player);
        TickBoundary active = ACTIVE_TICK.get();
        if (active != null && active.grant.authority.player() == player) {
            if (active.precisionStop) {
                active.invalidateOnClose = true;
                return;
            }
            active.valid = false;
        }
        if (grant != null) GadgetAuditRuntime.invalidateOperation(grant.authority);
    }

    public static TickBoundary beginTick(Level level, LivingEntity entity, ItemStack stack) {
        return observe(() -> beginTickObserved(level, entity, stack));
    }

    private static TickBoundary beginTickObserved(Level level, LivingEntity entity, ItemStack stack) {
        TickBoundary previous = ACTIVE_TICK.get();
        if (previous != null) {
            previous.valid = false;
            return null;
        }
        if (!(entity instanceof ServerPlayer player)) return null;
        UseGrant grant = USES.get(player);
        if (grant == null) return null;
        if (level != grant.authority.level() || stack != grant.stack || !activeUse(grant)) {
            stopUsing(player);
            return null;
        }
        TickBoundary boundary = new TickBoundary(grant);
        ACTIVE_TICK.set(boundary);
        return boundary;
    }

    public static void finishTick(TickBoundary boundary, boolean returnedNormally) {
        try {
            observeRun(() -> finishTickObserved(boundary, returnedNormally));
        } finally {
            if (boundary != null && ACTIVE_TICK.get() == boundary) ACTIVE_TICK.remove();
        }
    }

    private static void finishTickObserved(TickBoundary boundary, boolean returnedNormally) {
        if (boundary == null) return;
        if (ACTIVE_TICK.get() != boundary) boundary.valid = false;
        ACTIVE_TICK.remove();
        if (!returnedNormally || !boundary.valid) {
            for (BlockEntity member : boundary.created) GadgetAuditRuntime.invalidate(member);
        }
        if (boundary.invalidateOnClose || !returnedNormally || !boundary.valid) {
            USES.remove(boundary.grant.authority.player());
            GadgetAuditRuntime.invalidateOperation(boundary.grant.authority);
        }
        boundary.staged.clear();
        boundary.created.clear();
        boundary.valid = false;
    }

    /** Only the authenticated ordinal-zero placeholder call enters this method. */
    public static boolean installPlaceholder(Level level, BlockPos pos, BlockState target,
                                             BooleanSupplier original) {
        TickBoundary tick = observe(MiningGadgetAuditBridge::currentTick);
        var candidate = observe(() -> tick == null || level != tick.grant.authority.level()
                ? null : GadgetAuditRuntime.prepare(tick.grant.authority, pos));
        boolean changed = original.getAsBoolean();
        observeRun(() -> {
            if (candidate != null && changed && currentTick() == tick) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof RenderBlockTileEntity render
                        && level.getBlockState(pos).equals(target)) {
                    tick.staged.put(render, candidate);
                }
            }
        });
        return changed;
    }

    /** Called after the exact mod initializer has assigned its saved owner. */
    public static void initialized(RenderBlockTileEntity placeholder, Player player) {
        observeRun(() -> initializedObserved(placeholder, player));
    }

    private static void initializedObserved(RenderBlockTileEntity placeholder, Player player) {
        TickBoundary tick = currentTick();
        // Any owner reassignment, including one back to the same UUID, invalidates
        // the earlier operation. Only our exact creation can attach a fresh one.
        GadgetAuditRuntime.invalidate(placeholder);
        if (tick == null) return;
        var candidate = tick.staged.remove(placeholder);
        if (candidate == null || player != tick.grant.authority.player()
                || !player.getUUID().equals(placeholder.getPlayerUUID())) return;
        if (GadgetAuditRuntime.attach(candidate, placeholder, placeholder.getRenderBlock(),
                Blocks.AIR.defaultBlockState(), GadgetAuditRuntime.Kind.BREAK)) {
            tick.created.add(placeholder);
        }
    }

    /** An unadmitted or different user touching a member destroys its old grant. */
    public static void continuing(RenderBlockTileEntity placeholder, ItemStack stack) {
        observeRun(() -> continuingObserved(placeholder, stack));
    }

    private static void continuingObserved(RenderBlockTileEntity placeholder, ItemStack stack) {
        TickBoundary tick = currentTick();
        if (tick == null || tick.grant.stack != stack
                || !tick.grant.authority.player().getUUID().equals(placeholder.getPlayerUUID())
                || !GadgetAuditRuntime.belongsTo(placeholder, tick.grant.authority.player())) {
            GadgetAuditRuntime.invalidate(placeholder);
        }
    }

    public static void precisionStop(Player player, Runnable original) {
        TickBoundary tick = observe(MiningGadgetAuditBridge::currentTick);
        if (tick == null || tick.grant.authority.player() != player) {
            original.run();
            return;
        }
        tick.precisionStop = true;
        try {
            original.run();
        } finally {
            tick.precisionStop = false;
        }
    }

    public static RemovalBoundary beginRemoval(RenderBlockTileEntity placeholder) {
        return observe(() -> beginRemovalObserved(placeholder));
    }

    private static RemovalBoundary beginRemovalObserved(RenderBlockTileEntity placeholder) {
        RemovalBoundary prior = ACTIVE_REMOVAL.get();
        if (prior != null) {
            prior.valid = false;
            GadgetAuditRuntime.invalidate(placeholder);
            return null;
        }
        TickBoundary tick = currentTick();
        if (tick == null
                || !tick.grant.authority.player().getUUID().equals(placeholder.getPlayerUUID())
                || !GadgetAuditRuntime.belongsTo(placeholder, tick.grant.authority.player())) {
            GadgetAuditRuntime.invalidate(placeholder);
            return null;
        }
        var claim = GadgetAuditRuntime.beginCommit(placeholder);
        if (claim == null) return null;
        if (claim.kind() != GadgetAuditRuntime.Kind.BREAK
                || !claim.authority().operationId().equals(tick.grant.authority.operationId())
                || !claim.preimage().blockState().equals(placeholder.getRenderBlock())) {
            GadgetAuditRuntime.finish(claim, false, false);
            return null;
        }
        RemovalBoundary boundary = new RemovalBoundary(tick, placeholder, claim,
                placeholder.getBlockState());
        ACTIVE_REMOVAL.set(boundary);
        return boundary;
    }

    /** Recheck after the mod's permission and drop callbacks, before BE removal. */
    public static void removingBlockEntity(Level level, BlockPos pos) {
        observeRun(() -> removingBlockEntityObserved(level, pos));
    }

    private static void removingBlockEntityObserved(Level level, BlockPos pos) {
        RemovalBoundary boundary = ACTIVE_REMOVAL.get();
        if (boundary == null) return;
        if (!currentRemoval(boundary, level, pos)
                || boundary.removalBoundarySeen
                || level.getBlockEntity(pos) != boundary.placeholder
                || !boundary.tick.grant.authority.player().getUUID().equals(boundary.placeholder.getPlayerUUID())
                || !boundary.claim.preimage().blockState().equals(boundary.placeholder.getRenderBlock())
                || !level.getBlockState(pos).equals(boundary.placeholderState)) {
            boundary.valid = false;
            return;
        }
        boundary.removalBoundarySeen = true;
    }

    public static boolean removeWorldBlock(Level level, BlockPos pos, BlockState target,
                                            BooleanSupplier original) {
        RemovalBoundary boundary = ACTIVE_REMOVAL.get();
        boolean eligible = Boolean.TRUE.equals(observe(() -> boundary != null && currentRemoval(boundary, level, pos)
                && boundary.removalBoundarySeen && !boundary.setterSeen
                && target.equals(Blocks.AIR.defaultBlockState())
                && level.getBlockState(pos).equals(boundary.placeholderState)));
        if (boundary != null) {
            if (!eligible) boundary.valid = false;
            boundary.setterSeen = true;
        }
        boolean changed = original.getAsBoolean();
        if (eligible) boundary.mutationSucceeded = changed;
        return changed;
    }

    public static void finishRemoval(RemovalBoundary boundary, boolean returnedNormally) {
        try {
            observeRun(() -> finishRemovalObserved(boundary, returnedNormally));
        } finally {
            if (boundary != null && ACTIVE_REMOVAL.get() == boundary) ACTIVE_REMOVAL.remove();
        }
    }

    private static void finishRemovalObserved(RemovalBoundary boundary, boolean returnedNormally) {
        if (boundary == null) return;
        if (ACTIVE_REMOVAL.get() != boundary) boundary.valid = false;
        ACTIVE_REMOVAL.remove();
        GadgetAuditRuntime.finish(boundary.claim,
                boundary.valid && boundary.mutationSucceeded
                        && currentTick() == boundary.tick,
                returnedNormally);
        boundary.valid = false;
    }

    public static void reset(BlockEntity placeholder) {
        observeRun(() -> GadgetAuditRuntime.invalidate(placeholder));
    }

    private static <T> T observe(Supplier<T> observation) {
        try {
            return observation.get();
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.MINING_GADGETS, "bridge:MiningGadgetAuditBridge.observe", failure);
            invalidateCurrentObservation();
            return null;
        }
    }

    private static void observeRun(Runnable observation) {
        observe(() -> { observation.run(); return Boolean.TRUE; });
    }

    private static void invalidateCurrentObservation() {
        TickBoundary tick = ACTIVE_TICK.get();
        if (tick != null) {
            tick.valid = false;
            try { GadgetAuditRuntime.invalidateOperation(tick.grant.authority); }
            catch (RuntimeException | LinkageError failure) {
                AdapterFailSafe.report(AdapterKey.MINING_GADGETS, "bridge:MiningGadgetAuditBridge.invalidateOperation", failure);
            }
        }
        RemovalBoundary removal = ACTIVE_REMOVAL.get();
        if (removal != null) removal.valid = false;
    }

    private static boolean currentRemoval(RemovalBoundary boundary, Level level, BlockPos pos) {
        return boundary.valid && currentTick() == boundary.tick
                && boundary.claim.authority().current()
                && level == boundary.claim.authority().level()
                && pos.equals(boundary.claim.preimage().position());
    }

    private static TickBoundary currentTick() {
        TickBoundary tick = ACTIVE_TICK.get();
        return tick != null && tick.valid && tick.grant.authority.current()
                && tick.grant.authority.player().getItemInHand(tick.grant.hand) == tick.grant.stack
                ? tick : null;
    }

    private static boolean activeUse(UseGrant grant) {
        ServerPlayer player = grant.authority.player();
        return grant.authority.current() && player.isUsingItem()
                && player.getUsedItemHand() == grant.hand && player.getUseItem() == grant.stack
                && player.getItemInHand(grant.hand) == grant.stack;
    }

    private static void tick() {
        for (var iterator = USES.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (!activeUse(entry.getValue())) {
                GadgetAuditRuntime.invalidateOperation(entry.getValue().authority);
                iterator.remove();
            }
        }
    }

    private static void clear() {
        USES.clear();
        TickBoundary tick = ACTIVE_TICK.get();
        if (tick != null) tick.valid = false;
        RemovalBoundary removal = ACTIVE_REMOVAL.get();
        if (removal != null) removal.valid = false;
        ACTIVE_TICK.remove();
        ACTIVE_REMOVAL.remove();
    }

    private static void logout(UUID playerId) {
        for (var iterator = USES.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (entry.getKey().getUUID().equals(playerId)) {
                GadgetAuditRuntime.invalidateOperation(entry.getValue().authority);
                iterator.remove();
            }
        }
    }

    public static final class UseBoundary {
        private final ServerPlayer player;
        private final Level level;
        private final InteractionHand hand;
        private final ItemStack stack;

        private UseBoundary(ServerPlayer player, Level level, InteractionHand hand, ItemStack stack) {
            this.player = player;
            this.level = level;
            this.hand = hand;
            this.stack = stack;
        }
    }

    private record UseGrant(GadgetAuditRuntime.Authority authority, InteractionHand hand, ItemStack stack) {}

    public static final class TickBoundary {
        private final UseGrant grant;
        private final IdentityHashMap<BlockEntity, GadgetAuditRuntime.Candidate> staged = new IdentityHashMap<>();
        private final ArrayList<BlockEntity> created = new ArrayList<>();
        private boolean valid = true;
        private boolean precisionStop;
        private boolean invalidateOnClose;

        private TickBoundary(UseGrant grant) { this.grant = grant; }
    }

    public static final class RemovalBoundary {
        private final TickBoundary tick;
        private final RenderBlockTileEntity placeholder;
        private final GadgetAuditRuntime.Claim claim;
        private final BlockState placeholderState;
        private boolean valid = true;
        private boolean removalBoundarySeen;
        private boolean setterSeen;
        private boolean mutationSucceeded;

        private RemovalBoundary(TickBoundary tick, RenderBlockTileEntity placeholder,
                                GadgetAuditRuntime.Claim claim, BlockState placeholderState) {
            this.tick = tick;
            this.placeholder = placeholder;
            this.claim = claim;
            this.placeholderState = placeholderState;
        }
    }
}
