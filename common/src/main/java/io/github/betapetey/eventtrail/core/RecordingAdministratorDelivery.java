package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.util.EventTrailFailure;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Bounded administrator delivery, separate from the console episode owner.
 * The supplied executor must serialize work on the server thread. Only tasks
 * submitted to that executor may access the player directory or message sink.
 * The recording supplier must return cached authoritative state, without I/O.
 */
public final class RecordingAdministratorDelivery {
    public static final int MAX_PLAYERS_PER_PASS = 32;
    public static final long PERMISSION_SCAN_INTERVAL_MILLIS = 5_000L;

    public interface PlayerDirectory {
        int onlineCount();
        UUID onlinePlayerId(int index);
        /** Re-resolve the online player and check exact status authorization now. */
        boolean authorizedOnline(UUID playerId);
    }

    public interface MessageSink {
        /** True only when the warning was positively enqueued to the current player's sink. */
        boolean warning(UUID playerId, EventTrailFailure cause);
        boolean recovery(UUID playerId);
    }

    private record Login(RecordingEpisodes.EpisodeKey key, UUID playerId) {}

    private final RecordingEpisodes episodes;
    private final Supplier<RecordingSnapshot> authoritativeRecording;
    private final PlayerDirectory directory;
    private final Executor mainThread;
    private final MessageSink sink;
    private final LongSupplier clock;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Object mailbox = new Object();
    private final Set<Login> logins = new LinkedHashSet<>();
    private volatile boolean stopped;

    // These fields are accessed only in the serial executor task.
    private RecordingEpisodes.EpisodeKey scanningEpisode;
    private int scanCursor;
    private boolean scanned;
    private long lastScanMillis;
    private RecordingEpisodes.EpisodeKey recoveringEpisode;
    private List<UUID> recoveryRecipients = List.of();
    private int recoveryCursor;

