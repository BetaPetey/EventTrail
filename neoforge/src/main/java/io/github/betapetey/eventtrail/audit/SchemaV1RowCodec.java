package io.github.betapetey.eventtrail.audit;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * The sole mapping between the grouped audit model and EventTrail's unchanged
 * schema-v1 columns.
 */
public final class SchemaV1RowCodec {
    private static final long ROW_FIXED_RETAINED_BYTES = 512L;
    private static final long STRING_FIXED_RETAINED_BYTES = 40L;
    private final AuditPayloadCodec payloadCodec;

    public SchemaV1RowCodec(AuditPayloadCodec payloadCodec) {
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
    }

    public static SchemaV1Row encode(AuditRecord record) {
        if (record == null) throw new IllegalArgumentException("record must not be null");
        record.validateForPersistence();

        AuditAttribution.IndexedParty party = record.attribution().indexedParty();
        return new SchemaV1Row(
                record.occurredAtEpochMillis(),
                record.action().storageCode(),
                party.storageId(),
                party.canonicalName(),
                record.location().dimensionId().toString(),
                record.location().position().getX(),
                record.location().position().getY(),
                record.location().position().getZ(),
                record.subject().technicalIdentity(),
                record.evidence().encodedPayload());
    }

    public AuditRecord decode(SchemaV1Row row) {
        if (row == null) throw new IllegalArgumentException("row must not be null");
        if (row.timestamp() < 0L) throw new IllegalArgumentException("Stored timestamp is negative");

        AuditAction action = AuditAction.fromStorageCode(row.actionCode())
                .orElseThrow(() -> new IllegalArgumentException("Unknown schema-v1 action code"));
        ResourceLocation dimension = ResourceLocation.tryParse(row.dimensionId());
        if (dimension == null || !dimension.toString().equals(row.dimensionId())) {
            throw new IllegalArgumentException("Stored dimension identity is invalid or noncanonical");
        }
        if (row.subjectIdentity() == null || row.subjectIdentity().isBlank()) {
            throw new IllegalArgumentException("Stored subject identity is missing");
        }

        AuditPayloadCodec.DecodedProjection projection = Objects.requireNonNull(
                payloadCodec.decode(action, row.subjectIdentity(), row.evidencePayload()),
                "payload codec returned no projection");
        AuditEvidence evidence = projection.evidence();
        AuditPayloadCodec.AttributionTraits traits = projection.attribution();
        if (evidence instanceof AuditEvidence.Unavailable
                && AuditAttribution.ENVIRONMENT_STORAGE_ID.equals(row.indexedUuid())) {
            traits = new AuditPayloadCodec.AttributionTraits(
                    AuditAttribution.IdentityKind.ENVIRONMENT,
                    AuditAttribution.CauseKind.ENVIRONMENT,
                    AuditAttribution.Causality.UNATTRIBUTED,
                    null);
        }
        AuditAttribution attribution = decodeAttribution(action, row, traits);
        validateIndexedPartyBinding(attribution, traits.indexedPartyBinding());
        AuditLocation location = new AuditLocation(
                dimension, new net.minecraft.core.BlockPos(row.x(), row.y(), row.z()));
        if (projection.exactLocation() != null
                && !projection.exactLocation().equals(location)) {
            throw new IllegalArgumentException(
                    "Stored location conflicts with its typed evidence payload");
        }
        if (projection.occurrenceConstraint() != null
                && !projection.occurrenceConstraint().permits(row.timestamp())) {
            throw new IllegalArgumentException(
                    "Stored timestamp conflicts with its typed evidence payload");
        }
        AuditSubject subject = new AuditSubject(
                projection.subjectKind(),
                row.subjectIdentity(),
                projection.subjectPresentationIdentity());
        return AuditRecord.restore(
                row.timestamp(),
                action,
                attribution,
                location,
                subject,
                evidence);
    }

    public static long retainedBytes(SchemaV1Row row) {
        if (row == null) return 0L;
        long retained = ROW_FIXED_RETAINED_BYTES;
        retained = add(retained, stringBytes(row.actionCode()));
        retained = add(retained, stringBytes(row.indexedUuid()));
        retained = add(retained, stringBytes(row.indexedName()));
        retained = add(retained, stringBytes(row.dimensionId()));
        retained = add(retained, stringBytes(row.subjectIdentity()));
        return add(retained, stringBytes(row.evidencePayload()));
    }

    private static AuditAttribution decodeAttribution(
            AuditAction action,
            SchemaV1Row row,
            AuditPayloadCodec.AttributionTraits traits) {
        AuditAttribution.IdentityKind identityKind = traits.indexedIdentityKind();
        UUID playerId = null;
        if (identityKind == AuditAttribution.IdentityKind.ENVIRONMENT) {
            if (!AuditAttribution.ENVIRONMENT_STORAGE_ID.equals(row.indexedUuid())) {
                throw new IllegalArgumentException(
                        "Stored indexed identity conflicts with payload actor kind");
            }
            if (action.indexedRole() != AuditAttribution.IndexedRole.ACTOR) {
                throw new IllegalArgumentException("An environment identity cannot be an indexed victim");
            }
        } else {
            if (AuditAttribution.ENVIRONMENT_STORAGE_ID.equals(row.indexedUuid())) {
                throw new IllegalArgumentException(
                        "Stored indexed identity conflicts with payload actor kind");
            }
            try {
                playerId = UUID.fromString(row.indexedUuid());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Stored indexed player UUID is invalid", invalid);
            }
        }

        return new AuditAttribution(
                new AuditAttribution.IndexedParty(
                        action.indexedRole(), identityKind, playerId, row.indexedName()),
                traits.causeKind(),
                traits.causality(),
                traits.causeDetail());
    }

    private static void validateIndexedPartyBinding(
            AuditAttribution attribution,
            AuditPayloadCodec.IndexedPartyBinding binding) {
        if (binding == null) return;
        AuditAttribution.IndexedParty party = attribution.indexedParty();
        if (!binding.playerId().equals(party.playerId())
                || !AuditAttribution.sameIndexedName(party.canonicalName(), binding.canonicalName())) {
            throw new IllegalArgumentException(
                    "Stored indexed player conflicts with its typed evidence payload");
        }
    }

    private static long stringBytes(String value) {
        return value == null
                ? 0L
                : add(STRING_FIXED_RETAINED_BYTES, (long) value.length() * Character.BYTES);
    }

    private static long add(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
