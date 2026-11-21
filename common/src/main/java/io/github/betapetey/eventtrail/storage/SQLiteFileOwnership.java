package io.github.betapetey.eventtrail.storage;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import io.github.betapetey.eventtrail.util.EventTrailFailure;
import io.github.betapetey.eventtrail.util.EventTrailFailure.Code;
import io.github.betapetey.eventtrail.util.EventTrailFailure.FileRole;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A cooperating-owner lease, not an SQLite lock or a hostile-filesystem sandbox.
 * The caller must acquire this before opening SQLite and retain it through all
 * native work and physical closes. There is deliberately no AutoCloseable API.
 * Files and their ancestry must not be replaced, relinked or renamed out of band.
 * Cooperating instances in one JVM must use this one trusted EventTrail
 * classloader; isolated duplicate copies do not share the POSIX descriptor guard.
 */
final class SQLiteFileOwnership {
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final Object ACQUISITION = new Object();
    // A bounded local-filesystem support profile, not a claim of Unix runtime
    // qualification. WAL's shared-memory protocol does not support network filesystems.
    private static final Set<String> LOCAL_UNIX_TYPES =
            Set.of("ext2", "ext3", "ext4", "xfs", "btrfs", "apfs", "zfs", "ufs", "hfs", "hfsplus");
    // Reserve physical sidecar identity before opening another locking channel.
    // On POSIX, closing a losing duplicate descriptor can drop the winner's lock.
    // An entry leaves only in releaseOnlyAfterQuiescent, after every resource closed and
    // this owner's released is set (the coordinator sets ownershipReleased() just after that
    // normal return), or when an acquisition that was never returned fails with proven cleanup.
    // A poisoned owner never leaves: this JVM's successors are denied until it exits.
    private static final Map<Identity, SQLiteFileOwnership> OWNERS = new ConcurrentHashMap<>();
    /**
     * Spool r9c FINAL item (b): a -wal or -shm seen vanishing (link count 0 at inspect, Windows error 2 after the
     * file was seen, or access denied, as while SQLite deletes it at its last close) is never verified and is not by
     * itself an owner conflict. It is observed again up to this many times, {@link #AUXILIARY_REOBSERVATION_PAUSE_MILLIS}
     * apart: at most 250 ms per file and pass. Why this bound: a link count of 0 means SQLite's delete was already
     * issued; with POSIX delete semantics the name is gone at once, with classic semantics when the last handle
     * closes, and ours is closed before each pause, so one pause normally settles it. The rest cover another short
     * handle (an indexer or a virus scan) keeping a delete-pending name, and the bound keeps the JVM-wide
     * ACQUISITION lock short. Past it (spf1 r2 MINOR-3) a link count of 0 or the error 2 is
     * SQLITE_VERIFICATION_UNAVAILABLE and a denial SQLITE_ACCESS_DENIED, both with the file's role, or, with both
     * permanent files present before the lease, the lease decides (owner conflict, or the revalidation under the
     * lease verifies or fails).
     */
    static final int AUXILIARY_REOBSERVATIONS = 10;
    static final long AUXILIARY_REOBSERVATION_PAUSE_MILLIS = 25L;
    private static final Set<SQLiteFileOwnership> UNPROVEN =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final AcquisitionObservation NO_OBSERVATION = new AcquisitionObservation() {};

    private final Path databasePath;
    private final Path sidecarPath;
    private final boolean windows;
    private final DirectoryAuthority directory;
    private final LinuxDatabaseFamilyVerifier linuxDirectory;
    private final AcquisitionObservation observation;
    private Pin databasePin;
    private Pin sidecarPin;
    private FileChannel channel;
    private FileLock lock;
    private Identity identity;
    private boolean createdDatabase;
    private boolean released;
    private Throwable poison;

    private SQLiteFileOwnership(Path databasePath, boolean windows, AcquisitionObservation observation,
                                DirectoryAuthority directory) {
        this.databasePath = databasePath;
        this.sidecarPath = databasePath.resolveSibling(databasePath.getFileName() + ".eventtrail.lock");
        this.windows = windows;
        this.directory = directory;
        this.linuxDirectory = directory.linux;
        this.observation = observation;
    }

    static SQLiteFileOwnership acquire(Path requestedDatabase) throws IOException {
        return acquire(requestedDatabase, NO_OBSERVATION);
    }

    /** Per-acquisition deterministic observation seam; production uses the no-op observer. */
    static SQLiteFileOwnership acquire(Path requestedDatabase, AcquisitionObservation observation) throws IOException {
        try { return acquireChecked(requestedDatabase, Objects.requireNonNull(observation)); }
        catch (AccessDeniedException denied) {
            throw typed(Code.SQLITE_ACCESS_DENIED, FileRole.UNKNOWN,
                    "SQLite file-family access was denied; startup denied.", denied);
        }
    }

