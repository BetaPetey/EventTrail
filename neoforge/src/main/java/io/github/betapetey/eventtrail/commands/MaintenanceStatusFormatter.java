package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.core.EventTrailMaintenance.RetentionSnapshot;
import io.github.betapetey.eventtrail.storage.RetentionResult;
import io.github.betapetey.eventtrail.storage.StorageMaintenanceSnapshot;
import io.github.betapetey.eventtrail.storage.StorageMaintenanceSnapshot.Metric;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Pure rendering of bounded immutable facts. No storage, filesystem, config or scheduling access. */
final class MaintenanceStatusFormatter {
    private MaintenanceStatusFormatter() {}

    static List<String> lines(RetentionSnapshot retention, StorageMaintenanceSnapshot storage, long now, boolean detail) {
        return lines(retention, storage, now, detail, true);
    }

    static List<String> lines(RetentionSnapshot retention, StorageMaintenanceSnapshot storage, long now, boolean detail,
                              boolean reclaimEnabled) {
        List<String> lines = new ArrayList<>();
        if (detail) lines.add("§6EventTrail Maintenance Status (cached; read-only)");
        String policy = retention.configuredDays() == 0 ? "disabled (retention.days=0)" : retention.configuredDays() + " days";
        String worker = retention.passInFlight() ? "; maintenance worker in flight"
                : retention.lifecycleActive() ? "; lifecycle active" : "; no active maintenance lifecycle";
        lines.add("§7Retention: §f" + policy + worker);
        if (retention.lastResult() == null) {
            lines.add("§7Last retention: §eNot observed; deleted count/cutoff unavailable");
        } else {
            lines.add("§7Last retention: §f" + retention.lastResult().outcome() + "; confirmed deleted rows: "
                    + retention.lastResult().deletedCount());
            lines.add("§7Retention cutoff (strictly older): §f" + Instant.ofEpochMilli(retention.lastCutoffTimestamp())
                    + " §7Result at: §f" + Instant.ofEpochMilli(retention.completedAtMillis()));
            if (detail) lines.add(retention.lastResult().outcome() == RetentionResult.Outcome.EXHAUSTED
                    ? "§7Backlog: §fNo further expired rows observed at that cutoff; later rows may age into retention"
                    : "§7Backlog: §eExpired rows may remain; no remaining-row count was queried");
        }
        lines.add("§7EventTrail reclamation: §f" + publicDisposition(storage.reclamationState().name())
                + " §8(independent of logical retention; retention.reclaim.enabled=" + reclaimEnabled + ")");
        if (!reclaimEnabled || retention.configuredDays() == 0)
            lines.add("§7Automatic reclamation is disabled by configuration; persistent file mode and internal reuse are unchanged.");
        lines.add("§7Reclamation capability: §f" + publicDisposition(storage.capability().name())
                + (storage.backend() == StorageMaintenanceSnapshot.Backend.SQLITE ? "; SQLite mode " + storage.sqliteMode() : ""));
        if (!detail) {
            switch (storage.capability()) {
                case MIGRATION_REQUIRED -> lines.addAll(legacyNoneExplanation());
                case FULL_MODE_UNCHANGED -> lines.add("§7Existing FULL database policy is preserved; EventTrail does not switch modes.");
                case EXTERNAL_DBA_MANAGED -> lines.add(capabilityExplanation(storage).getFirst());
                default -> { }
            }
        }
        lines.add("§7Allocation scope: §f" + scope(storage.scope()));
        if (storage.backend() == StorageMaintenanceSnapshot.Backend.SQLITE) {
            lines.add("§7Whole free pages reusable: §f" + metric(storage.allocation().reusableWholePageBytes(), true)
                    + " §7Main-file apparent length: §f" + metric(storage.allocation().mainFileLengthBytes(), true));
        } else {
            lines.add("§7Physical allocation/reclaimable bytes: §eUnavailable; no external allocation queries are issued");
        }
        lines.add(observation(storage, now));
        if (detail) {
            lines.addAll(capabilityExplanation(storage));
            var policyFacts = storage.policyStatus();
            if (policyFacts != null) {
                lines.add("§7Observed SQLite JDBC/native: §f" + policyFacts.jdbcVersion() + " / " + policyFacts.nativeVersion());
                lines.add("§7Reclaim policy: §f" + policyFacts.phase() + "; reason=" + publicDisposition(policyFacts.reason())
                        + "; max next unit=" + policyFacts.unitCap() + " pages");
                lines.add("§7Episode pages (initial/reserve/remaining/baseline): §f" + value(policyFacts.initialFreePages())
                        + "/" + value(policyFacts.reservePages()) + "/" + value(policyFacts.remainingPages())
                        + "/" + value(policyFacts.baselinePages()));
                lines.add("§7Reserved/forfeited allowance (not reclaimed pages): §f" + value(policyFacts.reservedPages())
                        + "/" + value(policyFacts.forfeitedPages()));
                lines.add("§7Last timing ns (native/settled unit/slice/checkpoint): §f" + value(policyFacts.lastNativeNanos())
                        + "/" + value(policyFacts.lastSettledUnitNanos()) + "/" + value(policyFacts.lastSliceNanos())
                        + "/" + value(policyFacts.lastCheckpointNanos()) + "; finite operation, no universal latency guarantee");
            }
            if (storage.backend() == StorageMaintenanceSnapshot.Backend.SQLITE) {
                var allocation = storage.allocation();
                lines.add("§7Page size: §f" + metric(allocation.pageSizeBytes(), true)
                        + " §7Page count: §f" + metric(allocation.pageCount(), false)
                        + " §7Freelist pages: §f" + metric(allocation.freelistPages(), false));
                lines.add("§7WAL apparent length: §f" + metric(allocation.walLengthBytes(), true)
                        + " §7SHM apparent length: §f" + metric(allocation.shmLengthBytes(), true));
                lines.add("§7Filesystem allocated bytes: §f" + metric(allocation.filesystemAllocatedBytes(), true));
                lines.add("§7Free whole pages exclude partial-page slack; they are not guaranteed physically reclaimable bytes.");
                lines.add("§7Apparent file length is not filesystem blocks, backup/snapshot space or outer-volume reclamation.");
            }
            lines.add("§7Current row count: §eUnavailable; status performs no count or full scan");
            var reclaim = storage.lastReclamation();
            if (reclaim == null) {
                lines.add("§7Last EventTrail reclamation: §eNot run; physical reduction unavailable (not zero-byte success)");
            } else {
                lines.add("§7Last EventTrail reclamation: §f" + publicDisposition(reclaim.outcome().name()) + " at " + Instant.ofEpochMilli(reclaim.completedAtMillis()));
                lines.add("§7Released pages: §f" + metric(reclaim.pagesReleased(), false)
                        + " §7Logical page-byte reduction: §f" + metric(reclaim.logicalPageBytesReduced(), true));
                lines.add("§7Observed main-file reduction: §f" + metric(reclaim.apparentMainFileBytesReduced(), true)
                        + " §7Allocated-block reduction: §f" + metric(reclaim.filesystemAllocatedBytesReduced(), true));
            }
        }
        return List.copyOf(lines);
    }

