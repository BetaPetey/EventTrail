package io.github.betapetey.eventtrail.inventory.access;

import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Safe, immutable public target identity for one menu-access session.
 *
 * <p>The private {@link ContainerTarget#stableContextKey()} is never copied
 * verbatim. Access providers must supply an authoritative, non-secret backing
 * identity; only its fixed-size digest is retained publicly.</p>
 */
public record ContainerAccessTargetSnapshot(
        String dimensionId,
        int x,
        int y,
        int z,
        String displayLabel,
        String technicalTargetId,
        String providerId,
        AccessKind accessKind,
        BackingContextKind backingContextKind,
        String backingReference) {

    public static final int MAX_DIMENSION_CHARS = 255;
    public static final int MAX_TECHNICAL_ID_CHARS = 255;
    public static final int MAX_PROVIDER_ID_CHARS = 96;
    public static final int MAX_DISPLAY_UTF8_BYTES = 256;
    public static final int BACKING_REFERENCE_HEX_CHARS = 64;

    public ContainerAccessTargetSnapshot {
        dimensionId = boundedCanonicalResourceLocation(
                dimensionId, "dimensionId", MAX_DIMENSION_CHARS);
        displayLabel = EventTrailTextBounds.boundAuditDescription(
                boundedRequired(displayLabel, "displayLabel", 4_096));
        displayLabel = boundUtf8(displayLabel, MAX_DISPLAY_UTF8_BYTES);
        technicalTargetId = boundedCanonicalResourceLocation(
                technicalTargetId, "technicalTargetId", MAX_TECHNICAL_ID_CHARS);
        providerId = boundedRequired(providerId, "providerId", MAX_PROVIDER_ID_CHARS)
                .toLowerCase(Locale.ROOT);
        if (!providerId.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("providerId contains unsupported characters");
        }
        accessKind = Objects.requireNonNull(accessKind, "accessKind");
        backingContextKind = Objects.requireNonNull(backingContextKind, "backingContextKind");
        if (backingContextKind == BackingContextKind.UNKNOWN) {
            throw new IllegalArgumentException("Unknown backing identity cannot produce access evidence");
        }
        backingReference = boundedRequired(
                backingReference, "backingReference", BACKING_REFERENCE_HEX_CHARS)
                .toLowerCase(Locale.ROOT);
        if (!backingReference.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backingReference must be an opaque SHA-256 identity");
        }
    }

    public static ContainerAccessTargetSnapshot from(
            ContainerTarget target, String technicalTargetId, String providerId) {
        Objects.requireNonNull(target, "target");
        if (!target.hasAccessPosition()) {
            throw new IllegalArgumentException("Container access requires an exact access position");
        }
        BlockPos pos = target.accessPosition();
        return new ContainerAccessTargetSnapshot(
                target.dimensionId(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                target.label(),
                technicalTargetId,
                providerId,
                target.accessKind(),
                target.backingContextKind(),
                opaqueBackingReference(target.stableContextKey()));
    }

    public BlockPos position() {
        return new BlockPos(x, y, z);
    }

    private static String boundedRequired(String value, String field, int limit) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > limit) {
            throw new IllegalArgumentException(field + " exceeds its retained-size limit");
        }
        return trimmed;
    }

    static boolean isCanonicalResourceLocation(String value) {
        if (value == null) return false;
        ResourceLocation parsed = ResourceLocation.tryParse(value);
        return parsed != null && parsed.toString().equals(value);
    }

    private static String boundedCanonicalResourceLocation(
            String value, String field, int limit) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > limit
                || !isCanonicalResourceLocation(value)) {
            throw new IllegalArgumentException(field + " must be an exact resource location");
        }
        return value;
    }

    static String opaqueBackingReference(String stableContextKey) {
        String value = boundedRequired(stableContextKey, "stableContextKey", 4_096);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String boundUtf8(String value, int limit) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= limit) return value;
        StringBuilder bounded = new StringBuilder(value.length());
        int retainedBytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
            if (retainedBytes + unitBytes > limit - 3) break;
            bounded.append(unit);
            retainedBytes += unitBytes;
            offset += Character.charCount(codePoint);
        }
        return bounded.append("...").toString();
    }
}
