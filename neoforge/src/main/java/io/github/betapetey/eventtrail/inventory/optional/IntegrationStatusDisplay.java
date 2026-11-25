package io.github.betapetey.eventtrail.inventory.optional;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.hooks.HookVerdicts;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility.ContractState;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility.IntegrationState;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility.Snapshot;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * GF-WARNINGS (B49): the status command's line for one optional integration, showing what really records.
 *
 * <p>Display only. The line folds in facts the published entry does not carry: a stopped hook family
 * ({@link HookVerdicts}), an adapter built without a supported behaviour path (its id ends with
 * {@code -off}) or a key with no adapter yet ({@link #NO_ADAPTER_YET}), an addon beside a base that is not in use, the Sable location provider that could not be
 * loaded, and the actions skipped since server start. None of this is written back into the published
 * snapshot, {@link OptionalMenuAdapterLoader#compatibilitySnapshot()} or the capture gate
 * {@link OptionalMenuAdapterLoader#isRuntimeAdapterDisabled(String)}: a menu that still records keeps
 * recording. With no such fact the line is exactly {@link IntegrationState#statusSummary()}.</p>
 */
public final class IntegrationStatusDisplay {
    private IntegrationStatusDisplay() {
    }

    /**
     * GF-WARNINGS r2 (MAJOR-1): the MENU keys that keep recording their other menu actions while their hook family
     * is stopped. Sophisticated Backpacks: its hooks are the two Core payload hooks only (bulk and full-slot moves,
     * {@code BackpackMenuActions}); every other click of its menus goes through the core click path, which its family
     * does not gate.
     */
    static final Set<AdapterKey> PARTLY_WHEN_HOOKS_STOP = Collections.unmodifiableSet(
            EnumSet.of(AdapterKey.SOPHISTICATED_BACKPACKS));

    /**
     * GF-WARNINGS r2 (MAJOR-1): the MENU keys that record nothing in their menus while their hook family is stopped.
     * The authoritative adapters (AE2, Refined Storage, Integrated Terminals, RFTools Storage, Pocket Storage,
     * Occultism) record only through their hooks; the bound adapters keep a binding only while
     * {@link AdapterFailSafe#active(AdapterKey)} holds (Occultism, Ender Storage, RFTools scanner, Create mounted,
     * Storage in Motion). A MENU key in neither set reads "hooks stopped" with no claim about its other actions.
     */
    static final Set<AdapterKey> OFF_WHEN_HOOKS_STOP = Collections.unmodifiableSet(EnumSet.of(
            AdapterKey.AE2, AdapterKey.REFINED_STORAGE, AdapterKey.INTEGRATED_TERMINALS, AdapterKey.OCCULTISM,
            AdapterKey.RFTOOLS_STORAGE, AdapterKey.POCKET_STORAGE, AdapterKey.CREATE, AdapterKey.ENDER_STORAGE,
            AdapterKey.STORAGE_IN_MOTION));

    /**
     * Graves L-0 (D-482, D-483): the bridge keys that have a key and a folder but no adapter yet. Their published entry
     * is ON while the mod is installed (presence only, as every bridge key's), but nothing is recorded for them, so the
     * line reads as an off-path adapter's: "off (no supported behaviour path was found; nothing is recorded)". A key
     * leaves this set when its adapter lands.
     */
    static final Set<AdapterKey> NO_ADAPTER_YET = Collections.unmodifiableSet(EnumSet.of(
            AdapterKey.TOMBSTONE, AdapterKey.SIMPLE_TOMB, AdapterKey.CORPSE, AdapterKey.YIGD, AdapterKey.EPITAPHS,
            AdapterKey.MINECOLONIES));

    /**
     * The display facts of one entry. {@code stop} is null when its hook family runs; {@code baseStop} is the stop
     * of the base family of an addon (null when there is no base or its family runs).
     */
    record Facts(boolean offPath, HookVerdicts.Stop stop, boolean baseOff, boolean locationProviderUnavailable,
                 long skipped, HookVerdicts.Stop baseStop) {
        static final Facts NONE = new Facts(false, null, false, false, 0L, null);

        Facts(boolean offPath, HookVerdicts.Stop stop, boolean baseOff, boolean locationProviderUnavailable,
              long skipped) {
            this(offPath, stop, baseOff, locationProviderUnavailable, skipped, null);
        }
    }

    /** The live line of {@code state} in {@code snapshot}; never throws (falls back to the published line). */
    public static String line(IntegrationState state, Snapshot snapshot, boolean locationProviderUnavailable) {
        if (state == null) return "";
        try {
            return line(state, facts(state, snapshot, locationProviderUnavailable));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return state.statusSummary();
        }
    }

    static Facts facts(IntegrationState state, Snapshot snapshot, boolean locationProviderUnavailable) {
        AdapterKey key = state.key();
        if (key == null) return Facts.NONE;
        AdapterKey base = baseOf(key);
        return new Facts(
                OptionalMenuAdapterLoader.offPath(key) || NO_ADAPTER_YET.contains(key),
                HookVerdicts.stopOf(key).orElse(null),
                baseOff(key, snapshot),
                locationProviderUnavailable,
                AdapterFailSafe.skippedActions(key),
                base == null ? null : HookVerdicts.stopOf(base).orElse(null));
    }

    /** The base key of an addon that records only through its base, or null. */
    static AdapterKey baseOf(AdapterKey key) {
        if (key == AdapterKey.AE2WTLIB) return AdapterKey.AE2;
        if (key == AdapterKey.SOPHISTICATED_STORAGE_CREATE) return AdapterKey.CREATE;
        return null;
    }

    private static boolean baseOff(AdapterKey key, Snapshot snapshot) {
        AdapterKey base = baseOf(key);
        if (base == null) return false;
        if (AdapterFailSafe.switchedOff(base)) return true;
        IntegrationState baseState = snapshot == null ? null : snapshot.find(base);
        return baseState != null && baseState.contractState() != ContractState.ABSENT && !baseState.inUse();
    }

    /** The pure rule, in order: off path, base off, location provider not loaded, hooks stopped; then skip counts. */
    static String line(IntegrationState state, Facts facts) {
        String published = state.statusSummary();
        if (state.contractState() == ContractState.ABSENT) return published;
        Facts known = facts == null ? Facts.NONE : facts;
        String line = published;
        if (state.inUse()) {
            String name = safe(state.displayName(), 64);
            String version = safe(state.detectedVersion(), 64);
            AdapterKey key = state.key();
            if (known.offPath()) {
                line = name + ": off " + version + " (no supported behaviour path was found; nothing is recorded)";
            } else if (known.baseOff() && baseOf(key) != null) {
                line = name + ": off (base off) " + version + " (" + baseOf(key).displayName()
                        + " is not in use, so nothing is recorded through it)";
            } else if (known.baseStop() != null && baseOf(key) != null) {
                line = name + ": off (base off) " + version + " (hook " + safe(known.baseStop().hook(), 96) + " of "
                        + baseOf(key).displayName() + " stopped: " + safe(known.baseStop().reason(), 96)
                        + "; nothing is recorded through it)";
            } else if (known.locationProviderUnavailable() && key != null && key.kind() == AdapterKey.Kind.LOCATION) {
                line = name + ": off " + version
                        + " (the location provider could not be loaded; every block position is withheld)";
            } else if (known.stop() != null && key != null && PARTLY_WHEN_HOOKS_STOP.contains(key)) {
                line = name + ": partly " + version + " (hook " + safe(known.stop().hook(), 96) + " stopped: "
                        + safe(known.stop().reason(), 96) + "; the actions made through its hooks are not recorded;"
                        + " its other menu actions are still recorded)";
            } else if (known.stop() != null && key != null && OFF_WHEN_HOOKS_STOP.contains(key)) {
                line = name + ": off " + version + " (hook " + safe(known.stop().hook(), 96) + " stopped: "
                        + safe(known.stop().reason(), 96) + "; nothing is recorded in its menus)";
            } else if (known.stop() != null && key != null && key.kind() == AdapterKey.Kind.MENU) {
                line = name + ": hooks stopped " + version + " (hook " + safe(known.stop().hook(), 96) + " stopped: "
                        + safe(known.stop().reason(), 96) + "; the menu actions that need its hooks are not recorded)";
            } else if (known.stop() != null) {
                line = name + ": off " + version + " (hook " + safe(known.stop().hook(), 96) + " stopped: "
                        + safe(known.stop().reason(), 96) + "; nothing is recorded through its hooks)";
            }
        }
        if (known.skipped() > 0L) {
            line = line + "; " + known.skipped() + " action(s) not recorded since server start";
        }
        return line;
    }

    private static String safe(String value, int maxLength) {
        return EventTrailDiagnostics.sanitizeLogValue(value, maxLength).replace('§', '?');
    }
}
