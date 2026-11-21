package io.github.betapetey.eventtrail.storage;

/** Safe, path-free transport-security metadata for administrative status output. */
public record StorageTransportSecurity(Mode mode, TrustSource trustSource) {
    public StorageTransportSecurity {
        if (mode == null || trustSource == null) {
            throw new IllegalArgumentException("Transport security fields are required");
        }
    }

    public static StorageTransportSecurity local() {
        return new StorageTransportSecurity(Mode.LOCAL, TrustSource.NOT_APPLICABLE);
    }

    public static StorageTransportSecurity disabled() {
        return new StorageTransportSecurity(Mode.TLS_DISABLED, TrustSource.NOT_APPLICABLE);
    }

    public static StorageTransportSecurity verifiedTls(boolean customCa) {
        return new StorageTransportSecurity(
                Mode.VERIFIED_TLS,
                customCa ? TrustSource.CONFIGURED_CA : TrustSource.DRIVER_DEFAULT);
    }

    public static StorageTransportSecurity unknown() {
        return new StorageTransportSecurity(Mode.UNKNOWN, TrustSource.UNKNOWN);
    }

    public enum Mode {
        LOCAL,
        TLS_DISABLED,
        VERIFIED_TLS,
        UNKNOWN
    }

    public enum TrustSource {
        NOT_APPLICABLE,
        DRIVER_DEFAULT,
        CONFIGURED_CA,
        UNKNOWN
    }
}
