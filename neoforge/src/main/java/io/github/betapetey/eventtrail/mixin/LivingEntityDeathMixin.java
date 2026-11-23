package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.events.evidence.EntityDeathAuditSession;
import io.github.betapetey.eventtrail.events.platform.NeoForgeEntityDeathAuditBridge;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Records non-player living deaths only after the exact die operation commits. */
@Mixin(LivingEntity.class)
abstract class LivingEntityDeathMixin {
    @Shadow
    protected boolean dead;

    /** Exact-instance, server-thread-owned state for one synchronous die call. */
    @Unique
    private EntityDeathAuditSession eventtrail$activeDeathAudit;

    @Unique
    private DamageSource eventtrail$activeDeathSource;

    @WrapMethod(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$recordCommittedLivingDeath(
            DamageSource source,
            Operation<Void> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        EntityDeathAuditSession active = this.eventtrail$activeDeathAudit;
        if (active != null) {
            // A nested death of the same live object cannot be separated into
            // trustworthy physical operations. Preserve gameplay but fail both
            // audit candidates closed.
            active.abort();
            DamageSource previousSource = this.eventtrail$activeDeathSource;
            this.eventtrail$activeDeathAudit = null;
            this.eventtrail$activeDeathSource = null;
            try {
                original.call(source);
            } finally {
                this.eventtrail$activeDeathAudit = active;
                this.eventtrail$activeDeathSource = previousSource;
            }
            return;
        }

        boolean wasDead = this.dead;
        if (wasDead
                || self.isRemoved()
                || self.getHealth() > 0.0F
                || !NeoForgeEntityDeathAuditBridge.canBegin(self)) {
            original.call(source);
            return;
        }

        EntityDeathAuditSession session = new EntityDeathAuditSession(self.getUUID());
        this.eventtrail$activeDeathAudit = session;
        this.eventtrail$activeDeathSource = source;
        boolean completedNormally = false;
        try {
            original.call(source);
            completedNormally = true;
        } finally {
            this.eventtrail$activeDeathAudit = null;
            this.eventtrail$activeDeathSource = null;
            if (!completedNormally) {
                session.abort();
            } else {
                boolean committed = !wasDead
                        && this.dead
                        && self.getHealth() <= 0.0F
                        && self.getPose() == Pose.DYING;
                session.complete(self.getUUID(), committed)
                        .ifPresent(NeoForgeEntityDeathAuditBridge::persist);
            }
        }
    }

    @WrapOperation(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/common/CommonHooks;onLivingDeath("
                            + "Lnet/minecraft/world/entity/LivingEntity;"
                            + "Lnet/minecraft/world/damagesource/DamageSource;)Z"),
            require = 1,
            expect = 1,
            allow = 1)
    private boolean eventtrail$captureAcceptedLivingDeath(
            LivingEntity victim,
            DamageSource source,
            Operation<Boolean> original) {
        boolean canceled = original.call(victim, source);
        EntityDeathAuditSession session = this.eventtrail$activeDeathAudit;
        if (!canceled
                && session != null
                && victim == (LivingEntity) (Object) this
                && source == this.eventtrail$activeDeathSource) {
            NeoForgeEntityDeathAuditBridge.capture(victim, source)
                    .ifPresent(session::accept);
        }
        return canceled;
    }
}
