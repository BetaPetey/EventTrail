package io.github.betapetey.eventtrail.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.betapetey.eventtrail.events.evidence.ExplosionAuditSession;
import io.github.betapetey.eventtrail.events.platform.ExplosionRemovalAudit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BiConsumer;

/**
 * Records only block effects proven by Explosion.finalizeExplosion callbacks.
 *
 * <p>GF-EXPLOSIONSESSION: the gates, the capture before and the commit after each block call, the close with its one
 * publish and the warnings are the shared {@link ExplosionRemovalAudit} code that every mod explosion hook runs too;
 * this mixin keeps only the two wraps, the session field and the rule for a second finalization of the same
 * Explosion.</p>
 */
@Mixin(Explosion.class)
abstract class ExplosionMixin {
    @Shadow
    @Final
    private Level level;

    /** Server-thread-owned and restored around an unusual nested finalization. */
    @Unique
    private ExplosionAuditSession eventtrail$activeExplosion;

    @WrapMethod(
            method = "finalizeExplosion(Z)V",
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$recordCommittedExplosionEffects(
            boolean spawnParticles,
            Operation<Void> original) {
        Explosion self = (Explosion) (Object) this;
        ExplosionAuditSession previous = this.eventtrail$activeExplosion;
        if (previous != null) {
            // Recursive finalization of the same Explosion object cannot be
            // separated into trustworthy physical operations. Suppress its
            // audit callbacks while preserving gameplay and fail the active
            // in-flight candidate closed.
            previous.stop(ExplosionAuditSession.StopReason.EVIDENCE_PROCESSING_FAILURE);
            this.eventtrail$activeExplosion = null;
            try {
                original.call(spawnParticles);
            } finally {
                this.eventtrail$activeExplosion = previous;
            }
            return;
        }
        // GF-EXPLOSIONSESSION: today's gates and session, plus the "explosion is removing blocks now" flag.
        ExplosionRemovalAudit.VanillaBracket bracket = ExplosionRemovalAudit.openVanilla(this.level, self);
        ExplosionAuditSession session = bracket.session();
        this.eventtrail$activeExplosion = session;

        Throwable gameFailure = null;
        try {
            original.call(spawnParticles);
        } catch (Throwable thrown) {
            gameFailure = thrown;
            throw thrown;
        } finally {
            this.eventtrail$activeExplosion = previous;
            // A2-44: the explosion is one action, published here once. A throw out of the game's finalization
            // cuts it short: its rows (each proven when its own callback returned) plus one error row. The game's
            // throwable is never replaced; the flag is lowered last.
            ExplosionRemovalAudit.closeVanilla(bracket, gameFailure);
        }
    }

    @WrapOperation(
            method = "finalizeExplosion(Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;onExplosionHit"
                            + "(Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/Explosion;"
                            + "Ljava/util/function/BiConsumer;)V"),
            require = 1,
            expect = 1,
            allow = 1)
    private void eventtrail$observeCommittedBlockEffect(
            BlockState state,
            Level level,
            BlockPos position,
            Explosion explosion,
            BiConsumer<ItemStack, BlockPos> drops,
            Operation<Void> original) {
        ExplosionRemovalAudit.Ticket ticket = ExplosionRemovalAudit.beforeVanillaBlock(
                (Explosion) (Object) this, this.eventtrail$activeExplosion, level, position, explosion, state);

        original.call(state, level, position, explosion, drops);

        ExplosionRemovalAudit.afterVanillaBlock(ticket);
    }
}
