package io.github.betapetey.eventtrail;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.github.betapetey.eventtrail.security.PemCertificateBundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;

/** EventTrail server configuration and validation. */
public class EventTrailConfig {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    static final String CONFIG_FILE_NAME = "eventtrail-server.properties";
    public static final int MAX_EXTERNAL_SERVER_ID_LENGTH = 64;
    public static final int DEFAULT_LOOKUP_MAX_DAYS_WITHOUT_EXTENDED_PERMISSION = 0;
    public static final int MAX_LOOKUP_DAYS_WITHOUT_EXTENDED_PERMISSION = 36500;
    public static final String SQLITE_SYNCHRONOUS_KEY = "database.sqlite.synchronous";
    /**
     * Optional permission-source setting. It is not written into the file
     * unless an administrator adds it; an absent key means {@code auto}.
     */
    public static final String PERMISSIONS_SOURCE_KEY = "permissions.source";

    /** SQLite committed-transaction durability; neither mode persists the in-memory queue. */
    public enum SQLiteSynchronousMode { FULL, NORMAL }

    private static volatile SQLiteSynchronousMode sqliteSynchronous = SQLiteSynchronousMode.FULL;
    private static volatile String permissionsSource;

    public static final IntValue LOG_RETENTION_DAYS = new IntValue("retention.days", 90, 0, 36500);
    public static final BooleanValue RETENTION_RECLAIM_ENABLED =
            new BooleanValue("retention.reclaim.enabled", true);
    public static final IntValue PAGE_SIZE = new IntValue("inspect.pageSize", 5, 1, 50);
    public static final IntValue MAX_SEARCH_RADIUS = new IntValue("lookup.maxRadiusBlocks", 500, 1, 10000);
    public static final ValidatedIntValue LOOKUP_MAX_DAYS_WITHOUT_EXTENDED_PERMISSION =
            new ValidatedIntValue(
                    "lookup.maxDaysWithoutExtendedPermission",
                    DEFAULT_LOOKUP_MAX_DAYS_WITHOUT_EXTENDED_PERMISSION,
                    0,
                    MAX_LOOKUP_DAYS_WITHOUT_EXTENDED_PERMISSION);

    public static final BooleanValue TRACK_MULTI_BLOCK_PLACE = new BooleanValue("recording.blocks.multiPlace.enabled", true);
    public static final BooleanValue TRACK_EXPLOSIONS = new BooleanValue("recording.blocks.explosions.enabled", true);
    public static final BooleanValue TRACK_CONTAINER_ACCESS =
            new BooleanValue("recording.containers.access.enabled", true);
    public static final BooleanValue TRACK_CONTAINER_DEPOSITS =
            new BooleanValue("recording.containers.deposits.enabled", true);
    public static final BooleanValue TRACK_CONTAINER_WITHDRAWALS =
            new BooleanValue("recording.containers.withdrawals.enabled", true);
    public static final BooleanValue TRACK_ENTITY_KILLS = new BooleanValue("recording.entities.kills.enabled", true);
    public static final BooleanValue TRACK_PLAYER_DEATHS = new BooleanValue("recording.players.deaths.enabled", true);
    public static final BooleanValue TRACK_ITEM_DROPS = new BooleanValue("recording.items.drops.enabled", true);
    public static final BooleanValue TRACK_ITEM_PICKUPS = new BooleanValue("recording.items.pickups.enabled", true);

    // Database backend. SQLite is intentionally the default and requires no setup.
    public static final StringValue DATABASE_BACKEND = new StringValue("database.backend", "sqlite");
    public static final StringValue DATABASE_SERVER_ID = new StringValue("database.serverId", "");
    public static final IntValue DATABASE_POOL_SIZE = new IntValue("database.poolSize", 10, 1, 50);
    public static final IntValue DATABASE_CONNECTION_TIMEOUT_MS = new IntValue("database.connectionTimeoutMs", 10000, 1000, 60000);
    public static final IntValue DATABASE_QUERY_TIMEOUT_SECONDS = new IntValue("database.queryTimeoutSeconds", 30, 1, 300);
    /** writer-spool design-01 12 (L10): the disk spool for actions the database writer cannot take yet. */
    public static final BooleanValue DATABASE_SPOOL_ENABLED = new BooleanValue("database.spool.enabled", true);
    /** writer-spool design-01 5 and 12: the spool's size cap, 64 to 65,536 MiB. */
    public static final IntValue DATABASE_SPOOL_MAX_MEGABYTES = new IntValue("database.spool.maxMegabytes", 512, 64, 65536);

