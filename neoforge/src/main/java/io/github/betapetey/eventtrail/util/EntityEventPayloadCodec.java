package io.github.betapetey.eventtrail.util;

import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.CommittedEntityDeathEvidence;
import io.github.betapetey.eventtrail.events.evidence.EntityDamageEvidence;
import io.github.betapetey.eventtrail.events.evidence.EntityIdentity;
import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Versioned, bounded codec for entity audit row metadata. */
public final class EntityEventPayloadCodec {
    public static final int CURRENT_VERSION = 1;
    public static final String PREFIX = "EVENTTRAIL_ENTITY_V1|";
    public static final String METADATA_UNAVAILABLE_KEY = "eventtrail_entity_metadata_unavailable";

    private static final String VERSION_KEY = "eventtrail_entity_event_version";
    private static final String OPERATION_ID_KEY = "operation_id";
    private static final String ROW_KIND_KEY = "row_kind";
    private static final String VICTIM_KEY = "victim";
    private static final String DAMAGE_KEY = "damage";
    private static final String ATTRIBUTION_KEY = "attribution";
    private static final String RELATED_KEY = "related";
    private static final String RELATED_ROLE_OWNER = "owner";
    private static final String RETALIATION_EFFECT_KEY = "retaliation_effect";
    private static final String RETALIATION_NAME_KEY = "retaliation_name";
    private static final int MAX_MINIMAL_TECHNICAL_ID_CHARS = 2_048;

    private EntityEventPayloadCodec() {
    }

    public static String encode(
            CommittedEntityDeathEvidence evidence,
            RowKind rowKind,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean rowActorSynthetic,
            boolean captureFullWeaponMetadata) {
        return encode(
                evidence,
                rowKind,
                damage,
                attribution,
                rowActorSynthetic,
                captureFullWeaponMetadata,
                EventTrailNbtUtil::stackToAuditString);
    }

    static String encode(
            CommittedEntityDeathEvidence evidence,
            RowKind rowKind,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean rowActorSynthetic,
            boolean captureFullWeaponMetadata,
            WeaponPayloadEncoder weaponEncoder) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(rowKind, "rowKind");
        Objects.requireNonNull(damage, "damage");
        Objects.requireNonNull(attribution, "attribution");
        Objects.requireNonNull(weaponEncoder, "weaponEncoder");

