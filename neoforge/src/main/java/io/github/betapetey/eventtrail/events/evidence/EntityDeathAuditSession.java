package io.github.betapetey.eventtrail.events.evidence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Loader-neutral, exactly-once finality gate for one synchronous death call.
 * Platform code owns live-object identity and reentrancy; this class verifies
 * only immutable victim identity and the final committed postcondition.
 */
public final class EntityDeathAuditSession {
    private final UUID exactVictimId;
    private CommittedEntityDeathEvidence candidate;
    private boolean closed;

    public EntityDeathAuditSession(UUID exactVictimId) {
        this.exactVictimId = Objects.requireNonNull(exactVictimId, "exactVictimId");
    }

    public boolean accept(CommittedEntityDeathEvidence candidate) {
        if (closed || this.candidate != null || candidate == null
                || !exactVictimId.equals(candidate.victim().entityId())) {
            return false;
        }
        this.candidate = candidate;
        return true;
    }

    public void abort() {
        closed = true;
        candidate = null;
    }

    /** Returns evidence on the first valid close and never again. */
    public Optional<CommittedEntityDeathEvidence> complete(
            UUID exactVictimId,
            boolean committedPostcondition) {
        if (closed) return Optional.empty();
        closed = true;

        CommittedEntityDeathEvidence accepted = candidate;
        candidate = null;
        if (!committedPostcondition
                || accepted == null
                || !this.exactVictimId.equals(exactVictimId)
                || !this.exactVictimId.equals(accepted.victim().entityId())) {
            return Optional.empty();
        }
        return Optional.of(accepted);
    }
}