    public static final StringValue MYSQL_HOST = new StringValue("database.mysql.host", "127.0.0.1");
    public static final IntValue MYSQL_PORT = new IntValue("database.mysql.port", 3306, 1, 65535);
    public static final StringValue MYSQL_DATABASE = new StringValue("database.mysql.database", "eventtrail");
    public static final StringValue MYSQL_USERNAME = new StringValue("database.mysql.username", "eventtrail");
    public static final StringValue MYSQL_PASSWORD = new StringValue(
            "database.mysql.password", "", StringWhitespacePolicy.PRESERVE);
    public static final BooleanValue MYSQL_SSL = new BooleanValue("database.mysql.ssl", false);
    public static final StringValue MYSQL_SSL_CA_FILE =
            new StringValue("database.mysql.sslCaFile", "");

    public static final StringValue POSTGRESQL_HOST = new StringValue("database.postgresql.host", "127.0.0.1");
    public static final IntValue POSTGRESQL_PORT = new IntValue("database.postgresql.port", 5432, 1, 65535);
    public static final StringValue POSTGRESQL_DATABASE = new StringValue("database.postgresql.database", "eventtrail");
    public static final StringValue POSTGRESQL_USERNAME = new StringValue("database.postgresql.username", "eventtrail");
    public static final StringValue POSTGRESQL_PASSWORD = new StringValue(
            "database.postgresql.password", "", StringWhitespacePolicy.PRESERVE);
    public static final BooleanValue POSTGRESQL_SSL = new BooleanValue("database.postgresql.ssl", false);
    public static final StringValue POSTGRESQL_SSL_CA_FILE =
            new StringValue("database.postgresql.sslCaFile", "");

    private static final IntValue[] INT_VALUES = {
            LOG_RETENTION_DAYS, PAGE_SIZE, MAX_SEARCH_RADIUS,
            DATABASE_POOL_SIZE, DATABASE_CONNECTION_TIMEOUT_MS,
            DATABASE_QUERY_TIMEOUT_SECONDS, MYSQL_PORT, POSTGRESQL_PORT, DATABASE_SPOOL_MAX_MEGABYTES
    };

    private static final BooleanValue[] BOOL_VALUES = {
            RETENTION_RECLAIM_ENABLED,
            TRACK_MULTI_BLOCK_PLACE, TRACK_EXPLOSIONS,
            TRACK_CONTAINER_ACCESS, TRACK_CONTAINER_DEPOSITS, TRACK_CONTAINER_WITHDRAWALS,
            TRACK_ENTITY_KILLS, TRACK_PLAYER_DEATHS, TRACK_ITEM_DROPS, TRACK_ITEM_PICKUPS,
            MYSQL_SSL, POSTGRESQL_SSL, DATABASE_SPOOL_ENABLED
    };

    private static final StringValue[] STRING_VALUES = {
            DATABASE_BACKEND, DATABASE_SERVER_ID,
            MYSQL_HOST, MYSQL_DATABASE, MYSQL_USERNAME, MYSQL_PASSWORD, MYSQL_SSL_CA_FILE,
            POSTGRESQL_HOST, POSTGRESQL_DATABASE, POSTGRESQL_USERNAME, POSTGRESQL_PASSWORD,
            POSTGRESQL_SSL_CA_FILE
    };

    private static Path configPath;
    private static Path mysqlSslCaPath;
    private static Path postgresqlSslCaPath;

    static void register(Path path) {
        register(path, Files::move);
    }

