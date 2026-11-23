package io.github.betapetey.eventtrail.events.block;

import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed facts retained in a schema-v1 block-effect row's versioned payload.
 *
 * <p>{@code explosionTag} (explosion split, Owner decision B) is null on every row except the rows of an explosion
 * stored in parts: its block rows carry a block-form {@link ExplosionPartTag} and its part rows a part-form one. A
 * payload with a tag is encoded as payload version 2, one without as version 1 (BlockEffectPayloadCodec).</p>
 */
public record BlockEffectPayload(
        SubjectKind subjectKind,
        String technicalIdentity,
        String presentationIdentity,
        ActorKind actorKind,
        Causality causality,
        String causeDetail,
        BlockEntityEvidence blockEntityEvidence,
        BlockEffectTruncation truncation,
        String explosionTag) {

    /** A payload without an explosion tag (payload version 1): every row that is not part of a split explosion. */
    public BlockEffectPayload(
            SubjectKind subjectKind,
            String technicalIdentity,
            String presentationIdentity,
            ActorKind actorKind,
            Causality causality,
            String causeDetail,
            BlockEntityEvidence blockEntityEvidence,
            BlockEffectTruncation truncation) {
        this(subjectKind, technicalIdentity, presentationIdentity, actorKind, causality, causeDetail,
                blockEntityEvidence, truncation, null);
    }

    public BlockEffectPayload {
        subjectKind = Objects.requireNonNull(subjectKind, "subjectKind");
        if (subjectKind == SubjectKind.BLOCK) {
            if (!CanonicalBlockStateSnapshot.isCanonicalStorageText(technicalIdentity)) {
                throw new IllegalArgumentException(
                        "Block payloads require an exact canonical block-state identity");
            }
        } else {
            ResourceLocation operation = technicalIdentity == null
                    ? null
                    : ResourceLocation.tryParse(technicalIdentity);
            if (operation == null || !operation.toString().equals(technicalIdentity)) {
                throw new IllegalArgumentException(
                        "Operation payloads require an exact canonical operation identity");
            }
        }
        presentationIdentity = EventTrailTextBounds.literalDisplayText(presentationIdentity);
        if (presentationIdentity == null) {
            throw new IllegalArgumentException("presentationIdentity must not be blank");
        }
        actorKind = Objects.requireNonNull(actorKind, "actorKind");
        causality = Objects.requireNonNull(causality, "causality");
        causeDetail = causeDetail == null || causeDetail.isBlank()
                ? null
                : EventTrailTextBounds.boundAuditDescription(causeDetail);
        blockEntityEvidence = Objects.requireNonNull(blockEntityEvidence, "blockEntityEvidence");
        truncation = Objects.requireNonNull(truncation, "truncation");
        if ((actorKind == ActorKind.ENVIRONMENT) != (causality == Causality.UNATTRIBUTED)) {
            throw new IllegalArgumentException(
                    "Environment block effects must be unattributed; player effects must carry causality");
        }
        if (explosionTag != null) {
            ExplosionPartTag tag = ExplosionPartTag.parse(explosionTag).orElseThrow(
                    () -> new IllegalArgumentException("Malformed explosion part tag"));
            // A block row carries the block form; only the part row (an operation) carries the part form.
            if ((tag.form() == ExplosionPartTag.Form.BLOCK) != (subjectKind == SubjectKind.BLOCK)) {
                throw new IllegalArgumentException("Explosion part tag form does not match the subject kind");
            }
        }
    }

    /** The parsed explosion tag, when this row belongs to an explosion stored in parts. */
    public Optional<ExplosionPartTag> explosionPartTag() {
        return explosionTag == null ? Optional.empty() : ExplosionPartTag.parse(explosionTag);
    }

    /** This payload with {@code tag} as its explosion tag (every other field unchanged). */
    public BlockEffectPayload withExplosionTag(ExplosionPartTag tag) {
        Objects.requireNonNull(tag, "tag");
        return new BlockEffectPayload(subjectKind, technicalIdentity, presentationIdentity, actorKind, causality,
                causeDetail, blockEntityEvidence, truncation, tag.format());
    }

    public enum SubjectKind {
        BLOCK("block"),
        OPERATION("operation");

        private final String storageCode;

        SubjectKind(String storageCode) {
            this.storageCode = storageCode;
        }

        public String storageCode() {
            return storageCode;
        }

        public static Optional<SubjectKind> fromStorageCode(String value) {
            return Arrays.stream(values())
                    .filter(kind -> kind.storageCode.equals(value))
                    .findFirst();
        }
    }

    public enum ActorKind {
        PLAYER("player"),
        SYNTHETIC_PLAYER("synthetic_player"),
        ENVIRONMENT("environment");

        private final String storageCode;

        ActorKind(String storageCode) {
            this.storageCode = storageCode;
        }

        public String storageCode() {
            return storageCode;
        }

        public static Optional<ActorKind> fromStorageCode(String value) {
            return Arrays.stream(values())
                    .filter(kind -> kind.storageCode.equals(value))
                    .findFirst();
        }
    }

    public enum Causality {
        DIRECT("direct"),
        IMMEDIATE_SECONDARY("immediate_secondary"),
        UNATTRIBUTED("unattributed");

        private final String storageCode;

        Causality(String storageCode) {
            this.storageCode = storageCode;
        }

        public String storageCode() {
            return storageCode;
        }

        public static Optional<Causality> fromStorageCode(String value) {
            return Arrays.stream(values())
                    .filter(causality -> causality.storageCode.equals(value))
                    .findFirst();
        }
    }
}
