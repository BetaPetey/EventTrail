package io.github.betapetey.eventtrail.commands.presentation;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayload;
import io.github.betapetey.eventtrail.events.block.BlockEffectPayloadCodec;
import io.github.betapetey.eventtrail.events.block.BlockEffectTruncation;
import io.github.betapetey.eventtrail.events.block.ExplosionPartTag;
import io.github.betapetey.eventtrail.events.block.ExplosionPartText;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Explosion split (design-02 1.5 and 3.4, Advisor M-6 c): the completeness verdict of one explosion stored in parts,
 * computed from the part rows a reader fetched for its part timestamp. Pure; the only code that can produce COMPLETE.
 *
 * <p>COMPLETE only if part rows 1..k are all present, every present part row agrees on k, L, N, exactness and reason,
 * two rows with the same index are identical in every field (whole tag, reason and text; otherwise they disagree), the
 * present parts' block rows add up to L, N = 0, the count is exact and the reason is none. Otherwise INCOMPLETE, with
 * the missing count when the part rows agree (exact, because each stored part is whole), or with no count when no
 * part row is left or they disagree. A part row that cannot be read, or a fetched list that may be cut by the query's
 * limit, makes the verdict NOT_CHECKED, never COMPLETE.</p>
 */
public final class ExplosionCompleteness {
    private ExplosionCompleteness() {
    }

    public enum State { COMPLETE, INCOMPLETE, NOT_CHECKED }

    /** The identity of one explosion: its id and its part rows' timestamp. */
    public record Key(long explosionId, long partTimestamp) {
        public static Key of(ExplosionPartTag tag) {
            return new Key(tag.explosionId(), tag.partTimestamp());
        }
    }

    /**
     * @param partsPresent     distinct part indices found
     * @param parts            k, from the part rows (or the subject's tag when none was found)
     * @param listedNotStored  listed block rows in parts that are not stored (exact), when {@code countKnown}
     * @param notListed        destroyed blocks never listed (N), when {@code countKnown}
     * @param countKnown       whether the two counts are known
     * @param atLeast          whether N is a lower bound (inexact, or the examination stopped)
     */
    public record Verdict(State state, int partsPresent, int parts, long listedNotStored, long notListed,
                          boolean countKnown, boolean atLeast) {
        public Verdict {
            state = Objects.requireNonNull(state, "state");
        }

        public static Verdict notChecked() {
            return new Verdict(State.NOT_CHECKED, 0, 0, 0L, 0L, false, false);
        }

        static Verdict unknownCount(int parts) {
            return new Verdict(State.INCOMPLETE, 0, parts, 0L, 0L, false, false);
        }

        /** The text a lookup or inspect line gets after the explosion's tag or part text. */
        public String suffix() {
            return switch (state) {
                case NOT_CHECKED -> " - completeness not checked";
                case COMPLETE -> " - COMPLETE (" + parts + " of " + parts + " part" + (parts == 1 ? "" : "s")
                        + ", as of this lookup)";
                case INCOMPLETE -> incompleteSuffix();
            };
        }

        private String incompleteSuffix() {
            if (!countKnown) {
                return " - INCOMPLETE: its part records are missing, were purged or disagree (as of this lookup)";
            }
            StringBuilder text = new StringBuilder(" - INCOMPLETE: ")
                    .append(partsPresent).append(" of ").append(parts).append(" part")
                    .append(parts == 1 ? "" : "s").append(" stored, ")
                    .append(ExplosionPartText.grouped(listedNotStored)).append(" listed block")
                    .append(listedNotStored == 1L ? "" : "s").append(" not stored");
            if (notListed > 0L) {
                text.append(", ").append(atLeast ? "at least " : "")
                        .append(ExplosionPartText.grouped(notListed)).append(" more not listed");
            } else if (atLeast) {
                text.append(", more destroyed blocks may not be listed");
            }
            return text.append(" (as of this lookup)").toString();
        }
    }

