package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.events.evidence.ActorProvenance;
import io.github.betapetey.eventtrail.events.evidence.CommittedEntityDeathEvidence;
import io.github.betapetey.eventtrail.events.evidence.EntityDamageEvidence;
import io.github.betapetey.eventtrail.events.evidence.EntityEventEvidenceNormalizer;
import io.github.betapetey.eventtrail.events.evidence.EntityIdentity;
import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import io.github.betapetey.eventtrail.events.evidence.EntityKillAttributionClassifier;
import io.github.betapetey.eventtrail.events.spells.IronsSpellAuditRuntime;
import io.github.betapetey.eventtrail.events.spells.IronsEngineCreditGuard;
import io.github.betapetey.eventtrail.events.spells.ArsSpellAuditRuntime;
import io.github.betapetey.eventtrail.events.spells.ArsEngineCreditGuard;
import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * NeoForge 1.21.1 live-object boundary for immutable death evidence. The two
 * exact Minecraft die mixins own finality; this bridge owns platform identity,
 * source attribution, normalization, and persistence.
 */
public final class NeoForgeEntityDeathAuditBridge {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    private static final RateLimitedDiagnosticGate FAILURE_DIAGNOSTICS =
            new RateLimitedDiagnosticGate(60_000L);
    private static final ResourceLocation UNREGISTERED_ENTITY_TYPE =
            ResourceLocation.fromNamespaceAndPath("eventtrail", "unregistered_entity_type");
    private static final ResourceLocation UNREGISTERED_DAMAGE_TYPE =
            ResourceLocation.fromNamespaceAndPath("eventtrail", "unregistered_damage_type");

    private NeoForgeEntityDeathAuditBridge() {
    }

    /** Cheap admission check performed before allocating a method-scoped session. */
    public static boolean canBegin(LivingEntity victim) {
        try {
            if (victim == null
                    || !(victim.level() instanceof ServerLevel level)
                    || !level.getServer().isSameThread()
                    || !victim.isAddedToLevel()
                    || victim.isRemoved()
                    || level.getEntity(victim.getUUID()) != victim
                    || (!EventTrailConfig.TRACK_PLAYER_DEATHS.get()
                    && !EventTrailConfig.TRACK_ENTITY_KILLS.get())) {
                return false;
            }
            // af-2: counted capture gate; a death that passed it at the session start reaches it again in capture()
            // only to be counted if EventTrail stopped in between, so each skipped death is counted once.
            return EventTrailManager.getInstance().admitCapture();
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("admit entity-death evidence", failure);
            return false;
        }
    }

