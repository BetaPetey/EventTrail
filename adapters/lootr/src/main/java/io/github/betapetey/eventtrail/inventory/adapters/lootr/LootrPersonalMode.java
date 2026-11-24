package io.github.betapetey.eventtrail.inventory.adapters.lootr;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.FailureClassifier;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Exact personal-storage contracts; team storage is deliberately excluded.
 *
 * <p>The behaviour path is chosen by what is present (build B R1d/Row A; no version is read). Any build that cannot
 * be placed, or whose team mode cannot be read, is OFF: never the always-personal path. Row A evidence (class tables
 * read from the jars; NOTES-f1-3c):</p>
 * <ul>
 *   <li>{@code LootrAPI.isTeamLoot()} is absent from Lootr 1.21.1-1.11.38.124 and public static {@code ()Z} in .126
 *       and .127;</li>
 *   <li>{@code common.api.team.ITeamResolver} is absent from .124 and present in .126 and .127;</li>
 *   <li>{@code LootrSavedData} declares its own {@code addActualOpener(UUID)} override in .124 only. In all three
 *       builds the UUID opener is also a public default of the interface {@code IOpeners}, which .126 and .127
 *       {@code LootrSavedData} inherit while declaring only the {@code Player} overload;</li>
 *   <li>{@code LootrAPI.getSyncedConfig()} is present in .127 only.</li>
 * </ul>
 */
final class LootrPersonalMode {
    static final String API = "noobanidus.mods.lootr.common.api.LootrAPI";
    static final String TEAM_RESOLVER = "noobanidus.mods.lootr.common.api.team.ITeamResolver";
    static final String SAVED_DATA = "noobanidus.mods.lootr.common.data.LootrSavedData";
    /** Concept marker: the team-mode switch the team-API path itself reads. */
    static final String TEAM_API_MARKER = "LootrAPI.isTeamLoot()";
    /** Older-path guard: the team-mode class family of the team-mode generation; it must be absent. */
    static final String TEAM_GUARD = "common.api.team.ITeamResolver";
    /** Older-path marker: LootrSavedData's own per-player-UUID opener override (the generation before team mode). */
    static final String PRE_TEAM_MARKER = "LootrSavedData's own addActualOpener(UUID)";
    /** Label marker inside the team-API path only: picks the provider id, never the path or ON/OFF. */
    static final String LABEL_MARKER = "LootrAPI.getSyncedConfig()";
    /** Provider ids are row data: one fixed id per path (and per label), byte for byte as before build B. */
    static final String NO_TEAM_API_ID = "lootr-1.11.38.124";
    static final String TEAM_API_ID = "lootr-1.11.38.126";
    static final String TEAM_API_SYNCED_CONFIG_ID = "lootr-1.11.38.127";
    /** Never in a row: an OFF path matches nothing it could record. Keeps the loader's id prefix. */
    static final String OFF_ID = "lootr-off";
    static final String PATH_SITE = "adapter:LootrMenuAdapter.path";
    static final String CONCEPT_LOOKED_FOR = TEAM_API_MARKER + " (team-mode Lootr) or " + PRE_TEAM_MARKER
            + " with LootrAPI present and no " + TEAM_GUARD + " (Lootr before team mode)";
    static final String API_MISSING_LOOKED_FOR = "LootrAPI (not found, so " + TEAM_API_MARKER + " cannot be checked)";
    static final String UNUSABLE_LOOKED_FOR = TEAM_API_MARKER
            + " as a declared public static zero-argument boolean of a public LootrAPI";
    static final String TEAM_WITHOUT_SWITCH_LOOKED_FOR = TEAM_API_MARKER + " (not found although " + TEAM_GUARD
            + " is present: team mode without a usable switch)";
    static final String PROBE_FAILED_PREFIX = "the Lootr presence probe failed: ";
    static final String VM_ERROR_LOOKED_FOR = PROBE_FAILED_PREFIX
            + "VirtualMachineError (not cached; probed again when the loader next builds the adapter)";
    private static final int MAX_MESSAGE = 200;
    private static final ApiLookup INSTALLED_API =
            () -> Class.forName(API, false, LootrPersonalMode.class.getClassLoader());
    private static final ApiLookup INSTALLED_TEAM_RESOLVER =
            () -> Class.forName(TEAM_RESOLVER, false, LootrPersonalMode.class.getClassLoader());
    private static final ApiLookup INSTALLED_SAVED_DATA =
            () -> Class.forName(SAVED_DATA, false, LootrPersonalMode.class.getClassLoader());
    /** Built ahead so the VirtualMachineError branch allocates nothing. */
    private static final LootrPersonalMode VM_ERROR_MODE =
            new LootrPersonalMode(Path.CONCEPT_UNUSABLE, null, OFF_ID, VM_ERROR_LOOKED_FOR, false);
    private static volatile LootrPersonalMode installed;

