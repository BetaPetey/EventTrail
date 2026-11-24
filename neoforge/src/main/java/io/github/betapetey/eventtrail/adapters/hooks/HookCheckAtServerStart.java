package io.github.betapetey.eventtrail.adapters.hooks;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.StringJoiner;

/**
 * Recomputes the optional-mod hook check when a server is about to start, before any adapter or recording starts
 * (work map R4-wire point 3, estimate A). By reading, the hook loader shares this class's {@link HookVerdicts}: Mixin
 * loads the hook loader through the thread context class loader, which ModLauncher sets to the game-layer class
 * loader that also loads EventTrail. The first line written here says whether that held in this launch. Either way,
 * recomputing the same pure check here puts every family stop into the runtime's {@link HookVerdicts}; a stop the
 * hook loader had not recorded writes one warning. The recomputation uses its own class source and drops it when
 * done. The summary line ends with the Integrated Terminals hook variant in force ({@code NONE}, {@code LEGACY} or
 * {@code CURRENT}), so the first launch log names it.
 */
@EventBusSubscriber(modid = EventTrailIds.MOD_ID)
public final class HookCheckAtServerStart {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);

    private HookCheckAtServerStart() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        try {
            LOGGER.info("[EVENTTRAIL_COMPATIBILITY] Hook-loader verdicts visible at server start: {} (hook verdicts"
                    + " loaded by {})", HookVerdicts.hookLoaderDecided() ? "yes" : "no",
                    String.valueOf(HookVerdicts.class.getClassLoader()));
            Map<String, HookAdmission.Verdict> verdicts = HookAdmission.recomputeAtServerStart(
                    message -> LOGGER.warn("{}", message), message -> LOGGER.error("{}", message));
            int passed = 0;
            int skipped = 0;
            for (HookAdmission.Verdict verdict : verdicts.values()) {
                if (verdict.decision() == HookAdmission.Decision.APPLY) passed++;
                if (verdict.decision() == HookAdmission.Decision.SKIPPED) skipped++;
            }
            if (passed == 0 && skipped == 0) return;
            StringJoiner stopped = new StringJoiner(", ");
            for (AdapterKey key : HookVerdicts.stoppedFamilies().keySet()) stopped.add(key.displayName());
            LOGGER.info("[EVENTTRAIL_COMPATIBILITY] Optional-mod hooks at server start: {} passed the check, {} did"
                    + " not; hook-based recording stopped for: {}; Integrated Terminals hook variant: {}", passed,
                    skipped, stopped.length() == 0 ? "none" : stopped.toString(), HookVerdicts.integratedVariant());
        } catch (Throwable failure) {
            // The recomputation stops families itself when it fails; startup is never blocked.
            LOGGER.error("[EVENTTRAIL_COMPATIBILITY] The optional-mod hook check at server start failed ({})",
                    failure.getClass().getSimpleName());
        }
    }
}
