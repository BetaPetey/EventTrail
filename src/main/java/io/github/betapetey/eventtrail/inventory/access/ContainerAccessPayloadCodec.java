package io.github.betapetey.eventtrail.inventory.access;

import io.github.betapetey.eventtrail.inventory.core.AccessKind;
import io.github.betapetey.eventtrail.inventory.core.BackingContextKind;
import io.github.betapetey.eventtrail.util.AuditSnbtParser;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** Strict, versioned, bounded storage and display codec for container access rows. */
public final class ContainerAccessPayloadCodec {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_ENCODED_BYTES = 2_048;
    public static final String PREFIX = "EVENTTRAIL_CONTAINER_ACCESS_V1|";
    /**
     * Own-slot screens "opened" line (Owner decision A, 11-14): the provider id of an access row for a block screen no
     * adapter records. It marks the row "items not tracked": no item row is ever written for that screen. It uses the
     * existing provider field, so neither the payload keys nor the schema change.
     */
    public static final String ITEMS_NOT_TRACKED_PROVIDER = "eventtrail.items-not-tracked";

    private static final String VERSION_KEY = "eventtrail_container_access_version";
    private static final String SESSION_ID_KEY = "session_id";
    private static final String PHASE_KEY = "phase";
    private static final String OPENED_AT_KEY = "opened_at";
    private static final String TARGET_KEY = "target";
    private static final Set<String> ROOT_KEYS = Set.of(
            VERSION_KEY, SESSION_ID_KEY, PHASE_KEY, OPENED_AT_KEY,
            "synthetic_actor", TARGET_KEY);
    private static final Set<String> TARGET_KEYS = Set.of(
            "dimension", "x", "y", "z", "display", "technical_id",
            "provider", "access_kind", "backing_kind", "backing_ref");

    private ContainerAccessPayloadCodec() {}

    public static String phasePrefix(ContainerAccessPhase phase) {
        return PREFIX + Objects.requireNonNull(phase, "phase").name() + '|';
    }

    public static String encode(ContainerAccessEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        ContainerAccessTargetSnapshot target = evidence.target();

        CompoundTag root = new CompoundTag();
        root.putInt(VERSION_KEY, CURRENT_VERSION);
        root.putUUID(SESSION_ID_KEY, evidence.sessionId());
        root.putString(PHASE_KEY, evidence.phase().name());
        root.putLong(OPENED_AT_KEY, evidence.openedAt());
        root.putBoolean("synthetic_actor", evidence.actor().synthetic());

        CompoundTag targetTag = new CompoundTag();
        targetTag.putString("dimension", target.dimensionId());
        targetTag.putInt("x", target.x());
        targetTag.putInt("y", target.y());
        targetTag.putInt("z", target.z());
        targetTag.putString("display", target.displayLabel());
        targetTag.putString("technical_id", target.technicalTargetId());
        targetTag.putString("provider", target.providerId());
        targetTag.putString("access_kind", target.accessKind().name());
        targetTag.putString("backing_kind", target.backingContextKind().name());
        targetTag.putString("backing_ref", target.backingReference());
        root.put(TARGET_KEY, targetTag);

        String encoded = phasePrefix(evidence.phase()) + root;
        if (utf8Length(encoded) > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Container access payload exceeds its retained-size limit");
        }
        return encoded;
    }