    /** Only TEAM_API and NO_TEAM_API record; the other two switch the key OFF at first use with one warning. */
    enum Path {
        /** {@code isTeamLoot} is declared with the exact contract: personal only while team loot is off. */
        TEAM_API,
        /** LootrAPI present without {@code isTeamLoot}, no team class family, and the pre-team override: always personal. */
        NO_TEAM_API,
        /** The team concept is present but its switch is unusable, or the probe threw: never NO_TEAM_API. */
        CONCEPT_UNUSABLE,
        /** The build cannot be placed (including LootrAPI not found). */
        CONCEPT_NOT_FOUND;

        boolean records() {
            return this == TEAM_API || this == NO_TEAM_API;
        }
    }

    /** A path and, for an OFF path, what was looked for (the warning detail). */
    record Selection(Path path, String lookedFor) {
    }

    private final Path path;
    private final Method teamLoot;
    private final String adapterId;
    private final String lookedFor;
    private final boolean cacheable;

    private LootrPersonalMode(Path path, Method teamLoot, String adapterId, String lookedFor, boolean cacheable) {
        this.path = path;
        this.teamLoot = teamLoot;
        this.adapterId = adapterId;
        this.lookedFor = lookedFor;
        this.cacheable = cacheable;
    }

    /**
     * The installed Lootr's mode, probed when the loader builds the adapter (start-up; silent, never reported) and
     * kept for the JVM. Never throws. A VirtualMachineError result is not kept, so the next build probes again.
     */
    static LootrPersonalMode installed() {
        LootrPersonalMode current = installed;
        if (current != null) return current;
        current = probeOrUnusable(INSTALLED_API, INSTALLED_TEAM_RESOLVER, INSTALLED_SAVED_DATA);
        if (current.cacheable) installed = current;
        return current;
    }

    /**
     * {@link #probe} inside the fail-safe's catch-all: a probe that throws is present-but-unusable (OFF). The warning
     * names the failure; a VirtualMachineError is OFF for this build only (not cacheable).
     */
    static LootrPersonalMode probeOrUnusable(ApiLookup api, ApiLookup teamResolver, ApiLookup savedData) {
        try {
            return probe(api, teamResolver, savedData);
        } catch (VirtualMachineError transientFailure) {
            return VM_ERROR_MODE;
        } catch (Throwable unprobed) {
            return new LootrPersonalMode(Path.CONCEPT_UNUSABLE, null, OFF_ID, probeFailed(unprobed), true);
        }
    }

    /**
     * Presence probe (lookups only: no class initialisation, no call, never reported). Only a genuine not-found
     * ({@code ClassNotFoundException}, {@code NoSuchMethodException}) counts as absent; any other failure propagates
     * to {@link #probeOrUnusable}. The older-path guard and marker are looked up only when the concept is absent,
     * and the label marker only on the team-API path.
     */
    static LootrPersonalMode probe(ApiLookup api, ApiLookup teamResolver, ApiLookup savedData) {
        Class<?> apiClass = loadOrNull(api);
        boolean concept = apiClass != null && declaredInHierarchy(apiClass, "isTeamLoot");
        Method[] exact = new Method[1];
        Selection selection = select(apiClass != null, concept, () -> (exact[0] = exactTeamLoot(apiClass)) != null,
                () -> loadOrNull(teamResolver) != null, () -> preTeamOverrideDeclared(savedData));
        Path path = selection.path();
        if (path != Path.TEAM_API) return new LootrPersonalMode(path, null, adapterId(path, false), selection.lookedFor(), true);
        // The label lookup reads the same declared-method table the concept lookup has just resolved.
        return new LootrPersonalMode(path, exact[0], adapterId(path, declaredZeroArgument(apiClass, "getSyncedConfig")),
                null, true);
    }