    public RecordingAdministratorDelivery(RecordingEpisodes episodes,
            Supplier<RecordingSnapshot> authoritativeRecording, PlayerDirectory directory,
            Executor mainThread, MessageSink sink, LongSupplier clock) {
        this.episodes = Objects.requireNonNull(episodes, "episodes");
        this.authoritativeRecording = Objects.requireNonNull(authoritativeRecording, "authoritativeRecording");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Called after the existing episode owner accepts this transition; never accepts it again. */
    public void onRecordingChanged(RecordingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        requestPass();
    }

    public void onLogin(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (stopped) return;
        var active = episodes.activeEpisode();
        if (active.isEmpty() || !episodes.warningEligible(active.get(), playerId)) return;
        synchronized (mailbox) {
            if (!stopped && logins.size() < MAX_PLAYERS_PER_PASS) {
                logins.add(new Login(active.get(), playerId));
            }
        }
        // Overflow is covered by the fair, bounded permission scan.
        requestPass();
    }

    /** The ordinary server tick drives scan deadlines and remaining recovery batches. */
    public void tick() {
        if (!stopped && (episodes.activeEpisode().isPresent() || episodes.recoveryEpisode().isPresent())) {
            requestPass();
        }
    }

    /** Terminal for this controller instance; a later server run gets a new controller. */
    public void stop() {
        stopped = true;
        synchronized (mailbox) { logins.clear(); }
        // The shared reducer owns episode cleanup on stop/new-run acceptance.
        // Stopping an old controller must not finish a replacement run's recovery.
        enqueuePass();
    }

    private void requestPass() {
        if (!stopped) enqueuePass();
    }

    private void enqueuePass() {
        if (!queued.compareAndSet(false, true)) return;
        try {
            mainThread.execute(() -> {
                try {
                    if (stopped) clearLocalState();
                    else runPass();
                } finally {
                    queued.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            // Shutdown/rejected scheduling does not consume any player's send claim.
            queued.set(false);
        }
    }

    private void runPass() {
        RecordingSnapshot snapshot = currentSnapshot();
        if (snapshot == null) return;
        if (snapshot.state() == RecordingSnapshot.State.DISABLED) {
            episodes.activeEpisode().ifPresent(this::warningPass);
        } else if (snapshot.recordingAvailable()) {
            var recovery = episodes.recoveryEpisode();
            if (recovery.isPresent()) recoveryPass(recovery.get());
            else clearLocalState();
        } else {
            clearLocalState();
        }
    }

    private RecordingSnapshot currentSnapshot() {
        if (stopped) return null;
        try {
            RecordingSnapshot snapshot = authoritativeRecording.get();
            return episodes.isCurrentSnapshot(snapshot) ? snapshot : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void warningPass(RecordingEpisodes.EpisodeKey key) {
        clearRecovery();
        if (!key.equals(scanningEpisode)) {
            scanningEpisode = key;
            scanCursor = 0;
            scanned = false;
        }
        int remaining = MAX_PLAYERS_PER_PASS;
        long now = clock.getAsLong();
        if (!scanned || now < lastScanMillis || now - lastScanMillis >= PERMISSION_SCAN_INTERVAL_MILLIS) {
            scanned = true;
            lastScanMillis = now;
            int count;
            try {
                count = Math.max(0, directory.onlineCount());
            } catch (RuntimeException unavailable) {
                count = 0;
            }
            int visits = Math.min(count, remaining);
            if (count == 0) scanCursor = 0;
            for (int visit = 0; visit < visits; visit++) {
                int index = scanCursor % count;
                scanCursor = index + 1;
                remaining--;
                UUID playerId;
                try {
                    playerId = directory.onlinePlayerId(index);
                } catch (RuntimeException changedDirectory) {
                    continue;
                }
                if (playerId != null) warn(key, playerId);
            }
        }
        // Login candidates use only the remainder of this pass's shared 32-player budget.
        List<Login> candidates = new ArrayList<>();
        synchronized (mailbox) {
            var iterator = logins.iterator();
            while (iterator.hasNext() && candidates.size() < remaining) {
                candidates.add(iterator.next());
                iterator.remove();
            }
        }
        for (Login login : candidates) {
            if (key.equals(login.key())) warn(key, login.playerId());
        }
    }

    private void warn(RecordingEpisodes.EpisodeKey key, UUID playerId) {
        if (stopped || !episodes.warningEligible(key, playerId)) return;
        if (!authorized(playerId) || !currentFor(key, false)) return;
        var cause = episodes.claimWarning(key, playerId);
        if (cause.isEmpty()) return;
        try {
            if (sink.warning(playerId, cause.get())) episodes.warningEnqueued(key, playerId);
        } catch (RuntimeException ambiguousSend) {
            // At-most-once attempt: an exception does not prove that the sink saw nothing.
        }
    }

    private void recoveryPass(RecordingEpisodes.EpisodeKey key) {
        scanningEpisode = null;
        synchronized (mailbox) { logins.clear(); }
        if (!key.equals(recoveringEpisode)) {
            recoveringEpisode = key;
            recoveryRecipients = new ArrayList<>(episodes.recoveryRecipients(key));
            recoveryCursor = 0;
        }
        int visited = 0;
        while (recoveryCursor < recoveryRecipients.size() && visited++ < MAX_PLAYERS_PER_PASS) {
            if (!currentFor(key, true)) return;
            UUID playerId = recoveryRecipients.get(recoveryCursor);
            boolean authorized = authorized(playerId);
            if (!currentFor(key, true)) return;
            recoveryCursor++;
            if (!authorized || !episodes.claimRecovery(key, playerId)) continue;
            try {
                sink.recovery(playerId);
            } catch (RuntimeException ambiguousSend) {
                // Recovery attempts are likewise never retried.
            }
        }
        if (recoveryCursor == recoveryRecipients.size()) {
            episodes.finishRecovery(key);
            clearRecovery();
        }
    }

    private boolean authorized(UUID playerId) {
        try {
            return directory.authorizedOnline(playerId);
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private boolean currentFor(RecordingEpisodes.EpisodeKey key, boolean recovery) {
        RecordingSnapshot snapshot = currentSnapshot();
        if (snapshot == null || !snapshot.serverRunId().equals(key.serverRunId())) return false;
        return recovery
                ? snapshot.recordingAvailable() && episodes.recoveryEpisode().filter(key::equals).isPresent()
                : snapshot.state() == RecordingSnapshot.State.DISABLED
                        && episodes.activeEpisode().filter(key::equals).isPresent();
    }

    private void clearRecovery() {
        recoveringEpisode = null;
        recoveryRecipients = List.of();
        recoveryCursor = 0;
    }

    private void clearLocalState() {
        scanningEpisode = null;
        scanned = false;
        synchronized (mailbox) { logins.clear(); }
        clearRecovery();
    }
}