    public static DecodedPayload decode(String extraData) {
        if (extraData == null || !extraData.startsWith(PREFIX)) {
            return new DecodedPayload(DecodeStatus.UNSUPPORTED, null, null);
        }
        if (extraData.length() > MAX_ENCODED_BYTES
                || utf8Length(extraData) > MAX_ENCODED_BYTES) {
            return new DecodedPayload(DecodeStatus.TOO_LARGE, null, null);
        }

        int phaseEnd = extraData.indexOf('|', PREFIX.length());
        if (phaseEnd < 0) {
            return new DecodedPayload(DecodeStatus.MALFORMED, null, null);
        }
        String encodedPhase = extraData.substring(PREFIX.length(), phaseEnd);
        ContainerAccessPhase prefixPhase = parseCanonicalPhase(encodedPhase);
        if (prefixPhase == null) {
            return new DecodedPayload(DecodeStatus.UNSUPPORTED, null, null);
        }

        AuditSnbtParser.ParseResult parsed = AuditSnbtParser.parsePayload(extraData, phaseEnd + 1);
        if (!parsed.successful()) {
            return new DecodedPayload(DecodeStatus.MALFORMED, prefixPhase, null);
        }
        CompoundTag root = parsed.tag();
        if (!root.contains(VERSION_KEY, Tag.TAG_INT)) {
            return new DecodedPayload(DecodeStatus.MALFORMED, prefixPhase, null);
        }
        if (root.getInt(VERSION_KEY) != CURRENT_VERSION) {
            return new DecodedPayload(DecodeStatus.UNSUPPORTED, prefixPhase, null);
        }
        if (!root.hasUUID(SESSION_ID_KEY)
                || !root.contains(PHASE_KEY, Tag.TAG_STRING)
                || !root.contains(OPENED_AT_KEY, Tag.TAG_LONG)
                || root.getLong(OPENED_AT_KEY) < 0L
                || !root.contains("synthetic_actor", Tag.TAG_BYTE)
                || (root.getByte("synthetic_actor") != 0
                    && root.getByte("synthetic_actor") != 1)
                || !root.contains(TARGET_KEY, Tag.TAG_COMPOUND)) {
            return new DecodedPayload(DecodeStatus.MALFORMED, prefixPhase, null);
        }
        if (!root.getAllKeys().equals(ROOT_KEYS)) {
            return new DecodedPayload(DecodeStatus.UNSUPPORTED, prefixPhase, null);
        }
        if (!prefixPhase.name().equals(root.getString(PHASE_KEY))) {
            return new DecodedPayload(DecodeStatus.MALFORMED, prefixPhase, null);
        }

        CompoundTag target = root.getCompound(TARGET_KEY);
        if (!target.contains("dimension", Tag.TAG_STRING)
                || !target.contains("x", Tag.TAG_INT)
                || !target.contains("y", Tag.TAG_INT)
                || !target.contains("z", Tag.TAG_INT)
                || !target.contains("display", Tag.TAG_STRING)
                || !target.contains("technical_id", Tag.TAG_STRING)
                || !target.contains("provider", Tag.TAG_STRING)
                || !target.contains("access_kind", Tag.TAG_STRING)
                || !target.contains("backing_kind", Tag.TAG_STRING)
                || !target.contains("backing_ref", Tag.TAG_STRING)) {
            return new DecodedPayload(DecodeStatus.MALFORMED, prefixPhase, null);
        }
        if (!target.getAllKeys().equals(TARGET_KEYS)) {
            return new DecodedPayload(DecodeStatus.UNSUPPORTED, prefixPhase, null);
        }
        String dimension = target.getString("dimension");
        String technicalId = target.getString("technical_id");
        String provider = target.getString("provider");
        String display = target.getString("display");
        String backingReference = target.getString("backing_ref");
        if (!ContainerAccessTargetSnapshot.isCanonicalResourceLocation(dimension)
                || dimension.length() > ContainerAccessTargetSnapshot.MAX_DIMENSION_CHARS
                || !ContainerAccessTargetSnapshot.isCanonicalResourceLocation(technicalId)
                || technicalId.length() > ContainerAccessTargetSnapshot.MAX_TECHNICAL_ID_CHARS
                || display.isBlank()
                || display.length() > EventTrailTextBounds.MAX_AUDIT_DESCRIPTION_CHARS
                || utf8Length(display) > ContainerAccessTargetSnapshot.MAX_DISPLAY_UTF8_BYTES
                || provider.isBlank()
                || provider.length() > ContainerAccessTargetSnapshot.MAX_PROVIDER_ID_CHARS
                || !provider.matches("[a-z0-9_.-]+")
                || !backingReference.matches("[0-9a-f]{64}")
                || parseEnum(AccessKind.class, target.getString("access_kind")) == null
                || parseEnum(BackingContextKind.class, target.getString("backing_kind")) == null
                || parseEnum(BackingContextKind.class, target.getString("backing_kind"))
                    == BackingContextKind.UNKNOWN) {
            return new DecodedPayload(DecodeStatus.UNSUPPORTED, prefixPhase, null);
        }
        return new DecodedPayload(DecodeStatus.OK, prefixPhase, root);
    }