        try {
            CompoundTag root = basePayload(evidence, rowKind, damage, attribution, rowActorSynthetic);
            putWeapon(
                    root.getCompound(DAMAGE_KEY),
                    damage,
                    captureFullWeaponMetadata,
                    weaponEncoder,
                    evidence.victim().typeId().toString());
            String encoded = PREFIX + root;
            if (encoded.length() <= AuditSnbtParser.MAX_PAYLOAD_CHARS) {
                return encoded;
            }

            EventTrailNbtUtil.reportMetadataTooLarge(
                    "entity event write",
                    evidence.victim().typeId().toString(),
                    encoded.length());
            CompoundTag damageTag = root.getCompound(DAMAGE_KEY);
            damageTag.remove("weapon");
            damageTag.putBoolean("weapon_unavailable", true);
            damageTag.putBoolean("weapon_metadata_unavailable", true);
            damageTag.putString("weapon_metadata_reason", EventTrailTextBounds.METADATA_REASON_TOO_LARGE);
            encoded = PREFIX + root;
            if (encoded.length() <= AuditSnbtParser.MAX_PAYLOAD_CHARS) {
                return encoded;
            }
            return boundedFallbackPayload(
                    evidence,
                    rowKind,
                    damage,
                    attribution,
                    rowActorSynthetic,
                    EventTrailTextBounds.METADATA_REASON_TOO_LARGE);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            EventTrailNbtUtil.reportSerializationFailure(
                    "entity event write",
                    evidence.victim().typeId().toString(),
                    failure);
            return boundedFallbackPayload(
                    evidence,
                    rowKind,
                    damage,
                    attribution,
                    rowActorSynthetic,
                    EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE);
        }
    }

    public static DecodedPayload decode(String extraData) {
        if (extraData == null || !extraData.startsWith(PREFIX)) {
            return new DecodedPayload(DecodeStatus.UNSUPPORTED, null);
        }
        AuditSnbtParser.ParseResult parsed = AuditSnbtParser.parsePayload(extraData, PREFIX.length());
        if (!parsed.successful()) {
            return new DecodedPayload(
                    parsed.status() == AuditSnbtParser.Status.TOO_LARGE
                            || parsed.status() == AuditSnbtParser.Status.TOO_COMPLEX
                            ? DecodeStatus.TOO_LARGE
                            : DecodeStatus.MALFORMED,
                    null);
        }
        CompoundTag tag = parsed.tag();
        String rowKind = tag.getString(ROW_KIND_KEY);
        if (tag.getInt(VERSION_KEY) != CURRENT_VERSION
                || !tag.hasUUID(OPERATION_ID_KEY)
                || (!RowKind.DEATH.storedName.equals(rowKind)
                && !RowKind.KILL.storedName.equals(rowKind))
                || !tag.contains(VICTIM_KEY, Tag.TAG_COMPOUND)
                || !tag.contains(DAMAGE_KEY, Tag.TAG_COMPOUND)
                || !tag.contains(ATTRIBUTION_KEY, Tag.TAG_COMPOUND)) {
            return new DecodedPayload(DecodeStatus.UNSUPPORTED, null);
        }
        return new DecodedPayload(DecodeStatus.OK, tag);
    }

    /**
     * Extracts only the typed facts needed to restore EventTrail's grouped
     * audit model. Actor and cause traits come from the authenticated payload
     * structure; row identity columns are deliberately not inputs here.
     */
    public static AuditProjectionResult decodeAuditProjection(
            String canonicalVictimId,
            String extraData) {
        DecodedPayload decoded = decode(extraData);
        if (!decoded.successful()) {
            return AuditProjectionResult.failure(decoded.status());
        }

        try {
            CompoundTag root = decoded.tag();
            if (!root.contains("row_actor_synthetic", Tag.TAG_BYTE)
                    || (root.getByte("row_actor_synthetic") != 0
                    && root.getByte("row_actor_synthetic") != 1)) {
                return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
            }
            RowKind rowKind = parseRowKind(root.getString(ROW_KIND_KEY));
            CompoundTag victim = root.getCompound(VICTIM_KEY);
            VictimKind victimKind = parseVictimKind(victim.getString("kind"));
            if (rowKind == null || victimKind == null) {
                return AuditProjectionResult.failure(DecodeStatus.UNSUPPORTED);
            }

            String storedVictimId = safeText(victim.getString("id"), null);
            String technicalVictimId = storedVictimId == null
                    ? safeText(canonicalVictimId, null)
                    : storedVictimId;
            if (technicalVictimId == null
                    || (storedVictimId != null && !storedVictimId.equals(canonicalVictimId))) {
                return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
            }

            CompoundTag attribution = root.getCompound(ATTRIBUTION_KEY);
            ProvenanceKind provenance = parseProvenance(attribution.getString("kind"));
            if (provenance == null) {
                return AuditProjectionResult.failure(DecodeStatus.UNSUPPORTED);
            }
            boolean accountable = provenance.accountable;
            boolean hasPlayerUuid = attribution.hasUUID("player_uuid");
            boolean hasPlayerName = !attribution.getString("player_name").isBlank();
            boolean hasSynthetic = attribution.contains("synthetic", Tag.TAG_BYTE)
                    && (attribution.getByte("synthetic") == 0
                    || attribution.getByte("synthetic") == 1);
            if (accountable != (hasPlayerUuid && hasPlayerName && hasSynthetic)) {
                return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
            }
            if (!accountable && (hasPlayerUuid || hasPlayerName
                    || attribution.contains("synthetic"))) {
                return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
            }
            if (!relatedAndRetaliationWellFormed(attribution, provenance)) {
                return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
            }

            boolean accountableSynthetic = accountable && attribution.getBoolean("synthetic");
            boolean rowActorSynthetic = root.getBoolean("row_actor_synthetic");
            if (rowKind == RowKind.KILL) {
                if (!accountable || rowActorSynthetic != accountableSynthetic) {
                    return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
                }
            } else {
                boolean victimSynthetic = victimKind == VictimKind.SYNTHETIC_PLAYER;
                if ((victimKind != VictimKind.PLAYER && !victimSynthetic)
                        || rowActorSynthetic != victimSynthetic) {
                    return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
                }
            }

            ProjectionIdentity indexedIdentity = rowKind == RowKind.KILL
                    ? (accountableSynthetic
                        ? ProjectionIdentity.SYNTHETIC_PLAYER
                        : ProjectionIdentity.PLAYER)
                    : (victimKind == VictimKind.SYNTHETIC_PLAYER
                        ? ProjectionIdentity.SYNTHETIC_PLAYER
                        : ProjectionIdentity.PLAYER);
            UUID indexedPlayerId;
            String indexedCanonicalName;
            if (rowKind == RowKind.KILL) {
                indexedPlayerId = attribution.getUUID("player_uuid");
                indexedCanonicalName = attribution.getString("player_name");
            } else {
                if (!victim.hasUUID("uuid")) {
                    return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
                }
                indexedPlayerId = victim.getUUID("uuid");
                indexedCanonicalName = rowSubjectPresentation(victim, technicalVictimId);
            }
            CauseProjection cause = causeProjection(provenance, accountableSynthetic);
            return AuditProjectionResult.success(new AuditProjection(
                    rowKind,
                    indexedIdentity,
                    cause.kind,
                    cause.causality,
                    cause.detail,
                    indexedPlayerId,
                    indexedCanonicalName,
                    technicalVictimId,
                    rowSubjectPresentation(victim, technicalVictimId)));
        } catch (RuntimeException | LinkageError failure) {
            return AuditProjectionResult.failure(DecodeStatus.MALFORMED);
        }
    }

    /**
     * Decodes presentation facts without deciding sentence layout. The shared
     * audit presenter is the only authority that turns these facts into a row.
     */
    public static PresentationProjectionResult decodePresentationProjection(
            String canonicalVictimId,
            String extraData) {
        AuditProjectionResult audit = decodeAuditProjection(canonicalVictimId, extraData);
        if (!audit.successful()) {
            return PresentationProjectionResult.failure(audit.status());
        }
        DecodedPayload decoded = decode(extraData);
        try {
            CompoundTag root = decoded.tag();
            CompoundTag victim = root.getCompound(VICTIM_KEY);
            CompoundTag damage = root.getCompound(DAMAGE_KEY);
            CompoundTag attribution = root.getCompound(ATTRIBUTION_KEY);
            VictimKind victimKind = parseVictimKind(victim.getString("kind"));
            ProvenanceKind provenance = parseProvenance(attribution.getString("kind"));
            if (victimKind == null || provenance == null) {
                return PresentationProjectionResult.failure(DecodeStatus.UNSUPPORTED);
            }
            String accountableActor = provenance.accountable
                    ? safeText(attribution.getString("player_name"), null)
                    : null;
            ViaForm via = viaForm(damage);
            return PresentationProjectionResult.success(new PresentationProjection(
                    audit.projection().rowKind(),
                    PresentationSubjectKind.valueOf(victimKind.name()),
                    identityDisplay(victim, canonicalVictimId),
                    PresentationProvenance.valueOf(provenance.name()),
                    accountableActor,
                    provenance.accountable && attribution.getBoolean("synthetic"),
                    causeIdentityPresentation(damage),
                    safeText(damage.getString("type"), "unknown damage"),
                    damage.getBoolean("direct_entity_unavailable")
                            || damage.getBoolean("causing_entity_unavailable")
                            || damage.getBoolean(METADATA_UNAVAILABLE_KEY),
                    safeText(damage.getString("mechanism"), null),
                    weaponPresentation(damage),
                    root.getBoolean("row_actor_synthetic"),
                    ownerPresentation(attribution),
                    ownerSynthetic(attribution),
                    via == null ? null : via.killer(),
                    via == null ? null : via.via(),
                    provenance == ProvenanceKind.RETALIATION
                            ? safeText(attribution.getString(RETALIATION_NAME_KEY), null)
                            : null,
                    provenance == ProvenanceKind.RETALIATION
                            ? safeText(attribution.getString(RETALIATION_EFFECT_KEY), null)
                            : null));
        } catch (RuntimeException | LinkageError failure) {
            return PresentationProjectionResult.failure(DecodeStatus.MALFORMED);
        }
    }

    private static CompoundTag basePayload(
            CommittedEntityDeathEvidence evidence,
            RowKind rowKind,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean rowActorSynthetic) {
        CompoundTag root = new CompoundTag();
        root.putInt(VERSION_KEY, CURRENT_VERSION);
        root.putUUID(OPERATION_ID_KEY, evidence.operationId());
        root.putString(ROW_KIND_KEY, rowKind.storedName);
        root.put(VICTIM_KEY, identityTag(evidence.victim()));
        root.put(DAMAGE_KEY, damageTag(damage));
        root.put(ATTRIBUTION_KEY, attributionTag(attribution));
        root.putBoolean("row_actor_synthetic", rowActorSynthetic);
        return root;
    }

    private static CompoundTag minimalPayload(
            CommittedEntityDeathEvidence evidence,
            RowKind rowKind,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean rowActorSynthetic,
            String metadataReason) {
        CompoundTag root = new CompoundTag();
        root.putInt(VERSION_KEY, CURRENT_VERSION);
        root.putUUID(OPERATION_ID_KEY, evidence.operationId());
        root.putString(ROW_KIND_KEY, rowKind.storedName);

        CompoundTag victim = new CompoundTag();
        putMinimalTechnicalId(
                victim,
                "id",
                evidence.victim().typeId().toString(),
                "id_unavailable");
        if (evidence.victim().kind() != EntityIdentity.EntityKind.REDACTED_SYNTHETIC_PLAYER) {
            victim.putUUID("uuid", evidence.victim().entityId());
        }
        putOptional(victim, "canonical_name", evidence.victim().canonicalName());
        victim.putString("kind", evidence.victim().kind().name().toLowerCase(Locale.ROOT));
        root.put(VICTIM_KEY, victim);

        CompoundTag cause = new CompoundTag();
        putMinimalTechnicalId(
                cause,
                "type",
                damage.damageTypeId().toString(),
                "type_unavailable");
        cause.putBoolean(METADATA_UNAVAILABLE_KEY, true);
        cause.putString("metadata_reason", metadataReason);
        if (damage.directEntityUnavailable()) {
            cause.putBoolean("direct_entity_unavailable", true);
        }
        if (damage.causingEntityUnavailable()) {
            cause.putBoolean("causing_entity_unavailable", true);
        }
        if (damage.suppliedWeaponUnavailable() || damage.hasSuppliedWeapon()) {
            cause.putBoolean("weapon_unavailable", true);
            cause.putString("weapon_metadata_reason", metadataReason);
        }
        root.put(DAMAGE_KEY, cause);
        root.put(ATTRIBUTION_KEY, attributionTag(attribution));
        root.putBoolean("row_actor_synthetic", rowActorSynthetic);
        root.putBoolean(METADATA_UNAVAILABLE_KEY, true);
        return root;
    }

    private static String boundedFallbackPayload(
            CommittedEntityDeathEvidence evidence,
            RowKind rowKind,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean rowActorSynthetic,
            String metadataReason) {
        String encoded = PREFIX + minimalPayload(
                evidence,
                rowKind,
                damage,
                attribution,
                rowActorSynthetic,
                metadataReason);
        if (encoded.length() <= AuditSnbtParser.MAX_PAYLOAD_CHARS) {
            return encoded;
        }

        // This fixed-shape envelope is the final deterministic escape hatch.
        // Canonical victim identity remains in the schema-v1 subject column; the
        // payload states explicitly that oversized identity metadata was
        // omitted instead of fabricating a different registry ID.
        CompoundTag root = new CompoundTag();
        root.putInt(VERSION_KEY, CURRENT_VERSION);
        root.putUUID(OPERATION_ID_KEY, evidence.operationId());
        root.putString(ROW_KIND_KEY, rowKind.storedName);
        CompoundTag victim = new CompoundTag();
        victim.putString("kind", evidence.victim().kind().name().toLowerCase(Locale.ROOT));
        victim.putBoolean("id_unavailable", true);
        victim.putBoolean(METADATA_UNAVAILABLE_KEY, true);
        if (evidence.victim().kind() != EntityIdentity.EntityKind.REDACTED_SYNTHETIC_PLAYER) {
            victim.putUUID("uuid", evidence.victim().entityId());
        }
        root.put(VICTIM_KEY, victim);
        CompoundTag cause = new CompoundTag();
        cause.putBoolean("type_unavailable", true);
        cause.putBoolean(METADATA_UNAVAILABLE_KEY, true);
        cause.putString("metadata_reason", metadataReason);
        cause.putBoolean("direct_entity_unavailable", damage.directEntityUnavailable());
        cause.putBoolean("causing_entity_unavailable", damage.causingEntityUnavailable());
        cause.putBoolean("weapon_unavailable",
                damage.suppliedWeaponUnavailable() || damage.hasSuppliedWeapon());
        root.put(DAMAGE_KEY, cause);
        CompoundTag storedAttribution = new CompoundTag();
        storedAttribution.putString("kind", storedProvenance(attribution.provenance()));
        root.put(ATTRIBUTION_KEY, storedAttribution);
        root.putBoolean("row_actor_synthetic", rowActorSynthetic);
        root.putBoolean(METADATA_UNAVAILABLE_KEY, true);
        return PREFIX + root;
    }

    private static CompoundTag damageTag(EntityDamageEvidence damage) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", damage.damageTypeId().toString());
        putOptional(tag, "message_id", damage.damageMessageId());
        if (damage.directEntity() != null) tag.put("direct", identityTag(damage.directEntity()));
        if (damage.causingEntity() != null) tag.put("causing", identityTag(damage.causingEntity()));
        if (damage.directEntityUnavailable()) {
            tag.putBoolean("direct_entity_unavailable", true);
        }
        if (damage.causingEntityUnavailable()) {
            tag.putBoolean("causing_entity_unavailable", true);
        }
        if (damage.mechanismId() != null) tag.putString("mechanism", damage.mechanismId().toString());
        if (damage.suppliedWeaponUnavailable()) tag.putBoolean("weapon_unavailable", true);
        return tag;
    }

    private static void putWeapon(
            CompoundTag damageTag,
            EntityDamageEvidence damage,
            boolean captureFullWeaponMetadata,
            WeaponPayloadEncoder weaponEncoder,
            String victimIdentity) {
        if (!damage.hasSuppliedWeapon()) return;
        ItemStack weapon = damage.suppliedWeapon();
        try {
            String serialized = weaponEncoder.encode(weapon, captureFullWeaponMetadata);
            CompoundTag encodedWeapon = TagParser.parseTag(serialized);
            if (encodedWeapon.isEmpty()) {
                throw new IllegalStateException("ItemStack audit codec returned an empty payload");
            }
            damageTag.put("weapon", encodedWeapon);
        } catch (Exception | StackOverflowError failure) {
            damageTag.putBoolean("weapon_unavailable", true);
            damageTag.putString("weapon_metadata_reason", EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE);
            EventTrailNbtUtil.reportSerializationFailure(
                    "entity event weapon write",
                    weaponIdentity(weapon, victimIdentity),
                    failure);
        }
    }

    private static CompoundTag attributionTag(EntityKillAttribution attribution) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", storedProvenance(attribution.provenance()));
        ActorProvenance actor = attribution.accountablePlayer();
        if (actor != null) {
            tag.putUUID("player_uuid", actor.playerId());
            tag.putString("player_name", actor.rowActorName());
            tag.putBoolean("synthetic",
                    actor.actorKind() == ActorProvenance.ActorKind.SYNTHETIC_PLAYER);
        }
        // Kill credit A (design-05c 4.1): the killer's recorded owner, and a RETALIATION row's enchantment.
        EntityKillAttribution.RelatedParty related = attribution.related();
        if (related != null) {
            CompoundTag owner = new CompoundTag();
            owner.putString("role", RELATED_ROLE_OWNER);
            owner.putString("kind", related.kind().name().toLowerCase(Locale.ROOT));
            owner.putUUID("uuid", related.uuid());
            if (related.kind() == EntityKillAttribution.RelatedParty.Kind.ENTITY) {
                owner.putString("id", related.typeId());
            }
            if (related.kind() != EntityKillAttribution.RelatedParty.Kind.UNRESOLVED) {
                putOptional(owner, "name", related.name());
            }
            tag.put(RELATED_KEY, owner);
        }
        EntityKillAttribution.RetaliationDetail retaliation = attribution.retaliation();
        if (retaliation != null) {
            putOptional(tag, RETALIATION_EFFECT_KEY, retaliation.effectId());
            putOptional(tag, RETALIATION_NAME_KEY, retaliation.name());
        }
        return tag;
    }

    /**
     * Kill credit A (design-05c 4.1): MALFORMED when {@code related} is on an accountable kind or on RETALIATION, its
     * role is not "owner", its kind is unknown, its uuid is missing, {@code id} is missing on an entity or {@code name}
     * is present on an unresolved owner, or a {@code retaliation_*} key is on a kind other than RETALIATION.
     */
    private static boolean relatedAndRetaliationWellFormed(CompoundTag attribution, ProvenanceKind provenance) {
        boolean retaliationKeys = attribution.contains(RETALIATION_EFFECT_KEY)
                || attribution.contains(RETALIATION_NAME_KEY);
        if (retaliationKeys && provenance != ProvenanceKind.RETALIATION) return false;
        if (!attribution.contains(RELATED_KEY)) return true;
        if (provenance.accountable || provenance == ProvenanceKind.RETALIATION) return false;
        if (!attribution.contains(RELATED_KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag related = attribution.getCompound(RELATED_KEY);
        if (!RELATED_ROLE_OWNER.equals(related.getString("role"))) return false;
        RelatedKind kind = parseRelatedKind(related.getString("kind"));
        if (kind == null || !related.hasUUID("uuid")) return false;
        boolean hasId = !related.getString("id").isBlank();
        if (kind == RelatedKind.ENTITY && !hasId) return false;
        return kind != RelatedKind.UNRESOLVED || !related.contains("name");
    }

    /**
     * The owner as the killer's label shows it (4.2): a name, the one-name rule, or an unresolved owner's full UUID. A
     * synthetic player owner shows its own name, else its UUID, never "Automated player" (lead ruling 3 of fold 01).
     */
    private static String ownerPresentation(CompoundTag attribution) {
        if (!attribution.contains(RELATED_KEY, Tag.TAG_COMPOUND)) return null;
        CompoundTag related = attribution.getCompound(RELATED_KEY);
        RelatedKind kind = parseRelatedKind(related.getString("kind"));
        if (kind == null || !related.hasUUID("uuid")) return null;
        String uuid = related.getUUID("uuid").toString();
        return switch (kind) {
            case UNRESOLVED -> uuid;
            case ENTITY -> safeText(related.getString("name"), safeText(related.getString("id"), uuid));
            case PLAYER, SYNTHETIC_PLAYER -> safeText(related.getString("name"), uuid);
        };
    }

    /**
     * Whether the owner {@link #ownerPresentation} shows is a synthetic player, so the label marks it " (automated)" as
     * the actor labels mark a synthetic actor (lead ruling 3 of fold 02). The stored owner is unchanged.
     */
    private static boolean ownerSynthetic(CompoundTag attribution) {
        if (!attribution.contains(RELATED_KEY, Tag.TAG_COMPOUND)) return false;
        CompoundTag related = attribution.getCompound(RELATED_KEY);
        return parseRelatedKind(related.getString("kind")) == RelatedKind.SYNTHETIC_PLAYER
                && related.hasUUID("uuid");
    }

    /**
     * 4.2's via form, from the stored identities (lead ruling 2 of fold 01): the killing entity's label and the other
     * entity's label, when the payload names a direct and a different causing entity and neither is a player. The
     * killing entity is {@link EntityKillAttribution#killingEntity}'s: the causing entity when the stored direct entity
     * is a projectile, otherwise the direct entity, the same entity whose owner the bridge attached. Otherwise null.
     */
    private static ViaForm viaForm(CompoundTag damage) {
        CompoundTag direct = damage.getCompound("direct");
        CompoundTag causing = damage.getCompound("causing");
        if (direct.isEmpty() || causing.isEmpty() || isPlayerIdentity(direct) || isPlayerIdentity(causing)) {
            return null;
        }
        if (direct.hasUUID("uuid") && causing.hasUUID("uuid")
                && direct.getUUID("uuid").equals(causing.getUUID("uuid"))) {
            return null;
        }
        CompoundTag killer = EntityKillAttribution.killingEntity(direct, causing, isProjectileIdentity(direct));
        CompoundTag other = killer == direct ? causing : direct;
        return new ViaForm(identityDisplay(killer, null), identityDisplay(other, null));
    }

    /** The two labels of 4.2's via form: "Killed by {@code killer} ... • via {@code via}". */
    private record ViaForm(String killer, String via) {
    }

    private static boolean isProjectileIdentity(CompoundTag identity) {
        return "projectile".equals(identity.getString("kind"));
    }

    private static RelatedKind parseRelatedKind(String value) {
        if (value == null || value.isBlank()) return null;
        for (RelatedKind kind : RelatedKind.values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(value)) return kind;
        }
        return null;
    }

    private static CompoundTag identityTag(EntityIdentity identity) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", identity.typeId().toString());
        if (identity.kind() != EntityIdentity.EntityKind.REDACTED_SYNTHETIC_PLAYER) {
            tag.putUUID("uuid", identity.entityId());
        }
        putOptional(tag, "canonical_name", identity.canonicalName());
        putOptional(tag, "type_name", identity.translatedTypeName());
        putOptional(tag, "display_name", identity.displayName());
        putOptional(tag, "custom_name", identity.customName());
        tag.putString("kind", identity.kind().name().toLowerCase(Locale.ROOT));
        return tag;
    }

    private static String causeIdentityPresentation(CompoundTag damage) {
        CompoundTag direct = damage.getCompound("direct");
        CompoundTag causing = damage.getCompound("causing");
        if (!direct.isEmpty()) {
            return identityDisplay(direct, null);
        }
        if (!causing.isEmpty() && !isPlayerIdentity(causing)) {
            return identityDisplay(causing, null);
        }
        return null;
    }

    private static boolean isPlayerIdentity(CompoundTag identity) {
        String kind = identity.getString("kind");
        return "player".equals(kind)
                || "synthetic_player".equals(kind)
                || "redacted_synthetic_player".equals(kind);
    }

    private static String identityDisplay(CompoundTag identity, String fallback) {
        for (String key : new String[]{
                "custom_name", "display_name", "canonical_name", "type_name", "id"}) {
            String value = safeText(identity.getString(key), null);
            if (value != null) return value;
        }
        return safeText(fallback, "Unknown entity");
    }

    private static WeaponPresentation weaponPresentation(CompoundTag damage) {
        return weaponPresentation(damage, EventTrailNbtUtil::decodeStackFromNbt);
    }

    /** Test seam: {@link #weaponPresentation(CompoundTag)} with a supplied weapon stack decoder. */
    static WeaponPresentation weaponPresentation(
            CompoundTag damage,
            java.util.function.Function<CompoundTag, EventTrailNbtUtil.StackDecodeResult> decoder) {
        if (!damage.contains("weapon", Tag.TAG_COMPOUND)) {
            if (!damage.getBoolean("weapon_unavailable")) return null;
            return new WeaponPresentation(
                    1, null, "Unknown weapon", WeaponMetadataStatus.UNAVAILABLE);
        }

        CompoundTag weapon = damage.getCompound("weapon");
        EventTrailNbtUtil.StackDecodeResult decoded;
        try {
            decoded = decoder.apply(weapon);
        } catch (RuntimeException | LinkageError failure) {
            decoded = new EventTrailNbtUtil.StackDecodeResult(
                    ItemStack.EMPTY, true, EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE);
        }
        if (decoded == null) {
            decoded = new EventTrailNbtUtil.StackDecodeResult(
                    ItemStack.EMPTY, true, EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE);
        }

        ItemStack stack = decoded.stack();
        int count = weapon.contains("count") ? Math.max(0, weapon.getInt("count")) : 1;
        String technicalIdentity = safeText(weapon.getString("id").replace("\"", ""), null);
        String presentationIdentity = null;
        if (stack != null && !stack.isEmpty()) {
            count = Math.max(0, stack.getCount());
            // A degraded weapon decodes to a plain stack without its components; it is named by its
            // item type below (never e.g. "Uncraftable Potion"), and the presenter adds the note.
            if (!decoded.metadataUnavailable()) presentationIdentity = DisplayNameResolver.stackDisplayName(stack);
            try {
                var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) technicalIdentity = id.toString();
            } catch (RuntimeException | LinkageError ignored) {
                // Retain the bounded technical identity from the encoded payload.
            }
        }
        if (presentationIdentity == null || presentationIdentity.isBlank()) {
            presentationIdentity = DisplayNameResolver.itemDisplayName(technicalIdentity);
        }
        if (presentationIdentity == null || presentationIdentity.isBlank()) {
            presentationIdentity = technicalIdentity == null
                    ? "Unknown weapon"
                    : "Unresolved item (" + technicalIdentity + ')';
        }
        WeaponMetadataStatus status = decoded.metadataUnavailable()
                ? (EventTrailTextBounds.METADATA_REASON_TOO_LARGE.equals(decoded.metadataReason())
                    ? WeaponMetadataStatus.TOO_LARGE
                    : WeaponMetadataStatus.UNAVAILABLE)
                : WeaponMetadataStatus.CAPTURED;
        return new WeaponPresentation(
                Math.max(0, count), technicalIdentity, presentationIdentity, status);
    }

    private static String weaponIdentity(ItemStack weapon, String fallback) {
        try {
            return BuiltInRegistries.ITEM.getResourceKey(weapon.getItem())
                    .map(key -> key.location().toString())
                    .orElse(fallback);
        } catch (RuntimeException | LinkageError ignored) {
            return fallback;
        }
    }

    private static String storedProvenance(EntityKillAttribution.ProvenanceKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static RowKind parseRowKind(String value) {
        for (RowKind kind : RowKind.values()) {
            if (kind.storedName.equals(value)) return kind;
        }
        return null;
    }

    private static VictimKind parseVictimKind(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return VictimKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static ProvenanceKind parseProvenance(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ProvenanceKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static CauseProjection causeProjection(
            ProvenanceKind provenance,
            boolean accountableSynthetic) {
        if (provenance.accountable) {
            return new CauseProjection(
                    accountableSynthetic
                            ? ProjectionCause.SYNTHETIC_PLAYER
                            : ProjectionCause.PLAYER,
                    provenance == ProvenanceKind.DIRECT_PLAYER
                            ? ProjectionCausality.DIRECT
                            : ProjectionCausality.IMMEDIATE_SECONDARY,
                    null);
        }
        return switch (provenance) {
            case EXCLUDED_SYNTHETIC_PLAYER -> new CauseProjection(
                    ProjectionCause.SYNTHETIC_PLAYER,
                    ProjectionCausality.IMMEDIATE_SECONDARY,
                    "Automated source");
            case ENVIRONMENT -> new CauseProjection(
                    ProjectionCause.ENVIRONMENT,
                    ProjectionCausality.UNATTRIBUTED,
                    "Environment");
            default -> new CauseProjection(
                    ProjectionCause.UNATTRIBUTED,
                    ProjectionCausality.UNATTRIBUTED,
                    provenance.name().toLowerCase(Locale.ROOT));
        };
    }

    private static String rowSubjectPresentation(
            CompoundTag victim,
            String technicalVictimId) {
        for (String key : new String[]{"canonical_name", "display_name", "type_name"}) {
            String value = safeText(victim.getString(key), null);
            if (value != null) return value;
        }
        return safeText(technicalVictimId, "Unknown entity");
    }

    private static void putOptional(CompoundTag tag, String key, String value) {
        if (value != null && !value.isBlank()) tag.putString(key, value);
    }

    private static void putMinimalTechnicalId(
            CompoundTag tag,
            String key,
            String value,
            String unavailableKey) {
        if (value != null && value.length() <= MAX_MINIMAL_TECHNICAL_ID_CHARS) {
            tag.putString(key, value);
        } else {
            tag.putBoolean(unavailableKey, true);
            tag.putBoolean(METADATA_UNAVAILABLE_KEY, true);
        }
    }

    private static String safeText(String value, String fallback) {
        String safe = EventTrailTextBounds.literalDisplayText(value);
        return safe == null ? fallback : safe;
    }

    public enum RowKind {
        DEATH("death"),
        KILL("kill");

        private final String storedName;

        RowKind(String storedName) {
            this.storedName = storedName;
        }
    }

    public enum DecodeStatus { OK, MALFORMED, TOO_LARGE, UNSUPPORTED }

    public record DecodedPayload(DecodeStatus status, CompoundTag tag) {
        public boolean successful() {
            return status == DecodeStatus.OK && tag != null;
        }
    }

    /** Structured projection used by schema-v1 restoration. */
    public record AuditProjection(
            RowKind rowKind,
            ProjectionIdentity indexedIdentity,
            ProjectionCause cause,
            ProjectionCausality causality,
            String causeDetail,
            UUID indexedPlayerId,
            String indexedCanonicalName,
            String technicalVictimIdentity,
            String subjectPresentationIdentity) {
        public AuditProjection {
            Objects.requireNonNull(rowKind, "rowKind");
            Objects.requireNonNull(indexedIdentity, "indexedIdentity");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(causality, "causality");
            Objects.requireNonNull(indexedPlayerId, "indexedPlayerId");
            if (indexedCanonicalName == null || indexedCanonicalName.isBlank()
                    || technicalVictimIdentity == null || technicalVictimIdentity.isBlank()
                    || subjectPresentationIdentity == null
                    || subjectPresentationIdentity.isBlank()) {
                throw new IllegalArgumentException("Entity projection identities must not be blank");
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

    /**
     * Immutable entity facts used by the one shared audit-row presenter. Kill credit A (design-05c 4.1, 4.2):
     * {@code ownerPresentation} is the killer's recorded owner as shown, {@code ownerSynthetic} whether that owner is a
     * synthetic player (lead ruling 3 of fold 02), {@code killingPresentation} and
     * {@code viaPresentation} the via form's two labels (the killing entity, then the other entity) when the payload
     * names two different non-player entities, and {@code retaliationName} and {@code retaliationEffect} a RETALIATION
     * row's enchantment text and id.
     */
    public record PresentationProjection(
            RowKind rowKind,
            PresentationSubjectKind subjectKind,
            String subjectPresentationIdentity,
            PresentationProvenance provenance,
            String accountableActorPresentation,
            boolean accountableActorSynthetic,
            String immediateCausePresentation,
            String damageType,
            boolean sourceIdentityUnavailable,
            String mechanism,
            WeaponPresentation weapon,
            boolean rowActorSynthetic,
            String ownerPresentation,
            boolean ownerSynthetic,
            String killingPresentation,
            String viaPresentation,
            String retaliationName,
            String retaliationEffect) {
        public PresentationProjection {
            Objects.requireNonNull(rowKind, "rowKind");
            Objects.requireNonNull(subjectKind, "subjectKind");
            Objects.requireNonNull(provenance, "provenance");
            if (subjectPresentationIdentity == null || subjectPresentationIdentity.isBlank()
                    || damageType == null || damageType.isBlank()) {
                throw new IllegalArgumentException("Entity presentation identities must not be blank");
            }
            if (provenance.accountable && (accountableActorPresentation == null
                    || accountableActorPresentation.isBlank())) {
                throw new IllegalArgumentException(
                        "Accountable entity presentation requires its actor identity");
            }
        }
    }

    public record PresentationProjectionResult(
            DecodeStatus status,
            PresentationProjection projection) {
        private static PresentationProjectionResult success(PresentationProjection projection) {
            return new PresentationProjectionResult(
                    DecodeStatus.OK, Objects.requireNonNull(projection));
        }

        private static PresentationProjectionResult failure(DecodeStatus status) {
            return new PresentationProjectionResult(Objects.requireNonNull(status), null);
        }

        public boolean successful() {
            return status == DecodeStatus.OK && projection != null;
        }
    }

    public record WeaponPresentation(
            int count,
            String technicalIdentity,
            String presentationIdentity,
            WeaponMetadataStatus metadataStatus) {
        public WeaponPresentation {
            if (count < 0) throw new IllegalArgumentException("Weapon count cannot be negative");
            if (presentationIdentity == null || presentationIdentity.isBlank()) {
                throw new IllegalArgumentException("Weapon presentation must not be blank");
            }
            Objects.requireNonNull(metadataStatus, "metadataStatus");
        }
    }

    public enum PresentationSubjectKind {
        PLAYER,
        SYNTHETIC_PLAYER,
        REDACTED_SYNTHETIC_PLAYER,
        PROJECTILE,
        TAMED_ANIMAL,
        LIVING_ENTITY,
        DEVICE_OR_AUTOMATION,
        OTHER
    }

    public enum PresentationProvenance {
        DIRECT_PLAYER(true),
        PLAYER_PROJECTILE(true),
        TNT_IGNITER(true),
        ENGINE_KILL_CREDIT(true),
        EXCLUDED_SYNTHETIC_PLAYER(false),
        PET_OR_MINION(false),
        DEVICE_OR_AUTOMATION(false),
        NON_PLAYER_ENTITY(false),
        ENVIRONMENT(false),
        UNSUPPORTED_SECONDARY(false),
        RETALIATION(false);

        private final boolean accountable;

        PresentationProvenance(boolean accountable) {
            this.accountable = accountable;
        }
    }

    public enum WeaponMetadataStatus { CAPTURED, UNAVAILABLE, TOO_LARGE }

    public enum ProjectionIdentity { PLAYER, SYNTHETIC_PLAYER }

    public enum ProjectionCause { PLAYER, SYNTHETIC_PLAYER, ENVIRONMENT, UNATTRIBUTED }

    public enum ProjectionCausality { DIRECT, IMMEDIATE_SECONDARY, UNATTRIBUTED }

    private enum VictimKind {
        PLAYER,
        SYNTHETIC_PLAYER,
        REDACTED_SYNTHETIC_PLAYER,
        PROJECTILE,
        TAMED_ANIMAL,
        LIVING_ENTITY,
        DEVICE_OR_AUTOMATION,
        OTHER
    }

    private enum ProvenanceKind {
        DIRECT_PLAYER(true),
        PLAYER_PROJECTILE(true),
        TNT_IGNITER(true),
        ENGINE_KILL_CREDIT(true),
        EXCLUDED_SYNTHETIC_PLAYER(false),
        PET_OR_MINION(false),
        DEVICE_OR_AUTOMATION(false),
        NON_PLAYER_ENTITY(false),
        ENVIRONMENT(false),
        UNSUPPORTED_SECONDARY(false),
        RETALIATION(false);

        private final boolean accountable;

        ProvenanceKind(boolean accountable) {
            this.accountable = accountable;
        }
    }

    /** The stored kinds of a {@code related} owner (4.1). */
    private enum RelatedKind { PLAYER, SYNTHETIC_PLAYER, ENTITY, UNRESOLVED }

    private record CauseProjection(
            ProjectionCause kind,
            ProjectionCausality causality,
            String detail) {
    }

    @FunctionalInterface
    interface WeaponPayloadEncoder {
        String encode(ItemStack stack, boolean captureFullMetadata);
    }
}