    /**
     * Pure path rule. A present concept never leads to the always-personal path, and neither does anything that is
     * not positively the pre-team generation: LootrAPI missing, or the team class family present, is OFF. Suppliers
     * are read lazily, in order.
     */
    static Selection select(boolean apiPresent, boolean conceptMarker, BooleanSupplier exactContractHolds,
            BooleanSupplier teamGuardPresent, BooleanSupplier preTeamMarker) {
        if (conceptMarker) {
            return exactContractHolds.getAsBoolean() ? new Selection(Path.TEAM_API, null)
                    : new Selection(Path.CONCEPT_UNUSABLE, UNUSABLE_LOOKED_FOR);
        }
        if (!apiPresent) return new Selection(Path.CONCEPT_NOT_FOUND, API_MISSING_LOOKED_FOR);
        if (teamGuardPresent.getAsBoolean()) return new Selection(Path.CONCEPT_UNUSABLE, TEAM_WITHOUT_SWITCH_LOOKED_FOR);
        return preTeamMarker.getAsBoolean() ? new Selection(Path.NO_TEAM_API, null)
                : new Selection(Path.CONCEPT_NOT_FOUND, CONCEPT_LOOKED_FOR);
    }

    /** One fixed id per path; the label marker is read only on the team-API path and changes nothing else. */
    static String adapterId(Path path, boolean labelMarker) {
        return switch (path) {
            case TEAM_API -> labelMarker ? TEAM_API_SYNCED_CONFIG_ID : TEAM_API_ID;
            case NO_TEAM_API -> NO_TEAM_API_ID;
            case CONCEPT_UNUSABLE, CONCEPT_NOT_FOUND -> OFF_ID;
        };
    }

    /** The OFF warning detail for a probe that threw: its class, message and, if missing, the member (classifier). */
    static String probeFailed(Throwable failure) {
        try {
            StringBuilder text = new StringBuilder(PROBE_FAILED_PREFIX).append(failure.getClass().getName());
            String message = failure.getMessage();
            if (message != null) {
                text.append(": ").append(message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) + "..." : message);
            }
            FailureClassifier.Classification sorted = FailureClassifier.classify(failure);
            if (sorted.missing() && sorted.member() != null) text.append(" (missing: ").append(sorted.member()).append(')');
            return text.toString();
        } catch (Throwable unreadable) {
            return PROBE_FAILED_PREFIX + "an unreadable failure";
        }
    }

    Path path() {
        return path;
    }

    String adapterId() {
        return adapterId;
    }

    /** The OFF warning detail; null on a recording path. */
    String lookedFor() {
        return lookedFor;
    }

    boolean cacheable() {
        return cacheable;
    }

    /** True on a recording path; otherwise switches LOOTR OFF (idempotent, one warning naming what was looked for). */
    boolean recordingPath() {
        if (path.records()) return true;
        AdapterFailSafe.conceptNotFound(AdapterKey.LOOTR, PATH_SITE, lookedFor);
        return false;
    }

    boolean isPersonal() {
        if (path == Path.NO_TEAM_API) return true;
        // An OFF path is blocked by LootrMenuAdapter.match() first; withhold here as well.
        if (path != Path.TEAM_API) return false;
        try {
            // Read every time: a configuration reload invalidates existing backing/access guards.
            return Boolean.FALSE.equals(teamLoot.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // The caller's fail-safe sorts this as the mod's own failure: a counted skip; LOOTR stays on, and Lootr
            // keeps ownership by still claiming its menus.
            throw new IllegalStateException("Lootr personal-mode observation unavailable", failure);
        }
    }

    private static Class<?> loadOrNull(ApiLookup lookup) {
        try {
            return lookup.load();
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    /** A method of that name is declared by the class or a superclass (any shape: the shape is the contract). */
    private static boolean declaredInHierarchy(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)) return true;
            }
        }
        return false;
    }

    /** The exact team-mode contract (the same checks as before build B), or null when it does not hold. */
    private static Method exactTeamLoot(Class<?> api) {
        Method method;
        try {
            method = api.getDeclaredMethod("isTeamLoot");
        } catch (NoSuchMethodException unusable) {
            return null;
        }
        return Modifier.isPublic(api.getModifiers()) && Modifier.isPublic(method.getModifiers())
                && Modifier.isStatic(method.getModifiers()) && method.getReturnType() == boolean.class ? method : null;
    }

    /**
     * LootrSavedData's OWN {@code addActualOpener(UUID)} returning {@code boolean}. {@code getDeclaredMethod} is
     * load-bearing: {@code getMethod} would also find the {@code IOpeners} interface default that .126 and .127
     * inherit, and so would read those team-mode builds as pre-team.
     */
    private static boolean preTeamOverrideDeclared(ApiLookup savedData) {
        Class<?> type = loadOrNull(savedData);
        if (type == null) return false;
        try {
            return type.getDeclaredMethod("addActualOpener", UUID.class).getReturnType() == boolean.class;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private static boolean declaredZeroArgument(Class<?> type, String name) {
        try {
            type.getDeclaredMethod(name);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    @FunctionalInterface
    interface ApiLookup {
        Class<?> load() throws ClassNotFoundException;
    }
}
