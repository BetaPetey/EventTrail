package io.github.betapetey.eventtrail.commands.presentation;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditAttribution;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayload;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayloadCodec;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.util.AuditSnbtParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Combines adjacent stored lookup rows into display lines.
 *
 * <p>Pure logic over rows already decoded by the read path: no storage, no
 * game registry and no chat components. The input is the lookup scan in the
 * SQL order {@code timestamp DESC, id DESC}; a row's index in that list stands
 * for its position in the order. Every key is a stored value compared by exact
 * equality.</p>
 *
 * <p>A burst is a maximal stretch of adjacent combinable rows sharing the
 * action, the indexed party (storage id, canonical indexed name, identity
 * kind), the world, the cause (kind, causality, detail) and, for transfers,
 * the backing reference, the container position, the stored container
 * label and the payload's shown cause with its providerId (graves design r6,
 * 1.9; null on both sides for a row without a cause). A burst ends before a row that has another burst key, that never
 * combines, that is more than the gap limit older than the row before it, or
 * that is more than the span limit older than the burst's newest row. Inside
 * a burst, one line per subject: for blocks the registry id (the stored block
 * state up to {@code [}) plus the logged block name; for transfers the item
 * id, the metadata state and the stored stack details without their top-level
 * {@code count} tag. Lines are ordered by their newest member.</p>
 */
public final class LookupGrouper {
    /** W: the largest gap between adjacent rows of one burst, in milliseconds (a gap of exactly W joins). */
    public static final long GAP_LIMIT_MS = 5_000L;
    /** S: the largest distance from a burst's newest row to any of its rows, in milliseconds (exactly S joins). */
    public static final long SPAN_LIMIT_MS = 300_000L;

    private static final Set<AuditAction> COMBINABLE = EnumSet.of(
            AuditAction.BLOCK_BREAK,
            AuditAction.BLOCK_PLACE,
            AuditAction.EXPLOSION,
            AuditAction.CONTAINER_DEPOSIT,
            AuditAction.CONTAINER_WITHDRAWAL);
    private static final String STACK_COUNT_TAG = "count";

    private LookupGrouper() {
    }

    /**
     * Groups one scan.
     *
     * @param scan rows in the SQL order, newest first
     * @param gapLimitMs W
     * @param spanLimitMs S
     * @param windowStartMillis the query's inclusive lower time bound ({@code timestamp >= fromTime})
     * @param scanTruncated whether rows older than the last scan row exist in the result
     * @return the lines, ordered by their newest member's position
     */
    public static List<Line> group(
            List<AuditRecord> scan,
            long gapLimitMs,
            long spanLimitMs,
            long windowStartMillis,
            boolean scanTruncated) {
        Objects.requireNonNull(scan, "scan");
        if (gapLimitMs < 0L || spanLimitMs < 0L) {
            throw new IllegalArgumentException("Grouping limits cannot be negative");
        }
        List<RowKey> keys = new ArrayList<>(scan.size());
        for (AuditRecord record : scan) {
            keys.add(keyOf(Objects.requireNonNull(record, "scan row")));
        }

        List<Line> lines = new ArrayList<>();
        int start = 0;
        while (start < scan.size()) {
            RowKey first = keys.get(start);
            if (first == null) {
                lines.add(Line.single(start, scan.get(start)));
                start++;
                continue;
            }

            long burstNewest = scan.get(start).occurredAtEpochMillis();
            int end = start + 1;
            while (end < scan.size()) {
                RowKey next = keys.get(end);
                if (next == null || !next.burst().equals(first.burst())) break;
                long current = scan.get(end).occurredAtEpochMillis();
                if (scan.get(end - 1).occurredAtEpochMillis() - current > gapLimitMs) break;
                if (burstNewest - current > spanLimitMs) break;
                end++;
            }

            // Partial marks are per burst: any line of a burst that touches an
            // edge could gain members from a row past that edge, including a
            // line whose own oldest member is not the burst's last row.
            boolean atScanEnd = scanTruncated && end == scan.size();
            long burstOldest = scan.get(end - 1).occurredAtEpochMillis();
            // The nearest row the time window excludes is at windowStart - 1; it
            // could have joined only if its gap to the burst's oldest row is at most W.
            // Such a row can only follow the result's last row, so only the burst that
            // ends the whole result (the last scan burst of a scan that was not cut) is marked.
            boolean atWindowStart = !scanTruncated && end == scan.size() && windowStartMillis > 0L
                    && burstOldest - windowStartMillis < gapLimitMs;

            Map<LineKey, LineBuilder> burstLines = new LinkedHashMap<>();
            for (int index = start; index < end; index++) {
                burstLines.computeIfAbsent(keys.get(index).line(), ignored -> new LineBuilder())
                        .add(index, scan.get(index));
            }
            for (LineBuilder builder : burstLines.values()) {
                lines.add(builder.build(atScanEnd, atWindowStart));
            }
            start = end;
        }
        return List.copyOf(lines);
    }

    /** The row's full combine key, or null when the row never combines. */
    private static RowKey keyOf(AuditRecord record) {
        if (!COMBINABLE.contains(record.action())) return null;
        AuditSubject subject = record.subject();
        if (record.evidence() instanceof AuditEvidence.Block) {
            // The "Explosion evidence" truncation row is an OPERATION subject.
            if (subject.kind() != AuditSubject.Kind.BLOCK) return null;
            String blockState = subject.technicalIdentity();
            int properties = blockState.indexOf('[');
            String registryId = properties < 0 ? blockState : blockState.substring(0, properties);
            return new RowKey(
                    burstKey(record, null, null, null),
                    new LineKey(registryId, subject.presentationIdentity(), null, null));
        }
        if (record.evidence() instanceof AuditEvidence.ContainerTransfer transfer) {
            if (subject.kind() != AuditSubject.Kind.CONTAINER) return null;
            CapturedTransfer captured = capturedTransfer(transfer);
            if (captured == null) return null;
            return new RowKey(
                    burstKey(record,
                            subject.technicalIdentity(),
                            record.location().position(),
                            subject.presentationIdentity(),
                            captured.cause()),
                    new LineKey(
                            transfer.itemId().toString(),
                            null,
                            ContainerTransferPayload.MetadataState.CAPTURED.name(),
                            captured.details()));
        }
        // Unavailable evidence: the payload did not decode.
        return null;
    }

    private static BurstKey burstKey(
            AuditRecord record,
            String backingReference,
            BlockPos containerPosition,
            String containerLabel) {
        return burstKey(record, backingReference, containerPosition, containerLabel, null);
    }

    private static BurstKey burstKey(
            AuditRecord record,
            String backingReference,
            BlockPos containerPosition,
            String containerLabel,
            CauseKey cause) {
        AuditAttribution attribution = record.attribution();
        AuditAttribution.IndexedParty party = attribution.indexedParty();
        return new BurstKey(
                record.action(),
                party.storageId(),
                party.canonicalName(),
                party.kind(),
                record.location().dimensionId().toString(),
                attribution.causeKind(),
                attribution.causality(),
                attribution.causeDetail(),
                backingReference,
                containerPosition,
                containerLabel,
                cause);
    }

    /**
     * The stored stack details of a CAPTURED transfer whose payload decodes and
     * matches its row, with the top-level {@code count} tag removed, and its
     * shown cause's key; null for a row that shows a fidelity note (decode
     * failure, mismatch, UNAVAILABLE). Nested {@code count} tags (container
     * contents) stay in the key.
     */
    private static CapturedTransfer capturedTransfer(AuditEvidence.ContainerTransfer transfer) {
        try {
            ContainerTransferPayloadCodec.DecodeResult decoded =
                    ContainerTransferPayloadCodec.decode(transfer.encodedPayload());
            if (!decoded.successful()) return null;
            ContainerTransferPayload payload = decoded.payload();
            if (!transferMatches(payload, transfer)) return null;
            if (payload.metadataState() != ContainerTransferPayload.MetadataState.CAPTURED) return null;
            AuditSnbtParser.ParseResult parsed = AuditSnbtParser.parsePayload(payload.metadataSnbt(), 0);
            if (!parsed.successful() || parsed.tag() == null) return null;
            CompoundTag details = parsed.tag().copy();
            details.remove(STACK_COUNT_TAG);
            return new CapturedTransfer(details, causeKey(payload));
        } catch (RuntimeException | LinkageError | StackOverflowError | IOException ignored) {
            // Fail closed: a row whose stored details cannot be read never combines.
            return null;
        }
    }

    /**
     * Graves design r6, 1.9: the decoded shown cause, compared by its canonical cause-block bytes, so a grave row never
     * combines with an un-caused row or with a row of another owner. A row with a cause also keys on its providerId, so
     * rows whose wording differs by provider (a Simple Tomb {@code .pulled} portion beside a take) stay apart. Null for a
     * row without a cause and for a row that is not a grave row ({@link GraveWording#isGraveProvider}): r6 1.9 adds the
     * cause to the key for grave rows only ("Rows that combine today still combine"), so a caused row of another
     * provider (a GF-ONPLAYER row) combines exactly as before.
     */
    private static CauseKey causeKey(ContainerTransferPayload payload) throws IOException {
        if (payload.shownCause() == null || !GraveWording.isGraveProvider(payload.providerId())) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            ShownCause.writeBlock(output, payload.shownCause());
        }
        return new CauseKey(payload.providerId(), HexFormat.of().formatHex(bytes.toByteArray()));
    }

    private record CapturedTransfer(CompoundTag details, CauseKey cause) {
    }

    /** A shown cause's canonical block bytes (as hex) and its row's providerId. */
    private record CauseKey(String providerId, String causeBlock) {
    }

    private static boolean transferMatches(
            ContainerTransferPayload payload,
            AuditEvidence.ContainerTransfer transfer) {
        TransactionDirection direction = transfer.direction()
                == AuditEvidence.TransferDirection.DEPOSIT
                ? TransactionDirection.DEPOSIT
                : TransactionDirection.WITHDRAWAL;
        return payload.direction() == direction
                && payload.itemId().equals(transfer.itemId())
                && payload.quantity() == transfer.quantity();
    }

    private static long quantityOf(AuditRecord record) {
        return record.evidence() instanceof AuditEvidence.ContainerTransfer transfer
                ? transfer.quantity()
                : 0L;
    }

    /**
     * One display line.
     *
     * @param memberPositions positions of the members in the SQL order, newest first
     * @param newest the newest member, which a line shows (age, party, detail, position)
     * @param quantitySum the members' transfer quantities added up; 0 for a line that is not a transfer
     * @param newestMillis the newest member's timestamp
     * @param oldestMillis the oldest member's timestamp
     * @param partialAtScanEnd the line's burst reaches the end of a truncated scan
     * @param partialAtWindowStart a row just before the time window could have joined the line's burst
     *        (only the burst that ends a scan that was not cut)
     */
    public record Line(
            List<Long> memberPositions,
            AuditRecord newest,
            long quantitySum,
            long newestMillis,
            long oldestMillis,
            boolean partialAtScanEnd,
            boolean partialAtWindowStart) {

        public Line {
            memberPositions = List.copyOf(Objects.requireNonNull(memberPositions, "memberPositions"));
            if (memberPositions.isEmpty()) throw new IllegalArgumentException("A line needs a member");
            newest = Objects.requireNonNull(newest, "newest");
            if (quantitySum < 0L) throw new IllegalArgumentException("quantitySum cannot be negative");
            if (oldestMillis > newestMillis) {
                throw new IllegalArgumentException("A line's oldest member cannot be newer than its newest");
            }
        }

        /** A line of one row that never combined. */
        static Line single(long position, AuditRecord record) {
            long at = record.occurredAtEpochMillis();
            return new Line(List.of(position), record, quantityOf(record), at, at, false, false);
        }

        /** k, the number of stored rows on the line. */
        public int count() {
            return memberPositions.size();
        }

        public boolean combined() {
            return memberPositions.size() > 1;
        }

        public long spanMillis() {
            return newestMillis - oldestMillis;
        }

        /** Whether the line is shown with "at least". */
        public boolean partial() {
            return partialAtScanEnd || partialAtWindowStart;
        }
    }

    private record RowKey(BurstKey burst, LineKey line) {
    }

    private record BurstKey(
            AuditAction action,
            String partyStorageId,
            String partyIndexedName,
            AuditAttribution.IdentityKind partyKind,
            String world,
            AuditAttribution.CauseKind causeKind,
            AuditAttribution.Causality causality,
            String causeDetail,
            String backingReference,
            BlockPos containerPosition,
            String containerLabel,
            CauseKey cause) {
    }

    /** Blocks: registry id and logged name. Transfers: item id, metadata state and count-free stack details. */
    private record LineKey(
            String subjectId,
            String loggedName,
            String metadataState,
            CompoundTag stackDetails) {
    }

    private static final class LineBuilder {
        private final List<Long> positions = new ArrayList<>();
        private AuditRecord newest;
        private long newestMillis;
        private long oldestMillis;
        private long quantitySum;

        void add(int position, AuditRecord record) {
            long at = record.occurredAtEpochMillis();
            if (newest == null) {
                newest = record;
                newestMillis = at;
                oldestMillis = at;
            }
            positions.add((long) position);
            oldestMillis = Math.min(oldestMillis, at);
            quantitySum += quantityOf(record);
        }

        Line build(boolean partialAtScanEnd, boolean partialAtWindowStart) {
            return new Line(positions, newest, quantitySum, newestMillis, oldestMillis,
                    partialAtScanEnd, partialAtWindowStart);
        }
    }
}