    /**
     * Snapshots live victim and DamageSource state only after NeoForge's final
     * LivingDeathEvent decision returned not-cancelled. No live object escapes.
     */
    public static Optional<CommittedEntityDeathEvidence> capture(
            LivingEntity victim,
            DamageSource source) {
        if (source == null || !canBegin(victim) || victim.getHealth() > 0.0F) {
            return Optional.empty();
        }

        try {
            ServerLevel level = (ServerLevel) victim.level();
            // Most entities are world-space; Sable's retained plot entities are not.
            // Use the exact entity point, never a block-center approximation.
            var physical = AuditBlockLocations.capture(level, victim.blockPosition())
                    .flatMap(frame -> frame.currentPointLocation(victim.position()));
            if (physical.isEmpty()) return Optional.empty();
            EntityExtraction directExtraction = captureDirectEntity(source);
            EntityExtraction causingExtraction = captureCausingEntity(source);
            Entity direct = directExtraction.entity();
            Entity causing = causingExtraction.entity();
            EntityIdentity victimIdentity = identity(victim);
            IdentityCapture directIdentityCapture = captureIdentity(directExtraction);
            IdentityCapture causingIdentityCapture = direct == causing
                    && !directExtraction.unavailable()
                    && !causingExtraction.unavailable()
                    ? directIdentityCapture
                    : captureIdentity(causingExtraction);
            // Fix 4a (review-01 F1): a source a FakePlayer attack wrote a real player into must not name that player
            // in the death row either. Its player identities become the existing "Automated player" form and no
            // weapon is claimed (the source's weapon is the named player's own hand, not the device's).
            boolean deviceNamedPlayer = FakePlayerAttackGuard.resolveDamage(direct, causing).isPresent();
            // Command credit guard (review-01 F3): a source a command wrote a real player into gets the same redaction.
            if (!deviceNamedPlayer) deviceNamedPlayer = CommandCreditGuard.redactsSource(direct, causing);
            EntityIdentity directIdentity = deviceNamedPlayer
                    ? redactDeviceNamedPlayer(directIdentityCapture.identity())
                    : directIdentityCapture.identity();
            EntityIdentity causingIdentity = deviceNamedPlayer
                    ? redactDeviceNamedPlayer(causingIdentityCapture.identity())
                    : causingIdentityCapture.identity();
            boolean directUnavailable = directExtraction.unavailable()
                    || directIdentityCapture.unavailable();
            boolean causingUnavailable = causingExtraction.unavailable()
                    || causingIdentityCapture.unavailable();
            ResourceLocation damageTypeId = damageTypeId(source);
            ResourceLocation mechanismId = direct != null && direct != causing
                    ? entityTypeId(direct)
                    : null;
            WeaponCapture weapon = deviceNamedPlayer
                    ? new WeaponCapture(ItemStack.EMPTY, false)
                    : captureWeapon(source);
            EntityKillAttribution attribution = safeAttribution(
                    victim,
                    directExtraction,
                    causingExtraction,
                    directUnavailable,
                    causingUnavailable,
                    source,
                    damageTypeId);
            // Kill credit A: the wearer drop and the owner attach, after the command screen (lead ruling 4a).
            DeathRowParts row = deathRowParts(
                    attribution,
                    directIdentity,
                    causingIdentity,
                    weapon.stack(),
                    weapon.unavailable(),
                    direct,
                    causing,
                    deviceNamedPlayer,
                    KillRelatedPartyCapture.players(level.getServer()));
            boolean victimPlayerTracked = victimPlayerTracked(victim);

            return Optional.of(new CommittedEntityDeathEvidence(
                    System.currentTimeMillis(),
                    UUID.randomUUID(),
                    victimIdentity,
                    physical.get().dimensionId(),
                    physical.get().position(),
                    new EntityDamageEvidence(
                            damageTypeId,
                            damageMessageId(source),
                            row.directIdentity(),
                            row.causingIdentity(),
                            mechanismId,
                            row.weapon(),
                            row.weaponUnavailable(),
                            directUnavailable,
                            causingUnavailable),
                    row.attribution(),
                    victimPlayerTracked,
                    CommittedEntityDeathEvidence.ProofKind
                            .ACCEPTED_EVENT_AND_COMPLETED_DIE_OPERATION));
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("capture entity-death evidence", failure);
            return Optional.empty();
        }
    }

    /** Persists only immutable evidence returned by a successfully closed session. */
    public static void persist(CommittedEntityDeathEvidence evidence) {
        if (evidence == null) return;
        try {
            EventTrailManager manager = EventTrailManager.getInstance();
            publishAction(
                    evidence,
                    EventTrailConfig.TRACK_PLAYER_DEATHS.get(),
                    EventTrailConfig.TRACK_ENTITY_KILLS.get(),
                    manager.canAcceptAuditWrite(),
                    manager::tryLogAll);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("persist committed entity-death evidence", failure);
        }
    }

    /**
     * Row C (writer-01 2.6): every row of one death (the victim's death row and the killer's kill row) is built
     * first and then published once, as one group, so the writer stores the whole action or none of it. Rows the
     * tracking policy omits stay omitted; when the policy omits every row nothing is published. A build failure
     * throws before anything is published. Public only for the writer's group tests.
     *
     * @return whether a group was published and accepted
     */
    public static boolean publishAction(
            CommittedEntityDeathEvidence evidence,
            boolean trackPlayerDeaths,
            boolean trackEntityKills,
            boolean captureFullPayload,
            Predicate<List<AuditRecord>> publisher) {
        List<AuditRecord> entries = EntityEventEvidenceNormalizer.toAuditRecords(
                evidence,
                trackPlayerDeaths,
                trackEntityKills,
                captureFullPayload);
        return !entries.isEmpty() && publisher.test(entries);
    }

