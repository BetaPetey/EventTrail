package io.github.betapetey.eventtrail.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Action-family payload boundary used when restoring a schema-v1 row.
 *
 * <p>The row codec deliberately does not interpret event or inventory payload
 * bytes. A production composite delegates to the authoritative family codec
 * and returns only the domain traits needed to rebuild an audit fact. The
 * stored technical subject is supplied separately so a family codec can derive
 * truthful presentation without guessing it from payload prose.</p>
 */
@FunctionalInterface
public interface AuditPayloadCodec {
    DecodedProjection decode(
            AuditAction action,
            String technicalSubjectIdentity,
            String encodedPayload);

    record DecodedProjection(
            AuditEvidence evidence,
            AuditSubject.Kind subjectKind,
            AttributionTraits attribution,
            String subjectPresentationIdentity,
            AuditLocation exactLocation,
            OccurrenceConstraint occurrenceConstraint) {
        public DecodedProjection(
                AuditEvidence evidence,
                AuditSubject.Kind subjectKind,
                AttributionTraits attribution,
                String subjectPresentationIdentity) {
            this(evidence, subjectKind, attribution, subjectPresentationIdentity, null, null);
        }

        public DecodedProjection(
                AuditEvidence evidence,
                AuditSubject.Kind subjectKind,
                AttributionTraits attribution,
                String subjectPresentationIdentity,
                AuditLocation exactLocation) {
            this(evidence, subjectKind, attribution, subjectPresentationIdentity,
                    exactLocation, null);
        }

        public DecodedProjection {
            evidence = Objects.requireNonNull(evidence, "evidence");
            subjectKind = Objects.requireNonNull(subjectKind, "subjectKind");
            attribution = Objects.requireNonNull(attribution, "attribution");
            if (subjectPresentationIdentity == null || subjectPresentationIdentity.isBlank()) {
                throw new IllegalArgumentException("subjectPresentationIdentity must not be blank");
            }
        }
    }

    record AttributionTraits(
            AuditAttribution.IdentityKind indexedIdentityKind,
            AuditAttribution.CauseKind causeKind,
            AuditAttribution.Causality causality,
            String causeDetail,
            IndexedPartyBinding indexedPartyBinding) {
        public AttributionTraits(
                AuditAttribution.IdentityKind indexedIdentityKind,
                AuditAttribution.CauseKind causeKind,
                AuditAttribution.Causality causality,
                String causeDetail) {
            this(indexedIdentityKind, causeKind, causality, causeDetail, null);
        }

        public AttributionTraits {
            indexedIdentityKind = Objects.requireNonNull(
                    indexedIdentityKind, "indexedIdentityKind");
            causeKind = Objects.requireNonNull(causeKind, "causeKind");
            causality = Objects.requireNonNull(causality, "causality");
        }
    }

    /** Optional exact identity duplicated inside a family payload. */
    record IndexedPartyBinding(UUID playerId, String canonicalName) {
        public IndexedPartyBinding {
            playerId = Objects.requireNonNull(playerId, "playerId");
            String bounded = AuditText.boundedRequired(canonicalName, "canonicalName");
            if (!bounded.equals(canonicalName)) {
                throw new IllegalArgumentException("canonicalName exceeds its retained-size limit");
            }
        }
    }

    /** Optional bounds duplicated inside a family payload for the row timestamp. */
    record OccurrenceConstraint(long earliestEpochMillis, Long exactEpochMillis) {
        public OccurrenceConstraint {
            if (earliestEpochMillis < 0L
                    || exactEpochMillis != null && exactEpochMillis < earliestEpochMillis) {
                throw new IllegalArgumentException("Occurrence bounds are invalid");
            }
        }

        public static OccurrenceConstraint exact(long epochMillis) {
            return new OccurrenceConstraint(epochMillis, epochMillis);
        }

        public static OccurrenceConstraint notBefore(long epochMillis) {
            return new OccurrenceConstraint(epochMillis, null);
        }

        public boolean permits(long epochMillis) {
            return epochMillis >= earliestEpochMillis
                    && (exactEpochMillis == null || epochMillis == exactEpochMillis);
        }
    }
}
