package io.github.betapetey.eventtrail.events.platform;

import io.github.betapetey.eventtrail.events.location.AuditBlockLocations;
import io.github.betapetey.eventtrail.events.location.sable.SableAuditLocationProvider;
import net.neoforged.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;

/**
 * Optional loader discovery remains outside Minecraft-only evidence/location types.
 *
 * <p>Build B checks no Sable version and no Sable class bytes. With Sable absent every address is stationary. With
 * Sable present the Sable provider is always installed; which addresses it records is decided when each address is
 * captured, by the {@code SABLE_RECOGNIZER} and {@code SABLE_FRAME} fail-safe keys and the provider's own checks
 * (see {@link SableAuditLocationProvider}). Only when the provider itself cannot be loaded is every address withheld
 * from the start, with one warning.</p>
 */
public final class NeoForgeAuditLocations {
    private NeoForgeAuditLocations() { }
    public static void initialize() {
        if (!sableInstalled()) {
            AuditBlockLocations.configure(AuditBlockLocations::stationary);
            return;
        }
        try {
            AuditBlockLocations.configure(new SableAuditLocationProvider());
        } catch (RuntimeException | LinkageError failure) {
            // GF-WARNINGS (B47/B49): the named fail-closed provider, so each withheld address names this cause and the
            // status command shows the Sable keys off.
            AuditBlockLocations.configure(AuditBlockLocations.unavailableProvider());
            LogManager.getLogger("eventtrail").warn(
                    "Sable physical audit locations unavailable: the Sable location provider could not be loaded ({});"
                            + " every block position is withheld until restart", failure.getClass().getSimpleName());
        }
    }

    /** Whether FML lists a loaded mod with the id {@code sable}; false when FML has no mod list (unit tests). */
    private static boolean sableInstalled() {
        LoadingModList mods = LoadingModList.get();
        return mods != null && mods.getMods().stream().anyMatch(mod -> "sable".equals(mod.getModId()));
    }
}
