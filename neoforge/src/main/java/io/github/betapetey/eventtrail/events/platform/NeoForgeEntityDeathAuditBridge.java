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
import java.util.function.BooleanSupplier;
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
            // Item 2 F4 A (KL-3 4a): so does a source naming another real player inside one real player's own attack.
            if (!deviceNamedPlayer) deviceNamedPlayer = PlayerAttackFrames.namesAnotherPlayer(direct, causing);
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
            // Item 2 F4 A (Q427 A): the status counter of direct-player credits given outside that player's own click
            // or key press.
            PlayerInputFrames.noteCredit(attribution);
            // Kill-weapon fix: a held item only for a direct player hitter whose weapon read threw (resolveWeapon).
            weapon = resolveWeapon(
                    weapon,
                    attribution,
                    direct,
                    directExtraction.unavailable(),
                    causing,
                    causingExtraction.unavailable(),
                    deviceNamedPlayer,
                    CommandCreditGuard.active());
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
            // Owner Q265 (a): the "last hit by" note, read after the attribution is final; it never changes it.
            CommittedEntityDeathEvidence.LastHit lastHitBy = victimPlayerTracked
                    ? safeLastHitNote(victim, row.attribution(), damageTypeId)
                    : null;

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
                            causingUnavailable,
                            weapon.source()),
                    row.attribution(),
                    victimPlayerTracked,
                    CommittedEntityDeathEvidence.ProofKind
                            .ACCEPTED_EVENT_AND_COMPLETED_DIE_OPERATION,
                    lastHitBy));
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
        boolean refusedFrame = RetaliationScope.withheldUnderRefusedFrame(attribution)
                || FramedSources.withheld(attribution);
        if (refusedFrame) {
            // Fold 02 ruling 1, item 2 F2 for a check that threw, and item 2 F4 A for a re-delivered framed source: the
            // player (the causing player, and the direct entity when it is a player) and the weapon are dropped, as on
            // a RETALIATION row; the row stays UNSUPPORTED_SECONDARY, counted once already.
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
        // Item 2 F4 A (KL-3 4a): inside one real player's own attack (a listed bot's, say), a source naming another real
        // player credits nobody. After the retaliation rule, so a hit-back inside an attack stays RETALIATION.
        Optional<EntityKillAttribution> otherPlayer = PlayerAttackFrames.resolveDamage(direct, causing);
        if (otherPlayer.isPresent()) return otherPlayer.get();
        // Item 2 F4 A (KL-4): a source first used under a hit-back, device or command frame, used again after that
        // frame ended, credits nobody. After every frame rule, so a death inside its own frame keeps that answer.
        Optional<EntityKillAttribution> redelivered = FramedSources.resolveDeath(source);
        if (redelivered.isPresent()) return redelivered.get();
        // Known Iron's sources cannot borrow generic player/owner/last-hit credit.
        Optional<EntityKillAttribution> spell = IronsSpellAuditRuntime.resolveDamage(victim, source);
        if (spell.isPresent()) return spell.get();
        Optional<EntityKillAttribution> ars = ArsSpellAuditRuntime.resolveDamage(victim, source);
        if (ars.isPresent()) return ars.get();
        return classifiedSource(directExtraction, causingExtraction, directUnavailable, causingUnavailable, source);
    }

    /**
     * The last rule of {@link #attribution}: the classifier over the source's own facts. Also asked by
     * {@link #provenOwnHit}, so the last-hit proof and the death row read one rule. Counts nothing.
     */
    private static EntityKillAttribution classifiedSource(
            EntityExtraction directExtraction,
            EntityExtraction causingExtraction,
            boolean directUnavailable,
            boolean causingUnavailable,
            DamageSource source) {
        Entity direct = directExtraction.entity();
        Entity causing = causingExtraction.entity();
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

        // Owner correction of 11-23 11:56 AM, final blow only: a kill is the player's only when their own attack or
        // weapon does the final damage (their hit, their projectile, TNT they lit). Minecraft's kill credit for a later
        // entityless death (a fall, lava, drowning, cactus, the void, or fire, a burn after their Fire Aspect or Flame
        // hit included, per the Owner's Q262 C) is never read here, so such a death is recorded by its cause with no
        // player, like other deaths with no player. No engine credit is supplied.
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
                        null));
    }

    /**
     * Every exclusion that withholds Minecraft's entityless kill credit; package-private for the guard tests. Each
     * guard can only withhold: none grants credit. No fire tracking is built, per the Owner's Q262 C (11-23 1:10 PM): a
     * burn death, even after a Fire Aspect or Flame hit, is a death by fire.
     */
    // Unreachable for kill credit since final-blow-only (Owner 11-23 11:56 AM); only the Q265 (a) note calls it.
    static boolean permitsEngineCredit(LivingEntity victim, ResourceLocation finalDamageTypeId) {
        return IronsEngineCreditGuard.permitsEngineCredit(victim)
                && ArsEngineCreditGuard.permitsEngineCredit(victim)
                && FakePlayerAttackGuard.permitsEngineCredit(victim)
                && AtomicReconstructorCreditGuard.permitsEngineCredit(finalDamageTypeId);
    }

    /**
     * The game's own window for a victim's last attacker: vanilla forgets that attacker 100 game ticks after the hit
     * (about 5 seconds at the normal 20 ticks per second; longer on a lagging server).
     */
    static final int LAST_HIT_WINDOW_TICKS = 100;

    /**
     * The note for {@link #capture}: any failure while reading it gives no note and leaves the death row as it is.
     * Package-private for the failure test.
     */
    static CommittedEntityDeathEvidence.LastHit safeLastHitNote(
            LivingEntity victim, EntityKillAttribution attribution, ResourceLocation damageTypeId) {
        try {
            return lastHitNote(victim, attribution, damageTypeId, NeoForgePlayerProvenance::isAdmittedPlayer);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("capture last-hit note", failure);
            return null;
        }
    }

    /**
     * The Owner's Q265 (a) (11-23 1:28 PM): a player who dies of a cause with no player credit (a fall, lava,
     * drowning, a cactus, the void, fire, a burn after a Fire Aspect or Flame hit included) within the game's window
     * after another player's hit gets "last hit by <player>" on their death row. It is a plain note, not a kill: the
     * attribution is final before this runs and is not changed, no kill row is written, and nothing is indexed under
     * the hitter. Read on the server thread at the death, from the game's own last-attacker fields; nothing is tracked.
     *
     * <p>No note when the death has player credit or is not entityless (only an ENVIRONMENT attribution qualifies);
     * inside a command, or when the victim's last hit was made inside one (no rows for anything a command does); when
     * the game names no player (no player hit, or the window passed); when the hitter is the victim; when a mob, a pet
     * or another entity hit after the player (the player must be the game's very last attacker); when the last hit is
     * older than {@link #LAST_HIT_WINDOW_TICKS}; when no proof says that player's own hit made them the last attacker at
     * that tick (item 2 F1, {@link LastHitProof}: a Thorns strike, an arrow the player did not shoot, a command's arrow,
     * or a victim without the proof carrier); under the Iron's, Ars, FakePlayer and Atomic Reconstructor guards of
     * {@link #permitsEngineCredit}; and when the hitter is no admitted real player: a NeoForge FakePlayer, a mod's
     * unlisted fake, or a player who left or respawned (the game also drops them as last attacker once they are gone).
     * Package-private for the behaviour tests, which supply {@code admitted}.</p>
     */
    static CommittedEntityDeathEvidence.LastHit lastHitNote(
            LivingEntity victim,
            EntityKillAttribution attribution,
            ResourceLocation damageTypeId,
            Predicate<Player> admitted) {
        if (!(victim instanceof Player)
                || attribution == null
                || attribution.provenance() != EntityKillAttribution.ProvenanceKind.ENVIRONMENT
                || attribution.accountablePlayer() != null) {
            return null;
        }
        if (CommandCreditGuard.active()) return null;
        if (victim instanceof CommandEngineCreditCarrier carrier && carrier.eventtrail$commandEngineCreditExcluded()) {
            return null;
        }
        if (!(victim.getKillCredit() instanceof Player hitter)) return null;
        if (hitter == victim || victim.getUUID().equals(hitter.getUUID())) return null;
        if (victim.getLastHurtByMob() != hitter) return null;
        int age = victim.tickCount - victim.getLastHurtByMobTimestamp();
        if (age < 0 || age > LAST_HIT_WINDOW_TICKS) return null;
        // Item 2 F1: the game names its last attacker from any damage that attacker caused (a Thorns strike, a
        // machine's arrow, a command's arrow), so the hurt step's proof must name this hitter at this very tick.
        if (!(victim instanceof LastHitProofCarrier proof)
                || !hitter.getUUID().equals(proof.eventtrail$provenLastHitPlayer())
                || proof.eventtrail$provenLastHitTick() != victim.getLastHurtByMobTimestamp()) {
            return null;
        }
        if (!permitsEngineCredit(victim, damageTypeId)) return null;
        if (hitter.isRemoved() || !admitted.test(hitter)) return null;
        String name = hitter.getName().getString();
        if (name == null || name.isBlank()) return null;
        return new CommittedEntityDeathEvidence.LastHit(hitter.getUUID(), name);
    }

    /**
     * Item 2 F1 (KL-265, KL-177, KL-176): whether the hit that just made {@code attacker} the victim's last attacker is
     * that real player's own act, asked by {@link LastHitProof} at the hurt step. True only when the attacker is a listed
     * real player and the rules a death row by this very damage would ask credit them: no command running, the player
     * is the causing entity, no
     * FakePlayer attack names them, no other real player's attack is running (item 2 F4 A), the source was not first
     * used under a frame, no hit-back signal or refused frame matches (Thorns), no Iron's or Ars rule owns
     * the source, and the classifier gives them a hit, a projectile proven theirs, or TNT they lit. Counts nothing and
     * changes nothing; package-private for the behaviour tests.
     */
    static boolean provenOwnHit(
            LivingEntity victim, LivingEntity attacker, DamageSource source, ResourceLocation damageTypeId) {
        // The listed check counts nothing (isAdmittedPlayer counts each refusal), and an unlisted hitter proves nothing.
        if (!(attacker instanceof ServerPlayer player)
                || NeoForgePlayerProvenance.isSynthetic(player)
                || !NeoForgePlayerProvenance.isListedPlayer(player)) {
            return false;
        }
        if (CommandCreditGuard.active()) return false;
        Entity direct = source.getDirectEntity();
        Entity causing = source.getEntity();
        if (causing != player) return false;
        if (FakePlayerAttackGuard.resolveDamage(direct, causing).isPresent()) return false;
        // Item 2 F4 A: nor inside another real player's own attack, nor by a source first used under a frame (a mark
        // set at this same hurt step needs one of the frame rules here, so the order of the two hurt hooks is moot).
        if (PlayerAttackFrames.namesAnotherPlayer(direct, causing)) return false;
        if (FramedSources.marked(source)) return false;
        BooleanSupplier spellSource = () -> IronsSpellAuditRuntime.isIronSource(source)
                || ArsSpellAuditRuntime.isArsDamage(victim, source);
        if (spellSource.getAsBoolean()) return false;
        if (RetaliationScope.match(victim, direct, causing, false, false, damageTypeId, spellSource).isPresent()) {
            return false;
        }
        EntityKillAttribution own = classifiedSource(
                new EntityExtraction(direct, false), new EntityExtraction(causing, false), false, false, source);
        EntityKillAttribution.ProvenanceKind kind = own.provenance();
        return (kind == EntityKillAttribution.ProvenanceKind.DIRECT_PLAYER
                || kind == EntityKillAttribution.ProvenanceKind.PLAYER_PROJECTILE
                || kind == EntityKillAttribution.ProvenanceKind.TNT_IGNITER)
                && own.accountablePlayer() != null
                && player.getUUID().equals(own.accountablePlayer().playerId());
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

    /**
     * Kill-weapon fix, the held fallback, applying the Owner's answers over design 3.3.4: the killer's held main-hand
     * item is logged only when the damage carries no weapon and its own weapon read threw (Q204 A's case (b)), and only
     * on a DIRECT_PLAYER row: then the player's own held item, only when the player hit for themselves (the direct
     * entity is the causing entity, or there is none). Package-private for the behaviour tests; never throws.
     * <p>The Owner's Q326 B: "No held item on rows that credit nobody; the held item still shows wherever a player is
     * the direct hitter." So the fallback is allowed on DIRECT_PLAYER rows alone (lead decision D-528): every other
     * kind, a mob's or a pet's own hit (NON_PLAYER_ENTITY, PET_OR_MINION) and an uncredited player row
     * (UNSUPPORTED_SECONDARY) included, keeps the damage's own capture. Never when the damage has no direct entity:
     * Q204 A's old case (a), a causing player with no direct hitter, credits nobody.</p>
     * Never for Q199 A's cases (a projectile, a swap, TNT, a crystal or a spell entity delivered the damage: the row
     * names that mechanism, and the launcher when the damage carries it) or K3 B's TNT: those rows have a direct entity
     * that is not the hitter, and their provenances are refused below as well. A fall, lava, drowning or other
     * entityless death after a hit is no player's kill (the Owner's final-blow correction of 11-23 11:56 AM) and is an
     * ENVIRONMENT row; the ENGINE_KILL_CREDIT kind stays refused. Never on a RETALIATION or ENVIRONMENT row, under
     * the Fix 4a or command redaction, inside a command scope, or when either source entity could not be read. A
     * refused-frame row still drops the weapon in {@link #deathRowParts}. The held stack is copied here, on the server
     * thread.
     */
    static WeaponCapture resolveWeapon(
            WeaponCapture fromDamage,
            EntityKillAttribution attribution,
            Entity direct,
            boolean directUnavailable,
            Entity causing,
            boolean causingUnavailable,
            boolean redacted,
            boolean commandScope) {
        if (!fromDamage.stack().isEmpty() || redacted || commandScope || attribution == null
                || directUnavailable || causingUnavailable) {
            return fromDamage;
        }
        if (attribution.provenance() != EntityKillAttribution.ProvenanceKind.DIRECT_PLAYER) {
            return fromDamage;                                              // Q326 B: only a direct player hitter
        }
        try {
            LivingEntity holder = heldWeaponHolder(direct, causing, fromDamage.unavailable());
            if (holder == null) return fromDamage;
            ItemStack held = holder.getWeaponItem();
            if (held == null || held.isEmpty()) return fromDamage;          // an empty hand: no weapon claimed
            return new WeaponCapture(held.copy(), false, EntityDamageEvidence.WeaponSource.HELD);
        } catch (RuntimeException | LinkageError | StackOverflowError failure) {
            logFailure("capture held weapon", failure);
            return fromDamage;
        }
    }

    /**
     * The player whose held item may stand in for the damage's weapon, or null (see {@link #resolveWeapon}): only a
     * direct player hitter that hit for themselves, after the damage's own weapon read threw. A mob or a pet is never a
     * holder, and no direct entity means no holder, whoever the causing entity is (the Owner's Q326 B, lead decision
     * D-528). A device-named player is refused earlier, in {@link #resolveWeapon}, by its redaction flag.
     */
    static LivingEntity heldWeaponHolder(Entity direct, Entity causing, boolean damageWeaponReadThrew) {
        if (damageWeaponReadThrew && direct instanceof Player hitter
                && (causing == null || causing == direct)) {
            return hitter;
        }
        return null;
    }

    record WeaponCapture(ItemStack stack, boolean unavailable, EntityDamageEvidence.WeaponSource source) {
        WeaponCapture {
            source = source == null || stack == null || stack.isEmpty()
                    ? EntityDamageEvidence.WeaponSource.DAMAGE
                    : source;
        }

        WeaponCapture(ItemStack stack, boolean unavailable) {
            this(stack, unavailable, EntityDamageEvidence.WeaponSource.DAMAGE);
        }
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
