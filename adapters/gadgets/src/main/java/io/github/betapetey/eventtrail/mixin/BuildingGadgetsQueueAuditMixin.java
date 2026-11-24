package io.github.betapetey.eventtrail.mixin;

import com.direwolf20.buildinggadgets2.common.events.ServerBuildList;
import com.direwolf20.buildinggadgets2.common.events.ServerTickHandler;
import com.direwolf20.buildinggadgets2.util.datatypes.StatePos;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.events.gadgets.BuildingGadgetsAuditBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

import java.util.UUID;

/** The optional exact-version queue supplies finite member identity, never a permission-event result. */
@Mixin(value = ServerTickHandler.class, remap = false)
abstract class BuildingGadgetsQueueAuditMixin {
    @WrapMethod(method = "addToMap", require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private static void eventtrail$admitQueuedMember(UUID buildId, StatePos member, Level level, byte renderType,
            Player player, boolean neededItems, boolean returnItems, ItemStack gadget,
            ServerBuildList.BuildType type, boolean dropContents, BlockPos lookingAt, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.BUILDING_GADGETS, "hook:BuildingGadgetsQueueAuditMixin.admitQueuedMember", original,
                once -> {
                    once.call();
                    BuildingGadgetsAuditBridge.enqueued(buildId, member, player);
                }, buildId, member, level, renderType, player, neededItems, returnItems,
                gadget, type, dropContents, lookingAt);
    }

    @WrapMethod(method = {"build", "exchange", "remove", "undoDestroy", "cut"}, require = 0, expect = 0)
    @HookSite(require = 5, expect = 5, allow = 5)
    private static void eventtrail$observeSelectedMember(ServerBuildList queue, Player player, Operation<Void> original) {
        HookGuard.wrap(AdapterKey.BUILDING_GADGETS, "hook:BuildingGadgetsQueueAuditMixin.observeSelectedMember",
                original, once -> {
                    BuildingGadgetsAuditBridge.MemberBoundary boundary =
                            BuildingGadgetsAuditBridge.beforeMember(queue, player);
                    boolean returnedNormally = false;
                    try {
                        once.call();
                        returnedNormally = true;
                    } finally {
                        BuildingGadgetsAuditBridge.afterMember(boundary, returnedNormally);
                    }
                }, queue, player);
    }
}
