package io.github.betapetey.eventtrail.storage;

/** Builds the minimal host portion of a JDBC URL without accepting URL syntax from config. */
final class JdbcUrlSupport {
    private JdbcUrlSupport() {}

    static String hostForUrl(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Database host is blank");
        }
        if (!host.equals(host.strip())) {
            throw new IllegalArgumentException("Database host must not contain surrounding whitespace");
        }

        if (host.startsWith("[") || host.endsWith("]")) {
            if (!(host.startsWith("[") && host.endsWith("]"))) {
                throw new IllegalArgumentException("Database IPv6 host brackets are malformed");
            }
            String literal = host.substring(1, host.length() - 1);
            requireIpv6Literal(literal);
            return host;
        }
        if (host.indexOf(':') >= 0) {
            requireIpv6Literal(host);
            return "[" + host + "]";
        }

        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            boolean safe = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '.' || character == '-' || character == '_';
            if (!safe) {
                throw new IllegalArgumentException(
                        "Database host must be a plain DNS name, IPv4 address, or IPv6 literal");
            }
        }
        return host;
    }

    private static void requireIpv6Literal(String literal) {
        if (literal.isEmpty() || literal.indexOf(':') < 0) {
            throw new IllegalArgumentException("Database IPv6 host is malformed");
        }
        for (int index = 0; index < literal.length(); index++) {
            char character = literal.charAt(index);
            boolean safe = character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F'
                    || character >= '0' && character <= '9'
                    || character == ':' || character == '.';
            if (!safe) {
                throw new IllegalArgumentException("Database IPv6 host is malformed");
            }
        }
    }
}