    static void register(Path path, EventTrailConfigFileIO.MoveExecutor mover) {
        configPath = path;
        Properties props = loadPropertiesStrict(configPath, "load");
        mysqlSslCaPath = null;
        postgresqlSslCaPath = null;

        String selectedBackend = normalizeBackend(
                props.getProperty(DATABASE_BACKEND.key, DATABASE_BACKEND.def));
        SQLiteSynchronousMode selectedSynchronous = parseSQLiteSynchronousMode(
                props.getProperty(SQLITE_SYNCHRONOUS_KEY, SQLiteSynchronousMode.FULL.name()));
        ValidatedTlsFiles tlsFiles = validateActiveTlsSettings(configPath, props, selectedBackend);

        LOOKUP_MAX_DAYS_WITHOUT_EXTENDED_PERMISSION.load(props);
        for (IntValue value : INT_VALUES) value.load(props);
        for (BooleanValue value : BOOL_VALUES) value.load(props);
        for (StringValue value : STRING_VALUES) value.load(props);

        DATABASE_BACKEND.set(normalizeBackend(DATABASE_BACKEND.get()));
        DATABASE_SERVER_ID.set(normalizeServerIdForBackend(
                DATABASE_BACKEND.get(), DATABASE_SERVER_ID.get()));
        sqliteSynchronous = selectedSynchronous;
        permissionsSource = props.getProperty(PERMISSIONS_SOURCE_KEY);
        mysqlSslCaPath = tlsFiles.mysqlCaFile();
        postgresqlSslCaPath = tlsFiles.postgresqlCaFile();
        publish(props, mover);
    }

    public static String mysqlSslCaFileForConnection() {
        return mysqlSslCaPath == null ? null : mysqlSslCaPath.toString();
    }

    /** Permission for future automatic reclaim work; does not alter retention or SQLite layout. */
    public static boolean reclaimEnabled() {
        return RETENTION_RECLAIM_ENABLED.get();
    }

    /**
     * The raw {@value #PERMISSIONS_SOURCE_KEY} value as loaded at start-up, or
     * null when the key is absent. It is deliberately not validated here: the
     * permission selection at server start matches it case-insensitively after
     * trimming, and an unknown value uses the operator check with a warning
     * instead of stopping the server.
     */
    public static String permissionsSourceSetting() {
        return permissionsSource;
    }

    /** Captured once by each SQLite storage lifetime; external backends do not apply it. */
    public static SQLiteSynchronousMode sqliteSynchronousMode() {
        return sqliteSynchronous;
    }