    /**
     * Kill credit A (lead answer 12, design-05c 4.1; lead ruling 4a of fold 01): the death row's parts once the
     * attribution is known. A RETALIATION row never names the wearer and claims no weapon: the killer is the hit-back
     * itself. A real player's death withheld under a refused VICTIM-frame push (RetaliationScope's one marked result)
     * stores no player identity, no player owner and no weapon either; a projectile's, a pet's or a summon's own identity
     * stays (lead ruling 1 of fold 02). Every other row gets the killer's recorded owner, after the command screen,
     * outside a command scope and outside the Fix 4a and command redaction ({@code redacted}), and only when the killing
     * entity's own identity is stored, so the owner is never printed next to the other entity (lead ruling 2 of fold
     * 02). Package-private for the behaviour tests.
     */
    static DeathRowParts deathRowParts(
            EntityKillAttribution attribution,
            EntityIdentity directIdentity,
            EntityIdentity causingIdentity,
            ItemStack weapon,
            boolean weaponUnavailable,
            Entity direct,
            Entity causing,
            boolean redacted,
            Function<UUID, ServerPlayer> playerList) {
        if (attribution != null
                && attribution.provenance() == EntityKillAttribution.ProvenanceKind.RETALIATION) {
            return new DeathRowParts(attribution, null, null, ItemStack.EMPTY, false);
        }
        boolean refusedFrame = RetaliationScope.withheldUnderRefusedFrame(attribution);
        if (refusedFrame) {
            // Fold 02 ruling 1: the player (the causing player, and the direct entity when it is a player) and the
            // weapon are dropped, as on a RETALIATION row; the row stays UNSUPPORTED_SECONDARY, counted once already.
            if (isPlayerIdentity(directIdentity)) directIdentity = null;
            if (isPlayerIdentity(causingIdentity)) causingIdentity = null;
            weapon = ItemStack.EMPTY;
            weaponUnavailable = false;
        }
        // Fold 02 ruling 2: the owner is attached only when the killing entity's own identity is stored.
        Entity killer = KillRelatedPartyCapture.killingEntity(direct, causing);
        EntityIdentity shown = killer == direct ? directIdentity : causingIdentity;
        EntityKillAttribution row = killer != null && shown == null
                ? attribution
                : KillRelatedPartyCapture.attach(attribution, direct, causing, redacted, playerList);
        if (refusedFrame && row != null && row.related() != null
                && row.related().kind() != EntityKillAttribution.RelatedParty.Kind.ENTITY) {
            // A player owner, or an owner the server cannot name (it may be a player), is not stored under the marker.
            row = attribution;
        }
        return new DeathRowParts(row, directIdentity, causingIdentity, weapon, weaponUnavailable);
    }

    /** A stored player identity of any kind: a player, a synthetic player, or its redacted form. */
    private static boolean isPlayerIdentity(EntityIdentity identity) {
        return identity != null
                && (identity.kind() == EntityIdentity.EntityKind.PLAYER
                || identity.kind() == EntityIdentity.EntityKind.SYNTHETIC_PLAYER
                || identity.kind() == EntityIdentity.EntityKind.REDACTED_SYNTHETIC_PLAYER);
    }

    /** A death row's attribution, its two source identities and its weapon (lead ruling 4a). */
    record DeathRowParts(
            EntityKillAttribution attribution,
            EntityIdentity directIdentity,
            EntityIdentity causingIdentity,
            ItemStack weapon,
            boolean weaponUnavailable) {
    }

    /**
     * The source rules in their order, over live entities that were read without failure (lead ruling 4b of fold 01):
     * package-private for the rule-order behaviour tests. The capture path calls the private form below.
     */
    static EntityKillAttribution attribution(
            LivingEntity victim,
            Entity direct,
            Entity causing,
            DamageSource source,
            ResourceLocation damageTypeId) {
        return attribution(
                victim,
                new EntityExtraction(direct, false),
                new EntityExtraction(causing, false),
                false,
                false,
                source,
                damageTypeId);
    }