    private static SQLiteFileOwnership acquireChecked(Path requestedDatabase, AcquisitionObservation observation) throws IOException {
        if (requestedDatabase == null) throw new IOException("SQLite database path is required.");
        Path requested = requestedDatabase.toAbsolutePath().normalize();
        if (requested.getFileSystem() != FileSystems.getDefault()
                || !requested.getFileSystem().provider().getScheme().equals("file")) {
            throw unsupported();
        }
        Path parent = requested.getParent();
        if (parent == null || parent.getParent() == null || requested.toString().contains("?")) {
            throw new IOException("SQLite ownership requires an ordinary local database filename without JDBC options.");
        }
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        boolean linux = System.getProperty("os.name", "").equals("Linux");
        DirectoryAuthority directory = resolveDirectory(parent, requested.getFileName().toString(), windows, linux);
        Path database = directory.path.resolve(requested.getFileName());

        synchronized (ACQUISITION) {
            SQLiteFileOwnership owner = new SQLiteFileOwnership(database, windows, observation, directory);
            try {
                // Inspect the complete existing family before creating either permanent child.
                // Spool r9c FINAL item (a): an existing sidecar is pinned and checked against this
                // JVM's owners first, so a previous lifetime still closing is named as the owner and
                // its -wal/-shm, which SQLite may be deleting, get no metadata call at all.
                owner.observeOwnerFiles();
                if (owner.sidecarPin != null) {
                    SQLiteFileOwnership live = OWNERS.get(owner.sidecarPin.identity());
                    if (live != null) throw ownedInThisJvm(live.retainedToJvmExit());
                }
                // Item (b): with both permanent files already present, a -wal/-shm still vanishing
                // past its bound is left to the lease, which creates nothing.
                IOException vanishing = owner.observeRecoveryFiles(
                        owner.sidecarPin != null && owner.databasePin != null);
                if (owner.sidecarPin == null) {
                    createEmptyIfAbsent(owner.sidecarPath);
                    owner.sidecarPin = owner.pin(owner.sidecarPath);
                }
                owner.identity = owner.sidecarPin.identity();
                if (OWNERS.putIfAbsent(owner.identity, owner) != null) throw alreadyOwned();
                owner.channel = FileChannel.open(owner.sidecarPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                owner.sidecarPin.verify(owner.sidecarPath);
                try { owner.lock = owner.channel.tryLock(0L, 1L, false); }
                catch (OverlappingFileLockException conflict) { throw ownerConflict(vanishing); }
                if (owner.lock == null) throw ownerConflict(vanishing);
                observation.beforeLeaseRevalidation();
                // A cooperating creator may have changed the family since preflight.
                // Recheck under the same lease before allowing a fresh database.
                owner.observeFamily();
                if (owner.databasePin == null) {
                    owner.createdDatabase = createEmptyIfAbsent(database);
                    owner.databasePin = owner.pin(database);
                }
                owner.verifyCurrent();
                return owner;
            } catch (IOException | RuntimeException | Error failure) {
                try { owner.releaseResources(); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                    owner.poison(cleanup);
                    UNPROVEN.add(owner);
                }
                if (owner.poison == null && owner.identity != null) OWNERS.remove(owner.identity, owner);
                throw failure;
            }
        }
    }

    Path databasePath() { return databasePath; }

    /** True only for this owner's successful CREATE_NEW, never inferred from file/schema contents. */
    boolean createdDatabase() { return createdDatabase; }

    /** Opaque scheduling-state binding; physical identities, not a user-controlled pathname. */
    synchronized String stableBinding() throws IOException {
        verifyCurrent();
        Identity directoryIdentity = linuxDirectory == null ? directory.pin.identity()
                : new Identity("linux-directory", linuxDirectory.directoryIdentity());
        String binding = identityDigest("eventtrail-reclamation-binding-v1\n" + directoryIdentity
                + "\n" + databasePin.identity());
        verifyCurrent();
        return binding;
    }

    Path reclamationStatePath(boolean temporary) {
        return databasePath.resolveSibling(databasePath.getFileName() + ".reclaim-state" + (temporary ? ".tmp" : ""));
    }

    /** The optional state adapter may inspect only these two fixed siblings, never a caller-selected file. */
    synchronized String reclamationStateIdentity(boolean temporary) throws IOException {
        verifyCurrent();
        Path path = reclamationStatePath(temporary);
        Identity observed;
        try {
            if (linuxDirectory != null) observed = new Identity("linux", linuxDirectory.inspectChild(path));
            else if (windows) {
                ordinaryFile(path);
                WindowsPin pin = WindowsPin.open(path, 7); // Short metadata pin permits atomic publication.
                try { observed = pin.identity(); } finally { pin.close(); }
            } else observed = unixIdentity(path);
        } catch (NoSuchFileException absent) {
            requireMissingDirectChild(path, absent);
            verifyCurrent();
            return null;
        }
        verifyCurrent();
        return identityDigest(observed.toString());
    }

    private static String identityDigest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static DirectoryAuthority resolveDirectory(Path requested, String databaseName,
                                                       boolean windows, boolean linux) throws IOException {
        boolean missing = false;
        try { basicAttributes(requested, FileRole.DIRECTORY); }
        catch (NoSuchFileException absent) { missing = true; }
        // Resolve only directories. An existing (including dangling) alias is
        // never mistaken for permission to create its target or a family file.
        if (!missing) {
            Path resolved = requested.toRealPath();
            // Preserve the ordinary non-root storage-directory boundary even
            // when an alias hides the target's depth. A world at a volume root
            // may still receive its normal fresh eventtrail child below it.
            if (resolved.getParent() == null) throw unsupported();
            return DirectoryAuthority.open(resolved, databaseName, windows, linux);
        }

        Path canonicalParent = requested.getParent().toRealPath();
        Path child = canonicalParent.resolve(requested.getFileName());
        DirectoryAuthority created = null;
        try (DirectoryAuthority parent = DirectoryAuthority.open(canonicalParent, databaseName, windows, linux)) {
            parent.verify();
            try { Files.createDirectory(child); }
            catch (FileAlreadyExistsException existing) { /* Validate the direct child, without following a new alias. */ }
            parent.verify();
            created = DirectoryAuthority.open(child, databaseName, windows, linux);
            parent.verify();
        } catch (IOException | RuntimeException | Error failure) {
            if (created != null) {
                try { created.close(); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
        return created;
    }

    /** The alias route is discarded; only this qualified, retained target is used thereafter. */
    private static final class DirectoryAuthority implements AutoCloseable {
        private final Path path;
        private final LinuxDatabaseFamilyVerifier linux;
        private final Pin pin;

        private DirectoryAuthority(Path path, LinuxDatabaseFamilyVerifier linux, Pin pin) {
            this.path = path;
            this.linux = linux;
            this.pin = pin;
        }

        static DirectoryAuthority open(Path path, String databaseName, boolean windows, boolean linux) throws IOException {
            if (path.toString().contains("?")) throw unsupported();
            verifyDirectories(path);
            if (linux) return new DirectoryAuthority(path, LinuxDatabaseFamilyVerifier.open(path, databaseName), null);
            if (windows) {
                if (!Files.getFileStore(path).type().equalsIgnoreCase("NTFS")
                        || path.toString().startsWith("\\\\")) throw unsupported();
                try {
                    WindowsPin.requireLocalVolume(path.getRoot());
                    return new DirectoryAuthority(path, null, WindowsPin.openDirectory(path));
                } catch (LinkageError unavailable) {
                    throw typed(Code.SQLITE_VERIFICATION_UNAVAILABLE, FileRole.DIRECTORY,
                            "SQLite Windows directory identity is unavailable; startup denied.", unavailable);
                }
            }
            if (!path.getFileSystem().supportedFileAttributeViews().contains("unix")
                    || !supportsUnixFileSystem(Files.getFileStore(path).type())) throw unsupported();
            return new DirectoryAuthority(path, null, new UnixDirectoryPin(directoryIdentity(path)));
        }

        void verify() throws IOException {
            try {
                if (linux != null) linux.verifyDirectory();
                else {
                    verifyDirectories(path);
                    pin.verify(path);
                }
            } catch (NoSuchFileException absent) {
                throw typed(Code.SQLITE_IDENTITY_CHANGED, FileRole.DIRECTORY,
                        "The retained SQLite directory disappeared; replacement is denied.", absent);
            } catch (IOException failure) { throw metadataFailure(FileRole.DIRECTORY, failure); }
        }

        @Override public void close() throws IOException {
            if (linux != null) linux.close();
            if (pin != null) pin.close();
        }
    }

    private static Identity directoryIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = basicAttributes(path, FileRole.DIRECTORY);
        ordinaryDirectory(attributes);
        if (attributes.fileKey() == null) throw unsupported();
        return new Identity("unix-directory", attributes.fileKey());
    }

    private record UnixDirectoryPin(Identity identity) implements Pin {
        @Override public void verify(Path path) throws IOException {
            if (!identity.equals(directoryIdentity(path))) throw typed(Code.SQLITE_IDENTITY_CHANGED, FileRole.DIRECTORY,
                    "SQLite directory identity changed; database work denied.", null);
        }
        @Override public void close() { /* Metadata only; no family-file descriptor. */ }
    }

    static boolean supportsUnixFileSystem(String type) {
        return type != null && LOCAL_UNIX_TYPES.contains(type.toLowerCase(Locale.ROOT));
    }

    static boolean isLocalWindowsDriveType(int type) { return type == 2 || type == 3; }

    interface AcquisitionObservation {
        default void beforeMetadata(FileRole role, Path path) throws IOException {}
        default void afterDatabaseObservation(boolean present) throws IOException {}
        default void beforeLeaseRevalidation() throws IOException {}
        /** Windows only: before the short share-all open of an existing -wal, -shm or -journal. */
        default void beforeAuxiliaryPin(FileRole role, Path path) throws IOException {}
        /** Windows only: the link count that open read; a test may replace it (0 models a delete in progress). */
        default long auxiliaryLinkCount(FileRole role, Path path, long links) throws IOException { return links; }
    }

    private void observeFamily() throws IOException {
        observeOwnerFiles();
        observeRecoveryFiles(false);
    }

    /** The directory and both permanent files; pins the existing ones. */
    private void observeOwnerFiles() throws IOException {
        verifyDirectory();
        observeDatabase();
        sidecarPin = observePin(sidecarPath, FileRole.LOCK, sidecarPin);
    }

    /**
     * The recovery files and the orphan rule. With {@code deferVanishing}, a -wal/-shm still vanishing past
     * {@link #AUXILIARY_REOBSERVATIONS} is returned instead of thrown, for the lease to decide.
     */
    private IOException observeRecoveryFiles(boolean deferVanishing) throws IOException {
        IOException[] deferred = new IOException[1];
        FileRole recovery = verifyExistingAuxiliaryFiles(deferVanishing ? deferred : null);
        if (databasePin == null && recovery != FileRole.UNKNOWN) {
            // DB absence and recovery presence are separate observations. Another
            // cooperating process may create both between them; reobserve once.
            observeDatabase();
            if (databasePin == null) {
                throw typed(Code.SQLITE_RECOVERY_REVIEW_REQUIRED, recovery,
                        "SQLite recovery files exist without their database; preserve the family for recovery review.", null);
            }
        }
        return deferred[0];
    }

    private void observeDatabase() throws IOException {
        databasePin = observePin(databasePath, FileRole.DATABASE, databasePin);
        observation.afterDatabaseObservation(databasePin != null);
    }

    private Pin observePin(Path path, FileRole role, Pin existing) throws IOException {
        try {
            observation.beforeMetadata(role, path);
            if (existing == null) return pin(path);
            existing.verify(path);
            return existing;
        } catch (NoSuchFileException absent) {
            if (existing != null) {
                throw typed(Code.SQLITE_IDENTITY_CHANGED, role,
                        "A previously observed SQLite family file disappeared; replacement is denied.", absent);
            }
            requireMissingDirectChild(path, absent);
            return null;
        } catch (IOException failure) {
            throw metadataFailure(role, failure);
        }
    }

    /** @param deferred null to throw a still-vanishing -wal/-shm failure; else its slot 0 receives the first one. */
    private FileRole verifyExistingAuxiliaryFiles(IOException[] deferred) throws IOException {
        // Startup-only metadata checks. SQLite must remain free to delete and
        // recreate its own native files after opening; no auxiliary pin survives.
        FileRole firstPresent = FileRole.UNKNOWN;
        for (FileRole role : new FileRole[]{FileRole.WAL, FileRole.SHM, FileRole.JOURNAL}) {
            String suffix = switch (role) {
                case WAL -> "-wal";
                case SHM -> "-shm";
                case JOURNAL -> "-journal";
                default -> throw new AssertionError(role);
            };
            Path path = databasePath.resolveSibling(databasePath.getFileName() + suffix);
            // SQLite deletes -wal and -shm itself at its last close; -journal is never re-observed.
            boolean vanishable = role != FileRole.JOURNAL;
            boolean present = false;
            for (int reobservation = 0; ; reobservation++) {
                try {
                    present = observeAuxiliary(role, path, vanishable);
                    break;
                } catch (Vanishing vanishing) {
                    if (reobservation < AUXILIARY_REOBSERVATIONS) {
                        pauseBeforeReobservation(vanishing.failure);
                        continue;
                    }
                    if (deferred == null) throw vanishing.failure;
                    if (deferred[0] == null) deferred[0] = vanishing.failure;
                    break;
                }
            }
            if (present && firstPresent == FileRole.UNKNOWN) firstPresent = role;
        }
        return firstPresent;
    }

    /** True when present and verified, false when absent; a vanishable file seen vanishing throws {@link Vanishing}. */
    private boolean observeAuxiliary(FileRole role, Path path, boolean vanishable) throws IOException {
        try {
            observation.beforeMetadata(role, path);
            if (linuxDirectory != null) linuxDirectory.inspectChild(path);
            else ordinaryFile(path);
        } catch (NoSuchFileException absent) {
            requireMissingDirectChild(path, absent);
            return false;
        } catch (IOException failure) { throw vanishingOr(role, failure, vanishable, false); }
        try {
            if (windows) {
                observation.beforeAuxiliaryPin(role, path);
                // Share read, write AND delete; the handle is closed before this returns.
                long links = observation.auxiliaryLinkCount(role, path, WindowsPin.auxiliaryLinkCount(path));
                if (links != 1L) {
                    // spf1 r3 ruling 4: a vanishing -wal/-shm names both causes; the code and role are as before.
                    IOException failure = metadataFailure(role, links > 1L ? linkedFile()
                            : links == 0L && vanishable ? vanishingUnverified() : unsupported());
                    // A zero link count is a delete already issued (by SQLite at its last close): never verified.
                    if (links == 0L && vanishable) throw new Vanishing(failure);
                    throw failure;
                }
            } else if (linuxDirectory == null) { unixIdentity(path); }
        } catch (Vanishing vanishing) { throw vanishing; }
        catch (IOException failure) { throw vanishingOr(role, failure, vanishable, true); }
        return true;
    }

    /** The as-now failure, wrapped as {@link Vanishing} when it is one a deleting -wal/-shm gives. */
    private static IOException vanishingOr(FileRole role, IOException original, boolean vanishable, boolean seen) {
        IOException failure = metadataFailure(role, original);
        if (!vanishable) return failure;
        boolean denied = failure instanceof EventTrailFailure.FailureIOException known
                && known.failure().code() == Code.SQLITE_ACCESS_DENIED;
        // Windows error 2 only after ordinaryFile saw the file: it went between the two observations.
        boolean gone = seen && original instanceof WindowsError windowsError && windowsError.error == 2;
        if (gone) {
            // spf1 r2 MINOR-3: past the bound this is SQLITE_VERIFICATION_UNAVAILABLE with the role, as a zero link
            // count is; a persistent denial keeps its own SQLITE_ACCESS_DENIED.
            IOException unverified = metadataFailure(role, vanishingUnverified());
            unverified.addSuppressed(original);
            return new Vanishing(unverified);
        }
        return denied ? new Vanishing(failure) : failure;
    }

    private static void pauseBeforeReobservation(IOException failure) throws IOException {
        try { Thread.sleep(AUXILIARY_REOBSERVATION_PAUSE_MILLIS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure.addSuppressed(interrupted);
            throw failure;
        }
    }

    /** Internal carrier only; never thrown out of this class. {@link #failure} is what is thrown as now. */
    private static final class Vanishing extends IOException {
        private final IOException failure;
        private Vanishing(IOException failure) {
            super(failure);
            this.failure = failure;
        }
    }

    /** The plain "Windows error N" failure, as before; the number lets error 2 be recognised. */
    private static final class WindowsError extends IOException {
        private final int error;
        private WindowsError(String message, int error) {
            super(message);
            this.error = error;
        }
    }

    private void requireMissingDirectChild(Path path, NoSuchFileException absent) throws IOException {
        // Missing ancestry and unidentifiable I/O failures are not a first-start verdict.
        verifyDirectory();
        if (absent.getFile() == null) throw absent;
        try {
            if (!path.equals(Path.of(absent.getFile()).toAbsolutePath().normalize())) throw absent;
        } catch (InvalidPathException unidentifiable) {
            throw typed(Code.SQLITE_VERIFICATION_UNAVAILABLE, FileRole.UNKNOWN,
                    "SQLite child absence could not be verified.", absent);
        }
    }

    private static IOException metadataFailure(FileRole role, IOException failure) {
        if (failure instanceof AccessDeniedException) {
            return typed(Code.SQLITE_ACCESS_DENIED, role,
                    "SQLite file metadata access was denied; ownership unproven.", failure);
        }
        if (failure instanceof EventTrailFailure.FailureIOException known
                && known.failure().role() == FileRole.UNKNOWN) {
            return typed(known.failure().code(), role, known.getMessage(), failure);
        }
        return failure;
    }

    synchronized void verifyCurrent() throws IOException {
        if (poison != null) {
            EventTrailFailure safe = EventTrailFailure.find(poison).orElseGet(() -> new EventTrailFailure(
                    Code.STORAGE_QUIESCENCE_UNPROVEN, EventTrailFailure.Phase.CLEANUP,
                    EventTrailFailure.Backend.SQLITE, FileRole.UNKNOWN));
            throw new EventTrailFailure.FailureIOException(safe, poison);
        }
        if (released || lock == null || !lock.isValid()
                || OWNERS.get(identity) != this) {
            throw new IOException("SQLite database ownership is not proven; new database work is denied.");
        }
        verifyDirectory();
        verifyRetainedPin(databasePin, databasePath, FileRole.DATABASE);
        verifyRetainedPin(sidecarPin, sidecarPath, FileRole.LOCK);
    }

    private static void verifyRetainedPin(Pin pin, Path path, FileRole role) throws IOException {
        try { pin.verify(path); }
        catch (NoSuchFileException absent) {
            throw typed(Code.SQLITE_IDENTITY_CHANGED, role,
                    "A previously observed SQLite family file disappeared; replacement is denied.", absent);
        } catch (IOException failure) { throw metadataFailure(role, failure); }
    }

    /** Sticky: a failed first physical close may have lost the driver's native handle. */
    synchronized void poison(Throwable cause) {
        if (released) throw new IllegalStateException("SQLite ownership was already released before native retirement.");
        if (poison == null) poison = cause == null ? new IOException("SQLite native retirement is unproven.") : cause;
    }

    /** Only a caller that has proved actual native quiescence may invoke this. */
    synchronized void releaseOnlyAfterQuiescent() throws IOException {
        if (released) return;
        if (poison != null) {
            throw new EventTrailFailure.FailureIOException(new EventTrailFailure(
                    Code.STORAGE_QUIESCENCE_UNPROVEN, EventTrailFailure.Phase.CLEANUP,
                    EventTrailFailure.Backend.SQLITE, FileRole.UNKNOWN),
                    "SQLite native cleanup is unproven; database ownership is retained until JVM exit.", poison);
        }
        try { releaseResources(); }
        catch (IOException | RuntimeException | Error failure) {
            poison(failure);
            throw failure;
        }
        released = true;
        OWNERS.remove(identity, this);
    }

    private void releaseResources() throws IOException {
        // Keep the OS lease until metadata pins have actually closed. Never delete
        // the permanent sidecar or inspect/close the SQLite database with Java I/O.
        if (databasePin != null) { databasePin.close(); databasePin = null; }
        if (sidecarPin != null) { sidecarPin.close(); sidecarPin = null; }
        directory.close();
        if (channel != null) { channel.close(); channel = null; lock = null; }
    }

    private void verifyDirectory() throws IOException {
        directory.verify();
    }

    private Pin pin(Path path) throws IOException {
        if (linuxDirectory != null) {
            return new LinuxPin(new Identity("linux", linuxDirectory.inspectChild(path)), linuxDirectory);
        }
        if (windows) {
            // Preserve NoSuchFileException for the pre-creation path, and reject
            // links before the Windows-only bridge can follow anything.
            ordinaryFile(path);
            try { return WindowsPin.open(path); }
            catch (LinkageError unavailable) {
                throw typed(Code.SQLITE_VERIFICATION_UNAVAILABLE, FileRole.UNKNOWN,
                        "SQLite Windows file identity is unavailable; startup denied.", unavailable);
            }
        }
        return new UnixPin(unixIdentity(path));
    }

    private static boolean createEmptyIfAbsent(Path path) throws IOException {
        try { Files.createFile(path); return true; }
        catch (FileAlreadyExistsException existing) { return false; /* Never truncate or claim creation. */ }
    }

    private static void verifyDirectories(Path path) throws IOException {
        Path current = path.getRoot();
        ordinaryDirectory(current);
        for (Path component : path) {
            current = current.resolve(component);
            ordinaryDirectory(current);
        }
    }

    private static void ordinaryDirectory(Path path) throws IOException {
        ordinaryDirectory(basicAttributes(path, FileRole.DIRECTORY));
    }

    /** Shared metadata decision boundary; no extra filesystem/profile acceptance. */
    static void ordinaryDirectory(BasicFileAttributes attributes) throws IOException {
        if (attributes.isSymbolicLink()) {
            throw typed(Code.SQLITE_VERIFICATION_UNAVAILABLE, FileRole.DIRECTORY,
                    "SQLite symbolic-directory ownership verification is unavailable; startup denied.", null);
        }
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("SQLite ownership rejects symbolic, reparse, or non-directory ancestry.");
        }
    }

    private static void ordinaryFile(Path path) throws IOException {
        ordinaryFile(basicAttributes(path, FileRole.UNKNOWN));
    }

    static void ordinaryFile(BasicFileAttributes attributes) throws IOException {
        if (attributes.isSymbolicLink()) {
            throw typed(Code.SQLITE_UNSAFE_ALIAS, FileRole.UNKNOWN,
                    "SQLite ownership requires plain non-symbolic database and sidecar files.", null);
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("SQLite ownership requires plain non-symbolic database and sidecar files.");
        }
    }

    private static Identity unixIdentity(Path path) throws IOException {
        Map<String, Object> attributes;
        try {
            attributes = Files.readAttributes(path,
                    "unix:isRegularFile,isSymbolicLink,isOther,fileKey,nlink", NO_FOLLOW);
        } catch (AccessDeniedException denied) {
            throw typed(Code.SQLITE_ACCESS_DENIED, FileRole.UNKNOWN,
                    "SQLite file metadata access was denied; ownership unproven.", denied);
        }
        if (Boolean.TRUE.equals(attributes.get("isSymbolicLink"))) {
            throw typed(Code.SQLITE_UNSAFE_ALIAS, FileRole.UNKNOWN,
                    "SQLite ownership requires plain non-symbolic database and sidecar files.", null);
        }
        Object key = attributes.get("fileKey");
        Object links = attributes.get("nlink");
        if (!Boolean.TRUE.equals(attributes.get("isRegularFile"))
                || !Boolean.FALSE.equals(attributes.get("isSymbolicLink"))
                || !Boolean.FALSE.equals(attributes.get("isOther")) || key == null
                || !(links instanceof Number count)) throw unsupported();
        if (count.longValue() != 1L) throw count.longValue() > 1L ? linkedFile() : unsupported();
        return new Identity("unix", key);
    }

    private static IOException alreadyOwned() {
        return typed(Code.SQLITE_OWNER_CONFLICT, FileRole.LOCK,
                "SQLite database is already owned by another EventTrail lifetime; startup denied.", null);
    }

    /** Item (b): the lease held elsewhere decides; a -wal/-shm left vanishing before it is kept as suppressed. */
    private static IOException ownerConflict(IOException vanishing) {
        IOException conflict = alreadyOwned();
        if (vanishing != null) conflict.addSuppressed(vanishing);
        return conflict;
    }

    /** Item (a): an owner in this JVM's OWNERS, found before any -wal/-shm metadata call. */
    private static IOException ownedInThisJvm(boolean retainedToJvmExit) {
        return typed(Code.SQLITE_OWNER_CONFLICT, FileRole.LOCK, retainedToJvmExit
                ? "SQLite database is already owned by a previous EventTrail lifetime in this JVM whose native "
                        + "cleanup is unproven; it keeps the database until this JVM exits; startup denied."
                : "SQLite database is already owned by another EventTrail lifetime in this JVM, such as a previous "
                        + "one still closing (its writer or a physical close has not finished); startup denied.", null);
    }

    /** True once poisoned: such an owner never releases, so it holds the database until this JVM exits. */
    private synchronized boolean retainedToJvmExit() { return poison != null; }

    private static IOException linkedFile() {
        return typed(Code.SQLITE_UNSAFE_ALIAS, FileRole.UNKNOWN,
                "SQLite database and ownership sidecar must each have exactly one hard link; startup denied.", null);
    }

    private static IOException unsupported() {
        return typed(Code.SQLITE_VERIFICATION_UNAVAILABLE, FileRole.UNKNOWN,
                "SQLite physical-file ownership is unverified on this filesystem/provider; startup denied. "
                + "Review the verified-WAL-fixed-core fallback; unsafe file aliases remain unsupported.", null);
    }

    /**
     * spf1 r3 ruling 4 (the r9c production note): a -wal/-shm seen vanishing past the bound (link count 0, or gone at
     * the short open). The same code as {@link #unsupported()}; the text names the other cause too.
     */
    private static IOException vanishingUnverified() {
        return typed(Code.SQLITE_VERIFICATION_UNAVAILABLE, FileRole.UNKNOWN,
                "SQLite physical-file ownership is unverified: a -wal or -shm file kept vanishing while it was checked "
                + "(SQLite deletes it at a last close). Either a previous EventTrail lifetime in this JVM is still "
                + "closing (start again once it has closed), or this filesystem/provider cannot verify ownership; "
                + "startup denied. Review the verified-WAL-fixed-core fallback; unsafe file aliases remain unsupported.",
                null);
    }

    private static IOException typed(Code code, FileRole role, String safeDiagnostic, Throwable cause) {
        return new EventTrailFailure.FailureIOException(new EventTrailFailure(code,
                EventTrailFailure.Phase.OWNERSHIP, EventTrailFailure.Backend.SQLITE, role), safeDiagnostic, cause);
    }

    private static BasicFileAttributes basicAttributes(Path path, FileRole role) throws IOException {
        try { return Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW); }
        catch (AccessDeniedException denied) {
            throw typed(Code.SQLITE_ACCESS_DENIED, role,
                    "SQLite file metadata access was denied; ownership unproven.", denied);
        }
    }

    private record Identity(String provider, Object key) {}
    private interface Pin {
        Identity identity();
        void verify(Path path) throws IOException;
        void close() throws IOException;
    }
    private record UnixPin(Identity identity) implements Pin {
        @Override public void verify(Path path) throws IOException {
            if (!identity.equals(unixIdentity(path))) throw typed(Code.SQLITE_IDENTITY_CHANGED, FileRole.UNKNOWN,
                    "SQLite file identity changed; database work denied.", null);
        }
        @Override public void close() { /* stat-only identity: no database descriptor to close. */ }
    }

    private record LinuxPin(Identity identity, LinuxDatabaseFamilyVerifier directory) implements Pin {
        @Override public void verify(Path path) throws IOException {
            if (!identity.key().equals(directory.inspectChild(path))) {
                throw typed(Code.SQLITE_IDENTITY_CHANGED, FileRole.UNKNOWN,
                        "SQLite file identity changed; database work denied.", null);
            }
        }
        @Override public void close() { /* Direct statx owns no family-file descriptor. */ }
    }

    /** Lazy NTFS-only bridge. No JDK internals, new native dependency, or module-opening flags. */
    private static final class WindowsPin implements Pin {
        // Attribute-only opens can bypass Windows share-access checks. Request
        // read-data access as well, but never perform a data read on this handle.
        private static final int READ_DATA_AND_ATTRIBUTES = 0x81, SHARE_READ_WRITE = 3, OPEN_EXISTING = 3;
        private static final int OPEN_REPARSE_POINT = 0x00200000, REPARSE_POINT = 0x400, DIRECTORY = 0x10;
        private static final int READ_ATTRIBUTES = 0x80, BACKUP_SEMANTICS = 0x02000000, SHARE_ALL = 7;
        private static final Set<WindowsPin> UNCLOSED = ConcurrentHashMap.newKeySet();
        private final Identity identity;
        private final boolean directory;
        private final long links;
        private Pointer handle;

        private WindowsPin(Pointer handle, Identity identity, boolean directory) {
            this(handle, identity, directory, 1L);
        }

        private WindowsPin(Pointer handle, Identity identity, boolean directory, long links) {
            this.handle = handle;
            this.identity = identity;
            this.directory = directory;
            this.links = links;
        }

        static void requireLocalVolume(Path root) throws IOException {
            int type = Api.INSTANCE.GetDriveTypeW(new WString(root.toString()));
            // DRIVE_REMOVABLE and DRIVE_FIXED are local; mapped remote/unknown
            // volumes cannot establish the supported local-WAL profile.
            if (!isLocalWindowsDriveType(type)) throw unsupported();
        }

        static WindowsPin open(Path path) throws IOException {
            return open(path, SHARE_READ_WRITE);
        }

        static WindowsPin open(Path path, int sharing) throws IOException {
            return open(path, sharing, false);
        }

        static WindowsPin openDirectory(Path path) throws IOException {
            return open(path, SHARE_ALL, true);
        }

        private static WindowsPin open(Path path, int sharing, boolean directory) throws IOException {
            return open(path, sharing, directory, false);
        }

        private static WindowsPin open(Path path, int sharing, boolean directory, boolean anyLinkCount) throws IOException {
            String name = path.toString();
            if (!name.startsWith("\\\\?\\")) name = "\\\\?\\" + name;
            Pointer handle = Api.INSTANCE.CreateFileW(new WString(name),
                    directory ? READ_ATTRIBUTES : READ_DATA_AND_ATTRIBUTES, sharing, null, OPEN_EXISTING,
                    OPEN_REPARSE_POINT | (directory ? BACKUP_SEMANTICS : 0), null);
            if (handle == null || Pointer.nativeValue(handle) == -1L
                    || (Native.POINTER_SIZE == 4 && Pointer.nativeValue(handle) == 0xffffffffL)) {
                throw windowsFailure("open metadata pin");
            }
            try {
                Information information = information(handle, directory, anyLinkCount);
                return new WindowsPin(handle, information.identity, directory, information.links);
            }
            catch (IOException | RuntimeException | Error failure) {
                try { new WindowsPin(handle, null, directory).close(); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (failure != cleanup) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        /**
         * Item (b): a short share-all open of an existing -wal, -shm or -journal, closed before this returns. The
         * native checks are the ones {@link #inspect} makes, except that the link count is returned to the caller,
         * which decides about 0 (a delete in progress) and more than 1 (an alias).
         */
        static long auxiliaryLinkCount(Path path) throws IOException {
            WindowsPin transientPin = open(path, SHARE_ALL, false, true);
            try { return transientPin.links; }
            finally { transientPin.close(); }
        }

        private static Identity inspect(Pointer handle, boolean directory) throws IOException {
            return information(handle, directory, false).identity;
        }

        private record Information(Identity identity, long links) {}

        private static Information information(Pointer handle, boolean directory, boolean anyLinkCount) throws IOException {
            // BY_HANDLE_FILE_INFORMATION consists of thirteen DWORDs (52 bytes).
            try (Memory info = new Memory(52)) {
                if (!Api.INSTANCE.GetFileInformationByHandle(handle, info)) throw windowsFailure("read file identity");
                if ((info.getInt(0) & REPARSE_POINT) != 0 || ((info.getInt(0) & DIRECTORY) != 0) != directory) {
                    if ((info.getInt(0) & REPARSE_POINT) != 0) {
                        throw typed(Code.SQLITE_UNSAFE_ALIAS, FileRole.UNKNOWN,
                                "SQLite ownership rejects reparse or non-file native targets.", null);
                    }
                    throw new IOException("SQLite ownership rejects reparse or non-file native targets.");
                }
                long links = Integer.toUnsignedLong(info.getInt(40));
                if (!directory && !anyLinkCount && links != 1L) throw links > 1L ? linkedFile() : unsupported();
                return new Information(new Identity(directory ? "windows-ntfs-directory" : "windows-ntfs",
                        new WindowsIdentity(info.getInt(28), info.getInt(44), info.getInt(48))), links);
            }
        }

        @Override public Identity identity() { return identity; }
        @Override public void verify(Path path) throws IOException {
            if (directory) ordinaryDirectory(path); else ordinaryFile(path);
            if (handle == null || !identity.equals(inspect(handle, directory))) throw typed(Code.SQLITE_IDENTITY_CHANGED,
                    FileRole.UNKNOWN, "SQLite pinned file identity changed.", null);
            WindowsPin current = directory ? openDirectory(path) : open(path);
            Throwable primary = null;
            try {
                if (!identity.equals(current.identity)) throw typed(Code.SQLITE_IDENTITY_CHANGED, FileRole.UNKNOWN,
                        "SQLite pathname identity changed; database work denied.", null);
            } catch (IOException | RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                try { current.close(); }
                catch (IOException | RuntimeException | Error cleanup) {
                    if (primary == null) throw cleanup;
                    if (primary != cleanup) primary.addSuppressed(cleanup);
                }
            }
        }
        @Override public void close() throws IOException {
            if (handle != null) {
                try {
                    if (!Api.INSTANCE.CloseHandle(handle)) throw windowsFailure("close metadata pin");
                    handle = null;
                } catch (IOException | RuntimeException | Error failure) {
                    UNCLOSED.add(this);
                    throw failure;
                }
            }
        }
        private static IOException windowsFailure(String operation) {
            int error = Native.getLastError();
            IOException original = new WindowsError("SQLite could not " + operation + " (Windows error " + error + "); ownership unproven.", error);
            if (error == 5) {
                return typed(Code.SQLITE_ACCESS_DENIED, FileRole.UNKNOWN,
                        "SQLite file metadata access was denied; ownership unproven.", original);
            }
            return original;
        }
        private record WindowsIdentity(int volume, int indexHigh, int indexLow) {}
        public interface Api extends StdCallLibrary {
            Api INSTANCE = Native.load("kernel32", Api.class);
            Pointer CreateFileW(WString name, int access, int sharing, Pointer security, int disposition, int flags, Pointer template);
            boolean GetFileInformationByHandle(Pointer handle, Pointer information);
            boolean CloseHandle(Pointer handle);
            int GetDriveTypeW(WString root);
        }
    }
}
