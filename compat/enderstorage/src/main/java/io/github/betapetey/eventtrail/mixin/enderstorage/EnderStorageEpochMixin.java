package io.github.betapetey.eventtrail.mixin.enderstorage;

import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderStorageCarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "codechicken.enderstorage.storage.EnderItemStorage", remap = false)
public abstract class EnderStorageEpochMixin implements EnderStorageCarrier {
    @Unique private volatile long eventtrail$externalEpoch;
    @Unique private volatile long eventtrail$actionEpoch;
    public long eventtrail$externalCapabilityEpoch() { return eventtrail$externalEpoch; }
    public synchronized void eventtrail$externalCapabilityMutation() {
        if (eventtrail$externalEpoch != Long.MAX_VALUE) eventtrail$externalEpoch++;
    }
    public synchronized void eventtrail$externalCapabilityInvalidated() { eventtrail$externalEpoch = Long.MAX_VALUE; }
    public long eventtrail$manualActionEpoch() { return eventtrail$actionEpoch; }
    public synchronized long eventtrail$beginManualAction() {
        if (eventtrail$actionEpoch != Long.MAX_VALUE) eventtrail$actionEpoch++;
        return eventtrail$actionEpoch;
    }
}