    private static EntityKillAttribution attribution(
            LivingEntity victim,
            EntityExtraction directExtraction,
            EntityExtraction causingExtraction,
            boolean directUnavailable,
            boolean causingUnavailable,
            DamageSource source,
            ResourceLocation damageTypeId) {
        Entity direct = directExtraction.entity();
        Entity causing = causingExtraction.entity();
        // Fix 4a: inside a FakePlayer's own attack a source naming a real player is a device kill. It runs before
        // every other rule so that no later rule can credit the player the device wrote into its source.
        Optional<EntityKillAttribution> device = FakePlayerAttackGuard.resolveDamage(direct, causing);
        if (device.isPresent()) return device.get();
        // Kill credit B (design-05c 2.4): Thorns and every other hit-back credit nobody, before any rule that could
        // credit the wearer. Signal 3 never applies to a source an Iron's or Ars rule owns.
        Optional<EntityKillAttribution> retaliation = RetaliationScope.resolveDeath(
                victim,
                direct,
                causing,
                directUnavailable,
                causingUnavailable,
                damageTypeId,
                () -> IronsSpellAuditRuntime.isIronSource(source)
                        || ArsSpellAuditRuntime.isArsDamage(victim, source));
        if (retaliation.isPresent()) return retaliation.get();
        // Known Iron's sources cannot borrow generic player/owner/last-hit credit.
        Optional<EntityKillAttribution> spell = IronsSpellAuditRuntime.resolveDamage(victim, source);
        if (spell.isPresent()) return spell.get();
        Optional<EntityKillAttribution> ars = ArsSpellAuditRuntime.resolveDamage(victim, source);
        if (ars.isPresent()) return ars.get();
        boolean sameDirectAndCausing = !directUnavailable
                && !causingUnavailable
                && direct != null
                && direct == causing;
        ActorProvenance causingPlayer = !causingUnavailable && causing instanceof Player player
                ? playerActor(
                        player,
                        sameDirectAndCausing
                                ? ActorProvenance.Causality.DIRECT
                                : ActorProvenance.Causality.IMMEDIATE_SECONDARY)
                : null;

        // The platform adapter supplies engine credit only after proving that
        // Minecraft's credited player is also the exact last attacking mob.
        // Tame damage keeps the tame entity in that field, so owner promotion
        // cannot cross this extraction boundary.
        ActorProvenance exactEngineCredit = null;
        LivingEntity killCredit = victim.getKillCredit();
        if (killCredit instanceof Player player
                && victim.getLastHurtByMob() == killCredit
                && permitsEngineCredit(victim, damageTypeId)) {
            exactEngineCredit = playerActor(
                    player,
                    ActorProvenance.Causality.IMMEDIATE_SECONDARY);
        }

        return EntityKillAttributionClassifier.classify(
                new EntityKillAttributionClassifier.SourceFacts(
                        sourceKind(
                                directExtraction,
                                causingPlayer,
                                directUnavailable,
                                isCanonicalPlayerTntSource(
                                        direct, causingPlayer, source)),
                        sourceKind(causingExtraction, null, causingUnavailable, false),
                        sameDirectAndCausing,
                        causingPlayer,
                        exactEngineCredit));
    }

    /**
     * Every exclusion that withholds Minecraft's entityless kill credit; package-private for the guard tests. Each
     * guard can only withhold: none grants credit the exact last-attacker proof above would not.
     */
    static boolean permitsEngineCredit(LivingEntity victim, ResourceLocation finalDamageTypeId) {
        return IronsEngineCreditGuard.permitsEngineCredit(victim)
                && ArsEngineCreditGuard.permitsEngineCredit(victim)
                && FakePlayerAttackGuard.permitsEngineCredit(victim)
                && AtomicReconstructorCreditGuard.permitsEngineCredit(finalDamageTypeId);
    }

    /**
     * Fix 4a (review-01 F1): a player identity in a source a FakePlayer attack wrote a real player into becomes the
     * existing "Automated player" form (zero UUID, no name), exactly as an excluded FakePlayer is stored. Any other
     * identity, including null, is unchanged apart from the usual synthetic-player redaction. Package-private for tests.
     */
    static EntityIdentity redactDeviceNamedPlayer(EntityIdentity identity) {
        if (identity == null) return null;
        if (identity.kind() != EntityIdentity.EntityKind.PLAYER) return identity.redactSyntheticPlayer();
        return new EntityIdentity(
                identity.typeId(),
                identity.entityId(),
                null,
                null,
                null,
                EntityIdentity.EntityKind.SYNTHETIC_PLAYER).redactSyntheticPlayer();
    }

    private static EntityKillAttribution safeAttribution(
            LivingEntity victim,
            EntityExtraction direct,
            EntityExtraction causing,
            boolean directUnavailable,
            boolean causingUnavailable,
            DamageSource source,
            ResourceLocation damageTypeId) {
        try {
            // Command credit guard: the last step, after every rule in attribution(), so it can only withhold. The
            // supplier tells a source that names the player (no spell rule claimed it) from every other credit.
            return CommandCreditGuard.screen(victim, attribution(
                    victim,
                    direct,
                    causing,
                    directUnavailable,
                    causingUnavailable,
                    source,
                    damageTypeId), () -> !directUnavailable && !causingUnavailable
                    && CommandCreditGuard.namesPlayerAsAttacker(direct.entity(), causing.entity(), damageTypeId)
                    && !IronsSpellAuditRuntime.isIronSource(source)
                    && !ArsSpellAuditRuntime.isArsDamage(victim, source));
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("resolve entity kill attribution", failure);
            return EntityKillAttribution.unattributed(
                    EntityKillAttribution.ProvenanceKind.UNSUPPORTED_SECONDARY);
        }
    }

