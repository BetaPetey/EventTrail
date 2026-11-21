package io.github.betapetey.eventtrail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Safe persistence boundary for the credential-bearing EventTrail server config. */
final class EventTrailConfigFileIO {
    static final long MAX_CONFIG_FILE_BYTES = 1024L * 1024L;
    static final Set<PosixFilePermission> OWNER_READ_WRITE =
            PosixFilePermissions.fromString("rw-------");
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private EventTrailConfigFileIO() {}

    static boolean exists(Path target) throws IOException {
        if (Files.notExists(target, NO_FOLLOW)) return false;
        requireSafeRegularTarget(target);
        return true;
    }

    static InputStream newInputStream(Path target) throws IOException {
        requireSafeRegularTarget(target);
        if (Files.size(target) > MAX_CONFIG_FILE_BYTES) {
            throw new IOException("EventTrail configuration exceeds the safe size limit.");
        }
        return Files.newInputStream(
                target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    static boolean writeAtomically(Path target, TempFileWriter writer) throws IOException {
        return writeAtomically(target, writer, Files::move);
    }

    static boolean writeAtomically(
            Path target,
            TempFileWriter writer,
            MoveExecutor mover) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("EventTrail configuration path has no parent directory.");
        }
        Files.createDirectories(parent);

        Set<PosixFilePermission> finalPermissions = desiredFinalPermissions(target, parent);
        Path temporary = createSecureTemp(parent, target.getFileName().toString());
        try {
            if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(temporary)) {
                throw new IOException("EventTrail configuration temporary file is not a regular file.");
            }
            boolean replace = writer.write(temporary);
            if (!replace) return false;

            applyPermissionsIfSupported(temporary, finalPermissions);
            requireSafeTargetForReplacement(target);
            replace(temporary, target, mover);
            applyPermissionsIfSupported(target, finalPermissions);
            return true;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void hardenExistingPermissions(Path target) throws IOException {
        requireSafeRegularTarget(target);
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null) return;
        applyPermissionsIfSupported(target, desiredFinalPermissions(target, parent));
    }

    static boolean contentEquals(Path first, Path second) throws IOException {
        requireSafeRegularTarget(first);
        requireSafeRegularTarget(second);
        try (InputStream left = Files.newInputStream(
                     first, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             InputStream right = Files.newInputStream(
                     second, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] leftBuffer = new byte[8192];
            byte[] rightBuffer = new byte[8192];
            while (true) {
                int leftRead = left.readNBytes(leftBuffer, 0, leftBuffer.length);
                int rightRead = right.readNBytes(rightBuffer, 0, rightBuffer.length);
                if (leftRead != rightRead) return false;
                if (leftRead == 0) return true;
                for (int i = 0; i < leftRead; i++) {
                    if (leftBuffer[i] != rightBuffer[i]) return false;
                }
            }
        }
    }

    private static Path createSecureTemp(Path parent, String targetName) throws IOException {
        String prefix = "." + targetName + "-";
        if (supportsPosix(parent)) {
            FileAttribute<Set<PosixFilePermission>> permissions =
                    PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE);
            return Files.createTempFile(parent, prefix, ".tmp", permissions);
        }
        return Files.createTempFile(parent, prefix, ".tmp");
    }

    private static void replace(Path temporary, Path target, MoveExecutor mover) throws IOException {
        try {
            mover.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            requireSafeTargetForReplacement(target);
            mover.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Set<PosixFilePermission> desiredFinalPermissions(
            Path target, Path parent) throws IOException {
        if (!supportsPosix(parent) || Files.notExists(target, NO_FOLLOW)) {
            return OWNER_READ_WRITE;
        }
        requireSafeRegularTarget(target);
        Set<PosixFilePermission> existing = Files.getPosixFilePermissions(
                target, LinkOption.NOFOLLOW_LINKS);
        if (OWNER_READ_WRITE.containsAll(existing)) {
            return Set.copyOf(existing);
        }
        return OWNER_READ_WRITE;
    }

    private static void applyPermissionsIfSupported(
            Path path, Set<PosixFilePermission> permissions) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null && supportsPosix(parent)) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }

    private static boolean supportsPosix(Path path) {
        return Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
    }

    private static void requireSafeTargetForReplacement(Path target) throws IOException {
        if (Files.notExists(target, NO_FOLLOW)) return;
        requireSafeRegularTarget(target);
    }

    private static void requireSafeRegularTarget(Path target) throws IOException {
        if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("EventTrail configuration target is not a regular non-symbolic file.");
        }
    }

    @FunctionalInterface
    interface TempFileWriter {
        boolean write(Path temporary) throws IOException;
    }

    @FunctionalInterface
    interface MoveExecutor {
        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }
}
