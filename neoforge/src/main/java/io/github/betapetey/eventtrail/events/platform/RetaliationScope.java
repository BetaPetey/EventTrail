package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Kill credit B (design-05c 2.2, 2.4, 3.2.5): recognises Thorns and every other hit-back, so a death it causes credits
 * nobody (P2-K8). One helper holds the three signals, so the bridge and every later guard ask the same question:
 * <ol>
 *   <li>the VICTIM frame: inside {@code Enchantment#doPostAttack} for the victim pass, a death whose causing entity is
 *       the frame's owner (identity), whatever the damage type;</li>
 *   <li>the damage type: {@code minecraft:thorns} with direct == causing == a real player (not Guardians);</li>
 *   <li>the being-hurt stack: while real player P's {@code hurt} runs on this thread, a death of another entity whose
 *       causing entity is P and whose direct entity is P, nothing, or a projectile of P's not proven his.</li>
 * </ol>
 * Inside a command scope no signal is asked: the command guard keeps today's path. No signal is asked for a death whose
 * causing entity is the victim himself (review-01 NIT 5): a player's own death credits nobody, and keeps today's path.
 *
 * <p>Both per-thread stacks follow 3.2.5: push and pop only in a {@code @WrapMethod} body's try/finally through
 * {@link Entry#close()}, pop by identity (closing an entry that is not the top clears the stack and counts
 * SCOPE_LEAK), every entry stamped with the server tick and the acting-entity depth, a push that finds a stale top
 * clears the stack first, a cap of {@value #CAP} entries with a refusal marker, and the {@code ServerTickEvent.Pre}
 * sweep ({@link ScopeLeakSweeper}). A real player's hurt pushes two being-hurt entries ({@code ServerPlayer#hurt} and,
 * through {@code super}, {@code LivingEntity#hurt}), so the being-hurt cap is reached at 8 nested player hurts
 * (review-01 NIT 7). These stacks only withhold, so a stale or leaked entry withholds more, never less; the stamps and
 * the sweep keep that from lasting past the next tick.</p>
 *
 * <p>A refused VICTIM-frame push (its refusal marker) withholds only a real player's credit (lead ruling 1 of fold 01),
 * and is asked after the three signals: the marker cannot prove a hit-back, so a real player's death that no signal
 * matched is UNSUPPORTED_SECONDARY, counted once as PROOF_FAILURE (the refusal itself was counted SCOPE_LEAK at the
 * push). Its row stores no player identity and no weapon (lead ruling 1 of fold 02): the bridge knows it by the one
 * value {@link #resolveDeath} returns for it ({@link #withheldUnderRefusedFrame}; a check that throws gets the same treatment
 * through its own marked value, item 2 F2). Each signal is proof by itself, so a
 * hit-back a signal proves under the marker stays RETALIATION ("Killed by Thorns"), counted once as RETALIATION (lead
 * ruling 7 of fold 02, re-check 01 check 2). A death by any other entity under the marker keeps today's rules, so a
 * zombie's or a pet's kill names that killer.</p>
 *
 * <p>The wearer is kept only in memory, on the frame; nothing here is stored. Nothing is thrown into the game short of
 * a {@link VirtualMachineError}.</p>
 */
public final class RetaliationScope {
    /** The cap of every stack here (3.2.5 rule 5). */
    static final int CAP = 16;
    /** A tick that could not be read: never compared, so it never counts as stale. */
    static final long UNKNOWN_TICK = Long.MIN_VALUE;

    private static final ResourceLocation THORNS = DamageTypes.THORNS.location();
    /**
     * The one attribution {@link #resolveDeath} returns for a real player's death under a refused VICTIM-frame push
     * (lead ruling 1 of fold 01). The bridge carries this value into its death-row parts and knows it by identity
     * (lead ruling 1 of fold 02), so no stack is asked a second time.
     */
    private static final EntityKillAttribution WITHHELD_UNDER_REFUSED_FRAME =
            EntityKillAttribution.unattributed(EntityKillAttribution.ProvenanceKind.UNSUPPORTED_SECONDARY);
    /**
     * The one attribution {@link #resolveDeath} returns when its own check threw (item 2 F2). The check could not rule
     * out a hit-back, so, like {@link #WITHHELD_UNDER_REFUSED_FRAME}, its row stores no player identity and no weapon.
     */
    private static final EntityKillAttribution WITHHELD_AFTER_CHECK_FAILURE =
            EntityKillAttribution.unattributed(EntityKillAttribution.ProvenanceKind.UNSUPPORTED_SECONDARY);
    private static final ThreadLocal<ScopeStack> VICTIM_FRAMES =
            ThreadLocal.withInitial(() -> new ScopeStack("Thorns and hit-back frames"));
    private static final ThreadLocal<ScopeStack> BEING_HURT =
            ThreadLocal.withInitial(() -> new ScopeStack("being-hurt stack"));

    private RetaliationScope() {
    }

    /**
     * Which signal matched (2.2). {@code REFUSED_FRAME} is not a hit-back: a real player's death under a refused
     * VICTIM-frame push, withheld as unproven (lead ruling 1 of fold 01).
     */
    public enum Signal { VICTIM_FRAME, DAMAGE_TYPE, BEING_HURT, REFUSED_FRAME }

    /** A matched retaliation. {@code wearer} stays in memory for the count; it is never stored. */
    public record Match(Signal signal, Entity wearer, EntityKillAttribution.RetaliationDetail detail) {
    }

    /**
     * RetaliationScopeMixin's push: a VICTIM frame for {@code item.owner()}, with the enchantment's id and description
     * text. Null (nothing opened) for any other pass or when the item has no owner.
     */
    public static Entry openVictimFrame(ServerLevel level, Enchantment enchantment, EnchantedItemInUse item) {
        try {
            Entity owner = item == null ? null : item.owner();
            if (owner == null) return null;
            ResourceLocation id = null;
            String name = null;
            try {
                id = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getKey(enchantment);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable unreadable) {
                // The frame still withholds; only its label is lost.
            }
            try {
                name = enchantment.description().getString();
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable unreadable) {
                // As above.
            }
            return enterVictimFrame(owner, id, name, tick(level == null ? null : level.getServer()));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** The VICTIM frame push itself; package-private for the tests. */
    static Entry enterVictimFrame(Entity owner, ResourceLocation effectId, String effectName, long tick) {
        if (owner == null) return null;
        return VICTIM_FRAMES.get().push(owner, effectId == null ? null : effectId.toString(), effectName, tick);
    }

    /**
     * The being-hurt push of BeingHurtStackMixin and ServerPlayerBeingHurtMixin: only a real ServerPlayer is pushed;
     * for any other entity nothing is opened (null).
     */
    public static Entry openBeingHurt(LivingEntity entity) {
        try {
            if (!(entity instanceof ServerPlayer player) || !FakePlayerAttackGuard.isRealPlayer(player)) return null;
            return enterBeingHurt(player, tick(serverOf(player)));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return null;
        }
    }

    /** The being-hurt push itself (a real player only); package-private for the tests. */
    static Entry enterBeingHurt(Entity player, long tick) {
        if (!FakePlayerAttackGuard.isRealPlayer(player)) return null;
        return BEING_HURT.get().push(player, null, null, tick);
    }

    /**
     * The bridge's retaliation rule (2.4), right after the FakePlayer rule: RETALIATION when a signal matched (counted
     * when the wearer is a real player), empty otherwise. A real player's death credit under a refused VICTIM-frame
     * push, and a throw, withhold as UNSUPPORTED_SECONDARY and count PROOF_FAILURE once. {@code spellSource} says an
     * Iron's or Ars rule owns the source (signal 3 never applies to it).
     */
    public static Optional<EntityKillAttribution> resolveDeath(
            LivingEntity victim,
            Entity direct,
            Entity causing,
            boolean directUnavailable,
            boolean causingUnavailable,
            ResourceLocation damageTypeId,
            BooleanSupplier spellSource) {
        try {
            Optional<Match> match = match(
                    victim, direct, causing, directUnavailable, causingUnavailable, damageTypeId, spellSource);
            if (match.isEmpty()) return Optional.empty();
            if (match.get().signal() == Signal.REFUSED_FRAME) {
                // Lead ruling 1: unproven, credit withheld; not RETALIATION, and the push's SCOPE_LEAK is not
                // counted again. The one marked value, so the bridge drops the player's identities (fold 02).
                OwnActionCreditGuard.count(OwnActionCreditGuard.Reason.PROOF_FAILURE);
                return Optional.of(WITHHELD_UNDER_REFUSED_FRAME);
            }
            if (FakePlayerAttackGuard.isRealPlayer(match.get().wearer())) {
                OwnActionCreditGuard.count(OwnActionCreditGuard.Reason.RETALIATION);
            }
            return Optional.of(EntityKillAttribution.retaliation(match.get().detail()));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            OwnActionCreditGuard.count(OwnActionCreditGuard.Reason.PROOF_FAILURE);
            // Item 2 F2: the marked value, so the bridge drops the player's identities and the weapon.
            return Optional.of(WITHHELD_AFTER_CHECK_FAILURE);
        }
    }

    /**
     * Whether {@code attribution} is one of {@link #resolveDeath}'s two withheld results whose row stores no player
     * identity and no weapon: a real player's death under a refused VICTIM-frame push (lead ruling 1 of fold 02), or a
     * death whose check threw (item 2 F2). Those two values, compared by identity; every other UNSUPPORTED_SECONDARY
     * result (an unadmitted spell, say) is another value and keeps today's row parts. Package-private for the bridge.
     */
    static boolean withheldUnderRefusedFrame(EntityKillAttribution attribution) {
        return attribution == WITHHELD_UNDER_REFUSED_FRAME || attribution == WITHHELD_AFTER_CHECK_FAILURE;
    }

    /**
     * The three signals in order (2.2), then the refused VICTIM-frame marker for a real player (lead ruling 1); empty
     * inside a command scope. Package-private for the tests.
     */
    static Optional<Match> match(
            LivingEntity victim,
            Entity direct,
            Entity causing,
            boolean directUnavailable,
            boolean causingUnavailable,
            ResourceLocation damageTypeId,
            BooleanSupplier spellSource) {
        if (CommandCreditGuard.active()) return Optional.empty();
        if (causingUnavailable || causing == null) return Optional.empty();
        // A death whose causing entity is the victim credits nobody: every signal keeps today's path (NIT 5).
        if (causing == victim) return Optional.empty();

        // Signal 1: the VICTIM frame's owner, any damage type, any owner (a zombie in Thorns armour too).
        ScopeStack frames = VICTIM_FRAMES.get();
        Entry frame = frames.find(causing);
        if (frame != null) {
            return Optional.of(new Match(Signal.VICTIM_FRAME, causing, frame.detail()));
        }

        // Signal 2: Thorns-type damage naming one real player as both entities, used whenever signal 1 did not match.
        if (!directUnavailable
                && direct == causing
                && THORNS.equals(damageTypeId)
                && FakePlayerAttackGuard.isRealPlayer(causing)) {
            return Optional.of(new Match(Signal.DAMAGE_TYPE, causing, null));
        }

        // Signal 3: P on this thread's being-hurt stack, narrowed for deaths (re-check N2). P's own death keeps
        // today's path (the check above).
        if (!directUnavailable && FakePlayerAttackGuard.isRealPlayer(causing)) {
            ScopeStack hurt = BEING_HURT.get();
            boolean onStack = hurt.find(causing) != null || hurt.refused > 0;
            if (onStack && hitBackShape(direct, causing, spellSource)) {
                return Optional.of(new Match(Signal.BEING_HURT, causing, null));
            }
        }

        // A refused VICTIM-frame push (its refusal marker): a real player's credit under it is withheld, never given
        // (3.2.5 rule 5). The marker cannot prove a hit-back, so it is asked last, only for a real player's death, as
        // REFUSED_FRAME (lead ruling 1); every other killer falls through to today's rules.
        if (frames.refused > 0 && FakePlayerAttackGuard.isRealPlayer(causing)) {
            return Optional.of(new Match(Signal.REFUSED_FRAME, causing, null));
        }
        return Optional.empty();
    }

    /**
     * Signal 3's direct-entity test for a death (re-check N2): P himself, nothing, or a projectile owned by P that is
     * not proven his own. Never a pet or summon, an Iron's or Ars source, or any other entity: those fall through to
     * the rules after this one.
     */
    private static boolean hitBackShape(Entity direct, Entity player, BooleanSupplier spellSource) {
        if (spellSource != null && spellSource.getAsBoolean()) return false;
        if (direct == null || direct == player) return true;
        if (direct instanceof OwnableEntity || direct instanceof TamableAnimal) return false;
        // NIT 6: a projectile owned by P; one owned by anyone else, or by nobody, falls through.
        return direct instanceof Projectile projectile
                && projectile.getOwner() == player
                && !NeoForgeProjectileActionAuditBridge.wasLaunchedByPlayerAction(direct, player.getUUID());
    }

    /**
     * The {@code ServerTickEvent.Pre} sweep of this thread's stacks (3.2.5 rule 4): any stack not empty, or with its
     * refusal marker up, is cleared and counted SCOPE_LEAK once.
     */
    static void sweepThisThread() {
        VICTIM_FRAMES.get().sweep();
        BEING_HURT.get().sweep();
    }

    /** Test view: this thread's open entries on each stack. */
    static int openVictimFrames() {
        return VICTIM_FRAMES.get().entries.size();
    }

    static int openBeingHurtEntries() {
        return BEING_HURT.get().entries.size();
    }

    static boolean onBeingHurtStack(Entity entity) {
        return BEING_HURT.get().find(entity) != null;
    }

    /** Test seam: empties both stacks on this thread without counting. */
    static void clearForTests() {
        VICTIM_FRAMES.get().clear();
        BEING_HURT.get().clear();
    }

    /** The player's server, or null when it cannot be read (the push still happens, unstamped). */
    private static MinecraftServer serverOf(ServerPlayer player) {
        try {
            return player.getServer();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static long tick(MinecraftServer server) {
        try {
            return server == null ? UNKNOWN_TICK : server.getTickCount();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return UNKNOWN_TICK;
        }
    }

    /**
     * The acting-entity depth stamp (3.2.5 rule 3). The acting-entity stack (ActingEntityStack) lands with D and rule
     * K, which read this stamp; until then no entity's turn is tracked and the depth is 0.
     */
    private static int actingEntityDepth() {
        return 0;
    }

    /** One open entry; close it in the {@code finally} of the frame that opened it. */
    public static final class Entry implements AutoCloseable {
        private final ScopeStack stack;
        private final Entity entity;
        private final String effectId;
        private final String effectName;
        private final long tick;
        private final int actingDepth;
        private final boolean refused;
        private final long generation;
        private boolean closed;

        private Entry(ScopeStack stack, Entity entity, String effectId, String effectName, long tick,
                      int actingDepth, boolean refused, long generation) {
            this.stack = stack;
            this.entity = entity;
            this.effectId = effectId;
            this.effectName = effectName;
            this.tick = tick;
            this.actingDepth = actingDepth;
            this.refused = refused;
            this.generation = generation;
        }

        /** Pops this entry by identity; never throws into the wrapped method's result or exception. */
        @Override
        public void close() {
            try {
                stack.close(this);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Closing never interrupts the game.
            }
        }

        long tick() {
            return tick;
        }

        int actingDepth() {
            return actingDepth;
        }

        boolean refused() {
            return refused;
        }

        private EntityKillAttribution.RetaliationDetail detail() {
            return effectId == null && effectName == null
                    ? null
                    : new EntityKillAttribution.RetaliationDetail(effectId, effectName);
        }
    }

    /** One per-thread stack with its refusal marker. Only its own thread touches it. */
    private static final class ScopeStack {
        private final String name;
        private final ArrayDeque<Entry> entries = new ArrayDeque<>();
        private int refused;
        /** Bumped by every clear, so a refused entry from before a clear never lowers a later marker. */
        private long generation;

        private ScopeStack(String name) {
            this.name = name;
        }

        Entry push(Entity entity, String effectId, String effectName, long tick) {
            Entry top = entries.peekFirst();
            if (top != null && tick != UNKNOWN_TICK && top.tick != UNKNOWN_TICK && top.tick != tick) {
                // Rule 3: a stale top means an entry leaked in an earlier tick.
                clear();
                OwnActionCreditGuard.scopeLeak(name);
            }
            if (entries.size() >= CAP) {
                // Rule 5: refused; its marker withholds for the code under it until its own finally.
                refused++;
                OwnActionCreditGuard.scopeLeak(name);
                return new Entry(this, entity, effectId, effectName, tick, actingEntityDepth(), true, generation);
            }
            Entry entry = new Entry(this, entity, effectId, effectName, tick, actingEntityDepth(), false, generation);
            entries.addFirst(entry);
            return entry;
        }

        void close(Entry entry) {
            if (entry.closed) return;
            entry.closed = true;
            if (entry.refused) {
                if (entry.generation == generation && refused > 0) refused--;
                return;
            }
            if (entries.peekFirst() == entry) {
                entries.removeFirst();
                return;
            }
            // Rule 2: out of order means an entry leaked. An entry a sweep already cleared was counted then.
            for (Entry open : entries) {
                if (open == entry) {
                    clear();
                    OwnActionCreditGuard.scopeLeak(name);
                    return;
                }
            }
        }

        Entry find(Entity entity) {
            for (Entry open : entries) {
                if (open.entity == entity) return open;
            }
            return null;
        }

        void sweep() {
            if (entries.isEmpty() && refused == 0) return;
            clear();
            OwnActionCreditGuard.scopeLeak(name);
        }

        void clear() {
            for (Entry open : entries) open.closed = true;
            entries.clear();
            refused = 0;
            generation++;
        }
    }
}