    private static ActorProvenance playerActor(
            Player player,
            ActorProvenance.Causality causality) {
        // Death classification needs the source kind even when it is excluded
        // from recording. The normalizer always redacts synthetic identity and
        // withholds its killer-side row while retaining eligible real victims.
        return new ActorProvenance(
                playerKind(player) == EntityIdentity.EntityKind.SYNTHETIC_PLAYER
                        ? ActorProvenance.ActorKind.SYNTHETIC_PLAYER
                        : ActorProvenance.ActorKind.PLAYER,
                causality,
                player.getUUID(),
                player.getName().getString(),
                null);
    }

    private static EntityKillAttributionClassifier.SourceKind sourceKind(
            EntityExtraction extraction,
            ActorProvenance causingPlayer,
            boolean unavailable,
            boolean provenPlayerTnt) {
        if (unavailable) {
            return EntityKillAttributionClassifier.SourceKind.UNAVAILABLE;
        }
        Entity entity = extraction.entity();
        if (entity == null) return EntityKillAttributionClassifier.SourceKind.NONE;
        if (entity instanceof Player) return EntityKillAttributionClassifier.SourceKind.PLAYER;
        if (isProvenPlayerProjectile(entity, causingPlayer)) {
            return EntityKillAttributionClassifier.SourceKind.PROJECTILE;
        }
        if (entity instanceof PrimedTnt) {
            return provenPlayerTnt
                    ? EntityKillAttributionClassifier.SourceKind.PRIMED_TNT
                    : EntityKillAttributionClassifier.SourceKind.OTHER_ENTITY;
        }
        // Kill credit A (design-05c 4.2): any entity with a recorded owner, not only a TamableAnimal, is a pet or
        // minion, so its owner is never promoted into the killer.
        if (entity instanceof TamableAnimal
                || (entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null)) {
            return EntityKillAttributionClassifier.SourceKind.PET_OR_MINION;
        }
        return EntityKillAttributionClassifier.SourceKind.OTHER_ENTITY;
    }

    private static boolean isCanonicalPlayerTntSource(
            Entity direct,
            ActorProvenance causingPlayer,
            DamageSource source) {
        return direct instanceof PrimedTnt tnt
                && tnt.getType() == EntityType.TNT
                && causingPlayer != null
                && source.is(DamageTypes.PLAYER_EXPLOSION)
                && NeoForgeTntActionAuditBridge.wasIgnitedByPlayerAction(
                        tnt,
                        causingPlayer.playerId());
    }

    /**
     * A projectile becomes player-accountable only when its owner assignment
     * occurred inside the exact serverbound use/release packet for this same
     * player. Registry type and owner identity alone are insufficient because
     * a turret or machine may spawn even a vanilla arrow with a stored owner.
     */
    private static boolean isProvenPlayerProjectile(
            Entity entity,
            ActorProvenance causingPlayer) {
        return entity instanceof Projectile
                && causingPlayer != null
                && NeoForgeProjectileActionAuditBridge.wasLaunchedByPlayerAction(
                        entity,
                        causingPlayer.playerId());
    }

    private static IdentityCapture captureIdentity(EntityExtraction extraction) {
        if (extraction.unavailable()) return new IdentityCapture(null, true);
        Entity entity = extraction.entity();
        if (entity == null) return new IdentityCapture(null, false);
        try {
            return new IdentityCapture(identity(entity), false);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("capture entity source identity", failure);
            return new IdentityCapture(null, true);
        }
    }

    /**
     * GF-FAKEPLAYER-ID (B51): a real player is listed when they die, so a victim that is not the PlayerList's own
     * object for its UUID (a fake carrying a real player's UUID) writes no death row on that player. Package-private
     * for tests.
     */
    static boolean victimPlayerTracked(LivingEntity victim) {
        return victim instanceof Player player && NeoForgePlayerProvenance.isAdmittedPlayer(player);
    }

