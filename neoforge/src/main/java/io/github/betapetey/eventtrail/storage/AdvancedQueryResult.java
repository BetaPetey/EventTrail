package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.audit.AuditRecord;

import java.util.List;

public record AdvancedQueryResult(
        long total,
        List<AuditRecord> entries,
        int page,
        int totalPages
) {}
