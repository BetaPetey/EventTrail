package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.events.evidence.EntityDeathAuditSession;
import io.github.betapetey.eventtrail.events.evidence.EntityDeathFinality;
import io.github.betapetey.eventtrail.events.platform.NeoForgeEntityDeathAuditBridge;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/** Records real and configured synthetic-player deaths after ServerPlayer.die commits. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerDeathMixin {
    /** Exact-instance, server-thread-owned state for one synchronous die call. */
    @Unique
    private EntityDeathAuditSession eventtrail$activePlayerDeathAudit;

    @Unique
    private DamageSource eventtrail$activePlayerDeathSource;

    /** ServerPlayer.die lacks LivingEntity's dead guard; respawn creates a new instance. */
    @Unique
    private boolean eventtrail$committedPlayerDeathClaimed;

    /** Reached only at the stable tail of the accepted vanilla death body. */
    @Unique
    private boolean eventtrail$playerDeathTailReached;

    @WrapMethod(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$recordCommittedPlayerDeath(
            DamageSource source,
            Operation<Void> original) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        EntityDeathAuditSession active = this.eventtrail$activePlayerDeathAudit;
        if (active != null) {
            // Do not guess which reentrant invocation owns the physical death.
            active.abort();
            DamageSource previousSource = this.eventtrail$activePlayerDeathSource;
            this.eventtrail$activePlayerDeathAudit = null;
            this.eventtrail$activePlayerDeathSource = null;
            try {
                original.call(source);
            } finally {
                this.eventtrail$activePlayerDeathAudit = active;
                this.eventtrail$activePlayerDeathSource = previousSource;
            }
            return;
        }

        if (this.eventtrail$committedPlayerDeathClaimed
                || self.isRemoved()
                || self.getHealth() > 0.0F) {
            original.call(source);
            return;
        }

        EntityDeathAuditSession session = new EntityDeathAuditSession(self.getUUID());
        this.eventtrail$activePlayerDeathAudit = session;
        this.eventtrail$activePlayerDeathSource = source;
        this.eventtrail$playerDeathTailReached = false;
        boolean completedNormally = false;
        try {
            original.call(source);
            completedNormally = true;
        } finally {
            boolean deathTailReached = this.eventtrail$playerDeathTailReached;
            this.eventtrail$activePlayerDeathAudit = null;
            this.eventtrail$activePlayerDeathSource = null;
            this.eventtrail$playerDeathTailReached = false;
            EntityDeathFinality.PlayerCompletion completion =
                    EntityDeathFinality.playerCompletion(
                            deathTailReached,
                            completedNormally,
                            self.isRemoved(),
                            self.getHealth());
            if (!completedNormally) {
                session.abort();
            } else {
                session.complete(self.getUUID(), completion.committedEvidence())
                        .ifPresent(NeoForgeEntityDeathAuditBridge::persist);
            }
            if (completion.claimOldInstance()) {
                // Claim even when capture was disabled or an outer wrapper
                // threw after the vanilla tail. A later duplicate die call
                // must never turn the old instance into a new positive fact.
                this.eventtrail$committedPlayerDeathClaimed = true;
            }
        }
    }

    @WrapOperation(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;setLastDeathLocation("
                            + "Ljava/util/Optional;)V"),
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$observeCompletedPlayerDeathTail(
            ServerPlayer player,
            Optional<GlobalPos> lastDeathLocation,
            Operation<Void> original) {
        original.call(player, lastDeathLocation);
        if (player == (ServerPlayer) (Object) this
                && this.eventtrail$activePlayerDeathAudit != null) {
            this.eventtrail$playerDeathTailReached = true;
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
    private boolean eventtrail$captureAcceptedPlayerDeath(
            LivingEntity victim,
            DamageSource source,
            Operation<Boolean> original) {
        boolean canceled = original.call(victim, source);
        EntityDeathAuditSession session = this.eventtrail$activePlayerDeathAudit;
        if (!canceled
                && session != null
                && victim == (ServerPlayer) (Object) this
                && source == this.eventtrail$activePlayerDeathSource) {
            NeoForgeEntityDeathAuditBridge.capture(victim, source)
                    .ifPresent(session::accept);
        }
        return canceled;
    }
}
