package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.EventTrailIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Adds server-side inspection of an AIR position next to a block.
 *
 * While /eventtrail inspect is enabled, crouch + right-click a block face. If the
 * block position immediately outside that face is air, EventTrail inspects the
 * history for that air position instead of the clicked solid block.
 *
 * This remains server-side only. Minecraft does not notify the server when an
 * empty-handed player right-clicks completely empty space, so the adjacent
 * block face supplies the exact coordinate of the air position to inspect.
 */
@EventBusSubscriber(modid = EventTrailIds.MOD_ID)
public final class InspectAirHandler {
    private InspectAirHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();

        if (event.getLevel().isClientSide()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!player.isShiftKeyDown()) return;
        if (!InspectCommand.isInspecting(player.getUUID())) return;

        Direction face = event.getFace();
        if (face == null) return;

        // The block space directly outside the face the player clicked.
        BlockPos airPos = event.getPos().relative(face);
        if (!event.getLevel().getBlockState(airPos).isAir()) return;

        // Prevent the normal inspect listener from also inspecting the solid
        // block that was clicked. Cancelled events are not passed to normal
        // downstream listeners unless they explicitly opt into cancelled events.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        // Reuse EventTrail's authoritative position-history query/display path
        // by invoking the block-inspect handler with the air coordinate.
        BlockHitResult syntheticHit = new BlockHitResult(
                Vec3.atCenterOf(airPos),
                face.getOpposite(),
                airPos,
                false
        );
        PlayerInteractEvent.RightClickBlock syntheticEvent =
                new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, airPos, syntheticHit);

        InspectCommand.onRightClickBlock(syntheticEvent);
    }
}
