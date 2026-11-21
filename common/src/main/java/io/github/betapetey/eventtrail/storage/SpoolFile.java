package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.util.EventTrailFailure;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * The disk spool's own SQLite file {@code <world>/eventtrail/spool/spool.db} (writer-spool design-02 2, 3, 7; the
 * Advisor's S-1, S-2, S-6, S-7, S-9, S-11). One plain sqlite-jdbc connection, opened under the
 * {@link SQLiteFileOwnership} lease and kept through every native call and the physical close. Not thread-safe: the
 * caller ({@link DiskSpool}) holds its one spool lock around every call, and every transaction begins and ends
 * inside one call. No SQL text here names another file; the spool is never ATTACHed to the target (S-1).
 */
final class SpoolFile {
    static final String FILE_NAME = "spool.db";
    static final String DAMAGE_MARKER = "spool.db.damaged";
    static final String ASIDE = "aside";
    static final String HELD = "held";
    static final String SET_PROPERTIES = "set.properties";
    /** "ETSP". */
    static final int APPLICATION_ID = 0x45545350;
    static final int USER_VERSION = 1;
    static final int BUSY_TIMEOUT_MILLIS = 1000;
    /** {@code max_page_count} backstop above the cap (design-02 3.3 step 7, S-11). */
    static final long PAGE_LIMIT_HEADROOM_BYTES = 64L * 1024L * 1024L;
    /**
     * design-02 18, lead decision L11, settled by the Owner (11-14, about 5:01 PM ET): set-aside and held spool files
     * and stored actions that failed their check are deleted once older than {@code retention.days}, with a warning
     * and a count; nothing is deleted when retention is off. Read through
     * {@link DiskSpool.Settings#deleteKeptAfterRetention()} so both branches are unit-tested.
     */
    static final boolean DELETE_KEPT_AFTER_RETENTION = true;
    static final int SQLITE_CORRUPT = 11;
    static final int SQLITE_FULL = 13;
    static final int SQLITE_NOTADB = 26;
    private static final DateTimeFormatter SET_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] FAMILY_SUFFIXES = {"-wal", "-shm", "-journal"};

    /** Opens a connection to the leased file; the bundled driver in production, another driver in the L17 test. */
    @FunctionalInterface
    interface ConnectionFactory {
        Connection connect(String url) throws SQLException;

        static ConnectionFactory bundled() {
            return url -> {
                final Driver driver;
                try {
                    driver = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException unavailable) {
                    throw new SQLException("SQLite JDBC driver unavailable", "08001", unavailable);
                }
                Connection connection = driver.connect(url, new Properties());
                if (connection == null) throw new SQLException("SQLite JDBC driver refused the spool URL", "08001");
                return connection;
            };
        }
    }

    /** The binding a spool's frames were captured under (design-01 8.4). */
    record Binding(int backend, String serverId) {
        Binding {
            if (backend < 1 || backend > 3) throw new IllegalArgumentException("spool backend code 1..3");
            serverId = serverId == null ? "" : serverId;
        }
    }

    /** The one meta row (design-02 3.1). */
    record Meta(byte[] spoolId, int backend, String serverId, long createdMillis, boolean positionCreated,
                long highWater, long replayedThrough, long waitingFrames, long waitingRows, long waitingBytes,
                long unreadableFrames, long unreadableRows, long unreadableBytes, boolean clean) {
        String spoolIdHex() { return HexFormat.of().formatHex(spoolId); }
        Binding binding() { return new Binding(backend, serverId); }
    }

    /** A frame row as read by the writer (design-02 4.5). */
    record StoredFrame(long seq, int rowCount, long minRowTs, byte[] payload, long crc32c) {}

    /** One staged action ready for the insert: payload already encoded on the spool thread. */
    record EncodedFrame(int rowCount, long minRowTs, byte[] payload) {}

    /** A family moved aside or held at this start (design-02 7.1, 7.4). */
    record MovedSet(String kind, String folder, String reason, Meta metaOrNull) {}

    /** The spool is off for this run, files untouched as far as the reason says (design-02 5.1 reason codes). */
    static final class SpoolOff extends Exception {
        final String reason;

        SpoolOff(String reason, Throwable cause) {
            super(reason, cause, false, false);
            this.reason = reason;
        }
    }

    /** A failed insert batch: the SQLite failure and the high-water read back on the same connection (S-5). */
    static final class InsertFailure extends Exception {
        final SQLException failure;
        /** The high-water read back after the rollback; -1 when the read-back failed too. */
        final long readBackHighWater;

        InsertFailure(SQLException failure, long readBackHighWater) {
            super(failure);
            this.failure = failure;
            this.readBackHighWater = readBackHighWater;
        }
    }

    /** Result of {@link #open}. */
    /**
     * {@code closedUnmarked} (MINOR-5, r2): the last run closed the file on purpose without the clean mark (clean = 2:
     * damage, or the writer still running at stop), so a missing -wal is the close's own checkpoint, not a loss.
     */
    record Opened(SpoolFile file, boolean fresh, boolean notClosedCleanly, boolean walMissing, List<MovedSet> movedNow,
                  boolean closedUnmarked) {}

    /** clean = 2 (MINOR-5): closed on purpose without the clean mark; 0 = open or crashed; 1 = clean. */
    static final int CLOSED_UNMARKED = 2;

    /** R-9 (r9) test seam: thrown by {@link #moveFamily} in place of writing set.properties (null in production). */
    volatile IOException setPropertiesFailureForTest;

    private final Path directory;
    private final Path databasePath;
    private final long capBytes;
    private final ConnectionFactory factory;
    private SQLiteFileOwnership lease;
    private Connection connection;
    private Meta meta;
    private int pageSize;
    private boolean closed;

    private SpoolFile(Path directory, long capBytes, ConnectionFactory factory) {
        this.directory = directory;
        this.databasePath = directory.resolve(FILE_NAME);
        this.capBytes = capBytes;
        this.factory = factory;
    }

    Meta meta() { return meta; }
    Path directory() { return directory; }
    Path databasePath() { return databasePath; }

    /**
     * R-4 (r9), tests only: one update on this file's own connection. No other connection can open the file while it
     * holds the EXCLUSIVE lock, so a test that changes a stored frame in the run does it here, on the spool thread.
     */
    int executeUpdateForTest(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    /**
     * design-02 5.1 step 3 with the Advisor's m-1: lease, note the -wal, connect, EXCLUSIVE locking, identity (read
     * before {@code journal_mode=WAL}, so a foreign file is never converted), then the settings of 3.3, each read back.
     * A fresh file is created (3.1); a corrupt, foreign or damaged one is set aside whole and a fresh one with a new
     * spool id is started (7.1). Any other failure is {@link SpoolOff}, files untouched.
     */
    static Opened open(Path spoolDirectory, Binding binding, long capBytes, ConnectionFactory factory)
            throws SpoolOff {
        List<MovedSet> moved = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            SpoolFile file = new SpoolFile(spoolDirectory, capBytes, factory);
            boolean walExisted = existsNoFollow(file.sibling("-wal"));
            boolean marker = existsNoFollow(spoolDirectory.resolve(DAMAGE_MARKER));
            file.lease = acquireLease(file.databasePath);
            String reason = marker ? null : headerReason(file.databasePath);
            Meta readable = null;
            if (reason == null) {
                try {
                    file.connection = connect(factory, file.databasePath);
                } catch (SpoolOff off) {
                    file.releaseQuietly();
                    throw off;
                }
            }
            if (reason == null) try {
                Identity identity = file.probeIdentity();
                if (identity.fresh && !marker) {
                    try {
                        file.createFresh(binding);
                    } catch (SQLException failure) {
                        file.closeAndRelease(false);
                        throw new SpoolOff(failure instanceof ReadbackMismatch ? "settings-readback"
                                : "create-failed", failure);
                    }
                    return new Opened(file, true, false, false, List.copyOf(moved), false);
                }
                reason = marker ? "damaged-in-run" : identity.reason;
                if (reason == null) {
                    file.applySettings(false);
                    try {
                        readable = file.readMeta();
                        file.checkEnds(readable);
                    } catch (MetaInvalid invalid) {
                        reason = "meta-invalid";
                    }
                }
                if (reason == null) {
                    boolean notClean = !readable.clean();
                    boolean closedUnmarked = notClean && file.rawClean() == CLOSED_UNMARKED;
                    file.meta = readable;
                    file.firstTransaction();
                    return new Opened(file, false, notClean, notClean && !closedUnmarked && !walExisted,
                            List.copyOf(moved), closedUnmarked);
                }
                if (readable == null && marker) readable = file.tryReadMeta();
            } catch (SQLException failure) {
                int code = primaryCode(failure);
                if (code == SQLITE_NOTADB) {
                    reason = "not-a-database";
                } else if (code == SQLITE_CORRUPT) {
                    reason = "corrupt";
                } else {
                    file.closeAndRelease(false);
                    throw new SpoolOff(failure instanceof ReadbackMismatch ? "settings-readback"
                            : offReasonFor(failure), failure);
                }
            } catch (RuntimeException failure) {
                file.closeAndRelease(false);
                throw new SpoolOff("unexpected-failure", failure);
            }
            Properties props = setProperties(readable, reason, null);
            String folder = setFolderName(System.currentTimeMillis(), reason);
            file.moveFamily(ASIDE, folder, props);
            moved.add(new MovedSet(ASIDE, folder, reason, readable));
        }
        throw new SpoolOff("set-aside-move-failed", null);
    }

    // ---- open helpers ---------------------------------------------------------------------------------------

    private record Identity(boolean fresh, String reason) {}

    /** A read-back that does not match its setting (design-02 3.3: spool off, reason settings-readback). */
    static final class ReadbackMismatch extends SQLException {
        ReadbackMismatch(String setting) { super("spool setting read-back mismatch: " + setting, "ETSP1"); }
    }

    private static final class MetaInvalid extends Exception {
        MetaInvalid(String why) { super(why, null, false, false); }
    }

    private static SQLiteFileOwnership acquireLease(Path database) throws SpoolOff {
        try {
            return SQLiteFileOwnership.acquire(database);
        } catch (IOException | RuntimeException failure) {
            throw new SpoolOff(leaseReason(failure), failure);
        } catch (LinkageError failure) {
            throw new SpoolOff("native-unavailable", failure);
        }
    }

    static String leaseReason(Throwable failure) {
        var identified = EventTrailFailure.find(failure);
        if (identified.isEmpty()) return failure instanceof IOException ? "filesystem-unsupported" : "unexpected-failure";
        return switch (identified.get().code()) {
            case SQLITE_OWNER_CONFLICT -> "already-owned";
            case SQLITE_RECOVERY_REVIEW_REQUIRED -> "recovery-files-without-database";
            case SQLITE_ACCESS_DENIED -> "access-denied";
            case SQLITE_VERIFICATION_UNAVAILABLE, SQLITE_UNSAFE_ALIAS -> "filesystem-unsupported";
            default -> "unexpected-failure";
        };
    }

    /** A read-only look (MINOR-6): the waiting count, exact only after a clean close with no -wal left. */
    record Peek(long waitingFrames, boolean exact) {}

    /**
     * MINOR-6 (r2), design-02 5.1 step 1 (n-9) for a disabled spool: reads the waiting count from spool.db opened
     * read-only and immutable (no -wal or -shm is opened or created, nothing is recovered, checkpointed, moved or
     * marked), under the lease. A file not closed cleanly may hold more in its -wal: then the count is a lower bound.
     */
    static Peek peek(Path spoolDirectory, ConnectionFactory factory) throws SpoolOff {
        Path database = spoolDirectory.resolve(FILE_NAME);
        String reason = headerReason(database);
        if (reason != null) throw new SpoolOff(reason, null);
        boolean walExists = existsNoFollow(spoolDirectory.resolve(FILE_NAME + "-wal"));
        SQLiteFileOwnership owner = acquireLease(database);
        try (Connection c = connectUrl(factory, "jdbc:sqlite:" + database.toAbsolutePath().toUri()
                + "?mode=ro&immutable=1");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT waiting_frames, clean FROM spool_meta WHERE one = 1")) {
            if (!rs.next()) throw new SpoolOff("meta-invalid", null);
            long waiting = rs.getLong(1);
            boolean clean = rs.getInt(2) == 1;
            if (waiting < 0) throw new SpoolOff("meta-invalid", null);
            return new Peek(waiting, clean && !walExists);
        } catch (SQLException failure) {
            throw new SpoolOff(offReasonFor(failure), failure);
        } finally {
            try { owner.releaseOnlyAfterQuiescent(); }
            catch (IOException | RuntimeException ignored) { /* poisoned, kept */ }
        }
    }

    private static Connection connect(ConnectionFactory factory, Path database) throws SpoolOff {
        return connectUrl(factory, "jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static Connection connectUrl(ConnectionFactory factory, String url) throws SpoolOff {
        try {
            return factory.connect(url);
        } catch (LinkageError unavailable) {
            throw new SpoolOff("native-unavailable", unavailable);
        } catch (SQLException failure) {
            if (failure.getCause() instanceof ReflectiveOperationException
                    || failure.getCause() instanceof LinkageError) {
                throw new SpoolOff("native-unavailable", failure);
            }
            throw new SpoolOff(offReasonFor(failure), failure);
        } catch (RuntimeException failure) {
            throw new SpoolOff("native-unavailable", failure);
        }
    }

    static String offReasonFor(SQLException failure) {
        return switch (primaryCode(failure)) {
            case 3, 8, 14, 23 -> "access-denied";      // PERM, READONLY, CANTOPEN, AUTH
            case 5, 6 -> "already-owned";             // BUSY, LOCKED: another holder
            default -> "unexpected-failure";          // IOERR, FULL and anything else: files untouched
        };
    }

    /** SQLite's primary result code from a driver failure (extended codes keep it in the low byte). */
    static int primaryCode(SQLException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getErrorCode() != 0) return sql.getErrorCode() & 0xFF;
        }
        return 0;
    }

    /**
     * m-1, before any SQLite connection: the 100-byte header read as plain bytes. A file that is not SQLite, a foreign
     * {@code application_id} or an unknown {@code user_version} of ours is set aside without ever being opened by
     * SQLite, because closing a connection may checkpoint or remove a -wal (found by SpoolFileTest: SQLite removed the
     * -wal next to a garbage file on close), and a set-aside family must be moved as found. Returns null when the
     * connection may decide (missing or 0-byte file, an empty or foreign file with id 0, or our own id and version).
     */
    static String headerReason(Path database) {
        byte[] header = new byte[100];
        int read = 0;
        try (var in = Files.newInputStream(database, LinkOption.NOFOLLOW_LINKS)) {
            while (read < header.length) {
                int n = in.read(header, read, header.length - read);
                if (n < 0) break;
                read += n;
            }
        } catch (java.nio.file.NoSuchFileException absent) {
            return null;
        } catch (IOException | RuntimeException unreadable) {
            return null;  // the connection reports what it finds
        }
        if (read == 0) return null;
        if (read < header.length) return "not-a-database";
        byte[] magic = "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < magic.length; i++) if (header[i] != magic[i]) return "not-a-database";
        java.nio.ByteBuffer fields = java.nio.ByteBuffer.wrap(header);
        int userVersion = fields.getInt(60);
        int applicationId = fields.getInt(68);
        if (applicationId == 0) return null;
        if (applicationId != APPLICATION_ID) return "foreign-application-id";
        if (userVersion != USER_VERSION) return "unknown-format-version";
        return null;
    }

    /** m-1: EXCLUSIVE first, then identity, before any setting that could convert a foreign file. */
    private Identity probeIdentity() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA locking_mode=EXCLUSIVE");
            requireText(st, "PRAGMA locking_mode", "exclusive", "locking_mode");
            long applicationId = scalar(st, "PRAGMA application_id");
            long userVersion = scalar(st, "PRAGMA user_version");
            long objects = scalar(st, "SELECT count(*) FROM sqlite_master");
            if (applicationId == 0 && userVersion == 0 && objects == 0) return new Identity(true, null);
            if (applicationId != APPLICATION_ID) return new Identity(false, "foreign-application-id");
            if (userVersion != USER_VERSION) return new Identity(false, "unknown-format-version");
            if (scalar(st, "PRAGMA auto_vacuum") != 2) return new Identity(false, "unknown-format");
            if (!"wal".equalsIgnoreCase(text(st, "PRAGMA journal_mode"))) return new Identity(false, "unknown-format");
            long tables = scalar(st, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN "
                    + "('spool_meta', 'spool_frame')");
            if (tables != 2) return new Identity(false, "unknown-format");
            return new Identity(false, null);
        }
    }

    /** design-02 3.3 steps 2 to 7 (step 1 ran in the probe, step 8 in {@link #createFresh}); each read back. */
    private void applySettings(boolean fresh) throws SQLException {
        try (Statement st = connection.createStatement()) {
            try (ResultSet journal = st.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!journal.next() || !"wal".equalsIgnoreCase(journal.getString(1))) {
                    throw new ReadbackMismatch("journal_mode");
                }
            }
            st.execute("PRAGMA synchronous=FULL");
            requireValue(st, "PRAGMA synchronous", 2, "synchronous");
            st.execute("PRAGMA temp_store=MEMORY");
            requireValue(st, "PRAGMA temp_store", 2, "temp_store");
            st.execute("PRAGMA secure_delete=ON");
            requireValue(st, "PRAGMA secure_delete", 1, "secure_delete");
            st.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
            requireValue(st, "PRAGMA busy_timeout", BUSY_TIMEOUT_MILLIS, "busy_timeout");
            pageSize = (int) scalar(st, "PRAGMA page_size");
            if (pageSize <= 0) throw new ReadbackMismatch("page_size");
            long requested = Math.max(1L, (capBytes + PAGE_LIMIT_HEADROOM_BYTES) / pageSize);
            st.execute("PRAGMA max_page_count=" + requested);
            long pages = scalar(st, "PRAGMA page_count");
            // A lowered cap deletes nothing (design-01 5): SQLite keeps the limit at the current size then.
            requireValue(st, "PRAGMA max_page_count", Math.max(requested, pages), "max_page_count");
            if (!fresh && scalar(st, "PRAGMA auto_vacuum") != 2) throw new ReadbackMismatch("auto_vacuum");
        }
    }

    /** design-02 3.1: one transaction; application_id and user_version last. */
    private void createFresh(Binding binding) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA auto_vacuum=INCREMENTAL");
            // An empty-schema file (header written, no table) keeps its old mode until a VACUUM rebuilds it; it holds
            // nothing, so the rebuild copies nothing.
            if (scalar(st, "PRAGMA auto_vacuum") != 2) st.execute("VACUUM");
            requireValue(st, "PRAGMA auto_vacuum", 2, "auto_vacuum");
        }
        applySettings(true);
        byte[] id = newSpoolId();
        long now = System.currentTimeMillis();
        begin();
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE spool_meta (
                      one INTEGER PRIMARY KEY CHECK (one = 1),
                      spool_id BLOB NOT NULL CHECK (length(spool_id) = 16),
                      backend INTEGER NOT NULL CHECK (backend IN (1, 2, 3)),
                      server_id TEXT NOT NULL,
                      created_millis INTEGER NOT NULL,
                      position_created INTEGER NOT NULL CHECK (position_created IN (0, 1)),
                      high_water INTEGER NOT NULL,
                      replayed_through INTEGER NOT NULL,
                      waiting_frames INTEGER NOT NULL, waiting_rows INTEGER NOT NULL, waiting_bytes INTEGER NOT NULL,
                      unreadable_frames INTEGER NOT NULL, unreadable_rows INTEGER NOT NULL,
                      unreadable_bytes INTEGER NOT NULL,
                      clean INTEGER NOT NULL CHECK (clean IN (0, 1, 2)))""");
            st.execute("""
                    CREATE TABLE spool_frame (
                      seq INTEGER PRIMARY KEY,
                      appended_at INTEGER NOT NULL,
                      row_count INTEGER NOT NULL CHECK (row_count BETWEEN 1 AND 1024),
                      min_row_ts INTEGER NOT NULL,
                      payload BLOB NOT NULL,
                      crc32c INTEGER NOT NULL,
                      unreadable INTEGER NOT NULL DEFAULT 0 CHECK (unreadable IN (0, 1)))""");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO spool_meta VALUES (1, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)")) {
                insert.setBytes(1, id);
                insert.setInt(2, binding.backend());
                insert.setString(3, binding.serverId());
                insert.setLong(4, now);
                insert.executeUpdate();
            }
            st.execute("PRAGMA application_id=" + APPLICATION_ID);
            st.execute("PRAGMA user_version=" + USER_VERSION);
            commit();
        } catch (SQLException failure) {
            rollbackQuietly(failure);
            throw failure;
        }
        meta = new Meta(id, binding.backend(), binding.serverId(), now, false, 0, 0, 0, 0, 0, 0, 0, 0, false);
        checkpointTruncate();
    }

    /** The first transaction after open: clean = 0, and the EXCLUSIVE lock from now on (design-02 3.3). */
    private void firstTransaction() throws SQLException {
        begin();
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("UPDATE spool_meta SET clean = 0 WHERE one = 1");
            commit();
        } catch (SQLException failure) {
            rollbackQuietly(failure);
            throw failure;
        }
        meta = withClean(meta, false);
        // Puts clean = 0 into spool.db itself, so a -wal removed by hand after an unclean stop is still seen.
        checkpointTruncate();
    }

    private Meta readMeta() throws SQLException, MetaInvalid {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT spool_id, backend, server_id, created_millis, position_created, "
                     + "high_water, replayed_through, waiting_frames, waiting_rows, waiting_bytes, unreadable_frames, "
                     + "unreadable_rows, unreadable_bytes, clean, one FROM spool_meta")) {
            if (!rs.next()) throw new MetaInvalid("no meta row");
            byte[] id = rs.getBytes(1);
            Meta read = new Meta(id, rs.getInt(2), rs.getString(3), rs.getLong(4), rs.getInt(5) == 1,
                    rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11),
                    rs.getLong(12), rs.getLong(13), rs.getInt(14) == 1);
            int one = rs.getInt(15);
            if (rs.next()) throw new MetaInvalid("more than one meta row");
            if (one != 1 || id == null || id.length != 16 || read.backend() < 1 || read.backend() > 3
                    || read.serverId() == null || read.highWater() < 0 || read.replayedThrough() < 0
                    || read.replayedThrough() > read.highWater() || read.waitingFrames() < 0 || read.waitingRows() < 0
                    || read.waitingBytes() < 0 || read.unreadableFrames() < 0 || read.unreadableRows() < 0
                    || read.unreadableBytes() < 0) {
                throw new MetaInvalid("meta values outside the format");
            }
            return read;
        }
    }

    private int rawClean() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT clean FROM spool_meta WHERE one = 1")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private Meta tryReadMeta() {
        try {
            return readMeta();
        } catch (SQLException | MetaInvalid | RuntimeException unreadable) {
            return null;
        }
    }

    /** The two b-tree end lookups of design-02 3.1; nothing counts over the file at open (S-4). */
    private void checkEnds(Meta read) throws SQLException, MetaInvalid {
        try (Statement st = connection.createStatement()) {
            try (ResultSet max = st.executeQuery("SELECT max(seq) FROM spool_frame")) {
                if (max.next()) {
                    long highest = max.getLong(1);
                    if (!max.wasNull() && highest > read.highWater()) throw new MetaInvalid("frame above high-water");
                }
            }
            try (ResultSet first = st.executeQuery(
                    "SELECT seq FROM spool_frame WHERE unreadable = 0 ORDER BY seq LIMIT 1")) {
                if (first.next() && first.getLong(1) <= read.replayedThrough()) {
                    throw new MetaInvalid("readable frame at or below replayed-through");
                }
            }
        }
    }

    // ---- the spool thread's statements ----------------------------------------------------------------------

    /**
     * design-02 4.4 step 4: sequence numbers H + 1.. inside the insert transaction, one meta UPDATE, one COMMIT (one
     * WAL sync per batch at FULL). On failure: ROLLBACK, then the high-water read back on the same connection
     * (step 6). Returns the new high-water.
     */
    long insertBatch(List<EncodedFrame> frames, long appendedAt) throws InsertFailure {
        if (frames.isEmpty()) return meta.highWater();
        long h = meta.highWater();
        long rows = 0L;
        long bytes = 0L;
        try {
            begin();
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO spool_frame "
                    + "(seq, appended_at, row_count, min_row_ts, payload, crc32c, unreadable) VALUES (?, ?, ?, ?, ?, ?, 0)");
                 PreparedStatement update = connection.prepareStatement("UPDATE spool_meta SET high_water = ?, "
                         + "waiting_frames = waiting_frames + ?, waiting_rows = waiting_rows + ?, "
                         + "waiting_bytes = waiting_bytes + ? WHERE one = 1")) {
                for (EncodedFrame frame : frames) {
                    long seq = ++h;
                    insert.setLong(1, seq);
                    insert.setLong(2, appendedAt);
                    insert.setInt(3, frame.rowCount());
                    insert.setLong(4, frame.minRowTs());
                    insert.setBytes(5, frame.payload());
                    insert.setLong(6, SpoolFrameCodec.crc(seq, frame.rowCount(), frame.payload()));
                    insert.executeUpdate();
                    rows += frame.rowCount();
                    bytes += frame.payload().length;
                }
                update.setLong(1, h);
                update.setLong(2, frames.size());
                update.setLong(3, rows);
                update.setLong(4, bytes);
                if (update.executeUpdate() != 1) throw new SQLException("spool meta row missing", "ETSP2");
            }
            SpoolKillPoint.reach(SpoolKillPoint.INSERT_BATCH);
            commit();
        } catch (SQLException failure) {
            // R-2 (r9): only a transaction known to be gone makes the read-back mean "committed"; after any other
            // ROLLBACK failure a still-open transaction would show its own uncommitted H, so the read-back fails.
            boolean transactionGone = rollbackQuietly(failure);
            long readBack = -1L;
            try {
                if (transactionGone) readBack = readHighWater();
            } catch (SQLException readFailure) {
                failure.addSuppressed(readFailure);
            }
            if (readBack == h) {
                // The commit reached the file although the call failed: the batch is stored (step 6).
                meta = addWaiting(meta, h, frames.size(), rows, bytes);
                return h;
            }
            throw new InsertFailure(failure, readBack);
        }
        meta = addWaiting(meta, h, frames.size(), rows, bytes);
        SpoolKillPoint.reach(SpoolKillPoint.BETWEEN_BATCHES);
        return h;
    }

    long readHighWater() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT high_water FROM spool_meta WHERE one = 1")) {
            if (!rs.next()) throw new SQLException("spool meta row missing", "ETSP2");
            return rs.getLong(1);
        }
    }

    /** design-02 4.5: readable frames after {@code afterSeq}, at most {@code throughSeq} (m-5), in sequence order. */
    List<StoredFrame> readFrames(long afterSeq, long throughSeq, int limit) throws SQLException {
        List<StoredFrame> frames = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT seq, row_count, min_row_ts, payload, crc32c "
                + "FROM spool_frame WHERE seq > ? AND seq <= ? AND unreadable = 0 ORDER BY seq LIMIT ?")) {
            select.setLong(1, afterSeq);
            select.setLong(2, throughSeq);
            select.setInt(3, limit);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    frames.add(new StoredFrame(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getBytes(4), rs.getLong(5)));
                }
            }
        }
        return frames;
    }

    /**
     * design-02 6.1 (S-12): one transaction deletes every readable frame at or below X (poison and expired frames
     * included once X passed them, n-8; unreadable frames never) and raises replayed_through. Returns the frames
     * deleted.
     */
    long deleteThrough(long x) throws SQLException {
        if (x <= meta.replayedThrough()) return 0L;
        long through = Math.min(x, meta.highWater());
        long frames;
        long rows;
        long bytes;
        begin();
        try {
            try (PreparedStatement count = connection.prepareStatement("SELECT count(*), coalesce(sum(row_count), 0), "
                    + "coalesce(sum(length(payload)), 0) FROM spool_frame WHERE seq <= ? AND unreadable = 0")) {
                count.setLong(1, through);
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    frames = rs.getLong(1);
                    rows = rs.getLong(2);
                    bytes = rs.getLong(3);
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM spool_frame WHERE seq <= ? AND unreadable = 0")) {
                delete.setLong(1, through);
                delete.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE spool_meta SET replayed_through = ?, "
                    + "waiting_frames = waiting_frames - ?, waiting_rows = waiting_rows - ?, "
                    + "waiting_bytes = waiting_bytes - ? WHERE one = 1")) {
                update.setLong(1, through);
                update.setLong(2, frames);
                update.setLong(3, rows);
                update.setLong(4, bytes);
                update.executeUpdate();
            }
            SpoolKillPoint.reach(SpoolKillPoint.DELETE);
            commit();
        } catch (SQLException failure) {
            rollbackQuietly(failure);
            throw failure;
        }
        Meta m = meta;
        meta = new Meta(m.spoolId(), m.backend(), m.serverId(), m.createdMillis(), m.positionCreated(), m.highWater(),
                through, m.waitingFrames() - frames, m.waitingRows() - rows, m.waitingBytes() - bytes,
                m.unreadableFrames(), m.unreadableRows(), m.unreadableBytes(), m.clean());
        return frames;
    }

    /** design-02 4.5: a frame that failed its CRC or the strict decode is marked, never deleted (S-6). */
    boolean markUnreadable(long seq) throws SQLException {
        int rows;
        long bytes;
        begin();
        try {
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT row_count, length(payload) FROM spool_frame WHERE seq = ? AND unreadable = 0")) {
                read.setLong(1, seq);
                try (ResultSet rs = read.executeQuery()) {
                    if (!rs.next()) {
                        commit();
                        return false;
                    }
                    rows = rs.getInt(1);
                    bytes = rs.getLong(2);
                }
            }
            try (PreparedStatement mark = connection.prepareStatement(
                    "UPDATE spool_frame SET unreadable = 1 WHERE seq = ?")) {
                mark.setLong(1, seq);
                mark.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE spool_meta SET "
                    + "waiting_frames = waiting_frames - 1, waiting_rows = waiting_rows - ?, waiting_bytes = waiting_bytes - ?, "
                    + "unreadable_frames = unreadable_frames + 1, unreadable_rows = unreadable_rows + ?, "
                    + "unreadable_bytes = unreadable_bytes + ? WHERE one = 1")) {
                update.setLong(1, rows);
                update.setLong(2, bytes);
                update.setLong(3, rows);
                update.setLong(4, bytes);
                update.executeUpdate();
            }
            commit();
        } catch (SQLException failure) {
            rollbackQuietly(failure);
            throw failure;
        }
        Meta m = meta;
        meta = new Meta(m.spoolId(), m.backend(), m.serverId(), m.createdMillis(), m.positionCreated(), m.highWater(),
                m.replayedThrough(), m.waitingFrames() - 1, m.waitingRows() - rows, m.waitingBytes() - bytes,
                m.unreadableFrames() + 1, m.unreadableRows() + rows, m.unreadableBytes() + bytes, m.clean());
        return true;
    }

    /**
     * L11 switch (design-02 18): deletes unreadable frames appended before {@code cutoffMillis}. Called only when
     * {@link DiskSpool.Settings#deleteKeptAfterRetention()} is true. Returns {frames, rows}.
     */
    long[] deleteUnreadableAppendedBefore(long cutoffMillis) throws SQLException {
        long frames;
        long rows;
        long bytes;
        begin();
        try {
            try (PreparedStatement count = connection.prepareStatement("SELECT count(*), coalesce(sum(row_count), 0), "
                    + "coalesce(sum(length(payload)), 0) FROM spool_frame WHERE unreadable = 1 AND appended_at < ?")) {
                count.setLong(1, cutoffMillis);
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    frames = rs.getLong(1);
                    rows = rs.getLong(2);
                    bytes = rs.getLong(3);
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM spool_frame WHERE unreadable = 1 AND appended_at < ?")) {
                delete.setLong(1, cutoffMillis);
                delete.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE spool_meta SET "
                    + "unreadable_frames = unreadable_frames - ?, unreadable_rows = unreadable_rows - ?, "
                    + "unreadable_bytes = unreadable_bytes - ? WHERE one = 1")) {
                update.setLong(1, frames);
                update.setLong(2, rows);
                update.setLong(3, bytes);
                update.executeUpdate();
            }
            commit();
        } catch (SQLException failure) {
            rollbackQuietly(failure);
            throw failure;
        }
        Meta m = meta;
        meta = new Meta(m.spoolId(), m.backend(), m.serverId(), m.createdMillis(), m.positionCreated(), m.highWater(),
                m.replayedThrough(), m.waitingFrames(), m.waitingRows(), m.waitingBytes(), m.unreadableFrames() - frames,
                m.unreadableRows() - rows, m.unreadableBytes() - bytes, m.clean());
        return new long[]{frames, rows};
    }

    /** m-2: set only after the position row exists in the database, and whenever it is found present. */
    void setPositionCreated() throws SQLException {
        if (meta.positionCreated()) return;
        updateMeta("UPDATE spool_meta SET position_created = 1 WHERE one = 1");
        Meta m = meta;
        meta = new Meta(m.spoolId(), m.backend(), m.serverId(), m.createdMillis(), true, m.highWater(),
                m.replayedThrough(), m.waitingFrames(), m.waitingRows(), m.waitingBytes(), m.unreadableFrames(),
                m.unreadableRows(), m.unreadableBytes(), m.clean());
    }

    /** S-5: next = max(H, P) + 1; a position past every local frame raises the high-water to it. */
    void raiseHighWater(long position) throws SQLException {
        if (position <= meta.highWater()) return;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE spool_meta SET high_water = ? WHERE one = 1")) {
            begin();
            try {
                update.setLong(1, position);
                update.executeUpdate();
                commit();
            } catch (SQLException failure) {
                rollbackQuietly(failure);
                throw failure;
            }
        }
        Meta m = meta;
        meta = new Meta(m.spoolId(), m.backend(), m.serverId(), m.createdMillis(), m.positionCreated(), position,
                m.replayedThrough(), m.waitingFrames(), m.waitingRows(), m.waitingBytes(), m.unreadableFrames(),
                m.unreadableRows(), m.unreadableBytes(), m.clean());
    }

    /**
     * design-02 5.1 step 4 (nothing waiting: rebind in place) and the 2^30 retirement (design-01 5): a new spool id,
     * so a new position row. {@code resetSequence} restarts the numbers (only when nothing is in the file at all).
     */
    void rebind(Binding binding, boolean resetSequence) throws SQLException {
        byte[] id = newSpoolId();
        long now = System.currentTimeMillis();
        try (PreparedStatement update = connection.prepareStatement("UPDATE spool_meta SET spool_id = ?, backend = ?, "
                + "server_id = ?, created_millis = ?, position_created = 0"
                + (resetSequence ? ", high_water = 0, replayed_through = 0" : "") + " WHERE one = 1")) {
            begin();
            try {
                update.setBytes(1, id);
                update.setInt(2, binding.backend());
                update.setString(3, binding.serverId());
                update.setLong(4, now);
                update.executeUpdate();
                commit();
            } catch (SQLException failure) {
                rollbackQuietly(failure);
                throw failure;
            }
        }
        Meta m = meta;
        meta = new Meta(id, binding.backend(), binding.serverId(), now, false,
                resetSequence ? 0 : m.highWater(), resetSequence ? 0 : m.replayedThrough(), m.waitingFrames(),
                m.waitingRows(), m.waitingBytes(), m.unreadableFrames(), m.unreadableRows(), m.unreadableBytes(),
                m.clean());
    }

    /**
     * S-11 shrink: {@code incremental_vacuum} gives free pages back, then {@code wal_checkpoint(TRUNCATE)} copies the
     * zeroed pages into spool.db and leaves a zero-length WAL. Returns true when both completed.
     */
    boolean shrink() throws SQLException {
        boolean vacuumed;
        try (Statement st = connection.createStatement()) {
            long free = scalar(st, "PRAGMA freelist_count");
            if (free > 0) {
                // sqlite-jdbc steps a statement without result columns once, and each step of incremental_vacuum
                // frees one page (pinned by SpoolFileTest): step it once per free page, in one transaction.
                begin();
                try {
                    // Closed before the commit: a statement left on its row would hold the transaction open.
                    try (PreparedStatement vacuum = connection.prepareStatement("PRAGMA incremental_vacuum")) {
                        for (long step = 0; step < free; step++) vacuum.execute();
                    }
                    SpoolKillPoint.reach(SpoolKillPoint.SHRINK);
                    commit();
                } catch (SQLException failure) {
                    rollbackQuietly(failure);
                    throw failure;
                }
            }
            vacuumed = scalar(st, "PRAGMA freelist_count") == 0;
        }
        return checkpointTruncate() && vacuumed;
    }

    /** {@code wal_checkpoint(TRUNCATE)}; true when it was not busy and copied every frame. */
    boolean checkpointTruncate() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!rs.next()) return false;
            int busy = rs.getInt(1);
            int log = rs.getInt(2);
            int done = rs.getInt(3);
            return busy == 0 && log == done;
        }
    }

    /**
     * design-02 5.2: the last transaction sets clean = 1 when {@code markClean}; then the physical close, then the
     * lease release. A failed close poisons the lease, kept to JVM exit (SQLiteFileOwnership's contract).
     */
    void closeAndRelease(boolean markClean) {
        closeAndRelease(markClean, false);
    }

    /**
     * MINOR-5 (r2): the spool's own close that runs without the clean mark (damaged, abandoned by a running writer,
     * failed): it marks the file clean = 2 first. A plain {@code closeAndRelease(false)} (an open that is refused, a
     * test standing in for a kill) leaves the mark as it is.
     */
    void closeAndReleaseWithoutCleanMark() {
        closeAndRelease(false, true);
    }

    private void closeAndRelease(boolean markClean, boolean markClosedUnmarked) {
        if (closed) return;
        closed = true;
        Connection c = connection;
        connection = null;
        Throwable closeFailure = null;
        if (c != null) {
            if (markClean) {
                try (Statement st = c.createStatement()) {
                    st.execute("BEGIN IMMEDIATE");
                    st.executeUpdate("UPDATE spool_meta SET clean = 1 WHERE one = 1");
                    st.execute("COMMIT");
                    meta = withClean(meta, true);
                } catch (SQLException | RuntimeException failure) {
                    try (Statement rollback = c.createStatement()) { rollback.execute("ROLLBACK"); }
                    catch (SQLException | RuntimeException ignored) { /* no transaction is fine */ }
                }
            } else if (markClosedUnmarked && meta != null) {
                // MINOR-5 (r2): a close on purpose without the clean mark says so (clean = 2), so the next start does
                // not report this close's own checkpoint of the -wal as a missing WAL. Best effort: a damaged file
                // may refuse it, and then the next start reports it as not closed cleanly, as before.
                try (Statement st = c.createStatement()) {
                    st.execute("BEGIN IMMEDIATE");
                    st.executeUpdate("UPDATE spool_meta SET clean = " + CLOSED_UNMARKED + " WHERE one = 1 AND clean = 0");
                    st.execute("COMMIT");
                } catch (SQLException | RuntimeException failure) {
                    try (Statement rollback = c.createStatement()) { rollback.execute("ROLLBACK"); }
                    catch (SQLException | RuntimeException ignored) { /* no transaction is fine */ }
                }
            }
            try {
                c.close();
            } catch (SQLException | RuntimeException failure) {
                closeFailure = failure;
            }
        }
        SQLiteFileOwnership owner = lease;
        lease = null;
        if (owner == null) return;
        if (closeFailure != null) {
            owner.poison(closeFailure);
            return;
        }
        try {
            owner.releaseOnlyAfterQuiescent();
        } catch (IOException | RuntimeException ignored) {
            // The lease poisons itself and stays to JVM exit.
        }
    }

    /** The lease's identity check (design-02 2.2): before every batch and delete; metadata calls only. */
    void verifyCurrent() throws IOException {
        SQLiteFileOwnership owner = lease;
        if (owner == null) throw new IOException("spool lease released");
        owner.verifyCurrent();
    }

    boolean isClosed() { return closed; }

    private void releaseQuietly() {
        SQLiteFileOwnership owner = lease;
        lease = null;
        if (owner == null) return;
        try { owner.releaseOnlyAfterQuiescent(); }
        catch (IOException | RuntimeException ignored) { /* poisoned, kept */ }
    }

    // ---- set-aside and held (design-02 7.1, 7.4) ------------------------------------------------------------

    /**
     * design-02 7.1 steps 1 to 3 and 5: close physically (a failed close poisons the lease: nothing moved, off), create
     * {@code spool/<kind>/<folder>/}, move spool.db first and then -wal, -shm, -journal and the damage marker, and
     * write set.properties last (R-9, r9) (POSIX: under the lease, released after; Windows: released first, the pin
     * blocks the rename). A failure moving spool.db leaves every file in place; a later failure, set.properties
     * included, moves the moved files back and removes the folder. Nothing else is ever deleted. After success this
     * instance is closed and released; open a new one for the fresh file.
     */
    void moveFamily(String kind, String folder, Properties props) throws SpoolOff {
        Connection c = connection;
        connection = null;
        closed = true;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException | RuntimeException failure) {
                SQLiteFileOwnership owner = lease;
                lease = null;
                if (owner != null) owner.poison(failure);
                throw new SpoolOff("set-aside-move-failed", failure);
            }
        }
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        Path target;
        try {
            Path parent = ensurePrivateDirectory(directory.resolve(kind));
            target = createSetFolder(parent, folder);
        } catch (IOException | RuntimeException failure) {
            releaseQuietly();
            throw new SpoolOff("set-aside-move-failed", failure);
        }
        if (windows) releaseQuietly();
        List<Path[]> done = new ArrayList<>();
        try {
            move(databasePath, target.resolve(FILE_NAME));
            done.add(new Path[]{databasePath, target.resolve(FILE_NAME)});
        } catch (IOException | RuntimeException failure) {
            removeOwnSetFolder(target);
            if (!windows) releaseQuietly();
            throw new SpoolOff("set-aside-move-failed", failure);
        }
        try {
            for (String suffix : FAMILY_SUFFIXES) {
                Path source = sibling(suffix);
                if (existsNoFollow(source)) {
                    Path destination = target.resolve(FILE_NAME + suffix);
                    move(source, destination);
                    done.add(new Path[]{source, destination});
                }
            }
            Path markerPath = directory.resolve(DAMAGE_MARKER);
            if (existsNoFollow(markerPath)) {
                move(markerPath, target.resolve(DAMAGE_MARKER));
                done.add(new Path[]{markerPath, target.resolve(DAMAGE_MARKER)});
            }
            // R-9 (r9): set.properties last, so a folder that has it holds the whole family; a failure here moves
            // the files back and removes the folder (its partial set.properties included), as any later failure does.
            IOException injected = setPropertiesFailureForTest;
            if (injected != null) throw injected;
            writeSetProperties(target, props);
        } catch (IOException | RuntimeException failure) {
            boolean movedBack = true;
            for (int i = done.size() - 1; i >= 0; i--) {
                try { move(done.get(i)[1], done.get(i)[0]); }
                catch (IOException | RuntimeException backFailure) { movedBack = false; failure.addSuppressed(backFailure); }
            }
            if (movedBack) removeOwnSetFolder(target);
            if (!windows) releaseQuietly();
            throw new SpoolOff(movedBack ? "set-aside-move-failed" : "set-aside-move-back-failed", failure);
        }
        if (!windows) releaseQuietly();
    }

    /**
     * L18 restore: moves the files of a held set back as spool.db (the live family was moved into held/ before).
     * spool.db first; a later failure moves the moved files back to the held folder. Nothing deleted but the held
     * folder's own set.properties once the set is back.
     */
    static void restoreHeld(Path spoolDirectory, Path heldFolder) throws IOException {
        Path database = spoolDirectory.resolve(FILE_NAME);
        if (existsNoFollow(database)) throw new FileAlreadyExistsException("spool.db");
        List<Path[]> done = new ArrayList<>();
        move(heldFolder.resolve(FILE_NAME), database);
        done.add(new Path[]{heldFolder.resolve(FILE_NAME), database});
        try {
            for (String suffix : FAMILY_SUFFIXES) {
                Path source = heldFolder.resolve(FILE_NAME + suffix);
                if (existsNoFollow(source)) {
                    Path destination = spoolDirectory.resolve(FILE_NAME + suffix);
                    move(source, destination);
                    done.add(new Path[]{source, destination});
                }
            }
        } catch (IOException | RuntimeException failure) {
            for (int i = done.size() - 1; i >= 0; i--) {
                try { move(done.get(i)[1], done.get(i)[0]); }
                catch (IOException | RuntimeException backFailure) { failure.addSuppressed(backFailure); }
            }
            throw failure;
        }
        removeOwnSetFolder(heldFolder);
    }

    static String setFolderName(long millis, String suffix) {
        return SET_TIME.format(Instant.ofEpochMilli(millis)) + "-" + suffix;
    }

    /** ids, counts and the reason code only, never row content (design-02 2.1). */
    static Properties setProperties(Meta meta, String reason, Binding heldBinding) {
        Properties props = new Properties();
        props.setProperty("format", "1");
        props.setProperty("reason", reason);
        if (meta == null) {
            props.setProperty("meta", "unreadable");
        } else {
            props.setProperty("meta", "readable");
            props.setProperty("spool_id", meta.spoolIdHex());
            props.setProperty("backend", Integer.toString(meta.backend()));
            props.setProperty("server_id", meta.serverId());
            props.setProperty("high_water", Long.toString(meta.highWater()));
            props.setProperty("replayed_through", Long.toString(meta.replayedThrough()));
            props.setProperty("waiting_frames", Long.toString(meta.waitingFrames()));
            props.setProperty("waiting_rows", Long.toString(meta.waitingRows()));
            props.setProperty("unreadable_frames", Long.toString(meta.unreadableFrames()));
            props.setProperty("unreadable_rows", Long.toString(meta.unreadableRows()));
        }
        if (heldBinding != null) {
            props.setProperty("backend", Integer.toString(heldBinding.backend()));
            props.setProperty("server_id", heldBinding.serverId());
        }
        return props;
    }

    /** One set folder of aside/ or held/, as listed at start (design-02 7.2, 7.4). */
    record KeptSet(String kind, Path folder, Properties props, long bytes, long newestWriteMillis) {
        boolean metaReadable() { return "readable".equals(props.getProperty("meta")); }
        long longValue(String key) {
            try { return Long.parseLong(props.getProperty(key, "-1")); }
            catch (NumberFormatException invalid) { return -1L; }
        }
        String spoolIdHex() { return props.getProperty("spool_id", ""); }
    }

    /**
     * Lists the sets of {@code spool/<kind>/}, oldest folder name first (NIT n-3). Never follows a link. R-9 (r9): a
     * folder without spool.db is not a set (an interrupted move or restore); {@link #countFoldersWithoutDatabase}
     * counts those apart, and they are never a restore candidate.
     */
    static List<KeptSet> listSets(Path spoolDirectory, String kind) {
        Path parent = spoolDirectory.resolve(kind);
        List<KeptSet> sets = new ArrayList<>();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return sets;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path folder : stream) {
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(folder.resolve(FILE_NAME), LinkOption.NOFOLLOW_LINKS)) continue;
                Properties props = new Properties();
                Path file = folder.resolve(SET_PROPERTIES);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { props.load(in); }
                    catch (IOException | IllegalArgumentException unreadable) { props.setProperty("meta", "unreadable"); }
                } else {
                    props.setProperty("meta", "unreadable");
                }
                long bytes = 0L;
                long newest = 0L;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                    for (Path member : files) {
                        BasicFileAttributes attributes = Files.readAttributes(member, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (!attributes.isRegularFile()) continue;
                        bytes += attributes.size();
                        FileTime written = attributes.lastModifiedTime();
                        newest = Math.max(newest, written.toMillis());
                    }
                } catch (IOException unreadable) {
                    // Counted with what could be read.
                }
                sets.add(new KeptSet(kind, folder, props, bytes, newest));
            }
        } catch (IOException unreadable) {
            return sets;
        }
        sets.sort((a, b) -> a.folder().getFileName().toString().compareTo(b.folder().getFileName().toString()));
        return sets;
    }

    /**
     * R-9 (r9): the folders of {@code spool/<kind>/} that hold no spool.db, so are not sets. spf1 r2 MINOR-2: the size
     * of {@link #foldersWithoutDatabase}, so the count and the deletion list use the same test. r3: plain folders
     * only; links and entries whose attributes cannot be read are counted apart by {@link #scanKeptFolders}.
     */
    static int countFoldersWithoutDatabase(Path spoolDirectory, String kind) {
        return foldersWithoutDatabase(spoolDirectory, kind).size();
    }

    /**
     * R-9 (r9), spf1 MINOR-1, r2 MINOR-2 and r3: the plain folders of {@code spool/<kind>/} that hold no spool.db, as
     * counted at the start and taken by the deletion after retention: {@link KeptFolderScan#withoutDatabase}.
     */
    static List<Path> foldersWithoutDatabase(Path spoolDirectory, String kind) {
        return scanKeptFolders(spoolDirectory, kind).withoutDatabase();
    }

    /** spf1 r3: what a path of the spool is, read without following a link. */
    enum EntryKind { PLAIN_FOLDER, LINK, FILE, UNREADABLE, ABSENT }

    /**
     * spf1 r3: a junction reads as a directory that {@code isOther()}; a symbolic link as {@code isSymbolicLink()}.
     * Both, and any other reparse point or special file, are {@link EntryKind#LINK}. Attributes that cannot be read
     * (other than absent) are {@link EntryKind#UNREADABLE}, never called a link.
     */
    static EntryKind entryKind(Path path) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return EntryKind.ABSENT;
        } catch (IOException cannotRead) {
            return EntryKind.UNREADABLE;
        }
        if (attributes.isSymbolicLink() || attributes.isOther()) return EntryKind.LINK;
        return attributes.isDirectory() ? EntryKind.PLAIN_FOLDER : EntryKind.FILE;
    }

    /**
     * spf1 r3: one look at {@code spool/<kind>/} for folders without spool.db. {@code withoutDatabase}: its plain
     * folders without a regular spool.db, oldest name first. {@code links}: its entries that are a link, junction,
     * other reparse point or special file, never looked into here (their spool.db is never looked for) and never
     * deleted by the retention cleanup. {@code unreadable}: its entries whose attributes could not be read, left in
     * place. {@code parentNotPlain}: {@code spool/<kind>} itself is not a plain folder; {@code parentUnreadable}: its
     * attributes could not be read. Either way it is never listed here, so nothing in it is counted as a folder
     * without spool.db, entered here or deleted by the retention cleanup. The start WARNs once for each count and
     * flag (AbstractJdbcStorage#logSpoolOpenLines). {@link #listSets} (r9) is unchanged (spf1 r4, the Lead's option
     * i): it still lists a junction, so a set behind one is counted and a held one can be restored.
     */
    record KeptFolderScan(String kind, List<Path> withoutDatabase, int links, int unreadable, boolean parentNotPlain,
                          boolean parentUnreadable) {
    }

    static KeptFolderScan scanKeptFolders(Path spoolDirectory, String kind) {
        Path parent = spoolDirectory.resolve(kind);
        // r3 ruling 1: the parent is tested before any listing.
        switch (entryKind(parent)) {
            case ABSENT: return new KeptFolderScan(kind, List.of(), 0, 0, false, false);
            case UNREADABLE: return new KeptFolderScan(kind, List.of(), 0, 0, false, true);
            case LINK, FILE: return new KeptFolderScan(kind, List.of(), 0, 0, true, false);
            default: break;
        }
        List<Path> folders = new ArrayList<>();
        int links = 0, unreadable = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                switch (entryKind(entry)) {
                    case LINK -> links++;
                    case UNREADABLE -> unreadable++;
                    case PLAIN_FOLDER -> {
                        if (!Files.isRegularFile(entry.resolve(FILE_NAME), LinkOption.NOFOLLOW_LINKS)) folders.add(entry);
                    }
                    default -> { } // a regular file, or gone since the listing
                }
            }
        } catch (IOException unreadableListing) {
            // Listed with what could be read; the rest are counted and tried at the next start.
        }
        folders.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return new KeptFolderScan(kind, List.copyOf(folders), links, unreadable, false, false);
    }

    /** spf1 MINOR-1: the newest write time of the folder's regular files (0 for none), as listSets reads it. */
    static long newestRegularFileMillis(Path folder) {
        long newest = 0L;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path member : files) {
                BasicFileAttributes attributes = Files.readAttributes(member, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) newest = Math.max(newest, attributes.lastModifiedTime().toMillis());
            }
        } catch (IOException unreadable) {
            // A file that cannot be read keeps the folder: it is treated as written now.
            return Long.MAX_VALUE;
        }
        return newest;
    }

    /** spf1 MINOR-1: a directory that is not a link, junction or other reparse point (read without following). */
    static boolean plainDirectory(Path path) {
        return entryKind(path) == EntryKind.PLAIN_FOLDER;
    }

    /** spf1 r2 MINOR-2: a plain folder (no link, junction or other reparse point) in a plain spool/aside or held. */
    static boolean plainFolderInPlainParent(Path folder) {
        return plainDirectory(folder) && folder.getParent() != null && plainDirectory(folder.getParent());
    }

    /** spf1 MINOR-1: why a kept folder that is not plain, or whose spool/aside or spool/held is not, stays. */
    static final String NOT_PLAIN_FOLDER = "a kept folder or its spool/aside or spool/held is not a plain folder; left "
            + "in place";

    /**
     * L11 switch: deletes one kept set folder (its regular files, then the folder); never follows a link. spf1
     * MINOR-1: a folder that is a link, junction or other reparse point is refused, never entered.
     */
    static void deleteKeptSet(Path folder) throws IOException {
        if (!plainFolderInPlainParent(folder)) {
            throw new IOException(NOT_PLAIN_FOLDER);
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path member : files) {
                if (Files.isRegularFile(member, LinkOption.NOFOLLOW_LINKS)) Files.delete(member);
            }
        }
        Files.delete(folder);
    }

    /** Creates the empty damage marker (design-02 7.3, CREATE_NEW). */
    static void createDamageMarker(Path spoolDirectory) throws IOException {
        Path marker = spoolDirectory.resolve(DAMAGE_MARKER);
        try {
            Files.newByteChannel(marker, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)).close();
        } catch (FileAlreadyExistsException present) {
            // Already marked.
        }
    }

    // ---- small helpers ---------------------------------------------------------------------------------------

    private Path sibling(String suffix) {
        return directory.resolve(FILE_NAME + suffix);
    }

    private void begin() throws SQLException {
        try (Statement st = connection.createStatement()) { st.execute("BEGIN IMMEDIATE"); }
    }

    private void commit() throws SQLException {
        try (Statement st = connection.createStatement()) { st.execute("COMMIT"); }
    }

    /**
     * A "no transaction is active" answer is fine: the transaction is gone either way (design-02 4.4 step 6). R-2
     * (r9): returns false for any other ROLLBACK failure, when the transaction may still be open (and a read on this
     * connection would see its own uncommitted rows).
     */
    private boolean rollbackQuietly(SQLException failure) {
        Connection c = connection;
        if (c == null) return false;
        try (Statement st = c.createStatement()) {
            st.execute("ROLLBACK");
            return true;
        } catch (SQLException | RuntimeException rollbackFailure) {
            if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
            return isNoTransactionActive(rollbackFailure);
        }
    }

    /** R-2 (r9): SQLite's answer to a ROLLBACK with no open transaction ("cannot rollback - no transaction is active"). */
    static boolean isNoTransactionActive(Throwable rollbackFailure) {
        String message = rollbackFailure.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("no transaction is active");
    }

    private void updateMeta(String sql) throws SQLException {
        begin();
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(sql);
            commit();
        } catch (SQLException failure) {
            rollbackQuietly(failure);
            throw failure;
        }
    }

    private static Meta withClean(Meta m, boolean clean) {
        return new Meta(m.spoolId(), m.backend(), m.serverId(), m.createdMillis(), m.positionCreated(), m.highWater(),
                m.replayedThrough(), m.waitingFrames(), m.waitingRows(), m.waitingBytes(), m.unreadableFrames(),
                m.unreadableRows(), m.unreadableBytes(), clean);
    }

    private static Meta addWaiting(Meta m, long highWater, long frames, long rows, long bytes) {
        return new Meta(m.spoolId(), m.backend(), m.serverId(), m.createdMillis(), m.positionCreated(), highWater,
                m.replayedThrough(), m.waitingFrames() + frames, m.waitingRows() + rows, m.waitingBytes() + bytes,
                m.unreadableFrames(), m.unreadableRows(), m.unreadableBytes(), m.clean());
    }

    static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("no result: " + sql, "ETSP3");
            return rs.getLong(1);
        }
    }

    private static String text(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("no result: " + sql, "ETSP3");
            return rs.getString(1);
        }
    }

    private static void requireValue(Statement st, String sql, long expected, String setting) throws SQLException {
        if (scalar(st, sql) != expected) throw new ReadbackMismatch(setting);
    }

    private static void requireText(Statement st, String sql, String expected, String setting) throws SQLException {
        if (!expected.equalsIgnoreCase(text(st, sql))) throw new ReadbackMismatch(setting);
    }

    static byte[] newSpoolId() {
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        return id;
    }

    static boolean existsNoFollow(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, LinkOption.NOFOLLOW_LINKS);
    }

    /** A real directory (never a link), created rwx------ where POSIX permissions exist (design-02 2.3). */
    static Path ensurePrivateDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("spool folder is a link");
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return path;
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(path);
            }
        } catch (FileAlreadyExistsException raced) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw raced;
        }
        return path;
    }

    private static Path createSetFolder(Path parent, String folder) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Path candidate = parent.resolve(attempt == 0 ? folder : folder + "-" + (attempt + 1));
            if (existsNoFollow(candidate)) continue;
            return ensurePrivateDirectory(candidate);
        }
        throw new IOException("no free set folder name");
    }

    private static void writeSetProperties(Path folder, Properties props) throws IOException {
        try (OutputStream out = Files.newOutputStream(folder.resolve(SET_PROPERTIES), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            props.store(out, null);
        }
    }

    /** Removes only a set folder this code made, holding nothing but its set.properties. */
    private static void removeOwnSetFolder(Path folder) {
        try {
            Files.deleteIfExists(folder.resolve(SET_PROPERTIES));
            Files.delete(folder);
        } catch (IOException | RuntimeException notEmpty) {
            // Left in place: never delete anything that is not our own metadata.
        }
    }
}
