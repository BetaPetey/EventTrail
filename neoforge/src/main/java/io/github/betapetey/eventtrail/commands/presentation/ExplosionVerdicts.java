package io.github.betapetey.eventtrail.commands.presentation;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec;
import io.github.betapetey.eventtrail.events.block.ExplosionPartTag;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Explosion split (design-02 3.1-3.2; Advisor M-6 b, d, e): the completeness verdicts of the explosions stored in
 * parts that one lookup page or inspect snapshot shows. Read once, on the query thread, when the page or snapshot is
 * read ({@link #read}); rendering (also later pages of an inspect snapshot on the server thread) only looks them up.
 * A tagged row whose explosion has no verdict here reads "completeness not checked", never complete.
 */
public final class ExplosionVerdicts {
    /** At most this many distinct explosions are checked per page, one read each; the rest read "not checked". */
    public static final int MAX_EXPLOSIONS_PER_PAGE = 8;

    /**
     * Rows one verdict read fetches (M-6 b): every explosion's part rows stamped with the same time share it, so it
     * stays well above {@link ExplosionPartTag#MAX_PARTS}; a result that reaches it reads "not checked".
     */
    public static final int PART_QUERY_LIMIT = 256;

    private static final ExplosionVerdicts NONE = new ExplosionVerdicts(Map.of());

    /** One verdict read: the part rows stamped {@code partTimestamp} with an id at most {@code maxIdInclusive}. */
    @FunctionalInterface
    public interface PartReader {
        List<AuditRecord> read(long partTimestamp, long maxIdInclusive, int limit) throws Exception;
    }

    private final Map<ExplosionCompleteness.Key, ExplosionCompleteness.Verdict> verdicts;

    private ExplosionVerdicts(Map<ExplosionCompleteness.Key, ExplosionCompleteness.Verdict> verdicts) {
        this.verdicts = Map.copyOf(verdicts);
    }

    /** No verdict read: every tagged row reads "completeness not checked". */
    public static ExplosionVerdicts none() {
        return NONE;
    }

    /**
     * Reads the verdicts of the first {@link #MAX_EXPLOSIONS_PER_PAGE} distinct explosions among {@code rows}, with
     * one guarded read per part timestamp (M-6 e: any failure, timeout or unsupported storage reads "not checked"
     * for the explosions stamped then and never fails the page). Only part rows with an id at most
     * {@code maxIdInclusive} count (M-6 d: the page's id mark), so the verdict can only err towards INCOMPLETE. Never
     * runs on the server thread.
     */
    public static ExplosionVerdicts read(List<AuditRecord> rows, PartReader reader, long maxIdInclusive) {
        if (rows == null || rows.isEmpty() || reader == null) return NONE;
        Map<ExplosionCompleteness.Key, ExplosionPartTag> subjects = new LinkedHashMap<>();
        for (AuditRecord row : rows) {
            Optional<ExplosionPartTag> tag = tagOf(row);
            if (tag.isEmpty()) continue;
            subjects.putIfAbsent(ExplosionCompleteness.Key.of(tag.get()), tag.get());
            if (subjects.size() >= MAX_EXPLOSIONS_PER_PAGE) break;
        }
        if (subjects.isEmpty()) return NONE;
        Map<ExplosionCompleteness.Key, ExplosionCompleteness.Verdict> read = new LinkedHashMap<>();
        // Review N-1: one read per part timestamp; explosions stamped with the same time share its rows. A read that
        // failed stays null here, so every explosion stamped then reads "not checked" without another read.
        Map<Long, List<AuditRecord>> fetched = new HashMap<>();
        for (Map.Entry<ExplosionCompleteness.Key, ExplosionPartTag> subject : subjects.entrySet()) {
            ExplosionCompleteness.Verdict verdict;
            try {
                long partTimestamp = subject.getKey().partTimestamp();
                List<AuditRecord> partRows;
                if (fetched.containsKey(partTimestamp)) {
                    partRows = fetched.get(partTimestamp);
                } else {
                    fetched.put(partTimestamp, null);
                    partRows = reader.read(partTimestamp, maxIdInclusive, PART_QUERY_LIMIT);
                    fetched.put(partTimestamp, partRows);
                }
                verdict = ExplosionCompleteness.evaluate(subject.getValue(), partRows,
                        partRows == null || partRows.size() >= PART_QUERY_LIMIT);
            } catch (Exception | LinkageError failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                verdict = ExplosionCompleteness.Verdict.notChecked();
            }
            read.put(subject.getKey(), verdict);
        }
        return new ExplosionVerdicts(read);
    }

    /** The verdict for the explosion of {@code tag}; "not checked" when it was not read. */
    public ExplosionCompleteness.Verdict verdictFor(ExplosionPartTag tag) {
        Objects.requireNonNull(tag, "tag");
        ExplosionCompleteness.Verdict verdict = verdicts.get(ExplosionCompleteness.Key.of(tag));
        return verdict == null ? ExplosionCompleteness.Verdict.notChecked() : verdict;
    }

    /** The explosion tag of a stored row of an explosion stored in parts (payload version 2), if it has one. */
    public static Optional<ExplosionPartTag> tagOf(AuditRecord row) {
        if (row == null || row.action() != AuditAction.EXPLOSION
                || !(row.evidence() instanceof AuditEvidence.Block block)
                || block.version() != BlockEffectPayloadCodec.TAGGED_VERSION) {
            return Optional.empty();
        }
        BlockEffectPayloadCodec.DecodeResult decoded = BlockEffectPayloadCodec.decode(block.encodedPayload());
        return decoded.successful() ? decoded.payload().explosionPartTag() : Optional.empty();
    }
}
