package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.events.evidence.EntityKillAttribution;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Kill credit A (design-05c 4.1): the killer's recorded owner for a death row, shown only inside the killer's label.
 * Only where the game records an owner: the killing entity (the direct entity, or the causing entity when the direct
 * entity is a projectile) is a vanilla {@link OwnableEntity} with an owner UUID (Ars Nouveau's summons implement it,
 * {@code ISummon extends OwnableEntity} in 5.13.4), or an Iron's Spells summon whose owner the mod records in
 * {@code SummonManager}. Never from a damage source.
 *
 * <p>Resolution order (review m2): 1. the live owner in the entity's own level; 2. the server's player list, every
 * dimension, with the synthetic-identity rule; 3. the profile cache is never read; 4. UNRESOLVED, the UUID only. A
 * synthetic owner keeps its kind and is stored with its own UUID and name, never redacted to the Automated-player form
 * (design-05c 4.2, the Owner: no such redaction for owners of pets or for Thorns rows; lead ruling 3 of fold 01). This
 * helper never builds an actor record: an owner is never an actor (a contract test pins it). A failure gives no owner
 * and never reaches the death path.</p>
 */
public final class KillRelatedPartyCapture {
    static final String IRONS_MOD_ID = "irons_spellbooks";

    private static final List<SummonerReader> DEFAULT_READERS = List.of(KillRelatedPartyCapture::ironsSummoner);
    private static volatile List<SummonerReader> summonerReaders = DEFAULT_READERS;

    private KillRelatedPartyCapture() {
    }

    /** One mod's summoner reader: the owner that mod records for {@code killer}, or null when it records none. */
    @FunctionalInterface
    interface SummonerReader {
        RecordedOwner read(Entity killer) throws Exception;
    }

    /** An owner as a mod or the game records it: its UUID, and the live owner when the record names one. */
    record RecordedOwner(UUID uuid, Entity live) {
    }

    /** The server's player list as the owner resolution asks it (4.1 step 2): null with no server or no list. */
    static Function<UUID, ServerPlayer> players(MinecraftServer server) {
        return uuid -> server == null || server.getPlayerList() == null
                ? null
                : server.getPlayerList().getPlayer(uuid);
    }

    /**
     * The death bridge's owner attach, after attribution: {@code resolved} with the killer's recorded owner, for an
     * unaccountable result other than RETALIATION, outside a command scope and outside the Fix 4a or command
     * redaction ({@code redacted}). Otherwise {@code resolved} unchanged.
     */
    static EntityKillAttribution attach(
            EntityKillAttribution resolved,
            Entity direct,
            Entity causing,
            boolean redacted,
            Function<UUID, ServerPlayer> playerList) {
        try {
            if (resolved == null
                    || resolved.provenance().accountable()
                    || resolved.provenance() == EntityKillAttribution.ProvenanceKind.RETALIATION
                    || resolved.related() != null
                    || redacted
                    || CommandCreditGuard.active()) {
                return resolved;
            }
            Optional<EntityKillAttribution.RelatedParty> owner = owner(killingEntity(direct, causing), playerList);
            return owner.isPresent() ? resolved.withRelated(owner.get()) : resolved;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return resolved;
        }
    }

    /**
     * The entity whose owner is shown: the direct entity, or the causing entity when the direct one is a projectile.
     * The shared definition the death-row label uses too (lead ruling 2).
     */
    static Entity killingEntity(Entity direct, Entity causing) {
        return EntityKillAttribution.killingEntity(direct, causing, direct instanceof Projectile);
    }

    /** The recorded owner of {@code killer}, resolved in the 4.1 order; empty when none is recorded or on any failure. */
    static Optional<EntityKillAttribution.RelatedParty> owner(Entity killer, Function<UUID, ServerPlayer> playerList) {
        try {
            if (killer == null || killer instanceof Player) return Optional.empty();
            RecordedOwner recorded = recordedOwner(killer);
            if (recorded == null || recorded.uuid() == null) return Optional.empty();
            UUID uuid = recorded.uuid();
            Entity live = recorded.live();
            if (live != null && uuid.equals(live.getUUID())) {
                if (live instanceof Player player) return Optional.of(playerParty(player, playerList));
                if (live instanceof LivingEntity) {
                    return Optional.of(new EntityKillAttribution.RelatedParty(
                            EntityKillAttribution.RelatedParty.Kind.ENTITY, uuid, typeId(live), oneName(live)));
                }
            }
            ServerPlayer listed = playerList == null ? null : playerList.apply(uuid);
            if (listed != null) {
                return Optional.of(new EntityKillAttribution.RelatedParty(
                        NeoForgePlayerProvenance.isSynthetic(listed)
                                ? EntityKillAttribution.RelatedParty.Kind.SYNTHETIC_PLAYER
                                : EntityKillAttribution.RelatedParty.Kind.PLAYER,
                        uuid, null, playerName(listed)));
            }
            return Optional.of(new EntityKillAttribution.RelatedParty(
                    EntityKillAttribution.RelatedParty.Kind.UNRESOLVED, uuid, null, null));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return Optional.empty();
        }
    }

