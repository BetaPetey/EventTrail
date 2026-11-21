package io.github.betapetey.eventtrail.storage;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.github.betapetey.eventtrail.util.EventTrailFailure;
import io.github.betapetey.eventtrail.util.EventTrailFailure.Code;
import io.github.betapetey.eventtrail.util.EventTrailFailure.FileRole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * One retained directory authority for Linux metadata and the JDBC pathname.
 * Direct statx never opens a database, lock, WAL, SHM or journal descriptor.
 * This is a static-topology/cooperating-owner check, not a hostile rename sandbox.
 * Missing statx mount information fails closed; there is no fdinfo fallback.
 */
final class LinuxDatabaseFamilyVerifier implements AutoCloseable {
    static final int AT_FDCWD = -100, AT_SYMLINK_NOFOLLOW = 0x100, AT_EMPTY_PATH = 0x1000;
    static final int O_PATH = 0x200000, O_CLOEXEC = 0x80000, O_NOFOLLOW = 0x20000, O_DIRECTORY = 0x10000;
    static final int F_GETFD = 1, F_GETFL = 3, FD_CLOEXEC = 1;
    static final int STATX_TYPE = 1, STATX_NLINK = 4, STATX_INO = 0x100, STATX_MNT_ID = 0x1000;
    static final int REQUIRED_MASK = STATX_TYPE | STATX_NLINK | STATX_INO | STATX_MNT_ID;
    static final int DIRECTORY_TYPE = 0x4000, REGULAR_TYPE = 0x8000, SYMLINK_TYPE = 0xa000;
    static final int STATX_BYTES = 256, STATFS_BYTES = 120;

    private final Path directoryPath;
    private final Set<String> family;
    private final NativeCalls calls;
    private final LinuxOwnedDescriptor directory;
    private final Metadata authority;

    private LinuxDatabaseFamilyVerifier(Path path, String databaseName, NativeCalls calls,
                                        LinuxOwnedDescriptor directory, Metadata authority) {
        this.directoryPath = path;
        this.family = Set.of(databaseName, databaseName + ".eventtrail.lock", databaseName + "-wal",
                databaseName + "-shm", databaseName + "-journal",
                databaseName + ".reclaim-state", databaseName + ".reclaim-state.tmp");
        this.calls = calls;
        this.directory = directory;
        this.authority = authority;
    }

    static LinuxDatabaseFamilyVerifier open(Path resolvedDirectory, String databaseName) throws IOException {
        return openWithProvider(resolvedDirectory, databaseName, () -> {
            String architecture = System.getProperty("os.arch", "");
            if (Native.POINTER_SIZE != 8 || Native.LONG_SIZE != 8
                    || !(architecture.equals("amd64") || architecture.equals("x86_64") || architecture.equals("aarch64"))) {
                throw unavailable(FileRole.DIRECTORY, "SQLite Linux native metadata ABI is unavailable.", null);
            }
            // JNA and the Unix Path provider must encode exactly the same pathname.
            if (!StandardCharsets.UTF_8.name().equalsIgnoreCase(System.getProperty("sun.jnu.encoding", ""))) {
                throw unavailable(FileRole.DIRECTORY, "SQLite Linux pathname encoding is unverified.", null);
            }
            return JnaCalls.INSTANCE;
        });
    }

    @FunctionalInterface interface NativeProvider { NativeCalls load() throws IOException; }

    static LinuxDatabaseFamilyVerifier openWithProvider(Path resolvedDirectory, String databaseName,
                                                        NativeProvider provider) throws IOException {
        // Native's ABI constants themselves initialize JNA. Keep that first
        // initialization in the same typed failure boundary as libc loading.
        try { return open(resolvedDirectory, databaseName, provider.load()); }
        catch (LinkageError unavailable) {
            throw unavailable(FileRole.DIRECTORY, "SQLite Linux native metadata functions are unavailable.", unavailable);
        }
    }

