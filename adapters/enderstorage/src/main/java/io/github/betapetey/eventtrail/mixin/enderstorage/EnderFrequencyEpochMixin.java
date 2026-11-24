package io.github.betapetey.eventtrail.mixin.enderstorage;

import codechicken.enderstorage.api.Frequency;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderFrequencyCarrier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Epoch producer. It runs only while the ENDER_STORAGE key is active; {@code EnderStorageBinding} therefore treats a
 * binding as current only while that key is active, so a skipped bump can never keep a binding open.
 */
@Pseudo
@Mixin(targets = "codechicken.enderstorage.tile.TileFrequencyOwner", remap = false)
public abstract class EnderFrequencyEpochMixin implements EnderFrequencyCarrier {
    @Unique private long eventtrail$frequencyEpoch;
    public long eventtrail$enderFrequencyEpoch() { return eventtrail$frequencyEpoch; }
    @Unique private void eventtrail$changed() { if (eventtrail$frequencyEpoch != Long.MAX_VALUE) eventtrail$frequencyEpoch++; }
    @Inject(method = "setFreq", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$retune(Frequency frequency, CallbackInfo ci) {
        HookGuard.observe(AdapterKey.ENDER_STORAGE, "hook:EnderFrequencyEpochMixin.retune", () -> eventtrail$changed());
    }
    @Inject(method = "loadAdditional", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$reload(CompoundTag tag, HolderLookup.Provider provider, CallbackInfo ci) {
        HookGuard.observe(AdapterKey.ENDER_STORAGE, "hook:EnderFrequencyEpochMixin.reload", () -> eventtrail$changed());
    }
}