    /**
     * Own-slot screens "opened" line: whether this stored access payload is a valid row marked "items not tracked"
     * (its provider is {@link #ITEMS_NOT_TRACKED_PROVIDER}). False for anything else, a payload that does not decode
     * included; never throws.
     */
    public static boolean itemsNotTracked(String extraData) {
        try {
            DecodedPayload decoded = decode(extraData);
            return decoded.successful() && ITEMS_NOT_TRACKED_PROVIDER.equals(
                    decoded.tag().getCompound(TARGET_KEY).getString("provider"));
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /**
     * GF-TRASHTARGET: whether this stored access payload is a valid row of a trash target (its backing kind, stored as
     * the enum name, is {@link BackingContextKind#TRASH}). False for anything else, a payload that does not decode
     * included; never throws.
     */
    public static boolean trashTarget(String extraData) {
        try {
            DecodedPayload decoded = decode(extraData);
            return decoded.successful() && BackingContextKind.TRASH.name().equals(
                    decoded.tag().getCompound(TARGET_KEY).getString("backing_kind"));
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** Returns only immutable domain facts required by schema-v1 restoration. */
    public static AuditProjectionResult decodeAuditProjection(String extraData) {
        DecodedPayload decoded = decode(extraData);
        if (!decoded.successful()) {
            return AuditProjectionResult.failure(decoded.status());
        }
        try {
            CompoundTag root = decoded.tag();
            CompoundTag target = root.getCompound(TARGET_KEY);
            String technicalIdentity = target.getString("technical_id");
            String presentationIdentity = EventTrailTextBounds.containerTargetDisplayName(
                    target.getString("display"));
            if (technicalIdentity.isBlank()
                    || presentationIdentity == null
                    || presentationIdentity.isBlank()) {
                return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
            }
            return AuditProjectionResult.success(new AuditProjection(
                    decoded.phase(),
                    root.getBoolean("synthetic_actor"),
                    technicalIdentity,
                    presentationIdentity,
                    target.getString("dimension"),
                    target.getInt("x"),
                    target.getInt("y"),
                    target.getInt("z"),
                    root.getLong(OPENED_AT_KEY)));
        } catch (RuntimeException | LinkageError failure) {
            return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static ContainerAccessPhase parseCanonicalPhase(String value) {
        if (value == null) return null;
        for (ContainerAccessPhase phase : ContainerAccessPhase.values()) {
            if (phase.name().equals(value)) return phase;
        }
        return null;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    public enum DecodeStatus { OK, MALFORMED, TOO_LARGE, UNSUPPORTED }

    public record DecodedPayload(
            DecodeStatus status, ContainerAccessPhase phase, CompoundTag tag) {
        public boolean successful() {
            return status == DecodeStatus.OK && phase != null && tag != null;
        }
    }

    public record AuditProjection(
            ContainerAccessPhase phase,
            boolean syntheticActor,
            String technicalTargetIdentity,
            String subjectPresentationIdentity,
            String dimensionId,
            int x,
            int y,
            int z,
            long openedAt) {
        public AuditProjection {
            Objects.requireNonNull(phase, "phase");
            if (technicalTargetIdentity == null || technicalTargetIdentity.isBlank()
                    || subjectPresentationIdentity == null
                    || subjectPresentationIdentity.isBlank()
                    || !ContainerAccessTargetSnapshot.isCanonicalResourceLocation(dimensionId)
                    || !ContainerAccessTargetSnapshot.isCanonicalResourceLocation(
                            technicalTargetIdentity)) {
                throw new IllegalArgumentException(
                        "Container access identities must be exact canonical resource locations");
            }
            if (openedAt < 0L) {
                throw new IllegalArgumentException("Container access time must not be negative");
            }
        }
    }

    public record AuditProjectionResult(DecodeStatus status, AuditProjection projection) {
        private static AuditProjectionResult success(AuditProjection projection) {
            return new AuditProjectionResult(DecodeStatus.OK, Objects.requireNonNull(projection));
        }

        private static AuditProjectionResult failure(DecodeStatus status) {
            return new AuditProjectionResult(Objects.requireNonNull(status), null);
        }

        public boolean successful() {
            return status == DecodeStatus.OK && projection != null;
        }
    }
}
