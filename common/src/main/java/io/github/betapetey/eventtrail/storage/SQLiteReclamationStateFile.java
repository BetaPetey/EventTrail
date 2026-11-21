package io.github.betapetey.eventtrail.storage;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Two fixed, bounded scheduling-state members under the existing SQLite owner.
 * The caller retains an auxiliary storage lifetime, outside native SQL, through
 * every operation. This shares ownership's cooperating/static-topology contract;
 * it is not a hostile-filesystem sandbox or an audit-data store.
 */
final class SQLiteReclamationStateFile {
    static final int MAX_BYTES = 4096;
    private final SQLiteFileOwnership owner;
    private final Path target;
    private final Path temporary;
    private final String binding;
    private final PublicationObservation observation;
    private Snapshot expected;
    private boolean initialized;
    private boolean failed;

    SQLiteReclamationStateFile(SQLiteFileOwnership owner) throws IOException {
        this(owner, phase -> {});
    }

    /** Bounded publication fault seam; no caller-selected file or production control. */
    SQLiteReclamationStateFile(SQLiteFileOwnership owner, PublicationObservation observation) throws IOException {
        this.owner = Objects.requireNonNull(owner);
        this.observation = Objects.requireNonNull(observation);
        this.binding = owner.stableBinding();
        this.target = owner.reclamationStatePath(false);
        this.temporary = owner.reclamationStatePath(true);
        String platform = System.getProperty("os.name", "");
        if (!platform.startsWith("Windows") && !platform.equals("Linux"))
            throw new IOException("Durable reclamation state is unverified on this platform");
    }

    /** Null is absent; an empty array is bounded invalid content, including an oversized file. */
    synchronized byte[] read() throws IOException {
        requireHealthy();
        try {
            initialize();
            requireUnchanged(expected, snapshot(false));
            if (owner.reclamationStateIdentity(true) != null)
                throw new IOException("Unexpected reclamation temporary member appeared during this lifetime");
            verifyOwner();
            return expected.identity == null ? null : expected.payload.clone();
        } catch (IOException | RuntimeException | LinkageError failure) { throw disable(failure); }
    }

