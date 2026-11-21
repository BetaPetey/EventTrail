package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.security.PemCertificateBundle;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Run-owned Connector/J truststore retained only for the lifetime of one MySQL pool. */
final class MySqlTlsTrustStore implements AutoCloseable {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_ONLY_POSIX = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private Path path;
    private char[] password;

    private MySqlTlsTrustStore(Path path, char[] password) {
        this.path = path;
        this.password = password;
    }

    static MySqlTlsTrustStore create(Path certificateFile)
            throws IOException, GeneralSecurityException {
        return create(certificateFile, null);
    }

    static MySqlTlsTrustStore create(Path certificateFile, Path temporaryDirectory)
            throws IOException, GeneralSecurityException {
        List<X509Certificate> certificates = PemCertificateBundle.read(certificateFile);
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        char[] password = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(random).toCharArray();
        Arrays.fill(random, (byte) 0);

        Path trustStore = null;
        boolean complete = false;
        try {
            trustStore = (temporaryDirectory == null
                    ? Files.createTempFile("eventtrail-mysql-trust-", ".p12")
                    : Files.createTempFile(
                            temporaryDirectory, "eventtrail-mysql-trust-", ".p12"))
                    .toAbsolutePath().normalize();
            restrictToOwner(trustStore);

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            for (int index = 0; index < certificates.size(); index++) {
                keyStore.setCertificateEntry(
                        String.format("eventtrail-ca-%04d", index + 1),
                        certificates.get(index));
            }
            try (OutputStream output = Files.newOutputStream(trustStore)) {
                keyStore.store(output, password);
            }
            MySqlTlsTrustStore result = new MySqlTlsTrustStore(trustStore, password);
            complete = true;
            return result;
        } finally {
            if (!complete) {
                Arrays.fill(password, '\0');
                deleteOrDefer(trustStore);
            }
        }
    }

    String url() {
        ensureOpen();
        return path.toUri().toASCIIString();
    }

    String password() {
        ensureOpen();
        return new String(password);
    }

    Path pathForTesting() {
        ensureOpen();
        return path;
    }

    private void ensureOpen() {
        if (path == null || password == null) {
            throw new IllegalStateException("MySQL TLS truststore is closed");
        }
    }

    @Override
    public void close() throws IOException {
        Path currentPath = path;
        char[] currentPassword = password;
        path = null;
        password = null;
        if (currentPassword != null) Arrays.fill(currentPassword, '\0');
        if (currentPath == null) return;
        try {
            Files.deleteIfExists(currentPath);
        } catch (IOException failure) {
            currentPath.toFile().deleteOnExit();
            throw failure;
        }
    }

    private static void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(OWNER_ONLY_POSIX);
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
        }
    }

    private static void deleteOrDefer(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            path.toFile().deleteOnExit();
        }
    }
}
