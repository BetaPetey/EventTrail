package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.ActionRecordBatch;
import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditAttribution;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.SchemaV1Row;
import io.github.betapetey.eventtrail.audit.SchemaV1RowCodec;
import io.github.betapetey.eventtrail.events.block.BlockAuditLimits;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec;
import io.github.betapetey.eventtrail.events.block.BlockEffectTruncation;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;
import io.github.betapetey.eventtrail.events.block.ExplosionPartTag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bounded, operation-scoped evidence state for one Explosion.finalizeExplosion call.
 *
 * <p>Explosion split (Owner decision B, explosion-split-design-02). Every proven-destroyed block is listed here
 * ({@link #list}) within the per-explosion budget ({@link ExplosionSplitLimits#MAX_EXPLOSION_RETAINED_BYTES}); rows
 * whose block had a block entity (containers and other blocks with contents) are kept ahead of plain blocks, so the
 * blocks the budget leaves out are plain blocks first. Every block left out is counted ("not listed"); the count is
 * exact only when nothing stopped the examination and no block could have been destroyed without being listed or
 * counted ({@link #unlisted}), otherwise it is a lower bound ("at least").</p>
 *
 * <p>When the explosion ends it is either WHOLE or stored IN PARTS ({@link #storedInParts}):</p>
 * <ul>
 *   <li>whole: nothing stopped or left out, the count is exact and every listed row fits one action (at most
 *       {@link #MAX_LISTED_BLOCK_ROWS} rows within {@link #MAX_LISTED_RETAINED_BYTES}). It is handed to storage as ONE
 *       action, all or none ({@link #publish}), exactly as before: untagged rows, no extra row (A2-44);</li>
 *   <li>in parts: every other explosion, including a truncated one that fits one group ("part 1 of 1"). It is cut
 *       into k parts ({@link #planParts}), each one action of one part row and up to {@link #MAX_LISTED_BLOCK_ROWS}
 *       tagged block rows within {@link #MAX_LISTED_RETAINED_BYTES}, which ExplosionPartPublisher offers to storage
 *       one by one. Every part row carries k, so a reader can prove the explosion stored whole or show it
 *       INCOMPLETE.</li>
 * </ul>
 */
public final class ExplosionAuditSession {
    /** Block rows one action (one part, or one whole explosion) lists: one action's row limit less one part row. */
    public static final int MAX_LISTED_BLOCK_ROWS = ActionRecordBatch.MAX_ROWS_PER_ACTION - 1;

    /**
     * Retained size of the block rows of one action (one part, or one whole explosion), counted as the writer counts
     * it ({@link SchemaV1RowCodec#retainedBytes}) with the largest explosion tag included (Advisor M-1): a quarter of
     * the writer's 32 MiB pending-payload limit ({@code AbstractJdbcStorage.MAX_PENDING_PAYLOAD_BYTES}), so one part is
     * never refused whole for its size.
     */
    public static final long MAX_LISTED_RETAINED_BYTES = 8L * 1024L * 1024L;

    private final ActorProvenance actor;
    private final ResourceLocation dimensionId;
    private final BlockPos center;
    private final long startedAtMillis;
    private final Set<BlockPos> seenPositions = new HashSet<>();
    private final int maxPartRows;
    private final long maxPartBytes;
    private final long maxExplosionBytes;
    private final List<ListedRow> contentRows = new ArrayList<>();
    private final List<ListedRow> plainRows = new ArrayList<>();

    private int workUsed;
    private int uniquePositions;
    private long contentBytes;
    private long plainBytes;
    private int notListed;
    /** Rows left out of the listing by the explosion budget or because they could not take the tag (M-1). */
    private int leftOutBySize;
    private boolean countIncomplete;
    private StopReason stopReason = StopReason.NONE;
    private boolean closed;
    private boolean published;
    private Summary terminalSummary;
    private boolean storedInParts;

    public ExplosionAuditSession(
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos center) {
        this(actor, dimensionId, center, System.currentTimeMillis());
    }

    /** @param startedAtMillis the wall-clock time the explosion's finalization began (design-02 1.2, t0) */
    public ExplosionAuditSession(
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos center,
            long startedAtMillis) {
        this(actor, dimensionId, center, startedAtMillis, MAX_LISTED_BLOCK_ROWS, MAX_LISTED_RETAINED_BYTES,
                ExplosionSplitLimits.MAX_EXPLOSION_RETAINED_BYTES);
    }

    /** Smaller per-part limits for tests only; production uses {@link #MAX_LISTED_BLOCK_ROWS} and the byte budgets. */
    ExplosionAuditSession(
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos center,
            int maxListedRows,
            long maxListedRetainedBytes) {
        this(actor, dimensionId, center, System.currentTimeMillis(), maxListedRows, maxListedRetainedBytes,
                Math.max(ExplosionSplitLimits.MAX_EXPLOSION_RETAINED_BYTES, maxListedRetainedBytes));
    }

    /** Smaller limits for tests only: rows and bytes per part, and the whole explosion's byte budget. */
    ExplosionAuditSession(
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos center,
            long startedAtMillis,
            int maxListedRows,
            long maxListedRetainedBytes,
            long maxExplosionRetainedBytes) {
        this.actor = Objects.requireNonNull(actor, "actor");
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.center = Objects.requireNonNull(center, "center").immutable();
        if (maxListedRows < 1 || maxListedRows > MAX_LISTED_BLOCK_ROWS) {
            throw new IllegalArgumentException("maxListedRows must be 1.." + MAX_LISTED_BLOCK_ROWS);
        }
        if (maxListedRetainedBytes < 1L) throw new IllegalArgumentException("maxListedRetainedBytes must be positive");
        if (maxExplosionRetainedBytes < maxListedRetainedBytes) {
            throw new IllegalArgumentException("maxExplosionRetainedBytes must be at least one part's budget");
        }
        this.startedAtMillis = startedAtMillis;
        this.maxPartRows = maxListedRows;
        this.maxPartBytes = maxListedRetainedBytes;
        this.maxExplosionBytes = maxExplosionRetainedBytes;
    }

    /**
     * GF-BLOCKIDENTITY: whether an explosion with this block interaction opens a session at all. A wind burst
     * ({@link Explosion.BlockInteraction#TRIGGER_BLOCK}) only toggles blocks (doors, levers, buttons, candles) and
     * destroys none, so it opens no session and leaves no truncated or "not listed" count; KEEP touches no block.
     */
    public static boolean opensSessionFor(Explosion.BlockInteraction interaction) {
        return interaction != null
                && interaction != Explosion.BlockInteraction.KEEP
                && interaction != Explosion.BlockInteraction.TRIGGER_BLOCK;
    }

    /**
     * Charges one unit for every wrapped block callback, including duplicates.
     * This keeps both retained state and EventTrail-side work bounded even for a
     * hostile explosion list containing repeated positions.
     */
    public Optional<Candidate> begin(
            ResourceLocation dimensionId,
            BlockPos position,
            BlockState liveState,
            BlockState operationState,
            boolean writeCapacityAvailable) {
        if (closed || stopReason != StopReason.NONE) return Optional.empty();
        if (workUsed >= BlockAuditLimits.MAX_EXPLOSION_EVIDENCE_WORK) {
            stopReason = StopReason.EVIDENCE_LIMIT;
            return Optional.empty();
        }
        workUsed++;

        if (!writeCapacityAvailable) {
            stopReason = StopReason.STORAGE_PRESSURE;
            return Optional.empty();
        }
        if (!this.dimensionId.equals(dimensionId)
                || position == null
                || liveState == null
                || operationState == null) {
            stopReason = StopReason.EVIDENCE_PROCESSING_FAILURE;
            return Optional.empty();
        }

        BlockPos immutablePosition = position.immutable();
        if (!seenPositions.add(immutablePosition)) return Optional.empty();
        uniquePositions++;

        if (liveState.isAir()) return Optional.empty();
        if (!liveState.equals(operationState)) {
            // The game may still destroy this block, but it can never be listed or counted (review-01 MAJOR-1).
            countIncomplete = true;
            return Optional.empty();
        }
        return Optional.of(new Candidate(this, immutablePosition, operationState));
    }

    /** Completes one exact callback after the original block implementation returned normally. */
    public Optional<CommittedBlockEvidence> complete(
            Candidate candidate,
            BlockAuditPreimage preimage,
            BlockState finalState) {
        if (closed || stopReason != StopReason.NONE
                || candidate == null || !candidate.consume(this)) {
            return Optional.empty();
        }
        if (preimage == null || finalState == null
                || !dimensionId.equals(preimage.dimensionId())
                || !candidate.position.equals(preimage.position())
                || !candidate.operationState.equals(preimage.blockState())) {
            // A begun block whose change can no longer be proven: never listed or counted (review-01 MAJOR-1).
            countIncomplete = true;
            return Optional.empty();
        }
        return CommittedBlockEvidence.verify(
                AuditAction.EXPLOSION,
                actor,
                preimage,
                finalState,
                true,
                CommittedBlockEvidence.ProofKind.EXPLOSION_FINALIZATION_POST_STATE);
    }

    /**
     * Keeps the row of one destroyed block (evidence from {@link #complete}) for this explosion. Nothing is handed to
     * storage here. The row's size is counted as the writer counts it WITH the largest explosion tag (Advisor M-1),
     * so tagging it at finish can never cross a size limit; a row that could not take the tag (codec binary or encoded
     * limit, or the writer's single-row limit) is left out now and counted as not listed. A row is also left out, and
     * counted, when the explosion's rows would pass the explosion budget; a row whose block had a block entity may then
     * displace the most recently listed plain row (which is counted as not listed); a plain row never displaces
     * anything. A row build failure changes nothing here and propagates to the caller's guard.
     *
     * @return whether the row is listed now (a later row with a block entity may still displace a plain row)
     * @throws IllegalArgumentException for evidence that is not explosion finalization evidence (review-01 NIT-6)
     */
    public boolean list(CommittedBlockEvidence evidence, AuditLocation location) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(location, "location");
        if (evidence.action() != AuditAction.EXPLOSION
                || evidence.proofKind() != CommittedBlockEvidence.ProofKind.EXPLOSION_FINALIZATION_POST_STATE) {
            throw new IllegalArgumentException("Only explosion finalization evidence can be listed");
        }
        if (closed) return false;
        boolean content = hasBlockEntity(evidence);
        AuditRecord row = BlockEvidenceNormalizer.toAuditRecord(evidence, location);
        long bytes = taggedRetainedBytes(row);
        if (bytes < 0L || bytes > ExplosionSplitLimits.MAX_ROW_RETAINED_BYTES || bytes > maxPartBytes) {
            leaveOut();
            return false;
        }
        if (!content) {
            if (contentBytes + plainBytes + bytes > maxExplosionBytes) {
                leaveOut();
                return false;
            }
            plainRows.add(new ListedRow(row, bytes));
            plainBytes += bytes;
            return true;
        }
        // Fits once every plain row is displaced? Otherwise leave it out and displace nothing.
        if (contentBytes + bytes > maxExplosionBytes) {
            leaveOut();
            return false;
        }
        while (contentBytes + plainBytes + bytes > maxExplosionBytes) {
            ListedRow displaced = plainRows.remove(plainRows.size() - 1);
            plainBytes -= displaced.retainedBytes();
            leaveOut();
        }
        contentRows.add(new ListedRow(row, bytes));
        contentBytes += bytes;
        return true;
    }

    private void leaveOut() {
        notListed++;
        leftOutBySize++;
    }

    /**
     * Review-01 MAJOR-1: a begun block of this explosion may have been destroyed without being listed or counted (no
     * location frame or preimage, or proven destroyed but its location is no longer current). The count of
     * destroyed blocks not listed is then only a lower bound ("at least"), and the explosion is stored in parts.
     */
    public void unlisted() {
        if (!closed) countIncomplete = true;
    }

    public void stop(StopReason reason) {
        if (!closed && stopReason == StopReason.NONE && reason != null && reason != StopReason.NONE) {
            stopReason = reason;
        }
    }

    /**
     * Closes the explosion after the game's finalization returned normally. The summary is truncated when the
     * examination stopped early (its stop reason) or when a destroyed block was left out
     * ({@link BlockEffectTruncation#EXPLOSION_EVIDENCE_LIMIT}). The count is exact only when nothing stopped the
     * examination and no block could have been destroyed without being listed or counted. Idempotent.
     */
    public Summary finish() {
        if (closed) return terminalSummary;
        BlockEffectTruncation truncation = stopReason != StopReason.NONE
                ? truncationFor(stopReason)
                : notListed > 0 ? BlockEffectTruncation.EXPLOSION_EVIDENCE_LIMIT : BlockEffectTruncation.NONE;
        return close(truncation, stopReason == StopReason.NONE && !countIncomplete);
    }

    /**
     * Closes the explosion after the game's finalization threw. Every listed row is a block whose change was already
     * proven when its own callback returned, so the rows stay; the summary says the explosion was cut short by an
     * error ({@link BlockEffectTruncation#EXPLOSION_PROCESSING_FAILURE}). Idempotent.
     */
    public Summary abort() {
        if (closed) return terminalSummary;
        return close(BlockEffectTruncation.EXPLOSION_PROCESSING_FAILURE, false);
    }

    /**
     * Whether this closed explosion is stored in parts (design-02 1.1): true unless nothing stopped the examination,
     * nothing was left out, the count is exact and every listed row fits one action (with
     * {@link ExplosionSplitLimits#STORE_TRUNCATED_SINGLE_GROUP_IN_PARTS} false: true only when the rows do not fit one
     * action).
     */
    public boolean storedInParts() {
        if (!closed) throw new IllegalStateException("Explosion session is still open");
        return storedInParts;
    }

    /**
     * A2-44, explosions NOT stored in parts: hands this explosion to {@code sink} as ONE action, all or none: the
     * listed block rows (rows whose block had a block entity first, then plain rows, each in the order they were
     * listed), untagged. A whole explosion has no other row. Only when the Owner's "part 1 of 1" choice is switched off
     * ({@link ExplosionSplitLimits#STORE_TRUNCATED_SINGLE_GROUP_IN_PARTS} false) can a truncated explosion come here; it
     * then gets the n6-3 truncation row last, stamped {@code truncationOccurredAtEpochMillis}. Requires a closed
     * session that is not stored in parts. Publishes at most once: a later call returns false without calling
     * {@code sink}. A truncation-row build failure discards the whole action and propagates.
     *
     * @return whether storage took the whole action (true when there was nothing to publish)
     */
    public boolean publish(long truncationOccurredAtEpochMillis, Predicate<List<AuditRecord>> sink) {
        Objects.requireNonNull(sink, "sink");
        if (!closed) throw new IllegalStateException("Explosion session is still open");
        if (storedInParts) throw new IllegalStateException("This explosion is stored in parts");
        if (published) return false;
        published = true;
        Summary summary = terminalSummary;
        List<AuditRecord> rows = new ArrayList<>(contentRows.size() + plainRows.size());
        for (ListedRow listed : contentRows) rows.add(listed.row());
        for (ListedRow listed : plainRows) rows.add(listed.row());
        contentRows.clear();
        plainRows.clear();
        contentBytes = 0L;
        plainBytes = 0L;
        return ActionRecordBatch.collect(sink, batch -> {
            batch.addAll(rows);
            if (summary.truncated()) {
                batch.add(BlockEvidenceNormalizer.toExplosionTruncationRecord(
                        summary, truncationOccurredAtEpochMillis));
            }
        });
    }

    /**
     * Explosion split (design-02 1.2-1.3), explosions stored in parts only: builds all k parts of this closed
     * explosion, in order, before any is offered (F8: a throw here leaves nothing built and nothing offered). Block
     * rows are ordered as for {@link #publish} (block entities first) and cut into parts of at most the per-part rows
     * and bytes; each part is its part row first, then its block rows re-built with the block-form tag. Every row of
     * the explosion carries {@code explosionId} and t0 = (the earliest of the session start and every listed row's
     * time) - 1 ms (Advisor M-7), the part rows' timestamp. Each untagged row is released as its tagged row is built
     * (Advisor m-5). Builds at most once.
     *
     * @param explosionId 64 random bits naming this explosion in every tag
     * @throws IllegalStateException when the session is open, not stored in parts, or already published, or when
     *                               t0 would be before the epoch or k above {@link ExplosionPartTag#MAX_PARTS} (neither
     *                               can happen in production: see ExplosionPartTag.MAX_PARTS)
     */
    public ExplosionParts planParts(long explosionId) {
        if (!closed) throw new IllegalStateException("Explosion session is still open");
        if (!storedInParts) throw new IllegalStateException("A whole explosion is published as one action");
        if (published) throw new IllegalStateException("Explosion already published");
        published = true;
        Summary summary = terminalSummary;
        List<ListedRow> ordered = new ArrayList<>(contentRows.size() + plainRows.size());
        ordered.addAll(contentRows);
        ordered.addAll(plainRows);
        contentRows.clear();
        plainRows.clear();
        contentBytes = 0L;
        plainBytes = 0L;

        long earliest = startedAtMillis;
        for (ListedRow listed : ordered) earliest = Math.min(earliest, listed.row().occurredAtEpochMillis());
        if (earliest < 1L) throw new IllegalStateException("Explosion time is not after the epoch");
        long partTimestamp = earliest - 1L;

        // Cut into groups by the per-part row and byte budgets (bytes include the largest tag, M-1).
        List<int[]> bounds = new ArrayList<>();
        int groupStart = 0;
        long groupBytes = 0L;
        for (int i = 0; i < ordered.size(); i++) {
            long bytes = ordered.get(i).retainedBytes();
            if (i > groupStart && (i - groupStart >= maxPartRows || groupBytes + bytes > maxPartBytes)) {
                bounds.add(new int[] {groupStart, i});
                groupStart = i;
                groupBytes = 0L;
            }
            groupBytes += bytes;
        }
        bounds.add(new int[] {groupStart, ordered.size()});
        int parts = bounds.size();
        if (parts > ExplosionPartTag.MAX_PARTS) {
            throw new IllegalStateException("Explosion needs more than " + ExplosionPartTag.MAX_PARTS + " parts");
        }
        int listed = ordered.size();
        List<ExplosionParts.Part> built = new ArrayList<>(parts);
        for (int p = 0; p < parts; p++) {
            int from = bounds.get(p)[0];
            int to = bounds.get(p)[1];
            int index = p + 1;
            ExplosionPartTag partTag = ExplosionPartTag.part(explosionId, partTimestamp, index, parts,
                    to - from, listed, summary.notListed(), summary.notListedExact());
            ExplosionPartTag blockTag = ExplosionPartTag.block(explosionId, partTimestamp, index, parts);
            List<AuditRecord> rows = new ArrayList<>(1 + to - from);
            rows.add(BlockEvidenceNormalizer.toExplosionPartRecord(
                    actor, dimensionId, center, partTag, summary.truncation()));
            for (int i = from; i < to; i++) {
                rows.add(BlockEvidenceNormalizer.withExplosionTag(ordered.get(i).row(), blockTag));
                ordered.set(i, null);
            }
            long retained = 0L;
            for (AuditRecord row : rows) retained += retainedBytes(row);
            built.add(new ExplosionParts.Part(index, rows, to - from, retained));
        }
        return new ExplosionParts(dimensionId, center, explosionId, partTimestamp, listed, summary.notListed(),
                summary.notListedExact(), summary.truncation(), leftOutBySize, built);
    }

    /** Retained size of {@code row} exactly as the writer counts it (SchemaV1RowCodec.retainedBytes). */
    static long retainedBytes(AuditRecord row) {
        AuditAttribution.IndexedParty party = row.attribution().indexedParty();
        BlockPos position = row.location().position();
        return SchemaV1RowCodec.retainedBytes(new SchemaV1Row(
                row.occurredAtEpochMillis(),
                row.action().storageCode(),
                party.storageId(),
                party.canonicalName(),
                row.location().dimensionId().toString(),
                position.getX(),
                position.getY(),
                position.getZ(),
                row.subject().technicalIdentity(),
                row.evidence().encodedPayload()));
    }

    /**
     * Advisor M-1: the retained size of the untagged block {@code row} once the LONGEST block-form tag is added, as
     * the writer counts it (2 bytes per payload character), or -1 when the tagged payload would pass a codec limit.
     */
    static long taggedRetainedBytes(AuditRecord row) {
        String encoded = row.evidence().encodedPayload();
        int tagged = BlockEffectPayloadCodec.taggedEncodedLength(encoded, ExplosionPartTag.MAX_BLOCK_TAG_UTF8_BYTES);
        if (tagged < 0) return -1L;
        return retainedBytes(row) + (long) Character.BYTES * (tagged - encoded.length());
    }

    private static boolean hasBlockEntity(CommittedBlockEvidence evidence) {
        return evidence.preimage().blockEntityEvidence().status() != BlockEntityEvidence.Status.ABSENT;
    }

    private Summary close(BlockEffectTruncation truncation, boolean notListedExact) {
        closed = true;
        seenPositions.clear();
        int listedRows = contentRows.size() + plainRows.size();
        boolean fitsOneAction = listedRows <= maxPartRows && contentBytes + plainBytes <= maxPartBytes;
        boolean whole = truncation == BlockEffectTruncation.NONE && notListedExact && notListed == 0;
        // Owner decision 3 (one constant): a truncated explosion that fits one action is "part 1 of 1".
        storedInParts = !fitsOneAction
                || (!whole && ExplosionSplitLimits.STORE_TRUNCATED_SINGLE_GROUP_IN_PARTS);
        terminalSummary = new Summary(actor, dimensionId, center, workUsed, uniquePositions, truncation,
                listedRows, notListed, notListedExact);
        return terminalSummary;
    }

    private static BlockEffectTruncation truncationFor(StopReason reason) {
        return switch (reason) {
            case NONE -> BlockEffectTruncation.NONE;
            case EVIDENCE_LIMIT -> BlockEffectTruncation.EXPLOSION_EVIDENCE_LIMIT;
            case STORAGE_PRESSURE -> BlockEffectTruncation.EXPLOSION_STORAGE_PRESSURE;
            case EVIDENCE_PROCESSING_FAILURE ->
                    BlockEffectTruncation.EXPLOSION_PROCESSING_FAILURE;
        };
    }

    private record ListedRow(AuditRecord row, long retainedBytes) {
    }

    public static final class Candidate {
        private final ExplosionAuditSession owner;
        private final BlockPos position;
        private final BlockState operationState;
        private boolean consumed;

        private Candidate(
                ExplosionAuditSession owner,
                BlockPos position,
                BlockState operationState) {
            this.owner = owner;
            this.position = position;
            this.operationState = operationState;
        }

        private boolean consume(ExplosionAuditSession expectedOwner) {
            if (consumed || owner != expectedOwner) return false;
            consumed = true;
            return true;
        }
    }

    public enum StopReason {
        NONE,
        EVIDENCE_LIMIT,
        STORAGE_PRESSURE,
        EVIDENCE_PROCESSING_FAILURE
    }

    /**
     * @param listedRows block rows the explosion lists (in one action when whole, over all parts otherwise)
     * @param notListed destroyed blocks (proven by {@link #complete}) that the explosion does not list
     * @param notListedExact whether every block of the explosion's list was examined AND no block could have been
     *                       destroyed without being listed or counted, so {@code notListed} is the exact count; false
     *                       means it is a lower bound
     */
    public record Summary(
            ActorProvenance actor,
            ResourceLocation dimensionId,
            BlockPos center,
            int workUsed,
            int uniquePositions,
            BlockEffectTruncation truncation,
            int listedRows,
            int notListed,
            boolean notListedExact) {
        public Summary {
            center = center.immutable();
            truncation = Objects.requireNonNull(truncation, "truncation");
            if (listedRows < 0) throw new IllegalArgumentException("listedRows must not be negative");
            if (notListed < 0) throw new IllegalArgumentException("notListed must not be negative");
        }

        public boolean truncated() {
            return truncation != BlockEffectTruncation.NONE;
        }
    }
}