    /** Returns only after forced content, atomic durable publication, readback and ownership checks. */
    synchronized void write(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0 || payload.length > MAX_BYTES) throw new IllegalArgumentException("Invalid bounded reclamation state size");
        byte[] bytes = payload.clone();
        requireHealthy();
        try {
            initialize();
            requireUnchanged(expected, snapshot(false));
            if (owner.reclamationStateIdentity(true) != null)
                throw new IOException("Unexpected reclamation temporary member appeared during this lifetime");
            verifyOwner();
            Set<java.nio.file.OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            String temporaryIdentity;
            try (FileChannel channel = System.getProperty("os.name", "").equals("Linux")
                    ? FileChannel.open(temporary, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : FileChannel.open(temporary, options)) {
                temporaryIdentity = owner.reclamationStateIdentity(true);
                if (temporaryIdentity == null) throw new IOException("Created reclamation temporary member disappeared");
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) if (channel.write(buffer) <= 0)
                    throw new IOException("Reclamation state write made no progress");
                channel.force(true);
            }
            Snapshot staged = snapshot(true);
            if (!temporaryIdentity.equals(staged.identity) || !Arrays.equals(bytes, staged.payload))
                throw new IOException("Reclamation temporary identity or content changed");
            observation.at(Phase.TEMP_FORCED);
            observation.at(Phase.BEFORE_PUBLICATION);
            verifyOwner();
            requireUnchanged(expected, snapshot(false));
            requireUnchanged(staged, snapshot(true));
            publishDurably();
            observation.at(Phase.AFTER_PUBLICATION);
            verifyOwner();
            Snapshot published = snapshot(false);
            if (!temporaryIdentity.equals(published.identity) || !Arrays.equals(bytes, published.payload)
                    || owner.reclamationStateIdentity(true) != null)
                throw new IOException("Reclamation publication could not be authenticated");
            expected = published;
        } catch (IOException | RuntimeException | LinkageError failure) {
            // Preserve any bounded temporary member. A later owner may discard it,
            // but this lifetime must not retry uncertain publication or authorize SQL.
            throw disable(failure);
        }
    }

    private void initialize() throws IOException {
        if (initialized) return;
        verifyOwner();
        Snapshot current = snapshot(false), abandoned = snapshot(true);
        if (abandoned.identity != null) {
            // Even corrupt/oversized leftovers are read only through the fixed
            // bounded prefix. Their unpublished bytes never become authority.
            requireUnchanged(abandoned, snapshot(true));
            verifyOwner();
            Files.delete(temporary); // Exact authenticated fixed member only; never state/WAL/SHM/database.
            verifyOwner();
            if (owner.reclamationStateIdentity(true) != null) throw new IOException("Reclamation temporary cleanup unproven");
        }
        requireUnchanged(current, snapshot(false));
        expected = current;
        initialized = true;
    }

    private Snapshot snapshot(boolean temp) throws IOException {
        verifyOwner();
        Path path = temp ? temporary : target;
        String identity = owner.reclamationStateIdentity(temp);
        if (identity == null) return new Snapshot(null, 0, FileTime.fromMillis(0), new byte[0], new byte[0]);
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        ByteBuffer bounded = ByteBuffer.allocate(MAX_BYTES + 1);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (bounded.hasRemaining()) {
                int read = channel.read(bounded);
                if (read < 0) break;
                if (read == 0) throw new IOException("Reclamation state read made no progress");
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!identity.equals(owner.reclamationStateIdentity(temp)) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || bounded.position() != Math.min((long) MAX_BYTES + 1, before.size()))
            throw new IOException("Reclamation state changed during observation");
        verifyOwner();
        byte[] prefix = Arrays.copyOf(bounded.array(), bounded.position());
        return new Snapshot(identity, before.size(), before.lastModifiedTime(),
                before.size() > MAX_BYTES ? new byte[0] : prefix, digest(prefix));
    }

    private void publishDurably() throws IOException {
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            // Qualified same-directory NTFS rename: no COPY_ALLOWED or non-atomic fallback.
            // WRITE_THROUGH acknowledges the move on disk, after the staged file was forced.
            if (!WindowsPublication.INSTANCE.MoveFileExW(wide(temporary), wide(target), 0x1 | 0x8))
                throw new IOException("Durable reclamation state replacement was not acknowledged");
        } else {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            verifyOwner();
            forceDirectory(target.getParent(), LinuxPublication.INSTANCE);
            verifyOwner();
        }
    }

    /** Exact native-call seam; production passes only its already-verified fixed parent directory. */
    static void forceDirectory(Path parent, LinuxPublication api) throws IOException {
        int descriptor = api.open(parent.toString(), LinuxDatabaseFamilyVerifier.O_DIRECTORY
                | LinuxDatabaseFamilyVerifier.O_NOFOLLOW | LinuxDatabaseFamilyVerifier.O_CLOEXEC);
        if (descriptor < 0) throw new IOException("Reclamation directory durability handle unavailable");
        try (LinuxOwnedDescriptor directory = new LinuxOwnedDescriptor(descriptor, fd -> {
            if (api.close(fd) != 0) throw new IOException("Reclamation directory durability handle closure unproven");
        })) {
            if (api.fsync(directory.value()) != 0) throw new IOException("Reclamation directory publication durability unproven");
        }
    }

    private void verifyOwner() throws IOException {
        owner.verifyCurrent();
        if (!binding.equals(owner.stableBinding())) throw new IOException("Reclamation database binding changed");
    }
    private static void requireUnchanged(Snapshot expected, Snapshot actual) throws IOException {
        if (!Objects.equals(expected.identity, actual.identity) || expected.size != actual.size
                || !expected.modified.equals(actual.modified) || !Arrays.equals(expected.fingerprint, actual.fingerprint))
            throw new IOException("Reclamation state was replaced or changed outside its owner");
    }
    private void requireHealthy() throws IOException {
        if (failed) throw new IOException("Reclamation state adapter is disabled after uncertain I/O");
    }
    private IOException disable(Throwable failure) {
        failed = true;
        return failure instanceof IOException io ? io : new IOException("Reclamation state I/O unavailable", failure);
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static WString wide(Path path) {
        String name = path.toString();
        return new WString(name.startsWith("\\\\?\\") ? name : "\\\\?\\" + name);
    }
    private record Snapshot(String identity, long size, FileTime modified, byte[] payload, byte[] fingerprint) {}
    enum Phase { TEMP_FORCED, BEFORE_PUBLICATION, AFTER_PUBLICATION }
    @FunctionalInterface interface PublicationObservation { void at(Phase phase) throws IOException; }
    public interface WindowsPublication extends StdCallLibrary {
        WindowsPublication INSTANCE = Native.load("kernel32", WindowsPublication.class);
        boolean MoveFileExW(WString source, WString target, int flags);
    }
    public interface LinuxPublication extends Library {
        LinuxPublication INSTANCE = Native.load("c", LinuxPublication.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
        int open(String name, int flags);
        int fsync(int descriptor);
        int close(int descriptor);
    }
}