    /**
     * GF-FAKEPLAYER-ID (B51): the kind of a player in a death (victim, attacker or kill credit): a player that is a
     * FakePlayer, or is not the PlayerList's own object for its UUID, is a synthetic player, stored redacted and never
     * credited. Package-private for tests.
     */
    static EntityIdentity.EntityKind playerKind(Player player) {
        return NeoForgePlayerProvenance.isAdmittedPlayer(player)
                ? EntityIdentity.EntityKind.PLAYER
                : EntityIdentity.EntityKind.SYNTHETIC_PLAYER;
    }

    private static EntityIdentity identity(Entity entity) {
        EntityIdentity.EntityKind kind;
        if (entity instanceof Player player) {
            kind = playerKind(player);
        } else if (entity instanceof Projectile) {
            kind = EntityIdentity.EntityKind.PROJECTILE;
        } else if (entity instanceof TamableAnimal) {
            kind = EntityIdentity.EntityKind.TAMED_ANIMAL;
        } else if (entity instanceof LivingEntity) {
            kind = EntityIdentity.EntityKind.LIVING_ENTITY;
        } else {
            kind = EntityIdentity.EntityKind.OTHER;
        }

        return new EntityIdentity(
                entityTypeId(entity),
                entity.getUUID(),
                entity instanceof Player
                        ? componentText(() -> entity.getName().getString())
                        : null,
                componentText(() -> entity.getType().getDescription().getString()),
                componentText(() -> entity.getDisplayName().getString()),
                componentText(() -> entity.hasCustomName() && entity.getCustomName() != null
                        ? entity.getCustomName().getString()
                        : null),
                kind);
    }

    private static ResourceLocation damageTypeId(DamageSource source) {
        try {
            return source.typeHolder().unwrapKey()
                    .map(key -> key.location())
                    .orElse(UNREGISTERED_DAMAGE_TYPE);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("capture damage type identity", failure);
            return UNREGISTERED_DAMAGE_TYPE;
        }
    }

    private static String damageMessageId(DamageSource source) {
        try {
            return source.getMsgId();
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            return null;
        }
    }

    private static ResourceLocation entityTypeId(Entity entity) {
        try {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            return id == null ? UNREGISTERED_ENTITY_TYPE : id;
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            return UNREGISTERED_ENTITY_TYPE;
        }
    }

    private static EntityExtraction captureDirectEntity(DamageSource source) {
        try {
            return new EntityExtraction(source.getDirectEntity(), false);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("capture direct damage entity", failure);
            return new EntityExtraction(null, true);
        }
    }

    private static EntityExtraction captureCausingEntity(DamageSource source) {
        try {
            return new EntityExtraction(source.getEntity(), false);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("capture causing damage entity", failure);
            return new EntityExtraction(null, true);
        }
    }

    private static WeaponCapture captureWeapon(DamageSource source) {
        try {
            ItemStack weapon = source.getWeaponItem();
            return new WeaponCapture(
                    weapon == null || weapon.isEmpty() ? ItemStack.EMPTY : weapon.copy(),
                    false);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("capture source weapon", failure);
            return new WeaponCapture(ItemStack.EMPTY, true);
        }
    }

    private static String componentText(TextCapture capture) {
        try {
            return capture.get();
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            return null;
        }
    }

    private static void logFailure(String phase, Throwable failure) {
        FAILURE_DIAGNOSTICS.acquire().ifPresent(emission -> {
            long suppressed = emission.suppressedSinceLastEmission();
            if (suppressed == 0L) {
                LOGGER.error("Could not {} ({})",
                        phase, EventTrailDiagnostics.safeFailureSummary(failure));
            } else {
                LOGGER.error("Could not {} ({}); suppressed {} similar entity-audit failure(s)",
                        phase, EventTrailDiagnostics.safeFailureSummary(failure), suppressed);
            }
        });
    }

    @FunctionalInterface
    private interface TextCapture {
        String get();
    }

    private record WeaponCapture(ItemStack stack, boolean unavailable) {
    }

    private record EntityExtraction(Entity entity, boolean unavailable) {
        private EntityExtraction {
            if (unavailable && entity != null) {
                throw new IllegalArgumentException(
                        "Unavailable entity extraction cannot contain a live entity");
            }
        }
    }

    private record IdentityCapture(EntityIdentity identity, boolean unavailable) {
        private IdentityCapture {
            if (unavailable && identity != null) {
                throw new IllegalArgumentException(
                        "Unavailable identity capture cannot contain an identity");
            }
        }
    }
}