    public static SQLiteSynchronousMode parseSQLiteSynchronousMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FULL" -> SQLiteSynchronousMode.FULL;
            case "NORMAL" -> SQLiteSynchronousMode.NORMAL;
            default -> throw new EventTrailConfigException(
                    SQLITE_SYNCHRONOUS_KEY + " must be FULL or NORMAL; invalid values are not defaulted.");
        };
    }

    public static String postgresqlSslCaFileForConnection() {
        return postgresqlSslCaPath == null ? null : postgresqlSslCaPath.toString();
    }

    private static ValidatedTlsFiles validateActiveTlsSettings(
            Path config, Properties properties, String backend) {
        return switch (backend) {
            case "mysql" -> new ValidatedTlsFiles(
                    validateTlsSettings(
                            config, properties, MYSQL_SSL.key, MYSQL_SSL.def,
                            MYSQL_SSL_CA_FILE.key, true),
                    null);
            case "postgresql" -> new ValidatedTlsFiles(
                    null,
                    validateTlsSettings(
                            config, properties, POSTGRESQL_SSL.key, POSTGRESQL_SSL.def,
                            POSTGRESQL_SSL_CA_FILE.key, false));
            default -> new ValidatedTlsFiles(null, null);
        };
    }

    private static Path validateTlsSettings(
            Path config,
            Properties properties,
            String sslKey,
            boolean sslDefault,
            String caFileKey,
            boolean requireCaFile) {
        boolean enabled = parseStrictTlsBoolean(properties, sslKey, sslDefault);
        String configuredCaFile = properties.getProperty(caFileKey, "").trim();
        if (!enabled && !configuredCaFile.isEmpty()) {
            throw new EventTrailConfigException(
                    caFileKey + " cannot be set while " + sslKey + " is false.");
        }
        if (enabled && requireCaFile && configuredCaFile.isEmpty()) {
            throw new EventTrailConfigException(
                    caFileKey + " must be set while " + sslKey + " is true.");
        }
        if (!enabled || configuredCaFile.isEmpty()) return null;

        Path certificateFile;
        try {
            certificateFile = Path.of(configuredCaFile);
            if (!certificateFile.isAbsolute()) {
                Path configDirectory = config.toAbsolutePath().normalize().getParent();
                if (configDirectory == null) throw new IllegalArgumentException("Missing config directory");
                certificateFile = configDirectory.resolve(certificateFile);
            }
            certificateFile = certificateFile.toAbsolutePath().normalize();
            PemCertificateBundle.read(certificateFile);
            return certificateFile;
        } catch (Exception failure) {
            throw new EventTrailConfigException(
                    caFileKey + " must identify a readable PEM X.509 certificate file.");
        }
    }

    private static boolean parseStrictTlsBoolean(
            Properties properties, String key, boolean defaultValue) {
        String raw = properties.getProperty(key);
        if (raw == null) return defaultValue;
        String normalized = raw.trim();
        if ("true".equalsIgnoreCase(normalized)) return true;
        if ("false".equalsIgnoreCase(normalized)) return false;
        throw new EventTrailConfigException(key + " must be exactly true or false.");
    }

    private record ValidatedTlsFiles(Path mysqlCaFile, Path postgresqlCaFile) {}

    public static void save() {
        if (configPath == null) return;

        /*
         * Preserve unknown/future settings already present in the file, then
         * overlay EventTrail's known in-memory values. This also lets us compare
         * the semantic property set and avoid rewriting an unchanged config
         * on every server startup.
         */
        Properties existing = loadPropertiesStrict(configPath, "save");
        publish(existing, Files::move);
    }

    private static void publish(Properties existing, EventTrailConfigFileIO.MoveExecutor mover) {
        Properties props = new Properties();
        props.putAll(existing);
        props.setProperty(SQLITE_SYNCHRONOUS_KEY, sqliteSynchronous.name());
        LOOKUP_MAX_DAYS_WITHOUT_EXTENDED_PERMISSION.write(props);
        for (IntValue value : INT_VALUES) value.write(props);
        for (BooleanValue value : BOOL_VALUES) value.write(props);
        for (StringValue value : STRING_VALUES) value.write(props);

        try {
            if (existing.equals(props)) {
                EventTrailConfigFileIO.hardenExistingPermissions(configPath);
                return;
            }
            if (!loadPropertiesStrict(configPath, "verify").equals(existing)) {
                throw new IOException("Configuration changed during loading; retry with a stable file.");
            }
            EventTrailConfigFormatter.writeCanonical(configPath, props, mover);
        } catch (IOException | SecurityException e) {
            throw configFailure("save", e);
        }
    }

    static Properties loadPropertiesStrict(Path path) {
        return loadPropertiesStrict(path, "load");
    }

    private static Properties loadPropertiesStrict(Path path, String operation) {
        Properties properties = EventTrailConfigInput.inputProperties();

        try {
            if (!EventTrailConfigFileIO.exists(path)) return properties;
        } catch (IOException | SecurityException failure) {
            throw configFailure(operation, failure);
        }

        try (InputStream in = EventTrailConfigFileIO.newInputStream(path)) {
            properties.load(in);
            return properties;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            throw configFailure(operation, failure);
        }
    }

    private static EventTrailConfigException configFailure(String operation, Throwable cause) {
        return new EventTrailConfigException(
                "Could not " + operation + " " + CONFIG_FILE_NAME
                        + "; any existing file was left unchanged.",
                cause);
    }

    public static String normalizeBackend(String value) {
        String v = value == null ? "sqlite" : value.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "sqlite", "mysql", "postgresql" -> v;
            default -> throw new EventTrailConfigException(
                    "database.backend must be sqlite, mysql, or postgresql.");
        };
    }

    public static String normalizeServerIdForBackend(String backend, String value) {
        String normalizedBackend = normalizeBackend(backend);
        if (isExternalBackend(normalizedBackend)) return validateExternalServerId(value);
        return value == null ? "" : value.trim();
    }

    public static String validateExternalServerId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new EventTrailConfigException(
                    "database.serverId must not be blank for an external database.");
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_EXTERNAL_SERVER_ID_LENGTH) {
            throw new EventTrailConfigException(
                    "database.serverId must not exceed " + MAX_EXTERNAL_SERVER_ID_LENGTH + " characters.");
        }
        return normalized;
    }

    private static boolean isExternalBackend(String backend) {
        return switch (backend) {
            case "mysql", "postgresql" -> true;
            default -> false;
        };
    }

    public static final class IntValue {
        private final String key;
        private final int def;
        private final int min;
        private final int max;
        private int value;

        IntValue(String key, int def, int min, int max) {
            this.key = key;
            this.def = def;
            this.min = min;
            this.max = max;
            this.value = def;
        }

        public int get() {
            return value;
        }

        void load(Properties props) {
            value = normalize(props.getProperty(key));
        }

        String key() { return key; }

        int normalize(String raw) {
            try {
                int parsed = Integer.parseInt(raw == null ? String.valueOf(def) : raw.trim());
                return Math.max(min, Math.min(max, parsed));
            } catch (NumberFormatException e) {
                return def;
            }
        }

        void write(Properties props) {
            props.setProperty(key, String.valueOf(value));
        }
    }

    public static final class BooleanValue {
        private final String key;
        private final boolean def;
        private volatile boolean value;

        BooleanValue(String key, boolean def) {
            this.key = key;
            this.def = def;
            this.value = def;
        }

        public boolean get() {
            return value;
        }

        void load(Properties props) {
            value = parseBooleanProperty(props, key, def, LOGGER::warn);
        }

        void write(Properties props) {
            props.setProperty(key, String.valueOf(value));
        }
    }

    /**
     * Integer setting whose boundary values have security meaning. Invalid
     * input is rejected instead of clamped so a negative history limit can
     * never become the special unlimited value {@code 0}.
     */
    public static final class ValidatedIntValue {
        private final String key;
        private final int def;
        private final int min;
        private final int max;
        private int value;

        ValidatedIntValue(String key, int def, int min, int max) {
            this.key = key;
            this.def = def;
            this.min = min;
            this.max = max;
            this.value = def;
        }

        public int get() {
            return value;
        }

        void load(Properties props) {
            String raw = props.getProperty(key);
            if (raw == null) {
                value = def;
                return;
            }

            String normalized = raw.trim();
            if (normalized.isEmpty()
                    || normalized.chars().anyMatch(c -> c < '0' || c > '9')) {
                throw invalidValue();
            }
            final int parsed;
            try {
                parsed = Integer.parseInt(normalized);
            } catch (NumberFormatException failure) {
                throw invalidValue();
            }
            if (parsed < min || parsed > max) throw invalidValue();
            value = parsed;
        }

        void write(Properties props) {
            props.setProperty(key, String.valueOf(value));
        }

        private EventTrailConfigException invalidValue() {
            return new EventTrailConfigException(
                    key + " must be a whole number from " + min + " to " + max + ".");
        }
    }

    static boolean parseBooleanProperty(
            Properties properties,
            String key,
            boolean defaultValue,
            Consumer<String> warningSink) {
        String raw = properties.getProperty(key);
        if (raw == null) return defaultValue;

        String normalized = raw.trim();
        if ("true".equalsIgnoreCase(normalized)) return true;
        if ("false".equalsIgnoreCase(normalized)) return false;

        warningSink.accept("Invalid boolean value for '" + key
                + "'; using default " + defaultValue + ".");
        return defaultValue;
    }

    enum StringWhitespacePolicy {
        TRIM,
        PRESERVE
    }

    public static final class StringValue {
        private final String key;
        private final String def;
        private final StringWhitespacePolicy whitespacePolicy;
        private String value;

        StringValue(String key, String def) {
            this(key, def, StringWhitespacePolicy.TRIM);
        }

        StringValue(String key, String def, StringWhitespacePolicy whitespacePolicy) {
            this.key = key;
            this.def = def;
            this.whitespacePolicy = whitespacePolicy;
            this.value = def;
        }

        public String get() {
            return value;
        }

        void set(String value) {
            this.value = value == null ? def : value;
        }

        void load(Properties props) {
            String loaded = props.getProperty(key, def);
            value = whitespacePolicy == StringWhitespacePolicy.TRIM ? loaded.trim() : loaded;
        }

        void write(Properties props) {
            props.setProperty(key, value == null ? "" : value);
        }
    }
}
