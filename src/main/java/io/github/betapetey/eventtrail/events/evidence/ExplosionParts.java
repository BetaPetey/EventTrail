package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.events.block.BlockEffectTruncation;
import io.github.betapetey.eventtrail.events.block.ExplosionPartTag;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * Explosion split (design-02 1.2-1.3, Advisor M-3): the k parts of one explosion, every row built and validated once
 * (AuditRecord.captureAt) by ExplosionAuditSession#planParts before any part is offered. The publisher holds these
 * rows and offers each part to storage as one action; a part is never wrapped in an ActionRecordBatch, so a NO_ROOM
 * answer keeps its rows and moves no counter.
 *
 * @param listedRows       L: block rows listed in all parts
 * @param notListed        N: proven-destroyed blocks not listed
 * @param exact            whether N is exact
 * @param reason           the explosion's reason code (NONE when nothing stopped it)
 * @param leftOutBySize    rows the explosion budget (or a row too large for its tag) left out; part of N
 * @param parts            the parts in order, part i at index i - 1
 */
public record ExplosionParts(
        ResourceLocation dimensionId,
        BlockPos center,
        long explosionId,
        long partTimestamp,
        int listedRows,
        int notListed,
        boolean exact,
        BlockEffectTruncation reason,
        int leftOutBySize,
        List<Part> parts) {

    public ExplosionParts {
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        center = Objects.requireNonNull(center, "center").immutable();
        reason = Objects.requireNonNull(reason, "reason");
        parts = List.copyOf(parts);
        if (parts.isEmpty() || parts.size() > ExplosionPartTag.MAX_PARTS) {
            throw new IllegalArgumentException("An explosion has 1.." + ExplosionPartTag.MAX_PARTS + " parts");
        }
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).index() != i + 1) throw new IllegalArgumentException("Parts out of order");
        }
    }

    /** The short display form of the explosion id (first six hex digits). */
    public String displayId() {
        return ExplosionPartTag.hex(explosionId).substring(0, 6);
    }

    /**
     * One part: its part row first, then its tagged block rows, offered as one action.
     *
     * @param blockRows      block rows in this part (rows less its part row)
     * @param retainedBytes  the rows' retained size exactly as the writer counts it
     */
    public record Part(int index, List<AuditRecord> rows, int blockRows, long retainedBytes) {
        public Part {
            rows = List.copyOf(rows);
            if (rows.isEmpty() || rows.size() != blockRows + 1) {
                throw new IllegalArgumentException("A part is its part row and its block rows");
            }
            if (retainedBytes < 0L) throw new IllegalArgumentException("retainedBytes must not be negative");
        }
    }
}
