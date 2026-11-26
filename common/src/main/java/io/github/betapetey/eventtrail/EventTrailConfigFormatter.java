package io.github.betapetey.eventtrail;

import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rewrites eventtrail-server.properties into stable, human-readable sections.
 *
 * This class does not own config values and does not change their meaning.
 * EventTrailConfig remains responsible for loading/defaulting/validating them.
 */
public final class EventTrailConfigFormatter {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final String FILE_NAME = "eventtrail-server.properties";
    private static final String PRESERVED_COMMENTS_BEGIN =
            "# >>> EventTrail preserved administrator comments >>>";
    private static final String PRESERVED_COMMENTS_END =
            "# <<< EventTrail preserved administrator comments <<<";

    private static final List<Section> SECTIONS = List.of(
            new Section("Retention", List.of("retention.days", "retention.reclaim.enabled")),

            new Section("Inspect", List.of("inspect.pageSize")),

            new Section("Lookup", List.of(
                    "lookup.maxRadiusBlocks",
                    "lookup.maxDaysWithoutExtendedPermission"
            )),

            // Optional: written only when an administrator has set permissions.source.
            new Section("Permissions", List.of("permissions.source"), true),

            new Section("Recording - Blocks", List.of(
                    "recording.blocks.multiPlace.enabled", "recording.blocks.explosions.enabled")),
            new Section("Recording - Containers", List.of(
                    "recording.containers.access.enabled", "recording.containers.deposits.enabled",
                    "recording.containers.withdrawals.enabled")),
            new Section("Recording - Entities / Players", List.of(
                    "recording.entities.kills.enabled", "recording.players.deaths.enabled")),
            new Section("Recording - Items", List.of(
                    "recording.items.drops.enabled", "recording.items.pickups.enabled")),

            new Section("Database", List.of(
                    "database.backend",
                    "database.serverId",
                    "database.poolSize",
                    "database.connectionTimeoutMs",
                    "database.queryTimeoutSeconds",
                    "database.spool.enabled",
                    "database.spool.maxMegabytes"
            )),

            new Section("SQLite", List.of(
                    "database.sqlite.synchronous"
            )),

            new Section("MySQL", List.of(
                    "database.mysql.host",
                    "database.mysql.port",
                    "database.mysql.database",
                    "database.mysql.username",
                    "database.mysql.password",
                    "database.mysql.ssl",
                    "database.mysql.sslCaFile"
            )),

            new Section("PostgreSQL", List.of(
                    "database.postgresql.host",
                    "database.postgresql.port",
                    "database.postgresql.database",
                    "database.postgresql.username",
                    "database.postgresql.password",
                    "database.postgresql.ssl",
                    "database.postgresql.sslCaFile"
            ))
    );

    private EventTrailConfigFormatter() {}

    static void formatIfPresent(Path config) {
        try {
            if (!EventTrailConfigFileIO.exists(config)) return;
            Properties properties = load(config);
            writeCanonical(config, properties);
        } catch (Exception e) {
            // Formatting must never prevent the server from starting.
            LOGGER.warn("Could not apply EventTrail's human-readable config formatting ({}).",
                    EventTrailDiagnostics.safeFailureSummary(e));
        }
    }

