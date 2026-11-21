package io.github.betapetey.eventtrail.events.gadgets;

import com.direwolf20.buildinggadgets2.common.blockentities.RenderBlockBE;
import com.direwolf20.buildinggadgets2.common.blocks.RenderBlock;
import com.direwolf20.buildinggadgets2.common.events.ServerBuildList;
import com.direwolf20.buildinggadgets2.common.events.ServerTickHandler;
import com.direwolf20.buildinggadgets2.common.items.GadgetBuilding;
import com.direwolf20.buildinggadgets2.common.items.GadgetCopyPaste;
import com.direwolf20.buildinggadgets2.common.items.GadgetDestruction;
import com.direwolf20.buildinggadgets2.common.items.GadgetExchanger;
import com.direwolf20.buildinggadgets2.util.GadgetNBT;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Exact queue/member authority for BG2 1.3.9 and 21.1.1 (their ServerTickHandler, RenderBlockBE, RenderBlock and
 * ServerBuildList classes are byte-identical; no version gate runs, the hooks are admitted by the structural pre-apply
 * check); no saved actor or tool-owner fallback. GADGET-DELAYED: a queue's later members and placeholders keep the
 * admission made at the use ({@link GadgetAuditRuntime.Authority#admitted}) and the account of its UUID, so they are
 * credited after the player respawned, logged out, changed dimension or switched to spectator.
 */
public final class BuildingGadgetsAuditBridge {
    static final int MAX_QUEUES = 64;
    static final int MAX_PLAYER_QUEUES = 4;
    static final int MAX_MEMBERS = 4096;
    private static final Map<ServerBuildList, QueueAuthority> QUEUES = new IdentityHashMap<>();

    static {
        GadgetAuditRuntime.registerLifecycle(AdapterKey.BUILDING_GADGETS, BuildingGadgetsAuditBridge::sweep,
                QUEUES::clear, BuildingGadgetsAuditBridge::logout);
    }

    private BuildingGadgetsAuditBridge() { }

    /** Called only after the native insertion returned, including unsupported insertions. */
    public static void enqueued(UUID buildId, StatePos node, Player player) {
        ServerBuildList queue = null;
        try {
            queue = ServerTickHandler.buildMap.get(buildId);
            if (queue == null) return;
            if (!(player instanceof ServerPlayer human) || GadgetAuditRuntime.currentInputActor() != human
                    || !supported(queue) || queue.statePosList.size() > MAX_MEMBERS) {
                QUEUES.remove(queue);
                return;
            }
            GadgetAuditRuntime.Authority input = GadgetAuditRuntime.captureInput(human, "buildinggadgets2");
            if (input == null || queue.level != input.level() || !buildId.equals(queue.buildUUID)
                    || !human.getUUID().equals(queue.playerUUID)) {
                QUEUES.remove(queue);
                return;
            }
            QueueAuthority admitted = QUEUES.get(queue);
            if (admitted == null) {
                long playerQueues = queuesHeldBy(human.getUUID());
                // An already-populated foreign queue cannot acquire authority through a later append.
                if (queue.statePosList.size() != 1 || QUEUES.size() >= MAX_QUEUES
                        || playerQueues >= MAX_PLAYER_QUEUES) return;
                admitted = new QueueAuthority(queue, input);
                QUEUES.put(queue, admitted);
            }
            if (!admitted.authority.operationId().equals(input.operationId())
                    || !admitted.sameFields(queue) || queue.statePosList.size() != admitted.order.size() + 1
                    || queue.statePosList.getLast() != node || admitted.members.containsKey(node)) {
                QUEUES.remove(queue);
                return;
            }
            BlockPos pos = targetPosition(queue, node);
            if (!input.level().hasChunkAt(pos)) {
                QUEUES.remove(queue);
                return;
            }
            Member member = new Member(node, pos, node.state, input.level().getBlockState(pos),
                    input.level().getBlockEntity(pos));
            admitted.members.put(node, member);
            admitted.order.addLast(node);
            admitted.originalSize = queue.originalSize;
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.BUILDING_GADGETS, "bridge:BuildingGadgetsAuditBridge.enqueued", failure);
            QUEUES.remove(queue);
        }
    }

    public static MemberBoundary beforeMember(ServerBuildList queue, Player player) {
        QueueAuthority admitted = QUEUES.get(queue);
        if (admitted == null) return null;
        try {
            if (!sameActor(admitted.authority, player) || !admitted.authority.admitted()
                    || !admitted.sameFields(queue) || queue.originalSize != admitted.originalSize
                    || ServerTickHandler.buildMap.get(admitted.buildId) != queue
                    || queue.statePosList.size() != admitted.order.size() || admitted.order.isEmpty()
                    || queue.statePosList.getFirst() != admitted.order.peekFirst() || !supported(queue)) {
                QUEUES.remove(queue);
                return null;
            }
            Member member = admitted.members.get(admitted.order.peekFirst());
            if (member == null || !member.node.pos.equals(member.relativePosition)
                    || member.node.state != member.target || member.attempts >= 2) {
                QUEUES.remove(queue);
                return null;
            }
            member.attempts++;
            ServerLevel level = admitted.authority.level();
            boolean current = level.hasChunkAt(member.position)
                    && level.getBlockState(member.position) == member.source
                    && level.getBlockEntity(member.position) == member.sourceEntity;
            GadgetAuditRuntime.Candidate candidate = current && supportedStates(queue, member)
                    ? GadgetAuditRuntime.prepare(admitted.authority, member.position) : null;
            return new MemberBoundary(queue, admitted, member, candidate, queue.statePosList.size());
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.BUILDING_GADGETS, "bridge:BuildingGadgetsAuditBridge.beforeMember", failure);
            QUEUES.remove(queue);
            return null;
        }
    }

    /** Successful member return is only a chance to bind a placeholder, never an audit fact. */
    public static void afterMember(MemberBoundary boundary, boolean returnedNormally) {
        if (boundary == null) return;
        ServerBuildList queue = boundary.queue;
        QueueAuthority admitted = boundary.admitted;
        try {
            if (!returnedNormally || QUEUES.get(queue) != admitted || !admitted.authority.admitted()
                    || !admitted.sameFields(queue) || !supported(queue)
                    || admitted.order.peekFirst() != boundary.member.node) {
                QUEUES.remove(queue);
                return;
            }
            StatePos node = admitted.order.removeFirst();
            boolean retry = queue.statePosList.size() == boundary.beforeSize;
            if (retry) {
                boolean retryMode = queue.buildType == ServerBuildList.BuildType.BUILD
                        || queue.buildType == ServerBuildList.BuildType.EXCHANGE;
                if (!retryMode || boundary.member.attempts != 1 || queue.statePosList.getLast() != node
                        || !queue.retryList.contains(boundary.member.position)) {
                    QUEUES.remove(queue);
                    return;
                }
                admitted.order.addLast(node);
                return;
            }
            if (queue.statePosList.size() != boundary.beforeSize - 1) {
                QUEUES.remove(queue);
                return;
            }
            admitted.members.remove(node);
            if (boundary.candidate != null) {
                ServerLevel level = admitted.authority.level();
                BlockEntity actual = level.getBlockEntity(boundary.member.position);
                if (actual instanceof RenderBlockBE render && actual != boundary.member.sourceEntity
                        && render.targetBlock == boundary.member.target
                        && render.sourceBlock == semanticSource(queue, boundary.member)) {
                    GadgetAuditRuntime.attach(boundary.candidate, render, render.sourceBlock,
                            render.targetBlock, kind(queue));
                }
            }
            if (admitted.order.isEmpty()) QUEUES.remove(queue);
        } catch (RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(AdapterKey.BUILDING_GADGETS, "bridge:BuildingGadgetsAuditBridge.afterMember", failure);
            QUEUES.remove(queue);
        }
    }

    private static GadgetAuditRuntime.Kind kind(ServerBuildList queue) {
        return switch (queue.buildType) {
            case DESTROY -> GadgetAuditRuntime.Kind.BREAK;
            case EXCHANGE -> GadgetAuditRuntime.Kind.REPLACE;
            default -> GadgetAuditRuntime.Kind.PLACE;
        };
    }

    private static BlockState semanticSource(ServerBuildList queue, Member member) {
        return queue.buildType == ServerBuildList.BuildType.BUILD
                ? Blocks.AIR.defaultBlockState() : member.source;
    }

    private static boolean supportedStates(ServerBuildList queue, Member member) {
        return classify(queue.buildType, member.source, member.target) != null;
    }

    static GadgetAuditRuntime.Kind classify(ServerBuildList.BuildType type, BlockState source, BlockState target) {
        if (type == null || source == null || target == null || source.getBlock() instanceof RenderBlock
                || target.getBlock() instanceof RenderBlock || !source.getFluidState().isEmpty()
                || !target.getFluidState().isEmpty()) return null;
        return switch (type) {
            case BUILD, UNDO_DESTROY -> source.isAir() && !target.isAir() ? GadgetAuditRuntime.Kind.PLACE : null;
            case EXCHANGE -> !source.isAir() && !target.isAir() && source != target ? GadgetAuditRuntime.Kind.REPLACE : null;
            case DESTROY -> !source.isAir() && target.isAir() ? GadgetAuditRuntime.Kind.BREAK : null;
            default -> null;
        };
    }

    private static boolean supported(ServerBuildList queue) {
        if (!(queue.level instanceof ServerLevel) || queue.statePosList == null || queue.gadget == null
                || queue.teData != null || queue.buildType == null || queue.lookingAt == null
                || queue.buildType == ServerBuildList.BuildType.CUT) return false;
        Class<?> item = queue.gadget.getItem().getClass();
        return item == GadgetBuilding.class || item == GadgetExchanger.class
                || item == GadgetDestruction.class || item == GadgetCopyPaste.class;
    }

    static BlockPos targetPosition(ServerBuildList queue, StatePos node) {
        return switch (queue.buildType) {
            case BUILD -> node.pos.offset(queue.lookingAt).immutable();
            case EXCHANGE -> queue.lookingAt.equals(GadgetNBT.nullPos)
                    ? node.pos.immutable() : node.pos.offset(queue.lookingAt).immutable();
            default -> node.pos.immutable();
        };
    }

    static void sweep() {
        QUEUES.entrySet().removeIf(entry -> !entry.getValue().authority.admitted()
                || ServerTickHandler.buildMap.get(entry.getValue().buildId) != entry.getKey());
    }

    /**
     * GADGET-DELAYED: whether the player BG2 passes for a member is the admitted account. BG2 passes
     * {@code PlayerList.getPlayer(queue.playerUUID)}, which is a new object after a respawn; the same UUID is the same
     * account (sameFields also pins that UUID). Null is never the admitted account.
     */
    static boolean sameActor(GadgetAuditRuntime.Authority authority, Player player) {
        return authority != null && player != null && authority.player().getUUID().equals(player.getUUID());
    }

    /** GADGET-DELAYED: the admitted queues held under this UUID, whichever player object admitted them. */
    static long queuesHeldBy(UUID player) {
        return QUEUES.values().stream().filter(q -> q.authority.player().getUUID().equals(player)).count();
    }

    private static void logout(UUID player) {
        QUEUES.values().removeIf(queue -> queue.authority.player().getUUID().equals(player));
    }

    static int retainedQueues() { return QUEUES.size(); }

    public static final class MemberBoundary {
        private final ServerBuildList queue;
        private final QueueAuthority admitted;
        private final Member member;
        private final GadgetAuditRuntime.Candidate candidate;
        private final int beforeSize;

        private MemberBoundary(ServerBuildList queue, QueueAuthority admitted, Member member,
                               GadgetAuditRuntime.Candidate candidate, int beforeSize) {
            this.queue = queue;
            this.admitted = admitted;
            this.member = member;
            this.candidate = candidate;
            this.beforeSize = beforeSize;
        }
    }

    private static final class QueueAuthority {
        private final GadgetAuditRuntime.Authority authority;
        private final UUID buildId;
        private final ArrayList<StatePos> nodes;
        private final ItemStack gadget;
        private final ServerBuildList.BuildType type;
        private final BlockPos offset;
        private final byte renderType;
        private final boolean needItems, returnItems, dropContents;
        private final Map<StatePos, Member> members = new IdentityHashMap<>();
        private final ArrayDeque<StatePos> order = new ArrayDeque<>();
        private int originalSize;

        private QueueAuthority(ServerBuildList queue, GadgetAuditRuntime.Authority authority) {
            this.authority = authority;
            buildId = queue.buildUUID;
            nodes = queue.statePosList;
            gadget = queue.gadget;
            type = queue.buildType;
            offset = queue.lookingAt.immutable();
            renderType = queue.renderType;
            needItems = queue.needItems;
            returnItems = queue.returnItems;
            dropContents = queue.dropContents;
        }

        private boolean sameFields(ServerBuildList queue) {
            return queue.level == authority.level() && authority.player().getUUID().equals(queue.playerUUID)
                    && buildId.equals(queue.buildUUID) && nodes == queue.statePosList && gadget == queue.gadget
                    && type == queue.buildType && offset.equals(queue.lookingAt) && renderType == queue.renderType
                    && needItems == queue.needItems && returnItems == queue.returnItems && dropContents == queue.dropContents;
        }
    }

    private static final class Member {
        private final StatePos node;
        private final BlockPos relativePosition, position;
        private final BlockState target, source;
        private final BlockEntity sourceEntity;
        private int attempts;

        private Member(StatePos node, BlockPos position, BlockState target, BlockState source, BlockEntity sourceEntity) {
            this.node = node;
            this.relativePosition = node.pos.immutable();
            this.position = position;
            this.target = target;
            this.source = source;
            this.sourceEntity = sourceEntity;
        }
    }
}
