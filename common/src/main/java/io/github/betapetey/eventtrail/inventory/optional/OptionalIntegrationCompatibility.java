package io.github.betapetey.eventtrail.inventory.optional;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Immutable operator-facing compatibility state for EventTrail's optional
 * inventory integrations. Warning emission is deliberately separate from the
 * snapshot so read-only consumers such as {@code /eventtrail status} have no log
 * side effects.
 */
public final class OptionalIntegrationCompatibility {
    public static final String LOG_MARKER = "[EVENTTRAIL_COMPATIBILITY]";
    static final int MAX_PROCESS_WARNING_KEYS = 64;
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final WarningRegistry PROCESS_WARNINGS =
            new WarningRegistry(MAX_PROCESS_WARNING_KEYS);

    private OptionalIntegrationCompatibility() {
    }

    /**
     * Names frozen (other code compiles against them). ON is VALIDATED with a specialized adapter and no warning;
     * every other installed state is OFF. UNVALIDATED is no longer produced, because no version is compared.
     */
    public enum ContractState {
        ABSENT,
        VALIDATED,
        UNVALIDATED,
        INCOMPATIBLE
    }

    public enum RuntimeDisposition {
        NOT_APPLICABLE,
        SPECIALIZED,
        GENERIC_FALLBACK,
        DISABLED
    }

    /**
     * Why an adapter is OFF. No reason names a version: adapters are used by presence (mod id), and no version is
     * compared.
     */
    public enum WarningReason {
        ADAPTER_UNAVAILABLE,
        RUNTIME_ADAPTER_FAILURE,
        /**
         * A class, method or field the adapter uses is missing: OFF until restart. A state with this
         * reason is built with {@code RuntimeDisposition.DISABLED}, as
         * {@code IntegrationState.runtimeFailure()} does, never through
         * {@code OptionalMenuAdapterLoader.exceptionalState}, which grants a generic fallback to
         * specifications that allow one; the warning text promises no weaker fallback.
         */
        MISSING_MEMBER,
        /**
         * None of the members that mark a supported behaviour path was found: OFF until restart. Built
         * with a disabled runtime disposition, as {@link #MISSING_MEMBER} is.
         */
        CONCEPT_NOT_FOUND
    }

