package io.github.betapetey.eventtrail.events.evidence;

/** Loader-neutral postcondition decisions for an exact synchronous death call. */
public final class EntityDeathFinality {
    private EntityDeathFinality() {
    }

    public static PlayerCompletion playerCompletion(
            boolean tailReached,
            boolean callCompletedNormally,
            boolean playerRemoved,
            float health) {
        boolean terminalOldInstance = tailReached && health <= 0.0F;
        boolean committedEvidence = callCompletedNormally
                && terminalOldInstance
                && !playerRemoved;
        return new PlayerCompletion(committedEvidence, terminalOldInstance);
    }

    public record PlayerCompletion(
            boolean committedEvidence,
            boolean claimOldInstance) {
    }
}
