package io.github.betapetey.eventtrail.audit;

/** Exact column projection for one EventTrail schema-v1 audit row. */
public record SchemaV1Row(
        long timestamp,
        String actionCode,
        String indexedUuid,
        String indexedName,
        String dimensionId,
        int x,
        int y,
        int z,
        String subjectIdentity,
        String evidencePayload) {
}