    /**
     * The status entry of one adapter key.
     *
     * <p>{@code key} is the entry's identity in a {@link Snapshot}: two keys can
     * share a mod id ({@code ARS_CAST}/{@code ARS_ITEM}, {@code SABLE_RECOGNIZER}/
     * {@code SABLE_FRAME}), so status is never looked up or replaced by mod id
     * alone. {@code key} is null only for a mod id that no {@link AdapterKey}
     * names (a test fixture or an unknown mod); a mod id that some key names
     * must carry that key.</p>
     */
    public record IntegrationState(
            AdapterKey key,
            String modId,
            String displayName,
            String detectedVersion,
            String family,
            String validatedContract,
            ContractState contractState,
            RuntimeDisposition disposition,
            WarningReason warningReason) {
        public IntegrationState {
            modId = Objects.requireNonNull(modId, "modId");
            if (key != null && !key.modId().equals(modId)) {
                throw new IllegalArgumentException("Adapter key " + key + " does not belong to mod " + modId);
            }
            if (key == null && namedByAnyKey(modId)) {
                throw new IllegalArgumentException("Integration state for mod " + modId + " needs its adapter key");
            }
            displayName = Objects.requireNonNull(displayName, "displayName");
            detectedVersion = detectedVersion == null ? "" : detectedVersion;
            family = Objects.requireNonNull(family, "family");
            validatedContract = Objects.requireNonNull(validatedContract, "validatedContract");
            contractState = Objects.requireNonNull(contractState, "contractState");
            disposition = Objects.requireNonNull(disposition, "disposition");
            if (contractState == ContractState.ABSENT && disposition != RuntimeDisposition.NOT_APPLICABLE) {
                throw new IllegalArgumentException("Absent integration must be not applicable");
            }
            if (contractState == ContractState.VALIDATED && disposition != RuntimeDisposition.SPECIALIZED) {
                throw new IllegalArgumentException("Validated integration must use its specialized adapter");
            }
            if ((contractState == ContractState.ABSENT || contractState == ContractState.VALIDATED)
                    && warningReason != null) {
                throw new IllegalArgumentException("Normal integration state cannot carry a warning reason");
            }
            if (contractState != ContractState.ABSENT
                    && contractState != ContractState.VALIDATED
                    && warningReason == null) {
                throw new IllegalArgumentException("Exceptional integration state requires a warning reason");
            }
        }

        /**
         * A state keyed by the menu key of {@code modId} ({@link AdapterKey#forMenuModId}), or unkeyed when
         * no key names that mod. A bridge or location mod id has no menu key, so this constructor rejects
         * it: those states are built with their key.
         */
        public IntegrationState(
                String modId,
                String displayName,
                String detectedVersion,
                String family,
                String validatedContract,
                ContractState contractState,
                RuntimeDisposition disposition,
                WarningReason warningReason) {
            this(menuKey(modId), modId, displayName, detectedVersion, family, validatedContract,
                    contractState, disposition, warningReason);
        }

        /**
         * The frozen ON check: the specialized adapter is in use (VALIDATED, SPECIALIZED, no warning
         * reason). Every other state is OFF and carries a warning reason, except ABSENT.
         */
        public boolean inUse() {
            return contractState == ContractState.VALIDATED
                    && disposition == RuntimeDisposition.SPECIALIZED
                    && warningReason == null;
        }

        boolean requiresWarning() {
            return warningReason != null;
        }

        WarningKey warningKey() {
            if (!requiresWarning()) {
                throw new IllegalStateException("Integration state has no warning");
            }
            return new WarningKey(key, modId, detectedVersion, family, warningReason);
        }

        /** Same entry of a snapshot: the same key, or for unkeyed states the same mod id. */
        boolean sameEntry(IntegrationState other) {
            return other != null && key == other.key && modId.equals(other.modId);
        }

        /**
         * One status line. ON ({@link #inUse()}): {@code <name>: in use <version> (specialized adapter)}. OFF:
         * {@code <name>: off <version> (<behaviour>)}, or {@code <name>: off, mod absent (<behaviour>)} for a key
         * switched off while its mod is not installed (a hook family of another mod failed). Not installed and not
         * off: {@code <name>: absent}. No line says "validated" or "unvalidated": no version is checked.
         *
         * <p>The line describes this entry only. An addon built beside an OFF base still reads "in use" while it
         * records nothing: AE2WTLib beside an OFF AE2 (the AE2 blocker claims every WCTMenu) and the mounted addon
         * beside a Create key switched off in the fail-safe (its bindings need that key active). This is the
         * no-cascade rule applied at start-up; the base's own line shows the OFF.
         */
        public String statusSummary() {
            String name = safe(displayName, 64);
            if (contractState == ContractState.ABSENT) {
                return name + ": absent";
            }

            String version = safe(detectedVersion, 64);
            if (inUse()) {
                return name + ": in use " + version + " (specialized adapter)";
            }

            String behavior = disposition == RuntimeDisposition.GENERIC_FALLBACK
                    ? "generic fallback for independently authoritative menus; specialized hierarchy disabled"
                    : "integration disabled";
            // A blank version is the only "mod not installed" signal an IntegrationState carries; an installed mod
            // whose version cannot be read shows "<unreadable>", not a blank.
            if (detectedVersion.isBlank()) {
                return name + ": off, mod absent (" + behavior + ")";
            }
            return name + ": off " + version + " (" + behavior + ")";
        }

        IntegrationState runtimeFailure() {
            return new IntegrationState(
                    key,
                    modId,
                    displayName,
                    detectedVersion,
                    family,
                    validatedContract,
                    ContractState.INCOMPATIBLE,
                    RuntimeDisposition.DISABLED,
                    WarningReason.RUNTIME_ADAPTER_FAILURE);
        }
    }

    /** Published status: at most one entry per adapter key (unkeyed entries may repeat a mod id). */
    public record Snapshot(List<IntegrationState> integrations) {
        public Snapshot {
            integrations = List.copyOf(integrations);
            Set<AdapterKey> keys = EnumSet.noneOf(AdapterKey.class);
            for (IntegrationState state : integrations) {
                if (state.key() != null && !keys.add(state.key())) {
                    throw new IllegalArgumentException("Two integration states for adapter key " + state.key());
                }
            }
        }

        /** The entry of {@code key}, or null. */
        public IntegrationState find(AdapterKey key) {
            if (key == null) return null;
            for (IntegrationState state : integrations) {
                if (state.key() == key) return state;
            }
            return null;
        }

        /**
         * The entry of the menu key of {@code modId}. A mod id that only bridge or location keys name gives
         * null, because two such keys can share it ({@code ars_nouveau}, {@code sable}); this stays so now that
         * the published snapshot also carries those keys' entries, so a lookup by mod id never picks one of two
         * keys by list order: use {@link #find(AdapterKey)}. A mod id that no key names gives its first unkeyed
         * entry. GF-ADAPTERSPECS: a mod id with several menu keys (several loader specifications) gives null too
         * ({@link AdapterKey#forMenuModId}); status and lookups go by key.
         */
        public IntegrationState find(String modId) {
            if (modId == null) return null;
            AdapterKey menu = AdapterKey.forMenuModId(modId).orElse(null);
            if (menu != null) return find(menu);
            if (namedByAnyKey(modId)) return null;
            for (IntegrationState state : integrations) {
                if (state.key() == null && state.modId().equals(modId)) return state;
            }
            return null;
        }

