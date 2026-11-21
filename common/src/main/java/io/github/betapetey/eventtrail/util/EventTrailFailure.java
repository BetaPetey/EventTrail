package io.github.betapetey.eventtrail.util;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Safe typed failure metadata. Raw exceptions remain causes only. */
public record EventTrailFailure(Code code, Phase phase, Backend backend, FileRole role) {
    private static final int MAX_CAUSE_DEPTH = 30;

    public EventTrailFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(role, "role");
    }

    public String explanation() { return code.explanation(); }
    public String action() { return code.action(); }

    /** Finds typed metadata in the bounded ordinary cause chain, never suppressed failures. */
    public static Optional<EventTrailFailure> find(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < MAX_CAUSE_DEPTH) {
            if (current instanceof Carrier carrier && carrier.failure() != null) {
                return Optional.of(carrier.failure());
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return Optional.empty();
    }

    /** Preserves a typed cause, or supplies the safe generic code for the named phase. */
    public static EventTrailFailure from(Throwable error, Phase phase, Backend backend) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(backend, "backend");
        return find(error).orElseGet(() -> new EventTrailFailure(
                genericCode(phase), phase, backend, FileRole.UNKNOWN));
    }

    public static FailureIOException io(
            Code code, Phase phase, FileRole role, Throwable cause) {
        return new FailureIOException(
                new EventTrailFailure(code, phase, Backend.SQLITE, role), cause);
    }

    /** Compatibility overload for an existing fixed, non-sensitive exception message. */
    public static FailureIOException io(
            Code code, Phase phase, FileRole role, String safeMessage, Throwable cause) {
        return new FailureIOException(
                new EventTrailFailure(code, phase, Backend.SQLITE, role), safeMessage, cause);
    }

    private static Code genericCode(Phase phase) {
        return switch (phase) {
            case SCHEMA_INITIALIZATION -> Code.STORAGE_SCHEMA_INIT_FAILED;
            case WRITER -> Code.AUDIT_WRITER_STOPPED;
            case CLEANUP -> Code.STORAGE_CLEANUP_FAILED;
            case OWNERSHIP, STORAGE_CREATION, STORAGE_INITIALIZATION ->
                    Code.STORAGE_INIT_FAILED;
            case RUNTIME_RESET, COMPATIBILITY, QUERY, MAINTENANCE, STATUS,
                    RUNTIME_SERVICE_START, UNKNOWN -> Code.RUNTIME_SERVICE_START_FAILED;
        };
    }

    public interface Carrier { EventTrailFailure failure(); }

    public static final class FailureIOException extends IOException implements Carrier {
        private final EventTrailFailure failure;

        public FailureIOException(EventTrailFailure failure, Throwable cause) {
            this(failure, "EventTrail operation failed [" +
                    Objects.requireNonNull(failure, "failure").code() + "].", cause);
        }

        public FailureIOException(EventTrailFailure failure, String safeMessage, Throwable cause) {
            super(Objects.requireNonNull(safeMessage, "safeMessage"), cause);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        @Override public EventTrailFailure failure() { return failure; }
    }

    public enum Phase {
        OWNERSHIP,
        RUNTIME_RESET,
        STORAGE_CREATION,
        STORAGE_INITIALIZATION,
        SCHEMA_INITIALIZATION,
        COMPATIBILITY,
        QUERY,
        MAINTENANCE,
        STATUS,
        WRITER,
        RUNTIME_SERVICE_START,
        CLEANUP,
        UNKNOWN
    }

    public enum Backend {
        SQLITE, MYSQL, POSTGRESQL, STORAGE;

        public static Backend fromLabel(String value) {
            if (value == null) return STORAGE;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "sqlite" -> SQLITE;
                case "mysql" -> MYSQL;
                case "postgresql" -> POSTGRESQL;
                default -> STORAGE;
            };
        }

        public String displayName() {
            return switch (this) {
                case SQLITE -> "SQLite";
                case MYSQL -> "MySQL";
                case POSTGRESQL -> "PostgreSQL";
                case STORAGE -> "Storage";
            };
        }
    }

    public enum FileRole {
        DIRECTORY, DATABASE, LOCK, WAL, SHM, JOURNAL, UNKNOWN
    }

    public enum Code {
        SQLITE_OWNER_CONFLICT(
                "Another cooperating owner holds the SQLite family lease.",
                "Identify the intended process with the host, stop the unintended owner cleanly, then retry. Uncertain ownership needs investigation. Never delete the lock."),
        SQLITE_FILE_FAMILY_SPLIT(
                "A confirmed individual-file mapping separates SQLite ownership and recovery paths.",
                "Ask the host for a qualified whole-directory mapping. Stop every user and preserve a complete backup before changing mappings or locations."),
        SQLITE_UNSAFE_ALIAS(
                "A confirmed final-file link or redirect violates unique SQLite family identity.",
                "Preserve the files and correct the mapping through the closed-world procedure. Do not create new history to bypass it."),
        SQLITE_ACCESS_DENIED(
                "The server account cannot inspect, create, access, or lock the indicated SQLite file role.",
                "Correct narrowly scoped ownership, search, read, write, or lock access with the host. Do not run the server as an administrator or grant blanket permissions."),
        SQLITE_VERIFICATION_UNAVAILABLE(
                "A required SQLite identity, mount, or lock capability is unsupported or inaccessible; danger is not established.",
                "Have the host expose the required capability or provide a qualified environment. Never disable the check."),
        SQLITE_TOPOLOGY_AMBIGUOUS(
                "Available information cannot prove that the SQLite family is co-located.",
                "Resolve the specific ambiguity with the host while preserving the files. Unreadable information is not a known bad mapping."),
        SQLITE_RECOVERY_REVIEW_REQUIRED(
                "Required SQLite database or recovery state is incomplete or inconsistent.",
                "Stop users, preserve the complete available family and backup, and obtain recovery guidance. Never delete journals or create an empty database."),
        SQLITE_IDENTITY_CHANGED(
                "A previously verified SQLite directory or file changed identity.",
                "Reconcile users and topology, preserve the data, and re-establish the qualified layout. Do not automatically bind to a replacement."),
        STORAGE_QUIESCENCE_UNPROVEN(
                "Prior native work or cleanup lacks acknowledgement.",
                "Resolve controlled shutdown and prove the old work or process gone before approved recovery. No forced lease release or file deletion."),
        STORAGE_READ_ONLY(
                "Intended writable storage is unavailable because it is read-only.",
                "Restore writability without deleting protected history or silently switching backends; distinguish this from permissions and ownership."),
        STORAGE_CAPACITY_UNAVAILABLE(
                "Intended writable storage capacity is unavailable.",
                "Restore capacity without deleting protected history or silently switching backends; distinguish this from permissions and ownership."),
        AUDIT_WRITER_STOPPED(
                "The writer no longer accepts events; history may remain readable.",
                "Inspect the typed cause and accounting; use controlled recovery after cause and native lifetime are resolved. No blind uncertain-batch retry."),
        STORAGE_SCHEMA_INIT_FAILED(
                "The storage schema initialization phase failed.",
                "Preserve data and use its validated diagnostic reference. No automatic schema repair, durability downgrade, or new database."),
        STORAGE_INIT_FAILED(
                "The storage initialization phase failed.",
                "Preserve data and use its validated diagnostic reference. No automatic schema repair, durability downgrade, or new database."),
        RUNTIME_SERVICE_START_FAILED(
                "The runtime service start phase failed.",
                "Preserve data and use its validated diagnostic reference. No automatic schema repair, durability downgrade, or new database."),
        STORAGE_CLEANUP_FAILED(
                "Storage cleanup failed without replacing the initiating failure.",
                "Preserve data and use its validated diagnostic reference. Prove native quiescence before recovery; do not force release or delete protected files.");

        private final String explanation;
        private final String action;

        Code(String explanation, String action) {
            this.explanation = explanation;
            this.action = action;
        }

        public String explanation() { return explanation; }
        public String action() { return action; }
    }
}