    private static List<String> capabilityExplanation(StorageMaintenanceSnapshot snapshot) {
        return switch (snapshot.capability()) {
            case MIGRATION_REQUIRED -> legacyNoneExplanation();
            case FULL_MODE_UNCHANGED -> List.of("§7Existing FULL auto-vacuum mode is preserved. EventTrail does not switch that database policy or enable incremental work.");
            case INCREMENTAL_CAPABLE -> List.of("§7Qualified incremental work requires successful retention, material free space and safe idle admission. Each episode retains half its initial free pool; work is gradual, never a full VACUUM.");
            case EXTERNAL_DBA_MANAGED -> List.of(
                    snapshot.backend() == StorageMaintenanceSnapshot.Backend.MYSQL
                            ? "§7InnoDB manages purge/reuse. EventTrail does not issue OPTIMIZE TABLE; rebuilds are DBA-managed."
                            : "§7PostgreSQL/DBA policy manages autovacuum/autoanalyze and reuse. EventTrail does not issue VACUUM, ANALYZE or VACUUM FULL.",
                    "§7External allocation belongs to the whole shared table/relation, not this server ID; precise per-server reclaimable bytes are unavailable.");
            case UNSUPPORTED_RUNTIME -> List.of("§7Reclamation is unavailable for this runtime; supported ordinary retention/storage remains independent.");
            case UNQUALIFIED_LAYOUT -> List.of("§7Automatic reclamation requires the qualified 4096-byte page layout; this file is preserved without rewriting it.");
            case UNKNOWN -> List.of("§7Reclamation capability is unknown; no mode or reclaimability is assumed.");
            case UNRECOGNIZED_OBJECTS -> List.of("§7Unexpected database objects prevent whole-file reclamation. Administrator review is required; objects are not changed and ordinary supported storage remains available.");
        };
    }

    // Preserve internal/historical state identity; only its administrator-facing interpretation changes.
    private static String publicDisposition(String value) {
        return "MIGRATION_REQUIRED".equals(value) ? "LEGACY_NONE_REUSE_ONLY" : value;
    }

    private static List<String> legacyNoneExplanation() {
        return List.of("§7Legacy/pre-release SQLite NONE mode: logical retention and internal reuse remain supported.",
                "§7Automatic physical shrinking is unavailable for this file. New EventTrail databases use incremental reclamation.");
    }

    private static String value(Long value) { return value == null ? "unavailable" : Long.toString(value); }

    private static String scope(StorageMaintenanceSnapshot.Scope scope) {
        return switch (scope) {
            case LOCAL_DATABASE_FILE -> "whole local SQLite database file; retention belongs to this local history";
            case WHOLE_EXTERNAL_TABLE -> "whole external table, potentially shared; retention is server-ID-scoped";
            case WHOLE_EXTERNAL_RELATION -> "whole external relation, potentially shared; retention is server-ID-scoped";
            case UNKNOWN -> "unavailable";
        };
    }

    private static String observation(StorageMaintenanceSnapshot snapshot, long now) {
        if (snapshot.observation() != StorageMaintenanceSnapshot.Observation.AVAILABLE) {
            return "§7Allocation observation: §e" + snapshot.observation()
                    + (snapshot.observedAtMillis() == 0L ? "; timestamp unavailable" : " at " + Instant.ofEpochMilli(snapshot.observedAtMillis()))
                    + "; no freshness or zero-allocation claim";
        }
        long ageSeconds = Math.max(0L, now - snapshot.observedAtMillis()) / 1000L;
        return "§7Allocation observation: §f" + Instant.ofEpochMilli(snapshot.observedAtMillis())
                + " (cached; " + ageSeconds + "s old; not a current-size guarantee)";
    }

    private static String metric(Metric metric, boolean bytes) {
        return metric.value() == null ? "unavailable" : metric.value() + (bytes ? " bytes" : "")
                + (metric.quality() == StorageMaintenanceSnapshot.Quality.EXACT ? " (exact observed)" : " (engine-estimated)");
    }
}