    /**
     * The verdict for the explosion of {@code subject} (any tag of it), from {@code partRows}: the part rows fetched
     * for its part timestamp (all explosions stamped then, at or below the reader's id mark).
     *
     * @param resultMayBeCut true when the fetch returned as many rows as its limit, so more may exist
     */
    public static Verdict evaluate(ExplosionPartTag subject, List<AuditRecord> partRows, boolean resultMayBeCut) {
        Objects.requireNonNull(subject, "subject");
        if (partRows == null || resultMayBeCut) return Verdict.notChecked();
        Map<Integer, PartFact> byIndex = new HashMap<>();
        boolean disagree = false;
        for (AuditRecord row : partRows) {
            Optional<PartFact> read = PartFact.read(row);
            if (read.isEmpty()) return Verdict.notChecked();
            PartFact fact = read.get();
            if (fact.tag.partTimestamp() != subject.partTimestamp()) return Verdict.notChecked();
            if (fact.tag.explosionId() != subject.explosionId()) continue;
            PartFact earlier = byIndex.putIfAbsent(fact.tag.index(), fact);
            if (earlier != null && !earlier.equals(fact)) disagree = true;
        }
        if (byIndex.isEmpty()) return Verdict.unknownCount(subject.parts());
        PartFact first = byIndex.values().iterator().next();
        int parts = first.tag.parts();
        long sumPresent = 0L;
        for (PartFact fact : byIndex.values()) {
            ExplosionPartTag tag = fact.tag;
            if (tag.parts() != parts
                    || tag.listedRows() != first.tag.listedRows()
                    || tag.notListed() != first.tag.notListed()
                    || tag.exact() != first.tag.exact()
                    || fact.reason != first.reason) {
                disagree = true;
            }
            sumPresent += tag.blockRows();
        }
        if (parts != subject.parts()) disagree = true;
        int listed = first.tag.listedRows();
        if (sumPresent > listed) disagree = true;
        if (byIndex.size() == parts && sumPresent != listed) disagree = true;
        if (disagree) return Verdict.unknownCount(parts);
        // Review X-1: the count is a lower bound exactly when the tag says it is not exact. EXPLOSION_EVIDENCE_LIMIT
        // is written both for a complete examination whose budget left rows out (exact) and for a work-limit stop
        // (not exact), so it adds nothing to the count; the two stop reasons are never exact. A combination production
        // cannot write (a stop reason with exact = 1, or the evidence limit with exact = 1 and nothing left out) is
        // treated as disagreeing part rows: no count.
        boolean stopped = first.reason != BlockEffectTruncation.NONE
                && first.reason != BlockEffectTruncation.EXPLOSION_EVIDENCE_LIMIT;
        if (first.tag.exact() && (stopped || (first.reason == BlockEffectTruncation.EXPLOSION_EVIDENCE_LIMIT
                && first.tag.notListed() == 0))) {
            return Verdict.unknownCount(parts);
        }
        boolean atLeast = !first.tag.exact() || stopped;
        if (byIndex.size() == parts && first.tag.notListed() == 0 && !atLeast
                && first.reason == BlockEffectTruncation.NONE) {
            return new Verdict(State.COMPLETE, parts, parts, 0L, 0L, true, false);
        }
        return new Verdict(State.INCOMPLETE, byIndex.size(), parts, listed - sumPresent, first.tag.notListed(),
                true, atLeast);
    }

    /** One readable part row: its whole tag, reason and text (two rows of one index must be equal in all three). */
    private record PartFact(ExplosionPartTag tag, BlockEffectTruncation reason, String text) {
        static Optional<PartFact> read(AuditRecord row) {
            if (row == null || row.action() != AuditAction.EXPLOSION
                    || row.subject().kind() != AuditSubject.Kind.OPERATION
                    || !ExplosionPartText.TECHNICAL_IDENTITY.equals(row.subject().technicalIdentity())
                    || !(row.evidence() instanceof AuditEvidence.Block block)
                    || block.version() != BlockEffectPayloadCodec.TAGGED_VERSION) {
                return Optional.empty();
            }
            BlockEffectPayloadCodec.DecodeResult decoded = BlockEffectPayloadCodec.decode(block.encodedPayload());
            if (!decoded.successful()) return Optional.empty();
            BlockEffectPayload payload = decoded.payload();
            ExplosionPartTag tag = payload.explosionPartTag().orElse(null);
            if (tag == null || tag.form() != ExplosionPartTag.Form.PART
                    || tag.partTimestamp() != row.occurredAtEpochMillis()
                    || !ExplosionPartText.isPresentation(row.subject().presentationIdentity(), tag,
                    payload.truncation())) {
                return Optional.empty();
            }
            return Optional.of(new PartFact(tag, payload.truncation(), row.subject().presentationIdentity()));
        }
    }
}
