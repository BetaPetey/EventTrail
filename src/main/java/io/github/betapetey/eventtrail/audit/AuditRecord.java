package io.github.betapetey.eventtrail.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * One immutable committed EventTrail audit fact.
 *
 * <p>All creation paths share the same compatibility validation. Capture code
 * must supply either an explicit timestamp or a {@link Clock}; database reads
 * restore the timestamp that was actually stored.</p>
 */
public final class AuditRecord {
    private final Instant occurredAt;
    private final AuditAction action;
    private final AuditAttribution attribution;
    private final AuditLocation location;
    private final AuditSubject subject;
    private final AuditEvidence evidence;

    private AuditRecord(
            Instant occurredAt,
            AuditAction action,
            AuditAttribution attribution,
            AuditLocation location,
            AuditSubject subject,
            AuditEvidence evidence,
            boolean validateStoredProjection) {
        this.occurredAt = normalizeTime(occurredAt);
        this.action = Objects.requireNonNull(action, "action");
        this.attribution = Objects.requireNonNull(attribution, "attribution");
        this.location = Objects.requireNonNull(location, "location");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        validateCompatibility(action, attribution, subject, evidence);
        if (validateStoredProjection) validatePersistableProjection();
    }

    public static AuditRecord capture(
            Clock clock,
            AuditAction action,
            AuditAttribution attribution,
            AuditLocation location,
            AuditSubject subject,
            AuditEvidence evidence) {
        return create(Objects.requireNonNull(clock, "clock").instant(),
                action, attribution, location, subject, evidence, true);
    }

    public static AuditRecord captureAt(
            long epochMillis,
            AuditAction action,
            AuditAttribution attribution,
            AuditLocation location,
            AuditSubject subject,
            AuditEvidence evidence) {
        if (epochMillis < 0L) throw new IllegalArgumentException("epochMillis must not be negative");
        return create(Instant.ofEpochMilli(epochMillis),
                action, attribution, location, subject, evidence, true);
    }

    static AuditRecord restore(
            long storedEpochMillis,
            AuditAction action,
            AuditAttribution attribution,
            AuditLocation location,
            AuditSubject subject,
            AuditEvidence evidence) {
        if (storedEpochMillis < 0L) {
            throw new IllegalArgumentException("storedEpochMillis must not be negative");
        }
        return create(Instant.ofEpochMilli(storedEpochMillis),
                action, attribution, location, subject, evidence, false);
    }

    private static AuditRecord create(
            Instant occurredAt,
            AuditAction action,
            AuditAttribution attribution,
            AuditLocation location,
            AuditSubject subject,
            AuditEvidence evidence,
            boolean validateStoredProjection) {
        return new AuditRecord(
                occurredAt, action, attribution, location, subject, evidence,
                validateStoredProjection);
    }

    public Instant occurredAt() { return occurredAt; }

    public long occurredAtEpochMillis() { return occurredAt.toEpochMilli(); }

    public AuditAction action() { return action; }

    public AuditAttribution attribution() { return attribution; }

    public AuditLocation location() { return location; }

    public AuditSubject subject() { return subject; }

    public AuditEvidence evidence() { return evidence; }

    /** Revalidates the exact typed payload projection before schema-v1 binding. */
    void validateForPersistence() {
        validatePersistableProjection();
    }