    /** Exact native-call seam; tests do not load Linux libc on the Windows host. */
    static LinuxDatabaseFamilyVerifier open(Path resolvedDirectory, String databaseName, NativeCalls calls) throws IOException {
        if (!resolvedDirectory.isAbsolute() || !resolvedDirectory.equals(resolvedDirectory.normalize())
                || !Path.of(resolvedDirectory.toString()).equals(resolvedDirectory)
                || databaseName.isEmpty() || databaseName.indexOf('/') >= 0 || databaseName.indexOf('\\') >= 0
                || databaseName.equals(".") || databaseName.equals("..")) {
            throw unavailable(FileRole.DIRECTORY, "SQLite directory authority is not a fixed resolved path.", null);
        }
        String name = nativeName(resolvedDirectory.toString());
        nativeName(databaseName);
        int fd = calls.openat(AT_FDCWD, name, O_PATH | O_CLOEXEC | O_NOFOLLOW | O_DIRECTORY);
        if (fd < 0) throw nativeFailure("open directory authority", calls.errno(), resolvedDirectory, FileRole.DIRECTORY);
        LinuxOwnedDescriptor descriptor = new LinuxOwnedDescriptor(fd, consumed -> {
            try {
                if (calls.close(consumed) != 0) {
                    throw nativeFailure("close directory authority", calls.errno(), resolvedDirectory, FileRole.DIRECTORY);
                }
            } catch (LinkageError | RuntimeException unavailable) {
                throw unavailable(FileRole.DIRECTORY, "SQLite Linux directory close could not be acknowledged.", unavailable);
            }
        });
        try {
            // Probe only a safe directory, before any existing family-file access.
            int flags = calls.fcntl(fd, F_GETFL);
            if (flags < 0) throw nativeFailure("read directory flags", calls.errno(), resolvedDirectory, FileRole.DIRECTORY);
            if ((flags & (O_PATH | O_NOFOLLOW | O_DIRECTORY)) != (O_PATH | O_NOFOLLOW | O_DIRECTORY)) {
                throw unavailable(FileRole.DIRECTORY, "SQLite Linux O_PATH directory capability was not honored.", null);
            }
            int descriptorFlags = calls.fcntl(fd, F_GETFD);
            if (descriptorFlags < 0) throw nativeFailure("read descriptor flags", calls.errno(), resolvedDirectory, FileRole.DIRECTORY);
            if ((descriptorFlags & FD_CLOEXEC) == 0) {
                throw unavailable(FileRole.DIRECTORY, "SQLite Linux close-on-exec capability was not honored.", null);
            }
            Metadata authority = read(calls, fd, "", AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW, resolvedDirectory, FileRole.DIRECTORY);
            requireDirectory(authority);
            try (Memory info = new Memory(STATFS_BYTES)) {
                info.clear();
                if (calls.fstatfs(fd, info) != 0) {
                    throw nativeFailure("identify directory filesystem", calls.errno(), resolvedDirectory, FileRole.DIRECTORY);
                }
                if (!supportsFileSystemMagic(info.getLong(0))) {
                    throw unavailable(FileRole.DIRECTORY, "SQLite Linux filesystem identity/locking profile is unverified.", null);
                }
            }
            LinuxDatabaseFamilyVerifier verifier = new LinuxDatabaseFamilyVerifier(resolvedDirectory,
                    databaseName, calls, descriptor, authority);
            verifier.verifyDirectory();
            return verifier;
        } catch (IOException | RuntimeException | Error failure) {
            try { descriptor.close(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    Path directoryPath() { return directoryPath; }

    /** Stable physical binding only; mount/current-path authority is still verified separately. */
    synchronized PhysicalIdentity directoryIdentity() throws IOException {
        verifyDirectory();
        return authority.physical;
    }

    synchronized void verifyDirectory() throws IOException {
        Metadata retained = read(calls, directory.value(), "", AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW,
                directoryPath, FileRole.DIRECTORY);
        Metadata pathname = read(calls, AT_FDCWD, nativeName(directoryPath.toString()), AT_SYMLINK_NOFOLLOW,
                directoryPath, FileRole.DIRECTORY);
        requireDirectory(retained);
        requireDirectory(pathname);
        if (!authority.sameObjectAndMount(retained) || !authority.sameObjectAndMount(pathname)) {
            throw typed(Code.SQLITE_IDENTITY_CHANGED, FileRole.DIRECTORY,
                    "SQLite retained directory identity changed; database work denied.", null);
        }
    }

    synchronized PhysicalIdentity inspectChild(Path path) throws IOException {
        if (!directoryPath.equals(path.getParent()) || !family.contains(path.getFileName().toString())) {
            throw unavailable(FileRole.UNKNOWN, "SQLite metadata target is not a fixed family child.", null);
        }
        Metadata child = read(calls, directory.value(), nativeName(path.getFileName().toString()),
                AT_SYMLINK_NOFOLLOW, path, FileRole.UNKNOWN);
        if (child.type == SYMLINK_TYPE) {
            throw typed(Code.SQLITE_UNSAFE_ALIAS, FileRole.UNKNOWN,
                    "SQLite ownership requires plain non-symbolic database and sidecar files.", null);
        }
        if (child.mountId != authority.mountId) {
            throw typed(Code.SQLITE_FILE_FAMILY_SPLIT, FileRole.UNKNOWN,
                    "SQLite family child occupies a different mount from its directory; startup denied.", null);
        }
        if (child.type != REGULAR_TYPE) {
            throw unavailable(FileRole.UNKNOWN, "SQLite family target is not an ordinary regular file.", null);
        }
        if (child.links != 1) {
            throw typed(Code.SQLITE_UNSAFE_ALIAS, FileRole.UNKNOWN,
                    "SQLite database and ownership sidecar must each have exactly one hard link; startup denied.", null);
        }
        return child.physical;
    }

    @Override public synchronized void close() throws IOException { directory.close(); }

    private static Metadata read(NativeCalls calls, int fd, String name, int flags, Path diagnostic, FileRole role) throws IOException {
        try (Memory info = new Memory(STATX_BYTES)) {
            info.clear();
            if (calls.statx(fd, name, flags, REQUIRED_MASK, info) != 0) {
                throw nativeFailure("read Linux metadata", calls.errno(), diagnostic, role);
            }
            return decode(info, role);
        } catch (LinkageError unavailable) {
            throw unavailable(role, "SQLite Linux statx metadata capability is unavailable.", unavailable);
        }
    }

    static Metadata decode(Memory info, FileRole role) throws IOException {
        if (info.size() != STATX_BYTES || (info.getInt(0) & REQUIRED_MASK) != REQUIRED_MASK) {
            throw unavailable(role, "SQLite Linux statx did not return required identity and mount fields.", null);
        }
        long links = Integer.toUnsignedLong(info.getInt(16));
        long inode = info.getLong(32), mount = info.getLong(144);
        if (links == 0 || inode == 0 || mount == 0) {
            throw unavailable(role, "SQLite Linux statx returned unusable identity fields.", null);
        }
        return new Metadata(new PhysicalIdentity(Integer.toUnsignedLong(info.getInt(136)),
                Integer.toUnsignedLong(info.getInt(140)), inode), mount,
                Short.toUnsignedInt(info.getShort(28)) & 0xf000, links);
    }

    private static void requireDirectory(Metadata metadata) throws IOException {
        if (metadata.type != DIRECTORY_TYPE) {
            throw unavailable(FileRole.DIRECTORY, "SQLite directory authority is not an ordinary directory.", null);
        }
    }

    static boolean supportsFileSystemMagic(long magic) {
        // Native equivalents of the existing bounded local Unix filesystem gate.
        return switch ((int) magic) {
            case 0xef53, 0x58465342, 0x9123683e, 0x2fc12fc1, 0x00011954, 0x4244, 0x482b -> true;
            default -> false;
        };
    }

    private static String nativeName(String value) throws IOException {
        if (value.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw unavailable(FileRole.UNKNOWN, "SQLite Linux pathname cannot be represented faithfully.", null);
        }
        return value;
    }

    private static IOException nativeFailure(String operation, int errno, Path path, FileRole role) {
        if (errno == 2) return new NoSuchFileException(path.toString());
        if (errno == 13 || errno == 1) {
            return typed(Code.SQLITE_ACCESS_DENIED, role, "SQLite Linux metadata access was denied; ownership unproven.",
                    new AccessDeniedException(path.toString(), null, "Linux errno " + errno));
        }
        return unavailable(role, "SQLite could not " + operation + "; ownership unproven.",
                new IOException("Linux native errno " + errno));
    }

    private static IOException unavailable(FileRole role, String message, Throwable cause) {
        return typed(Code.SQLITE_VERIFICATION_UNAVAILABLE, role, message, cause);
    }

    private static IOException typed(Code code, FileRole role, String message, Throwable cause) {
        return new EventTrailFailure.FailureIOException(new EventTrailFailure(code,
                EventTrailFailure.Phase.OWNERSHIP, EventTrailFailure.Backend.SQLITE, role), message, cause);
    }

    // Registry identity deliberately excludes namespace-local mount IDs. Complete
    // directory binds of the same files must share the same in-JVM reservation.
    record PhysicalIdentity(long deviceMajor, long deviceMinor, long inode) {}
    record Metadata(PhysicalIdentity physical, long mountId, int type, long links) {
        boolean sameObjectAndMount(Metadata other) {
            return physical.equals(other.physical) && mountId == other.mountId;
        }
    }

    interface NativeCalls {
        int openat(int directory, String name, int flags);
        int fcntl(int descriptor, int command);
        int statx(int directory, String name, int flags, int mask, Pointer buffer);
        int fstatfs(int descriptor, Pointer buffer);
        int close(int descriptor);
        int errno();
    }

    private static final class JnaCalls implements NativeCalls {
        static final JnaCalls INSTANCE = new JnaCalls();
        private final LibC libc = Native.load("c", LibC.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
        @Override public int openat(int directory, String name, int flags) { return libc.openat(directory, name, flags); }
        @Override public int fcntl(int descriptor, int command) { return libc.fcntl(descriptor, command); }
        @Override public int statx(int directory, String name, int flags, int mask, Pointer buffer) {
            return libc.statx(directory, name, flags, mask, buffer);
        }
        @Override public int fstatfs(int descriptor, Pointer buffer) { return libc.fstatfs(descriptor, buffer); }
        @Override public int close(int descriptor) { return libc.close(descriptor); }
        @Override public int errno() { return Native.getLastError(); }
    }

    public interface LibC extends Library {
        int openat(int directory, String name, int flags);
        int fcntl(int descriptor, int command);
        int statx(int directory, String name, int flags, int mask, Pointer buffer);
        int fstatfs(int descriptor, Pointer buffer);
        int close(int descriptor);
    }
}
