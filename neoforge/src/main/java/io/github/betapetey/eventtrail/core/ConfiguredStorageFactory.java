package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.storage.MySqlStorage;
import io.github.betapetey.eventtrail.storage.PostgreSqlStorage;
import io.github.betapetey.eventtrail.storage.SQLiteStorage;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;

import java.nio.file.Path;

/** Builds exactly the backend selected by EventTrail's strict configuration. */
final class ConfiguredStorageFactory implements EventTrailStorageFactory {
    @Override
    public String configuredBackendName() {
        return EventTrailDiagnostics.stableStorageBackendLabel(
                EventTrailConfig.DATABASE_BACKEND.get());
    }

    @Override
    public StorageCandidate create(Path eventTrailDirectory) {
        String backend = EventTrailConfig.normalizeBackend(EventTrailConfig.DATABASE_BACKEND.get());
        String serverId = EventTrailConfig.normalizeServerIdForBackend(
                backend, EventTrailConfig.DATABASE_SERVER_ID.get());

        io.github.betapetey.eventtrail.storage.AbstractJdbcStorage storage = switch (backend) {
            case "sqlite" -> new SQLiteStorage(eventTrailDirectory);
            case "mysql" -> createMySql(serverId);
            case "postgresql" -> createPostgreSql(serverId);
            default -> throw new IllegalArgumentException(
                    "Unknown database.backend. Use sqlite, mysql, or postgresql.");
        };
        // writer-spool design-02 2.1: <world>/eventtrail/spool/ for all three backends (today only SQLite got the
        // directory). The spool reads database.spool.* at init.
        if (eventTrailDirectory != null) storage.useDiskSpool(eventTrailDirectory);
        return candidate(storage, backend);
    }

    private static StorageCandidate candidate(
            io.github.betapetey.eventtrail.storage.ILogStorage storage, String backend) {
        return new StorageCandidate(
                storage, EventTrailDiagnostics.stableStorageBackendLabel(backend));
    }

    private static MySqlStorage createMySql(String serverId) {
        validateExternalConfig(
                "MySQL",
                EventTrailConfig.MYSQL_HOST.get(),
                EventTrailConfig.MYSQL_DATABASE.get(),
                EventTrailConfig.MYSQL_USERNAME.get());
        return new MySqlStorage(
                serverId,
                EventTrailConfig.MYSQL_HOST.get(),
                EventTrailConfig.MYSQL_PORT.get(),
                EventTrailConfig.MYSQL_DATABASE.get(),
                EventTrailConfig.MYSQL_USERNAME.get(),
                EventTrailConfig.MYSQL_PASSWORD.get(),
                EventTrailConfig.MYSQL_SSL.get(),
                EventTrailConfig.mysqlSslCaFileForConnection());
    }

    private static PostgreSqlStorage createPostgreSql(String serverId) {
        validateExternalConfig(
                "PostgreSQL",
                EventTrailConfig.POSTGRESQL_HOST.get(),
                EventTrailConfig.POSTGRESQL_DATABASE.get(),
                EventTrailConfig.POSTGRESQL_USERNAME.get());
        return new PostgreSqlStorage(
                serverId,
                EventTrailConfig.POSTGRESQL_HOST.get(),
                EventTrailConfig.POSTGRESQL_PORT.get(),
                EventTrailConfig.POSTGRESQL_DATABASE.get(),
                EventTrailConfig.POSTGRESQL_USERNAME.get(),
                EventTrailConfig.POSTGRESQL_PASSWORD.get(),
                EventTrailConfig.POSTGRESQL_SSL.get(),
                EventTrailConfig.postgresqlSslCaFileForConnection());
    }

    private static void validateExternalConfig(
            String backend, String host, String database, String username) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(backend + " host is blank");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException(backend + " database name is blank");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException(backend + " username is blank");
        }
    }
}
