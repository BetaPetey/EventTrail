package io.github.betapetey.eventtrail.inventory.adapters.create;

import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageMotion;

/**
 * Observes an oriented contraption entity's rotation around a writer that is not one of OrientedContraptionEntity's
 * own hooked members (Part 1 SA-create-mounted): the train carriage's {@code Carriage$DimensionalCarriageEntity#alignEntity}
 * and the Bits 'n' Bobs chain carriage's yaw write inside {@code AbstractContraptionEntity#tick}. A changed yaw, pitch or
 * initial yaw on the server changes the entity's moving-storage epoch, so an open mounted access stops being stationary.
 * Any other entity just runs the writer. A failing producer fails closed: the epoch is invalidated before the failure
 * goes on to the hook guard.
 */
public final class CreateOrientedPose {
    private CreateOrientedPose() {}

    public static void around(Object entity, Runnable writer) {
        if (!(entity instanceof OrientedContraptionEntity oriented) || !(entity instanceof MovingStorageMotion motion)) {
            writer.run();
            return;
        }
        float yaw, pitch, initial;
        try { yaw = oriented.yaw; pitch = oriented.pitch; initial = oriented.getInitialYaw(); }
        catch (Throwable failure) { failClosed(motion); throw failure; }
        try { writer.run(); }
        finally {
            try {
                if ((yaw != oriented.yaw || pitch != oriented.pitch || initial != oriented.getInitialYaw())
                        && !oriented.level().isClientSide())
                    motion.eventtrail$movingStorageEpoch().changed();
            } catch (Throwable failure) { failClosed(motion); throw failure; }
        }
    }

    /** Never throws: the producer's own failure is the one reported. */
    private static void failClosed(MovingStorageMotion motion) {
        try { motion.eventtrail$movingStorageEpoch().invalidate(); }
        catch (Throwable ignored) { /* never replace the producer's failure */ }
    }
}
