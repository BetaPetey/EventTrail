package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.audit.AuditRecord;

import java.util.List;

/** Storage API used by the structured /eventtrail lookup command. */
public interface AdvancedLogStorage extends ILogStorage {
    long captureHighWaterMark();

    AdvancedQueryResult queryAdvanced(AdvancedQuery query);

    String getBackendName();

    String getServerId();

    default StorageTransportSecurity getTransportSecurity() {
        return StorageTransportSecurity.unknown();
    }

    /**
     * Explosion split (design-02 3.1): the part rows of the explosions whose part rows are stamped
     * {@code partTimestamp}, on this server, with an id at most {@code maxIdInclusive}, in id order, at most
     * {@code limit}. Runs on a read worker. This default cannot run it and throws; a reader then shows
     * "completeness not checked", never complete.
     */
    default List<AuditRecord> explosionParts(long partTimestamp, long maxIdInclusive, int limit) {
        throw new UnsupportedOperationException("Storage cannot read explosion part rows");
    }
}
