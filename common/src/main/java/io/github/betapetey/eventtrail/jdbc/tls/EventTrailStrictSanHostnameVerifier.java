package io.github.betapetey.eventtrail.jdbc.tls;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/** Strict JDBC hostname verification accepting DNS/IP subject-alternative names only. */
public final class EventTrailStrictSanHostnameVerifier implements HostnameVerifier {
    private static final int DNS_NAME = 2;
    private static final int IP_ADDRESS = 7;

    public EventTrailStrictSanHostnameVerifier() {}

    @Override
    public boolean verify(String hostname, SSLSession session) {
        if (session == null) return false;
        try {
            Certificate[] peerCertificates = session.getPeerCertificates();
            return peerCertificates != null
                    && peerCertificates.length > 0
                    && peerCertificates[0] instanceof X509Certificate certificate
                    && matches(hostname, certificate);
        } catch (SSLPeerUnverifiedException | RuntimeException failure) {
            return false;
        }
    }

    public static boolean matches(String hostname, X509Certificate certificate) {
        if (certificate == null) return false;
        try {
            return matchesSubjectAlternativeNames(
                    hostname, certificate.getSubjectAlternativeNames());
        } catch (CertificateParsingException | RuntimeException failure) {
            return false;
        }
    }

    public static boolean matchesSubjectAlternativeNames(
            String hostname, Collection<List<?>> subjectAlternativeNames) {
        HostIdentity host = HostIdentity.parse(hostname);
        if (host == null || subjectAlternativeNames == null) return false;

        for (List<?> entry : subjectAlternativeNames) {
            if (entry == null || entry.size() < 2 || !(entry.get(0) instanceof Integer type)) {
                continue;
            }
            Object value = entry.get(1);
            if (host.address() != null && type == IP_ADDRESS
                    && matchesIpAddress(host.address(), value)) {
                return true;
            }
            if (host.dnsName() != null && type == DNS_NAME && value instanceof String dnsSan
                    && matchesDnsName(host.dnsName(), dnsSan)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesIpAddress(byte[] hostAddress, Object sanValue) {
        if (sanValue instanceof byte[] bytes) {
            return Arrays.equals(normalizeMappedIpv6(hostAddress), normalizeMappedIpv6(bytes));
        }
        if (!(sanValue instanceof String text)) return false;
        byte[] sanAddress = parseIpAddress(text);
        return sanAddress != null && Arrays.equals(
                normalizeMappedIpv6(hostAddress), normalizeMappedIpv6(sanAddress));
    }

    private static boolean matchesDnsName(String host, String san) {
        if (san == null || san.isBlank() || !san.equals(san.strip())) return false;
        String pattern;
        try {
            pattern = canonicalDnsPattern(san);
        } catch (IllegalArgumentException invalid) {
            return false;
        }

        int wildcard = pattern.indexOf('*');
        if (wildcard < 0) return host.equals(pattern);
        if (wildcard != 0 || !pattern.startsWith("*.")
                || pattern.indexOf('*', 1) >= 0) {
            return false;
        }

        String suffix = pattern.substring(2);
        int firstDot = host.indexOf('.');
        return firstDot > 0
                && firstDot == host.length() - suffix.length() - 1
                && host.regionMatches(firstDot + 1, suffix, 0, suffix.length());
    }

    private static String canonicalDnsPattern(String value) {
        if (value.endsWith("..")) {
            throw new IllegalArgumentException("Multiple trailing DNS root dots are not supported");
        }
        String normalized = stripTrailingDot(value);
        if (normalized.startsWith("*.")) {
            return "*." + canonicalDnsName(normalized.substring(2));
        }
        if (normalized.indexOf('*') >= 0) {
            throw new IllegalArgumentException("Partial wildcards are not supported");
        }
        return canonicalDnsName(normalized);
    }

    private static String canonicalDnsName(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("DNS name is invalid");
        }
        if (value.endsWith("..")) {
            throw new IllegalArgumentException("Multiple trailing DNS root dots are not supported");
        }
        String normalized = stripTrailingDot(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException("DNS name is blank");
        return IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES)
                .toLowerCase(Locale.ROOT);
    }

    private static String stripTrailingDot(String value) {
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }

    private static byte[] parseIpAddress(String value) {
        byte[] ipv4 = parseIpv4Address(value);
        return ipv4 != null ? ipv4 : parseIpv6Address(value);
    }

    private static byte[] parseIpv4Address(String value) {
        if (value == null) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] address = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].isEmpty()) return null;
            for (int character = 0; character < parts[index].length(); character++) {
                if (!Character.isDigit(parts[index].charAt(character))) return null;
            }
            try {
                int octet = Integer.parseInt(parts[index]);
                if (octet < 0 || octet > 255) return null;
                address[index] = (byte) octet;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        return address;
    }

    private static byte[] parseIpv6Address(String value) {
        if (value == null) return null;
        String candidate = value;
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.indexOf(':') < 0 || candidate.indexOf('%') >= 0) return null;
        try {
            InetAddress address = InetAddress.getByName(candidate);
            if (address instanceof Inet6Address) return address.getAddress();
            // The JDK canonicalizes IPv4-mapped IPv6 literals to Inet4Address.
            // Retaining those four address bytes lets the same literal compare
            // correctly with either a textual or 16-byte type-7 SAN.
            return address.getAddress();
        } catch (UnknownHostException invalid) {
            return null;
        }
    }

    private static byte[] normalizeMappedIpv6(byte[] address) {
        if (address == null || address.length != 16) return address;
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) return address;
        }
        if ((address[10] & 0xff) != 0xff || (address[11] & 0xff) != 0xff) return address;
        return Arrays.copyOfRange(address, 12, 16);
    }

    private static boolean looksLikeIpLiteral(String value) {
        if (value == null) return false;
        if (value.indexOf(':') >= 0) return true;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty()) return true;
            for (int index = 0; index < part.length(); index++) {
                if (!Character.isDigit(part.charAt(index))) return false;
            }
        }
        return true;
    }

    private record HostIdentity(String dnsName, byte[] address) {
        static HostIdentity parse(String hostname) {
            if (hostname == null || hostname.isBlank() || !hostname.equals(hostname.strip())) {
                return null;
            }
            String normalized = stripTrailingDot(hostname);
            if (normalized.isEmpty() || normalized.endsWith(".")) return null;
            byte[] address = parseIpAddress(normalized);
            if (address != null) return new HostIdentity(null, address);
            if (looksLikeIpLiteral(normalized)) return null;
            try {
                return new HostIdentity(canonicalDnsName(normalized), null);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }
    }
}