    private static Properties load(Path config) throws IOException {
        Properties properties = new Properties();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                EventTrailConfigFileIO.newInputStream(config), StandardCharsets.UTF_8))) {
            properties.load(reader);
        }
        return properties;
    }

    static void writeCanonical(Path config, Properties properties) throws IOException {
        writeCanonical(config, properties, Files::move);
    }

    static void writeCanonical(Path config, Properties properties,
                               EventTrailConfigFileIO.MoveExecutor mover) throws IOException {
        List<String> administratorComments = readAdministratorComments(config);

        EventTrailConfigFileIO.writeAtomically(config, temporary -> {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                writer.write("# ============================================================");
                writer.newLine();
                writer.write("# EventTrail Server Configuration");
                writer.newLine();
                writer.write("# ============================================================");
                writer.newLine();
                writer.write("# Changes are loaded when the server starts.");
                writer.newLine();
                writer.write("# SQLite is the default database backend.");
                writer.newLine();
                writer.write("# For a shared external database, give each Minecraft server");
                writer.newLine();
                writer.write("# a unique database.serverId.");
                writer.newLine();

                if (!administratorComments.isEmpty()) {
                    writer.newLine();
                    writer.write(PRESERVED_COMMENTS_BEGIN);
                    writer.newLine();
                    for (String comment : administratorComments) {
                        writer.write(comment);
                        writer.newLine();
                    }
                    writer.write(PRESERVED_COMMENTS_END);
                    writer.newLine();
                }

                Set<String> written = new HashSet<>();

                for (Section section : SECTIONS) {
                    if (section.optional() && section.keys().stream().noneMatch(properties::containsKey)) {
                        continue;
                    }
                    writer.newLine();
                    writer.write("# ------------------------------------------------------------");
                    writer.newLine();
                    writer.write("# " + section.name());
                    writer.newLine();
                    writer.write("# ------------------------------------------------------------");
                    writer.newLine();

                    if ("SQLite".equals(section.name())) {
                        writer.write("# Used only when database.backend=sqlite.");
                        writer.newLine();
                    } else if ("MySQL".equals(section.name())) {
                        writer.write("# Used only when database.backend=mysql.");
                        writer.newLine();
                    } else if ("PostgreSQL".equals(section.name())) {
                        writer.write("# Used only when database.backend=postgresql.");
                        writer.newLine();
                    }

                    for (String key : section.keys()) {
                        if (!properties.containsKey(key)) {
                            continue;
                        }

                        writePropertyComments(writer, key);
                        writeProperty(writer, key, properties.getProperty(key, ""));
                        written.add(key);
                    }
                }

                // Preserve future/unknown settings instead of silently dropping them.
                TreeSet<String> remaining = new TreeSet<>();
                for (String key : properties.stringPropertyNames()) {
                    if (!written.contains(key)) {
                        remaining.add(key);
                    }
                }

                if (!remaining.isEmpty()) {
                    writer.newLine();
                    writer.write("# ------------------------------------------------------------");
                    writer.newLine();
                    writer.write("# Other / Unrecognized Settings");
                    writer.newLine();
                    writer.write("# ------------------------------------------------------------");
                    writer.newLine();

                    for (String key : remaining) {
                        writeProperty(writer, key, properties.getProperty(key, ""));
                    }
                }
            }

            // Preserve timestamp/content idempotence while still hardening permissions.
            return !EventTrailConfigFileIO.exists(config)
                    || !EventTrailConfigFileIO.contentEquals(config, temporary);
        }, mover);
        EventTrailConfigFileIO.hardenExistingPermissions(config);
    }

    private static List<String> readAdministratorComments(Path config) throws IOException {
        if (!EventTrailConfigFileIO.exists(config)) return List.of();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                EventTrailConfigFileIO.newInputStream(config), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }

        boolean canonical = lines.stream()
                .map(String::trim)
                .anyMatch("# EventTrail Server Configuration"::equals);
        List<String> comments = new ArrayList<>();
        Set<String> generated = generatedComments();
        boolean insidePreservedBlock = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (canonical) {
                if (PRESERVED_COMMENTS_BEGIN.equals(trimmed)) {
                    insidePreservedBlock = true;
                    continue;
                }
                if (PRESERVED_COMMENTS_END.equals(trimmed)) {
                    insidePreservedBlock = false;
                    continue;
                }
                if (isCommentLine(trimmed) && (insidePreservedBlock || !generated.contains(trimmed))) {
                    comments.add(line);
                }
            } else if (isCommentLine(trimmed)) {
                comments.add(line);
            }
        }
        return List.copyOf(comments);
    }

    private static Set<String> generatedComments() throws IOException {
        Set<String> comments = new HashSet<>(List.of(
                "# ============================================================",
                "# ------------------------------------------------------------",
                "# EventTrail Server Configuration",
                "# Changes are loaded when the server starts.",
                "# SQLite is the default database backend.",
                "# For a shared external database, give each Minecraft server",
                "# a unique database.serverId.",
                "# Used only when database.backend=sqlite.",
                "# Used only when database.backend=mysql.",
                "# Used only when database.backend=postgresql.",
                "# Other / Unrecognized Settings"));
        StringWriter buffer = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(buffer)) {
            for (Section section : SECTIONS) {
                comments.add("# " + section.name());
                for (String key : section.keys()) writePropertyComments(writer, key);
            }
        }
        buffer.toString().lines().map(String::trim).forEach(comments::add);
        return comments;
    }

    private static boolean isCommentLine(String trimmed) {
        return trimmed.startsWith("#") || trimmed.startsWith("!");
    }

    private static void writePropertyComments(BufferedWriter writer, String key) throws IOException {
        switch (key) {
            case "retention.days" -> {
                writer.write("# Keep history for 90 days by default; 0 disables cleanup.");
                writer.newLine();
                writer.write("# Bounded startup cleanup; full-speed catch-up starts after 5 minutes with no real players.");
                writer.newLine();
                writer.write("# With players online it goes on gently (small batches, pauses); rechecks after 30 minutes.");
                writer.newLine();
                writer.write("# Deleting rows does not guarantee immediate filesystem shrinkage.");
                writer.newLine();
                writer.write("# Operators: /eventtrail purge [days] cleans up now; /eventtrail purge shrink gives space back.");
                writer.newLine();
            }
            case "retention.reclaim.enabled" -> {
                writer.write("# Permit supported low-risk automatic physical reclamation while idle. Default: true.");
                writer.newLine();
                writer.write("# Qualified SQLite INCREMENTAL files only; yields to players, investigations and writes.");
                writer.newLine();
                writer.write("# false blocks future automatic reclaim work; logical retention and reusable pages remain.");
                writer.newLine();
                writer.write("# retention.days=0 also disables automatic reclaim; new-file incremental capability remains.");
                writer.newLine();
                writer.write("# Existing SQLite modes are preserved; automatic reclaim never runs a full VACUUM or MySQL/PostgreSQL maintenance SQL; only /eventtrail purge shrink does.");
                writer.newLine();
            }
            case "inspect.pageSize" -> {
                writer.write("# Entries per inspect page (default 5, range 1-50).");
                writer.newLine();
            }
            case "lookup.maxRadiusBlocks" -> {
                writer.write("# Maximum lookup radius in blocks (default 500, range 1-10000).");
                writer.newLine();
            }
            case "lookup.maxDaysWithoutExtendedPermission" -> {
                writer.write("# Maximum days a LuckPerms user may search without");
                writer.newLine();
                writer.write("# command.eventtrail.lookup.extended. 0 means unlimited.");
                writer.newLine();
            }

            case "recording.containers.access.enabled" -> {
                writer.write("# Records proven player menu-session OPEN and CLOSE access facts.");
                writer.newLine();
                writer.write("# Independent of deposits and withdrawals; no container master switch.");
                writer.newLine();
                writer.write("# Enabled by default; adds two rows per completed session. Set false to disable both phases.");
                writer.newLine();
            }

            case "recording.containers.deposits.enabled" -> {
                writer.write("# Records proven manual player-to-storage item transfers.");
                writer.newLine();
                writer.write("# Independent of recording.containers.access.enabled and recording.containers.withdrawals.enabled.");
                writer.newLine();
                writer.write("# Enabled by default; excludes automatic/ghost-slot activity.");
                writer.newLine();
            }

            case "recording.containers.withdrawals.enabled" -> {
                writer.write("# Records proven manual storage-to-player item transfers.");
                writer.newLine();
                writer.write("# Independent of recording.containers.access.enabled and recording.containers.deposits.enabled.");
                writer.newLine();
                writer.write("# Enabled by default; excludes automatic/ghost-slot activity.");
                writer.newLine();
            }

            case "database.backend" -> {
                writer.write("# Database backend used by EventTrail.");
                writer.newLine();
                writer.write("# Options: sqlite (default), mysql, or postgresql.");
                writer.newLine();
                writer.write("# MySQL/PostgreSQL settings below are ignored while this is sqlite.");
                writer.newLine();
            }

            case "database.serverId" -> {
                writer.write("# Required nonblank ID for this Minecraft server when using an external database.");
                writer.newLine();
                writer.write("# Examples: atm11, aero, direwolf21");
                writer.newLine();
                writer.write("# If multiple servers use the same MySQL/PostgreSQL database,");
                writer.newLine();
                writer.write("# each server MUST use a different serverId or their EventTrail history can mix.");
                writer.newLine();
                writer.write("# SQLite does not persist this value; its fresh default is blank.");
                writer.newLine();
            }

            case "database.poolSize" -> {
                writer.write("# Maximum number of database connections kept in EventTrail's connection pool.");
                writer.newLine();
                writer.write("# The default is suitable for normal use; increase only for a busy external DB.");
                writer.newLine();
            }

            case "database.connectionTimeoutMs" -> {
                writer.write("# How long EventTrail waits for a database connection before failing, in milliseconds.");
                writer.newLine();
            }

            case "database.queryTimeoutSeconds" -> {
                writer.write("# Maximum time a database query may run before timing out, in seconds.");
                writer.newLine();
            }

            // Owner Q412 A (owner-texts-q405.md item d) and the Owner's drop rule (Q413 A, 4:03 PM 11-25).
            case "database.spool.maxMegabytes" -> {
                writer.write("# Size of the disk spool, in MiB (64 to 65536), counted as the bytes of the stored actions.");
                writer.newLine();
                writer.write("# The spool's files on disk can be about 17% larger than that.");
                writer.newLine();
                writer.write("# Actions are dropped only when the disk spool is full, when the drive it is on has less than");
                writer.newLine();
                writer.write("# 512 MiB free, or when they arrive faster than the disk can take them for long enough to fill");
                writer.newLine();
                writer.write("# the memory set aside for them. Each case has its own WARN.");
                writer.newLine();
            }

            case "database.sqlite.synchronous" -> {
                writer.write("# FULL (default) strengthens committed-transaction durability under SQLite's storage assumptions.");
                writer.newLine();
                writer.write("# NORMAL explicitly permits committed-transaction loss after OS/power failure.");
                writer.newLine();
                writer.write("# Only FULL or NORMAL is accepted. OFF and invalid values fail startup.");
                writer.newLine();
                writer.write("# Accepted queued entries remain volatile in either mode; accepted is not commit-confirmed.");
                writer.newLine();
                writer.write("# An uncertain commit acknowledgement proves neither absence nor confirmed success.");
                writer.newLine();
            }

            case "database.mysql.host" -> {
                writer.write("# MySQL server hostname or IP address.");
                writer.newLine();
            }

            case "database.mysql.port" -> {
                writer.write("# MySQL server port. Default: 3306.");
                writer.newLine();
            }

            case "database.mysql.database" -> {
                writer.write("# Database/schema name EventTrail should use.");
                writer.newLine();
            }

            case "database.mysql.username" -> {
                writer.write("# Database account EventTrail should authenticate as.");
                writer.newLine();
            }

            case "database.mysql.password" -> {
                writer.write("# Password for the database account above.");
                writer.newLine();
            }

            case "database.mysql.ssl" -> {
                writer.write("# Enable TLS for MySQL connections.");
                writer.newLine();
                writer.write("# true = EventTrail configures Connector/J sslMode=VERIFY_IDENTITY.");
                writer.newLine();
                writer.write("# Connector/J verifies the server certificate and hostname using its official identity policy.");
                writer.newLine();
                writer.write("# A validated database.mysql.sslCaFile is required; EventTrail does not use system-trust");
                writer.newLine();
                writer.write("# fallback or silently downgrade a TLS-enabled connection to plaintext.");
                writer.newLine();
                writer.write("# false explicitly disables TLS and uses plaintext (compatibility default).");
                writer.newLine();
                writer.write("# The database administrator must configure the server side; EventTrail configures only this client.");
                writer.newLine();
                writer.write("# Recommended: true whenever the database server supports verified TLS.");
                writer.newLine();
            }

            case "database.mysql.sslCaFile" -> {
                writer.write("# Required PEM CA certificate file used to verify MySQL servers when TLS is enabled.");
                writer.newLine();
                writer.write("# Relative paths are resolved from this config file's directory.");
                writer.newLine();
                writer.write("# This may be set only when database.mysql.ssl=true.");
                writer.newLine();
            }

            case "database.postgresql.host" -> {
                writer.write("# PostgreSQL server hostname or IP address.");
                writer.newLine();
            }

            case "database.postgresql.port" -> {
                writer.write("# PostgreSQL server port. Default PostgreSQL port: 5432.");
                writer.newLine();
            }

            case "database.postgresql.database" -> {
                writer.write("# Database name EventTrail should use.");
                writer.newLine();
            }

            case "database.postgresql.username" -> {
                writer.write("# Database account EventTrail should authenticate as.");
                writer.newLine();
            }

            case "database.postgresql.password" -> {
                writer.write("# Password for the database account above.");
                writer.newLine();
            }

            case "database.postgresql.ssl" -> {
                writer.write("# Enable TLS for PostgreSQL connections.");
                writer.newLine();
                writer.write("# true = EventTrail requires encrypted, certificate- and hostname-verified TLS (verify-full).");
                writer.newLine();
                writer.write("# EventTrail's PostgreSQL verifier requires a matching DNS/IP SAN and rejects");
                writer.newLine();
                writer.write("# common-name-only certificates.");
                writer.newLine();
                writer.write("# PostgreSQL must have TLS configured, and EventTrail must trust the server's CA certificate.");
                writer.newLine();
                writer.write("# false uses plaintext if the PostgreSQL server permits it; it will fail if TLS is required.");
                writer.newLine();
                writer.write("# Recommended: true whenever the database server supports verified TLS.");
                writer.newLine();
            }

            case "database.postgresql.sslCaFile" -> {
                writer.write("# Optional PEM CA certificate file used to verify PostgreSQL servers.");
                writer.newLine();
                writer.write("# Relative paths are resolved from this config file's directory.");
                writer.newLine();
                writer.write("# Leave blank to preserve the driver's default root-certificate behavior.");
                writer.newLine();
                writer.write("# This may be set only when database.postgresql.ssl=true.");
                writer.newLine();
            }

            case "permissions.source" -> {
                writer.write("# Which source decides EventTrail player permissions. Absent means auto.");
                writer.newLine();
                writer.write("# auto: LuckPerms if installed, else NeoForge's permission handler if a mod provides one,");
                writer.newLine();
                writer.write("# else FTB Ranks if installed, else the operator check.");
                writer.newLine();
                writer.write("# luckperms, neoforge, ftbranks or operator: only that source. If it is not installed,");
                writer.newLine();
                writer.write("# EventTrail uses the operator check and logs a warning; it never uses another mod.");
                writer.newLine();
                writer.write("# Read at server start; a change applies after a restart.");
                writer.newLine();
            }
            default -> {
                // Most general/tracking settings are self-explanatory by name.
            }
        }
    }

    private static void writeProperty(BufferedWriter writer, String key, String value) throws IOException {
        writer.write(escapeKey(key));
        writer.write('=');
        writer.write(escapeValue(value));
        writer.newLine();
    }

    private static String escapeKey(String value) {
        if (value == null) return "";

        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case ' ' -> out.append("\\ ");
                case '=' -> out.append("\\=");
                case ':' -> out.append("\\:");
                case '#' -> out.append("\\#");
                case '!' -> out.append("\\!");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\f' -> out.append("\\f");
                default -> appendPropertyCharacter(out, c);
            }
        }
        return out.toString();
    }

    private static String escapeValue(String value) {
        if (value == null) return "";

        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\f' -> out.append("\\f");
                default -> {
                    if (i == 0 && c == ' ') out.append("\\ ");
                    else appendPropertyCharacter(out, c);
                }
            }
        }
        return out.toString();
    }

    private static void appendPropertyCharacter(StringBuilder out, char c) {
        if (c >= 0x20 && c <= 0x7e) {
            out.append(c);
            return;
        }

        out.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            out.append(Character.forDigit((c >> shift) & 0xf, 16));
        }
    }

    /** A section of the canonical file; an optional section is written only when one of its keys is set. */
    private record Section(String name, List<String> keys, boolean optional) {
        Section(String name, List<String> keys) {
            this(name, keys, false);
        }
    }
}
