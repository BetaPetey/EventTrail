package io.github.betapetey.eventtrail.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads one bounded certificate-only PEM bundle without accepting private material. */
public final class PemCertificateBundle {
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    public static final int MAX_CERTIFICATES = 64;
    private static final Pattern CERTIFICATE_BLOCK = Pattern.compile(
            "-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\r\\n]+?)\\s*"
                    + "-----END CERTIFICATE-----");

    private PemCertificateBundle() {}

    public static List<X509Certificate> read(Path path)
            throws IOException, GeneralSecurityException {
        if (path == null) throw new IOException("Certificate path is missing");
        Path absolute = path.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() <= 0
                || attributes.size() > MAX_FILE_BYTES || !Files.isReadable(absolute)) {
            throw new IOException("Certificate file is not a bounded readable regular file");
        }

        byte[] bytes;
        try (var input = Files.newInputStream(
                absolute, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Certificate file size is invalid");
        }

        String pem = decodeAscii(bytes);
        if (pem.contains("PRIVATE KEY-----")) {
            throw new IOException("Certificate bundle must not contain private-key material");
        }

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certificates = new ArrayList<>();
        Matcher matcher = CERTIFICATE_BLOCK.matcher(pem);
        int consumed = 0;
        while (matcher.find()) {
            if (!pem.substring(consumed, matcher.start()).isBlank()) {
                throw new IOException("Certificate bundle contains non-certificate data");
            }
            if (certificates.size() == MAX_CERTIFICATES) {
                throw new IOException("Certificate bundle contains too many certificates");
            }
            byte[] der;
            try {
                der = Base64.getMimeDecoder().decode(matcher.group(1));
            } catch (IllegalArgumentException invalidBase64) {
                throw new IOException("Certificate bundle contains invalid base64", invalidBase64);
            }
            try (ByteArrayInputStream input = new ByteArrayInputStream(der)) {
                X509Certificate certificate = (X509Certificate) factory.generateCertificate(input);
                if (input.available() != 0) {
                    throw new IOException("Certificate block contains trailing binary data");
                }
                certificates.add(certificate);
            }
            consumed = matcher.end();
        }
        if (!pem.substring(consumed).isBlank()) {
            throw new IOException("Certificate bundle contains non-certificate data");
        }
        if (certificates.isEmpty()) {
            throw new IOException("Certificate bundle contains no X.509 certificates");
        }
        return List.copyOf(certificates);
    }

    private static String decodeAscii(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.US_ASCII.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidText) {
            throw new IOException("Certificate bundle is not ASCII PEM", invalidText);
        }
    }
}