        /**
         * The frozen ON check of {@code key}; false when the key has no entry. Status and harness only;
         * recording decisions read {@code AdapterFailSafe.active}.
         */
        public boolean inUse(AdapterKey key) {
            IntegrationState state = find(key);
            return state != null && state.inUse();
        }

        /**
         * Replaces the entry of the replacement's key, or appends it. Entries of other keys, including keys
         * of the same mod, are unchanged. Unkeyed: every unkeyed entry of its mod id is replaced, as before.
         */
        Snapshot replace(IntegrationState replacement) {
            Objects.requireNonNull(replacement, "replacement");
            List<IntegrationState> updated = new ArrayList<>(integrations.size());
            boolean replaced = false;
            for (IntegrationState state : integrations) {
                if (state.sameEntry(replacement)) {
                    updated.add(replacement);
                    replaced = true;
                } else {
                    updated.add(state);
                }
            }
            if (!replaced) updated.add(replacement);
            return new Snapshot(updated);
        }
    }

    static int emitProcessWarnings(Snapshot snapshot) {
        return emitWarnings(snapshot, PROCESS_WARNINGS, LOGGER::warn);
    }

    static int emitWarnings(
            Snapshot snapshot,
            WarningRegistry registry,
            Consumer<String> warningSink) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(warningSink, "warningSink");
        int emitted = 0;
        for (IntegrationState state : snapshot.integrations()) {
            if (!state.requiresWarning() || !registry.markIfNew(state.warningKey())) continue;
            warningSink.accept(warningMessage(state));
            emitted++;
        }
        return emitted;
    }

    static String warningMessage(IntegrationState state) {
        String detected = safe(state.modId(), 64) + " " + safe(state.detectedVersion(), 64);
        String behavior;
        if (state.warningReason() == WarningReason.RUNTIME_ADAPTER_FAILURE) {
            behavior = "The specialized integration is disabled after a runtime adapter failure; "
                    + "generic fallback was not activated for the failed menu because equivalent "
                    + "authoritative evidence could not be proven.";
        } else if (state.warningReason() == WarningReason.MISSING_MEMBER) {
            behavior = "The specialized integration is off until the server restarts because a part of the mod "
                    + "that EventTrail's adapter uses is missing; no weaker fallback was activated.";
        } else if (state.warningReason() == WarningReason.CONCEPT_NOT_FOUND) {
            behavior = "The specialized integration is off until the server restarts because the behaviour it "
                    + "records was not found in the installed mod; no weaker fallback was activated.";
        } else if (state.disposition() == RuntimeDisposition.GENERIC_FALLBACK) {
            behavior = "The specialized menu hierarchy is disabled; bounded generic tracking remains active only "
                    + "for menus with independent authoritative container evidence.";
        } else {
            behavior = "No authoritative generic path exists, so this integration is disabled and fails closed.";
        }
        return LOG_MARKER + " Detected " + detected
                + " for EventTrail " + safe(state.family(), 64)
                + "; the specialized adapter is off"
                + " (reason=" + state.warningReason().name() + "). "
                + behavior + " Tested with: " + safe(state.validatedContract(), 160) + ".";
    }

    private static String safe(String value, int maxLength) {
        return EventTrailDiagnostics.sanitizeLogValue(value, maxLength).replace('§', '?');
    }

    private static AdapterKey menuKey(String modId) {
        return modId == null ? null : AdapterKey.forMenuModId(modId).orElse(null);
    }

    private static boolean namedByAnyKey(String modId) {
        for (AdapterKey key : AdapterKey.values()) {
            if (key.modId().equals(modId)) return true;
        }
        return false;
    }

    /** Warning identity; the key keeps two keys of one mod from sharing a warning. */
    record WarningKey(
            AdapterKey key,
            String modId,
            String detectedVersion,
            String family,
            WarningReason reason) {
    }

    static final class WarningRegistry {
        private final int capacity;
        private final Set<WarningKey> seen = new LinkedHashSet<>();

        WarningRegistry(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
        }

        synchronized boolean markIfNew(WarningKey key) {
            if (seen.contains(key)) return false;
            if (seen.size() >= capacity) return false;
            seen.add(key);
            return true;
        }

        synchronized int size() {
            return seen.size();
        }
    }
}