    /** Test seam: the mod summoner readers to ask ({@code null} restores the production list). */
    static void useSummonerReadersForTests(List<SummonerReader> readers) {
        summonerReaders = readers == null ? DEFAULT_READERS : List.copyOf(readers);
    }

    private static RecordedOwner recordedOwner(Entity killer) {
        if (killer instanceof OwnableEntity ownable) {
            UUID uuid = ownable.getOwnerUUID();
            if (uuid != null) return new RecordedOwner(uuid, ownable.getOwner());
        }
        for (SummonerReader reader : summonerReaders) {
            RecordedOwner owner;
            try {
                owner = reader.read(killer);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                // A mod reader that throws gives no owner (7.2 #21).
                owner = null;
            }
            if (owner != null && owner.uuid() != null) return owner;
        }
        return null;
    }

    /**
     * A live player owner: the B51 rule, without counting (the owner is never credited). A FakePlayer, or a player
     * that is not the player list's own object for its UUID, is SYNTHETIC_PLAYER, with its own UUID and name.
     */
    private static EntityKillAttribution.RelatedParty playerParty(
            Player player, Function<UUID, ServerPlayer> playerList) {
        boolean synthetic = NeoForgePlayerProvenance.isSynthetic(player);
        if (!synthetic) {
            ServerPlayer listed = playerList == null ? null : playerList.apply(player.getUUID());
            synthetic = listed != player;
        }
        return new EntityKillAttribution.RelatedParty(
                synthetic
                        ? EntityKillAttribution.RelatedParty.Kind.SYNTHETIC_PLAYER
                        : EntityKillAttribution.RelatedParty.Kind.PLAYER,
                player.getUUID(), null, playerName(player));
    }

    private static String playerName(Player player) {
        try {
            return player.getGameProfile().getName();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static String oneName(Entity entity) {
        try {
            return entity.getName().getString();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static String typeId(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "eventtrail:unregistered_entity_type" : id.toString();
    }

    /** The Iron's Spells reader, gated by the mod being loaded; its classes load only then. */
    private static RecordedOwner ironsSummoner(Entity killer) throws Exception {
        ModList mods = ModList.get();
        if (mods == null || !mods.isLoaded(IRONS_MOD_ID)) return null;
        return IronsSummoner.read(killer);
    }

    /**
     * Iron's Spells 3.16.3 (pre-build read, javap): every summon implements
     * {@code io.redspace.ironsspellbooks.entity.mobs.IMagicSummon}, whose owner is kept in
     * {@code SummonManager.INSTANCE.summonToOwner} (private {@code HashMap<UUID, UUID>}, summon UUID to owner UUID);
     * {@code SummonManager.getOwner(Entity)} returns the live owner from the summon's own level only.
     */
    private static final class IronsSummoner {
        private static volatile Field summonToOwner;
        private static volatile boolean fieldUnreadable;

        static RecordedOwner read(Entity killer) throws Exception {
            if (!(killer instanceof io.redspace.ironsspellbooks.entity.mobs.IMagicSummon)) return null;
            Entity live = io.redspace.ironsspellbooks.capabilities.magic.SummonManager.getOwner(killer);
            UUID uuid = recordedUuid(killer);
            if (uuid == null && live != null) uuid = live.getUUID();
            return uuid == null ? null : new RecordedOwner(uuid, live);
        }

        private static UUID recordedUuid(Entity killer) throws Exception {
            Field field = summonToOwner;
            if (field == null) {
                if (fieldUnreadable) return null;
                Field declared = io.redspace.ironsspellbooks.capabilities.magic.SummonManager.class
                        .getDeclaredField("summonToOwner");
                if (!declared.trySetAccessible()) {
                    fieldUnreadable = true;
                    return null;
                }
                summonToOwner = declared;
                field = declared;
            }
            Object map = field.get(io.redspace.ironsspellbooks.capabilities.magic.SummonManager.INSTANCE);
            if (!(map instanceof Map<?, ?> owners)) return null;
            Object owner = owners.get(killer.getUUID());
            return owner instanceof UUID uuid ? uuid : null;
        }
    }
}
