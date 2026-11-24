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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
    // Kill-weapon fix: a weapon's bounded identity, stored beside the weapon compound in the damage compound.
    private static final String WEAPON_ID_KEY = "weapon_id";
    private static final String WEAPON_ID_UNAVAILABLE_KEY = "weapon_id_unavailable";
    private static final String WEAPON_NAME_KEY = "weapon_name";
    private static final String WEAPON_CUSTOM_NAME_KEY = "weapon_custom_name";
    private static final String WEAPON_RENAME_STATE_KEY = "weapon_rename_state";
    /** Written only for a held-item weapon (Owner K2: it reads "holding X"); absent means the damage's own weapon. */
    private static final String WEAPON_SOURCE_KEY = "weapon_source";
    private static final String WEAPON_SOURCE_HELD = "held";
    private static final String RENAME_STATE_UNREADABLE = "unreadable";
    private static final String RENAME_STATE_CHECK_FAILED = "check_failed";
    // Owner Q265 (a): a player death row's "last hit by <player>" note, a compound of its own at the payload root.
    private static final String LAST_HIT_KEY = "last_hit_by";
    private static final String LAST_HIT_PLAYER_UUID_KEY = "player_uuid";
    // The name is stored as its UTF-8 bytes, never as text: the free-text entity: and item: lookups match the stored
    // payload text, and the note must never make a row match its hitter's name.
    private static final String LAST_HIT_PLAYER_NAME_KEY = "player_name_utf8";
    /** Owner K1 A: a weapon existed but nothing that names it was stored. Never "Unknown". */
    static final String WEAPON_NOT_RECORDED = "Weapon not recorded";

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
        return encode(
                evidence,
                rowKind,
                damage,
                attribution,
                rowActorSynthetic,
                captureFullWeaponMetadata,
                weaponEncoder,
                GAME_WEAPON_IDENTITY);
    }

    /** Test seam: {@link #encode} with a supplied weapon identity reader (tests fake modded ids and names). */
    static String encode(
            CommittedEntityDeathEvidence evidence,
            RowKind rowKind,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean rowActorSynthetic,
            boolean captureFullWeaponMetadata,
            WeaponPayloadEncoder weaponEncoder,
            WeaponIdentityReader identityReader) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(rowKind, "rowKind");
        Objects.requireNonNull(damage, "damage");
        Objects.requireNonNull(attribution, "attribution");
        Objects.requireNonNull(weaponEncoder, "weaponEncoder");
        Objects.requireNonNull(identityReader, "identityReader");

        try {
            CompoundTag root = basePayload(evidence, rowKind, damage, attribution, rowActorSynthetic);
            CompoundTag damageTag = root.getCompound(DAMAGE_KEY);
            putWeapon(
                    damageTag,
                    damage,
                    captureFullWeaponMetadata,
                    weaponEncoder,
                    identityReader,
                    evidence.victim().typeId().toString());
            String encoded = PREFIX + root;
            if (storable(encoded)) {
                return encoded;
            }

            EventTrailNbtUtil.reportMetadataTooLarge(
                    "entity event write",
                    evidence.victim().typeId().toString(),
                    encoded.length());
            // Kill-weapon fix: the weapon text gives way to its bounded stub; its identity keys stay. A row with no
            // weapon claims none. A row the reader would refuse as too deep (K5) is handled the same way.
            degradeOversizedWeapon(damageTag, damage);
            encoded = PREFIX + root;
            if (storable(encoded)) {
                return encoded;
            }
            return boundedFallbackPayload(
                    evidence,
                    rowKind,
                    damage,
                    attribution,
                    rowActorSynthetic,
                    EventTrailTextBounds.METADATA_REASON_TOO_LARGE,
                    identityReader);
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
                    EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE,
                    identityReader);
        }
    }

    /**
     * A full row the reader accepts: within the row length limit and, K5 of the kill-weapon fix, within the reader's
     * nesting limit, so a row is never stored that the reader would refuse as too complex.
     */
    private static boolean storable(String encoded) {
        return encoded.length() <= AuditSnbtParser.MAX_PAYLOAD_CHARS
                && AuditSnbtParser.validateStructure(encoded, PREFIX.length()) == AuditSnbtParser.Status.OK;
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
                            : null,
                    lastHitByPresentation(root, audit.projection().rowKind(), provenance, victim)));
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
        putLastHit(root, evidence, rowKind);
        return root;
    }

    /**
     * The Owner's Q265 (a) note: a death row's "last hit by" player, as its own compound beside the attribution, so it
     * never reads as the row's killer or as an accountable player. Written only on a DEATH row. The UUID is an int
     * array and the name its UTF-8 bytes, so no text of the note is in the stored row for a text lookup to find.
     */
    private static void putLastHit(CompoundTag root, CommittedEntityDeathEvidence evidence, RowKind rowKind) {
        CommittedEntityDeathEvidence.LastHit note = evidence.lastHitBy();
        if (note == null || rowKind != RowKind.DEATH) return;
        CompoundTag tag = new CompoundTag();
        tag.putUUID(LAST_HIT_PLAYER_UUID_KEY, note.playerId());
        tag.putByteArray(LAST_HIT_PLAYER_NAME_KEY, note.playerName().getBytes(StandardCharsets.UTF_8));
        root.put(LAST_HIT_KEY, tag);
    }

    /** The stored name bytes as text, or null when they are not well-formed UTF-8. */
    private static String lastHitName(byte[] stored) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(stored))
                    .toString();
        } catch (CharacterCodingException malformed) {
            return null;
        }
    }

    /**
     * The note's name for the presenter, or null: a row stored before the note, or any row that is not a DEATH row
     * with no player credit (ENVIRONMENT), reads with no note, as does a note that is not well formed (no UUID, no name
     * bytes, name bytes that are not UTF-8, a blank name, or the victim's own UUID). It is read only here, never by the
     * audit projection, so it is never indexed or counted.
     */
    private static String lastHitByPresentation(
            CompoundTag root, RowKind rowKind, ProvenanceKind provenance, CompoundTag victim) {
        if (rowKind != RowKind.DEATH || provenance != ProvenanceKind.ENVIRONMENT) return null;
        if (!root.contains(LAST_HIT_KEY, Tag.TAG_COMPOUND)) return null;
        CompoundTag note = root.getCompound(LAST_HIT_KEY);
        if (!note.hasUUID(LAST_HIT_PLAYER_UUID_KEY)) return null;
        if (victim.hasUUID("uuid") && victim.getUUID("uuid").equals(note.getUUID(LAST_HIT_PLAYER_UUID_KEY))) {
            return null;
        }
        if (!note.contains(LAST_HIT_PLAYER_NAME_KEY, Tag.TAG_BYTE_ARRAY)) return null;
        String name = lastHitName(note.getByteArray(LAST_HIT_PLAYER_NAME_KEY));
        return name == null ? null : safeText(name, null);
    }

    /** Test seam (kill-weapon fix): the bounded minimal payload; it never throws for a bounded identity reader. */
    static CompoundTag minimalPayload(
            CommittedEntityDeathEvidence evidence,
            RowKind rowKind,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean rowActorSynthetic,
            String metadataReason,
            WeaponIdentityReader identityReader) {
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
        if (damage.hasSuppliedWeapon()) {
            // Kill-weapon fix: the weapon keeps its bounded identity and its stub here too.
            ItemStack weapon = damage.suppliedWeapon();
            putWeaponIdentity(cause, weapon, identityReader);
            putWeaponSource(cause, damage);
            putWeaponStub(cause, weapon, metadataReason);
            cause.putBoolean("weapon_metadata_unavailable", true);
            cause.putString("weapon_metadata_reason", metadataReason);
            markWeaponUnavailableIfNoIdentity(cause);
        } else if (damage.suppliedWeaponUnavailable()) {
            cause.putBoolean("weapon_unavailable", true);
            cause.putString("weapon_metadata_reason", metadataReason);
        }
        root.put(DAMAGE_KEY, cause);
        root.put(ATTRIBUTION_KEY, attributionTag(attribution));
        root.putBoolean("row_actor_synthetic", rowActorSynthetic);
        putLastHit(root, evidence, rowKind);
        root.putBoolean(METADATA_UNAVAILABLE_KEY, true);
        return root;
    }

    private static String boundedFallbackPayload(
            CommittedEntityDeathEvidence evidence,
            RowKind rowKind,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean rowActorSynthetic,
            String metadataReason,
            WeaponIdentityReader identityReader) {
        String encoded = PREFIX + minimalPayload(
                evidence,
                rowKind,
                damage,
                attribution,
                rowActorSynthetic,
                metadataReason,
                identityReader);
        if (encoded.length() <= AuditSnbtParser.MAX_PAYLOAD_CHARS) {
            return encoded;
        }
        return fixedEnvelope(evidence, rowKind, damage, attribution, rowActorSynthetic, metadataReason, identityReader);
    }

    /** Test seam (kill-weapon fix): the fixed-shape envelope; bounded identities cannot reach it through encode. */
    static String fixedEnvelope(
            CommittedEntityDeathEvidence evidence,
            RowKind rowKind,
            EntityDamageEvidence damage,
            EntityKillAttribution attribution,
            boolean rowActorSynthetic,
            String metadataReason,
            WeaponIdentityReader identityReader) {
        // This fixed-shape envelope is the final deterministic escape hatch.
        // Canonical victim identity remains in the schema-v1 subject column; the
        // payload states explicitly that oversized identity metadata was
        // omitted instead of fabricating a different registry ID. A weapon keeps
        // only its bounded identity keys here (kill-weapon fix), so the envelope
        // keeps a small fixed upper size. The "last hit by" note is left out on
        // purpose: it carries a name of variable length, and the note is only an
        // extra, so a row this far over the limit is stored with no note.
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
        if (damage.hasSuppliedWeapon()) {
            putWeaponIdentity(cause, damage.suppliedWeapon(), identityReader);
            putWeaponSource(cause, damage);
            cause.putString("weapon_metadata_reason", metadataReason);
        }
        cause.putBoolean("weapon_unavailable", damage.suppliedWeaponUnavailable()
                || (damage.hasSuppliedWeapon() && !hasWeaponIdentity(cause)));
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
            WeaponIdentityReader identityReader,
            String victimIdentity) {
        if (!damage.hasSuppliedWeapon()) return;
        ItemStack weapon = damage.suppliedWeapon();
        // Kill-weapon fix: the bounded identity is written first and never depends on the weapon text parsing.
        putWeaponIdentity(damageTag, weapon, identityReader);
        putWeaponSource(damageTag, damage);
        try {
            String serialized = weaponEncoder.encode(weapon, captureFullWeaponMetadata);
            // The re-parse stays (design 1.4): text the reader cannot parse is never stored.
            CompoundTag encodedWeapon = TagParser.parseTag(serialized);
            if (encodedWeapon.isEmpty()) {
                throw new IllegalStateException("ItemStack audit codec returned an empty payload");
            }
            damageTag.put("weapon", encodedWeapon);
        } catch (Exception | LinkageError | StackOverflowError failure) {
            putWeaponStub(damageTag, weapon, EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE);
            damageTag.putBoolean("weapon_metadata_unavailable", true);
            damageTag.putString("weapon_metadata_reason", EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE);
            EventTrailNbtUtil.reportSerializationFailure(
                    "entity event weapon write",
                    weaponIdentity(weapon, victimIdentity),
                    failure);
        }
        markWeaponUnavailableIfNoIdentity(damageTag);
    }

    /**
     * Kill-weapon fix, the too-large branch: a row with no supplied weapon is left as it is (it never claims one);
     * otherwise the weapon text gives way to its bounded stub and the identity keys written before stay.
     */
    static void degradeOversizedWeapon(CompoundTag damageTag, EntityDamageEvidence damage) {
        if (!damage.hasSuppliedWeapon()) return;
        damageTag.remove("weapon");
        putWeaponStub(damageTag, damage.suppliedWeapon(), EventTrailTextBounds.METADATA_REASON_TOO_LARGE);
        damageTag.putBoolean("weapon_metadata_unavailable", true);
        damageTag.putString("weapon_metadata_reason", EventTrailTextBounds.METADATA_REASON_TOO_LARGE);
        markWeaponUnavailableIfNoIdentity(damageTag);
    }

    /** Kill-weapon fix seam: how a weapon's id, plain real name and rename are read (tests fake modded ids and names). */
    interface WeaponIdentityReader {
        /** The registry id, or null. */
        String id(ItemStack stack);

        /** The written-book label, else the game's name with any rename removed. */
        String realName(ItemStack stack);

        DisplayNameResolver.RenameState rename(ItemStack stack);
    }

    static final WeaponIdentityReader GAME_WEAPON_IDENTITY = new WeaponIdentityReader() {
        @Override
        public String id(ItemStack stack) {
            return BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                    .map(key -> key.location().toString())
                    .orElse(null);
        }

        @Override
        public String realName(ItemStack stack) {
            String book = DisplayNameResolver.writtenBookLabel(stack);
            return book != null ? book : DisplayNameResolver.gameName(stack);
        }

        @Override
        public DisplayNameResolver.RenameState rename(ItemStack stack) {
            return DisplayNameResolver.RenameState.ofStack(stack);
        }
    };

    /**
     * Kill-weapon fix: writes {@code weapon_id} (or {@code weapon_id_unavailable}), {@code weapon_name} (the plain real
     * name, never the custom name) and the rename ({@code weapon_custom_name} or {@code weapon_rename_state}). Every
     * value is bounded and a failing read is recorded, never thrown. {@code weapon_id_unavailable} is not
     * {@link #METADATA_UNAVAILABLE_KEY}, which would mark the cause itself unavailable.
     */
    static void putWeaponIdentity(CompoundTag tag, ItemStack weapon, WeaponIdentityReader reader) {
        String id = null;
        try {
            id = reader.id(weapon);
        } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
            // Recorded as weapon_id_unavailable below.
        }
        if (id != null && !id.isBlank() && id.length() <= MAX_MINIMAL_TECHNICAL_ID_CHARS) {
            tag.putString(WEAPON_ID_KEY, id);
        } else {
            tag.putBoolean(WEAPON_ID_UNAVAILABLE_KEY, true);
        }
        String name = null;
        try {
            name = reader.realName(weapon);
        } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
            // No weapon_name; the stub's id still names the weapon on read.
        }
        name = safeText(name, null);
        if (name != null) tag.putString(WEAPON_NAME_KEY, name);
        DisplayNameResolver.RenameState rename;
        try {
            rename = reader.rename(weapon);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            rename = DisplayNameResolver.RenameState.CHECK_FAILED;
        }
        if (rename == null) rename = DisplayNameResolver.RenameState.NONE;
        switch (rename.kind()) {
            case TEXT -> tag.putString(WEAPON_CUSTOM_NAME_KEY, rename.text());   // RenameState bounds it to 100
            case UNREADABLE -> tag.putString(WEAPON_RENAME_STATE_KEY, RENAME_STATE_UNREADABLE);
            case CHECK_FAILED -> tag.putString(WEAPON_RENAME_STATE_KEY, RENAME_STATE_CHECK_FAILED);
            case NONE -> { }
        }
    }

    /** {@code weapon_source:"held"} for the killer's held item; nothing for the damage's own weapon (old rows match). */
    private static void putWeaponSource(CompoundTag tag, EntityDamageEvidence damage) {
        if (damage.suppliedWeaponHeld()) tag.putString(WEAPON_SOURCE_KEY, WEAPON_SOURCE_HELD);
    }

    /** The bounded stub under {@code weapon}; on any failure no compound is left and the identity keys still name it. */
    private static void putWeaponStub(CompoundTag tag, ItemStack weapon, String reason) {
        try {
            tag.put("weapon", EventTrailNbtUtil.unavailableStackTag(weapon, reason));
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            tag.remove("weapon");
        }
    }

    private static boolean hasWeaponIdentity(CompoundTag tag) {
        return tag.contains(WEAPON_ID_KEY, Tag.TAG_STRING) || tag.contains(WEAPON_NAME_KEY, Tag.TAG_STRING);
    }

    /** {@code weapon_unavailable} now means: a weapon existed and nothing that names it was stored. */
    private static void markWeaponUnavailableIfNoIdentity(CompoundTag tag) {
        if (!tag.contains("weapon", Tag.TAG_COMPOUND) && !hasWeaponIdentity(tag)) {
            tag.putBoolean("weapon_unavailable", true);
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
        // Kill-weapon fix: the stored identity keys come first; rows written before the fix have none.
        String storedId = damage.contains(WEAPON_ID_KEY, Tag.TAG_STRING)
                ? safeText(damage.getString(WEAPON_ID_KEY), null)
                : null;
        String storedName = damage.contains(WEAPON_NAME_KEY, Tag.TAG_STRING)
                ? safeText(damage.getString(WEAPON_NAME_KEY), null)
                : null;
        boolean identityStored = storedId != null || storedName != null;
        boolean hasCompound = damage.contains("weapon", Tag.TAG_COMPOUND);
        boolean held = WEAPON_SOURCE_HELD.equals(damage.getString(WEAPON_SOURCE_KEY));
        if (!hasCompound && !identityStored) {
            if (!damage.getBoolean("weapon_unavailable")) return null;
            return new WeaponPresentation(1, null, WEAPON_NOT_RECORDED, reasonStatus(damage), null, null, held);
        }

        int count = 1;
        String technicalIdentity = null;
        ItemStack captured = null;
        WeaponMetadataStatus status;
        if (hasCompound) {
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
            count = weapon.contains("count") ? Math.max(0, weapon.getInt("count")) : 1;
            technicalIdentity = safeText(weapon.getString("id").replace("\"", ""), null);
            if (stack != null && !stack.isEmpty()) {
                count = Math.max(0, stack.getCount());
                // A degraded weapon decodes to a plain stack without its components; it is named by its
                // item type below (never e.g. "Uncraftable Potion"), and the presenter adds the note.
                if (!decoded.metadataUnavailable()) captured = stack;
                try {
                    var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (id != null) technicalIdentity = id.toString();
                } catch (RuntimeException | LinkageError ignored) {
                    // Retain the bounded technical identity from the encoded payload.
                }
            }
            status = decoded.metadataUnavailable()
                    ? (EventTrailTextBounds.METADATA_REASON_TOO_LARGE.equals(decoded.metadataReason())
                        ? WeaponMetadataStatus.TOO_LARGE
                        : WeaponMetadataStatus.UNAVAILABLE)
                    : WeaponMetadataStatus.CAPTURED;
        } else {
            // The fixed envelope: the identity keys without a compound.
            status = reasonStatus(damage);
        }
        if (storedId != null) technicalIdentity = storedId;

        String realName = storedName;
        if (realName == null && captured != null) realName = capturedRealName(captured);
        if (realName == null) realName = safeText(DisplayNameResolver.itemDisplayName(technicalIdentity), null);
        if (realName == null && technicalIdentity != null) realName = "Unresolved item (" + technicalIdentity + ')';

        // A row written after the fix stores its rename even when the id and name reads failed.
        boolean renameStored = damage.contains(WEAPON_CUSTOM_NAME_KEY, Tag.TAG_STRING)
                || damage.contains(WEAPON_RENAME_STATE_KEY, Tag.TAG_STRING);
        boolean fixedRow = identityStored || renameStored;
        DisplayNameResolver.RenameState rename = fixedRow
                ? storedRename(damage)
                : captured != null ? DisplayNameResolver.RenameState.ofStack(captured)
                : DisplayNameResolver.RenameState.NONE;

        String presentationIdentity;
        if (!fixedRow && captured != null) {
            // A row written before the fix keeps exactly today's text.
            presentationIdentity = DisplayNameResolver.stackDisplayName(captured);
        } else {
            presentationIdentity = realName == null ? null : composedName(realName, rename);
        }
        if (presentationIdentity == null || presentationIdentity.isBlank()) {
            presentationIdentity = realName != null ? realName : WEAPON_NOT_RECORDED;
        }
        return new WeaponPresentation(
                Math.max(0, count), technicalIdentity, presentationIdentity, status, realName, rename, held);
    }

    /** The weapon status of a row with no weapon compound, from its stored reason. */
    private static WeaponMetadataStatus reasonStatus(CompoundTag damage) {
        return EventTrailTextBounds.METADATA_REASON_TOO_LARGE.equals(damage.getString("weapon_metadata_reason"))
                ? WeaponMetadataStatus.TOO_LARGE
                : WeaponMetadataStatus.UNAVAILABLE;
    }

    private static String capturedRealName(ItemStack stack) {
        try {
            String book = DisplayNameResolver.writtenBookLabel(stack);
            return book != null ? book : safeText(DisplayNameResolver.gameName(stack), null);
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static DisplayNameResolver.RenameState storedRename(CompoundTag damage) {
        if (damage.contains(WEAPON_CUSTOM_NAME_KEY, Tag.TAG_STRING)) {
            return DisplayNameResolver.RenameState.text(damage.getString(WEAPON_CUSTOM_NAME_KEY));
        }
        String state = damage.getString(WEAPON_RENAME_STATE_KEY);
        if (RENAME_STATE_UNREADABLE.equals(state)) return DisplayNameResolver.RenameState.UNREADABLE;
        if (RENAME_STATE_CHECK_FAILED.equals(state)) return DisplayNameResolver.RenameState.CHECK_FAILED;
        return DisplayNameResolver.RenameState.NONE;
    }

    /** The stored parts in {@link DisplayNameResolver#stackDisplayName(ItemStack)}'s exact form. */
    static String composedName(String real, DisplayNameResolver.RenameState rename) {
        return switch (rename.kind()) {
            case NONE -> real;
            case TEXT -> real + " (renamed \"" + DisplayNameResolver.quoted(rename.text()) + "\")";
            case UNREADABLE -> real + " (renamed, name unreadable)";
            case CHECK_FAILED -> real + DisplayNameResolver.RENAME_CHECK_FAILED;
        };
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
            String retaliationEffect,
            String lastHitByPresentation) {
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

    /**
     * A kill row's weapon. {@code technicalIdentity} is the registry id (the hover ID and the copy text);
     * {@code presentationIdentity} the composed text the presenter prints, never blank; {@code realName} the plain
     * real name, null only when the weapon was not recorded; {@code rename} its rename, never null; {@code held} that it
     * is the killer's held item, not the damage's own weapon (kill-weapon fix; the presenter reads it "holding X").
     */
    public record WeaponPresentation(
            int count,
            String technicalIdentity,
            String presentationIdentity,
            WeaponMetadataStatus metadataStatus,
            String realName,
            DisplayNameResolver.RenameState rename,
            boolean held) {
        public WeaponPresentation {
            if (count < 0) throw new IllegalArgumentException("Weapon count cannot be negative");
            if (presentationIdentity == null || presentationIdentity.isBlank()) {
                throw new IllegalArgumentException("Weapon presentation must not be blank");
            }
            Objects.requireNonNull(metadataStatus, "metadataStatus");
            rename = rename == null ? DisplayNameResolver.RenameState.NONE : rename;
        }

        /** The damage's own weapon, with its name parts. */
        public WeaponPresentation(int count, String technicalIdentity, String presentationIdentity,
                                  WeaponMetadataStatus metadataStatus, String realName,
                                  DisplayNameResolver.RenameState rename) {
            this(count, technicalIdentity, presentationIdentity, metadataStatus, realName, rename, false);
        }

        /** Today's shape: a weapon with no separate name parts. */
        public WeaponPresentation(int count, String technicalIdentity, String presentationIdentity,
                                  WeaponMetadataStatus metadataStatus) {
            this(count, technicalIdentity, presentationIdentity, metadataStatus, null, null, false);
        }

        /** Whether anything that names the weapon was stored. */
        public boolean recorded() {
            return technicalIdentity != null || realName != null;
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