    private void validatePersistableProjection() {
        if (evidence instanceof AuditEvidence.Unavailable) {
            throw new IllegalArgumentException(
                    "Unavailable stored evidence cannot be captured as a new audit fact");
        }
        if (attribution.indexedParty().kind()
                == AuditAttribution.IdentityKind.UNCLASSIFIED_PLAYER) {
            throw new IllegalArgumentException(
                    "An unclassified restored actor cannot be captured as a new audit fact");
        }

        AuditPayloadCodec.DecodedProjection projection =
                ProductionAuditPayloadCodec.instance().decode(
                        action, subject.technicalIdentity(), evidence.encodedPayload());
        if (projection.evidence() instanceof AuditEvidence.Unavailable
                || !evidence.equals(projection.evidence())
                || subject.kind() != projection.subjectKind()
                || !subject.presentationIdentity().equals(
                        projection.subjectPresentationIdentity())) {
            throw new IllegalArgumentException(
                    "Audit evidence does not match its typed action and subject projection");
        }

        AuditPayloadCodec.AttributionTraits traits = projection.attribution();
        if (attribution.indexedParty().kind() != traits.indexedIdentityKind()
                || attribution.causeKind() != traits.causeKind()
                || attribution.causality() != traits.causality()
                || !Objects.equals(attribution.causeDetail(), traits.causeDetail())) {
            throw new IllegalArgumentException(
                    "Audit evidence does not match its typed attribution projection");
        }
        AuditPayloadCodec.IndexedPartyBinding binding = traits.indexedPartyBinding();
        if (binding != null
                && (!binding.playerId().equals(attribution.indexedParty().playerId())
                || !AuditAttribution.sameIndexedName(
                        attribution.indexedParty().canonicalName(), binding.canonicalName()))) {
            throw new IllegalArgumentException(
                    "Audit evidence does not match its indexed player identity");
        }
        if (projection.exactLocation() != null
                && !projection.exactLocation().equals(location)) {
            throw new IllegalArgumentException(
                    "Audit evidence does not match its exact event location");
        }
        if (projection.occurrenceConstraint() != null
                && !projection.occurrenceConstraint().permits(occurredAtEpochMillis())) {
            throw new IllegalArgumentException(
                    "Audit evidence does not match its occurrence time");
        }
    }

    private static Instant normalizeTime(Instant value) {
        Instant instant = Objects.requireNonNull(value, "occurredAt");
        if (instant.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("occurredAt must not precede the Unix epoch");
        }
        return instant.truncatedTo(ChronoUnit.MILLIS);
    }

    private static void validateCompatibility(
            AuditAction action,
            AuditAttribution attribution,
            AuditSubject subject,
            AuditEvidence evidence) {
        if (action.evidenceKind() != evidence.kind()) {
            throw new IllegalArgumentException("Action and evidence kind do not match");
        }
        if (action.indexedRole() != attribution.indexedParty().role()) {
            throw new IllegalArgumentException("Action and indexed-party role do not match");
        }

        boolean subjectMatches = switch (evidence.kind()) {
            case BLOCK -> subject.kind() == AuditSubject.Kind.BLOCK
                    || action == AuditAction.EXPLOSION && subject.kind() == AuditSubject.Kind.OPERATION;
            case ITEM -> subject.kind() == AuditSubject.Kind.ITEM;
            case ENTITY -> subject.kind() == AuditSubject.Kind.ENTITY;
            case CONTAINER_ACCESS, CONTAINER_TRANSFER -> subject.kind() == AuditSubject.Kind.CONTAINER;
        };
        if (!subjectMatches) throw new IllegalArgumentException("Subject and evidence kind do not match");

        if (evidence instanceof AuditEvidence.ContainerTransfer transfer
                && transfer.direction() != action.transferDirection()) {
            throw new IllegalArgumentException("Container transfer direction does not match its action");
        }

        if (evidence instanceof AuditEvidence.Item item) {
            AuditEvidence.ItemOutcome required = action == AuditAction.ITEM_DROP
                    ? AuditEvidence.ItemOutcome.DROP
                    : AuditEvidence.ItemOutcome.PICKUP;
            if (item.outcome() != required) {
                throw new IllegalArgumentException("Item outcome does not match its action");
            }
        }
        if (evidence instanceof AuditEvidence.Entity entity) {
            AuditEvidence.EntityOutcome required = action == AuditAction.ENTITY_KILL
                    ? AuditEvidence.EntityOutcome.KILL
                    : AuditEvidence.EntityOutcome.DEATH;
            if (entity.outcome() != required) {
                throw new IllegalArgumentException("Entity outcome does not match its action");
            }
        }
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof AuditRecord other)) return false;
        return occurredAt.equals(other.occurredAt)
                && action == other.action
                && attribution.equals(other.attribution)
                && location.equals(other.location)
                && subject.equals(other.subject)
                && evidence.equals(other.evidence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(occurredAt, action, attribution, location, subject, evidence);
    }

    @Override
    public String toString() {
        return "AuditRecord[occurredAt=" + occurredAt + ", action=" + action
                + ", attribution=" + attribution + ", location=" + location
                + ", subject=" + subject + ", evidence=" + evidence + ']';
    }
}
