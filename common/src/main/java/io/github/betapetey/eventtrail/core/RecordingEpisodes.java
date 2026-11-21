package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.util.EventTrailFailure;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, synchronized episode bookkeeping. Callers perform all logging, permission
 * checks and delivery outside this object's monitor. Snapshots must come from one
 * lifecycle owner whose generation increases across server runs; construct a new
 * reducer when replacing that owner. Only that owner can verify recording recovery.
 */
public final class RecordingEpisodes {
    public record EpisodeKey(UUID serverRunId, long number) {
        public EpisodeKey {
            Objects.requireNonNull(serverRunId, "serverRunId");
            if (number < 1L) throw new IllegalArgumentException("Invalid episode number");
        }
    }

    public enum Kind { DISABLED, RECOVERY, DIAGNOSTIC }

    /** Cause is the safe current blocker, including for a recovery of that episode. */
    public record ConsoleNotice(Kind kind, EpisodeKey key, EventTrailFailure cause) {
        public ConsoleNotice {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(cause, "cause");
        }
    }

    // The diagnostic universe is bounded by these enums, never an arbitrary message.
    private record DiagnosticKey(EventTrailFailure.Code code,
                                 EventTrailFailure.Phase phase,
                                 EventTrailFailure.FileRole role) {
        static DiagnosticKey of(EventTrailFailure failure) {
            return new DiagnosticKey(failure.code(), failure.phase(), failure.role());
        }
    }

    private static final class Episode {
        final EpisodeKey key;
        final Set<DiagnosticKey> diagnostics = new HashSet<>();
        final Set<UUID> warningAttempts = new HashSet<>();
        final Set<UUID> warningEnqueued = new LinkedHashSet<>();
        final Set<UUID> recoveryAttempts = new HashSet<>();
        EventTrailFailure currentFailure;
        boolean recovering;

        Episode(EpisodeKey key, EventTrailFailure cause) {
            this.key = key;
            this.currentFailure = cause;
        }
    }

    private UUID serverRunId;
    private long generation = -1L;
    private long revision = -1L;
    private long episodeNumber;
    private boolean runEnded;
    // At most one active OR closing episode; a later failure invalidates old chat.
    private Episode episode;

    /** Returns every distinct console transition; never coalesce this returned list. */
    public synchronized List<ConsoleNotice> accept(RecordingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        boolean newRun = !snapshot.serverRunId().equals(serverRunId);
        if (newRun) {
            if (snapshot.generation() <= generation) return List.of();
            serverRunId = snapshot.serverRunId();
            episodeNumber = 0L;
            episode = null;
            runEnded = false;
        } else if (runEnded || snapshot.generation() < generation
                || snapshot.revision() <= revision) {
            return List.of();
        }
        generation = snapshot.generation();
        revision = snapshot.revision();

        if (snapshot.state() == RecordingSnapshot.State.STOPPING
                || snapshot.state() == RecordingSnapshot.State.STOPPED) {
            episode = null;
            runEnded = true;
            return List.of();
        }

        List<ConsoleNotice> notices = new ArrayList<>();
        if (snapshot.state() == RecordingSnapshot.State.DISABLED) {
            EventTrailFailure cause = snapshot.currentFailure() != null
                    ? snapshot.currentFailure() : snapshot.originalFailure();
            if (cause == null) {
                cause = EventTrailFailure.from(null, EventTrailFailure.Phase.UNKNOWN, snapshot.backend());
            }
            if (episode == null || episode.recovering) {
                episode = new Episode(new EpisodeKey(serverRunId, ++episodeNumber), cause);
                episode.diagnostics.add(DiagnosticKey.of(cause));
                notices.add(new ConsoleNotice(Kind.DISABLED, episode.key, cause));
            } else {
                episode.currentFailure = cause;
                addDiagnostic(notices, cause);
            }
            addDiagnostic(notices, snapshot.originalFailure());
            for (EventTrailFailure cleanup : snapshot.cleanupFailures()) addDiagnostic(notices, cleanup);
        } else if (snapshot.recordingAvailable()) {
            if (episode != null && !episode.recovering) {
                episode.recovering = true;
                notices.add(new ConsoleNotice(Kind.RECOVERY, episode.key, episode.currentFailure));
            }
        } else if (episode != null && episode.recovering) {
            // A new unverified startup also makes an old queued recovery obsolete.
            episode = null;
        }
        return List.copyOf(notices);
    }

    private void addDiagnostic(List<ConsoleNotice> notices, EventTrailFailure cause) {
        if (cause != null && episode.diagnostics.add(DiagnosticKey.of(cause))) {
            notices.add(new ConsoleNotice(Kind.DIAGNOSTIC, episode.key, cause));
        }
    }

    public synchronized Optional<EpisodeKey> activeEpisode() {
        return episode != null && !episode.recovering ? Optional.of(episode.key) : Optional.empty();
    }

    /** Delivery must wait if the authoritative publication has not reached this reducer yet. */
    public synchronized boolean isCurrentSnapshot(RecordingSnapshot snapshot) {
        return snapshot != null && snapshot.serverRunId().equals(serverRunId)
                && snapshot.generation() == generation && snapshot.revision() == revision;
    }

    /** State eligibility only: querying or queueing an effect never consumes a claim. */
    public synchronized boolean warningEligible(EpisodeKey key, UUID recipient) {
        Objects.requireNonNull(recipient, "recipient");
        return matches(key) && !episode.recovering && !episode.warningAttempts.contains(recipient);
    }

    /**
     * Call after final online/authorization validation, immediately before sending.
     * The returned current explanation may differ from when this effect was queued.
     * An exception or ambiguous send still consumes the attempt; do not retry it.
     */
    public synchronized Optional<EventTrailFailure> claimWarning(EpisodeKey key, UUID recipient) {
        if (!warningEligible(key, recipient)) return Optional.empty();
        episode.warningAttempts.add(recipient);
        return Optional.of(episode.currentFailure);
    }

    /** A positive sink acknowledgement, distinct from merely attempting a warning. */
    public synchronized void warningEnqueued(EpisodeKey key, UUID recipient) {
        Objects.requireNonNull(recipient, "recipient");
        if (matches(key) && episode.warningAttempts.contains(recipient)) {
            // A concurrent recovery may have arrived between the send and acknowledgement.
            episode.warningEnqueued.add(recipient);
        }
    }

    public synchronized Optional<EpisodeKey> recoveryEpisode() {
        return episode != null && episode.recovering ? Optional.of(episode.key) : Optional.empty();
    }

    /** Snapshot only. The caller filters online/authorized users without retaining an offline queue. */
    public synchronized Set<UUID> recoveryRecipients(EpisodeKey key) {
        return matches(key) && episode.recovering ? Set.copyOf(episode.warningEnqueued) : Set.of();
    }

    /** Call after final online/authorization validation, immediately before the recovery send. */
    public synchronized boolean claimRecovery(EpisodeKey key, UUID recipient) {
        Objects.requireNonNull(recipient, "recipient");
        return matches(key) && episode.recovering && episode.warningEnqueued.contains(recipient)
                && episode.recoveryAttempts.add(recipient);
    }

    /** Resolve online delivery once, including skipped/offline users, then discard all old UUIDs. */
    public synchronized void finishRecovery(EpisodeKey key) {
        if (matches(key) && episode.recovering) episode = null;
    }

    private boolean matches(EpisodeKey key) {
        return episode != null && episode.key.equals(key);
    }
}
