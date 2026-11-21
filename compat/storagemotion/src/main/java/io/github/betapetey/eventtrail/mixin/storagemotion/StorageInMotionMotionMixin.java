package io.github.betapetey.eventtrail.mixin.storagemotion;

import io.github.betapetey.eventtrail.inventory.moving.MovingStorageEpoch;
import io.github.betapetey.eventtrail.inventory.moving.MovingStorageMotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

/** Only the two admitted native entity classes receive a bounded access-motion witness. */
@Pseudo
@Mixin(targets = {"net.p3pp3rf1y.sophisticatedstorageinmotion.entity.StorageMinecart",
        "net.p3pp3rf1y.sophisticatedstorageinmotion.entity.StorageBoat"}, remap = false)
public abstract class StorageInMotionMotionMixin implements MovingStorageMotion {
    @Unique private MovingStorageEpoch eventtrail$storageMotionEpoch;

    @Override public MovingStorageEpoch eventtrail$movingStorageEpoch() {
        // Entity constructors can invoke the position setter before subclass initialization.
        if (eventtrail$storageMotionEpoch == null) eventtrail$storageMotionEpoch = new MovingStorageEpoch();
        return eventtrail$storageMotionEpoch;
    }

}
