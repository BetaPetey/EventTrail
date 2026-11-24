package io.github.betapetey.eventtrail.mixin.sophisticated;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookGuard;
import io.github.betapetey.eventtrail.adapters.hooks.HookSite;
import io.github.betapetey.eventtrail.inventory.adapters.sophisticated.LinkedGroupWatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Sophisticated part 4 (guard design 01, G3): observes Sophisticated Core's one linked-group write funnel, the private
 * {@code LinkedStorageGroupManager.commit(UUID, boolean, boolean, boolean)}, at HEAD. Every persisted change of a
 * group (slot writes, contents replaced, layout, membership) ends there, with the same descriptor on Core 1.5.17,
 * 1.5.25 and 1.5.32 (byte reads 01). Observation only: it never changes or cancels the mod's call.
 *
 * <p>It runs only while {@link AdapterKey#SOPHISTICATED_LINKED_GROUPS} is active (HookGuard); a linked binding is
 * current only while that key is active, so a skipped observation can never keep a guard open. Mixins do not apply
 * in unit tests: the unit gate proves that this site matches on each Core build, not that it fires. That it fires
 * (and so any off-thread detection) is proven only by the Owner-authorised GameTest; nothing claims it before.</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.linkedstorage.LinkedStorageGroupManager", remap = false)
public abstract class SophisticatedLinkedCommitMixin {
    @Inject(method = "commit(Ljava/util/UUID;ZZZ)V", at = @At("HEAD"), require = 0, expect = 0)
    @HookSite(require = 1, expect = 1, allow = 1)
    private void eventtrail$commit(UUID groupId, boolean contentsReplaced, boolean carrier, boolean layout,
            CallbackInfo ci) {
        HookGuard.observe(AdapterKey.SOPHISTICATED_LINKED_GROUPS, LinkedGroupWatch.COMMIT_SITE,
                () -> LinkedGroupWatch.observedCommit(groupId, contentsReplaced, layout));
    }
}
