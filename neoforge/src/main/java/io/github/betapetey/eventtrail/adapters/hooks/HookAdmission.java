package io.github.betapetey.eventtrail.adapters.hooks;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.AdapterKeyPresence;
import io.github.betapetey.eventtrail.adapters.hooks.HookCatalog.Entry;
import io.github.betapetey.eventtrail.adapters.hooks.HookCatalog.Group;
import io.github.betapetey.eventtrail.adapters.hooks.check.ClassSource;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookCheckResult;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookReader;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookTargetChecker;
import io.github.betapetey.eventtrail.adapters.hooks.check.ModFileClassSource;
import io.github.betapetey.eventtrail.adapters.hooks.check.UnsupportedHookException;
import net.neoforged.fml.loading.LoadingModList;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Decides, for each optional-mod hook class, whether the hook loader hands it to Mixin (work map R4-wire).
 *
 * <p>A hook is handed to Mixin only when the mod of one of its keys ({@link HookCatalog}) is installed and the hook
 * passed the pre-apply check ({@link HookTargetChecker}) on every target it declares. A hook whose mods are all absent
 * is left out quietly. A key whose feature is one package of its mod counts as installed only while its mod's file
 * holds that package ({@link AdapterKeyPresence}; Sophisticated part 4 core r2: the linked-groups key on a Core 1.4.x
 * with no linked-storage package is left out quietly, as an absent mod is). Any other hook that is left out is "skipped": exactly one warning names it and says it did not
 * pass the check (never that anything was or was not "applied"), and the hook-based recording of each of its keys
 * whose mod is installed stops until restart ({@link HookVerdicts#stopFamily}, Owner risk 3). A denial-only hook
 * (the Ars damage watchers and the denial hook, record-less path D2) stops nothing: its warning names the lost
 * protection instead. A family stop does not silence the denial-only hooks that were handed to Mixin: they call only
 * {@code HookGuard.wrapDenial} and {@code HookGuard.observeDenial}, whose bodies still run while their key
 * ({@code ARS_CAST}) is stopped or OFF (h review-02 MAJOR-1 (a); the hook scan test holds them to those entries).</p>
 *
 * <p>Exclusive groups ({@link Group}) are decided as a whole, the first time any of their hooks is asked about:</p>
 * <ul>
 *   <li>Integrated Terminals: the current variant (its own hooks plus the shared ones) wins if it passes; else the
 *       legacy variant if it passes. The variant not chosen is left out quietly. If neither passes, every hook of the
 *       group is skipped. The choice is recorded for the Integrated adapter ({@link HookVerdicts#integratedVariant},
 *       work map R1c).</li>
 *   <li>Ars Nouveau: if every positive hook passes, the positive group is applied and the denial hook is left out
 *       quietly. Otherwise the whole positive group is skipped (which stops {@code ARS_CAST}) and the denial hook is
 *       handed to Mixin if it passes its own check (brief 4.6 Q10 (a)). The denial hook's body, and the bodies of the
 *       watchers that passed, still run while {@code ARS_CAST} is stopped or OFF, through
 *       {@code HookGuard.wrapDenial} and {@code HookGuard.observeDenial}.</li>
 * </ul>
 *
 * <p><b>Target already loaded (design r3 7.4), decided here:</b> the hook loader also asks Mixin's class tracker
 * whether a target class is already loaded. Mixin never applies a hook to a class loaded before the hook list was
 * prepared: a required list stops the server before the loader is asked, a non-required list only logs "loaded too
 * early" and then asks the loader. So a loaded target makes the hook skipped (one warning, families stopped), the
 * same as a failed check. The check itself stays free of Mixin (JDK and ASM only); the tracker is one of the
 * {@link Inputs}. The server-start recomputation does not ask the tracker (every target is loaded by then), so for
 * this one case the runtime side relies on the stop the hook loader recorded; see {@link #recomputeAtServerStart}.</p>
 *
 * <p>Never throws to its caller: any failure while deciding a hook makes that hook skipped. The class source is held
 * only while hooks are being decided: {@link #release()} drops it (and its cache of parsed classes).</p>
 *
 * <p>Links only to the JDK, ASM (through the check), FML and EventTrail classes that do the same, so the hook loader
 * can use it.</p>
 */
public final class HookAdmission {
    /**
     * The hook list's {@code injectors.defaultRequire} for the adapter hooks: 0, the value of the non-required
     * {@code eventtrail-adapters.mixins.json} that holds them (work map R4-nonfatal; pinned to that file by
     * {@code EventTrailMixinPluginTest} and to {@code ADAPTERS_LIST_MOVED} by {@code HookCatalogTest}).
     */
    public static final int ADAPTERS_DEFAULT_REQUIRE = 0;
    /** The internal-name prefix of the mixin package. */
    public static final String HOOK_PACKAGE_PATH = "io/github/betapetey/eventtrail/mixin/";
    static final String OWN_MOD_ID = "eventtrail";
    static final String TAG = "[EVENTTRAIL_COMPATIBILITY] ";
    /** The hook name of an Integrated Terminals stop caused by a variant the two sides chose differently. */
    static final String INTEGRATED_VARIANT_HOOK = "integrated (hook variant)";

    /** What happens to one hook class. */
    public enum Decision {
        /** Passed the check (and won its group, if any): handed to Mixin. */
        APPLY,
        /** None of its mods is installed: left out, no warning. */
        NOT_INSTALLED,
        /** Its group chose another alternative that passed: left out, no warning. */
        NOT_CHOSEN,
        /** Did not pass the check, or its group did not: left out with one warning; its families stop. */
        SKIPPED
    }

    /** The decision for one hook class, with why when it is not {@link Decision#APPLY}. */
    public record Verdict(String hookClass, Decision decision, String reason) {
        public Verdict {
            Objects.requireNonNull(hookClass, "hookClass");
            Objects.requireNonNull(decision, "decision");
            reason = reason == null ? "" : reason;
        }
    }

    /** What the decision reads from the running game. */
    public interface Inputs {
        /** Whether a mod with this id is installed. */
        boolean modInstalled(String modId);

        /** The bytes of a hook class (relative to the mixin package) from EventTrail's own file, or null. */
        byte[] hookBytes(String hookClass) throws Exception;

        /** Whether the target class (internal name) is already loaded, so Mixin can no longer apply to it. */
        boolean targetAlreadyLoaded(String target) throws Exception;

        /**
         * Whether the file of the mod {@code modId} holds {@code resource}: TRUE, FALSE, or null when that cannot be
         * read (the default). Only a definite FALSE for a key's package marker makes that key not installed
         * ({@link AdapterKeyPresence}).
         */
        default Boolean modFileHolds(String modId, String resource) throws Exception {
            return null;
        }
    }

    /** Where warnings, stops and the Integrated Terminals variant go. */
    public interface Sink {
        void warn(String message);

        void stop(AdapterKey key, String hookClass, String reason);

        /** The Integrated Terminals variant chosen, once that group is decided. */
        default void integratedVariant(HookVerdicts.IntegratedVariant variant) {
        }
    }

    private final Map<String, Entry> catalog;
    private final Inputs inputs;
    private final Supplier<? extends ClassSource> sources;
    private final Sink sink;
    private final int defaultRequire;
    private final Map<String, Verdict> verdicts = new LinkedHashMap<>();
    private final Map<String, List<String>> declaredTargets = new HashMap<>();
    private final Set<String> warnedTargets = new HashSet<>();
    private ClassSource source;

    /**
     * @param catalog        the hook-to-key table (production: {@link HookCatalog#entries()})
     * @param inputs         installed mods, hook bytes and the class tracker
     * @param sources        makes the class source on first need, again after {@link #release()}
     * @param sink           warnings and family stops
     * @param defaultRequire the hook list's {@code defaultRequire}
     */
    public HookAdmission(List<Entry> catalog, Inputs inputs, Supplier<? extends ClassSource> sources, Sink sink,
            int defaultRequire) {
        Map<String, Entry> rows = new LinkedHashMap<>();
        for (Entry entry : catalog) rows.put(entry.hookClass(), entry);
        this.catalog = Collections.unmodifiableMap(rows);
        this.inputs = Objects.requireNonNull(inputs, "inputs");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.defaultRequire = defaultRequire;
    }

    /**
     * The production admission of the hook loader: installed mods, EventTrail's own file, the given class tracker;
     * warnings to {@code warnings}, family stops to {@link HookVerdicts}.
     */
    public static HookAdmission forHookLoader(TargetTracker tracker, Consumer<String> warnings) {
        return wired(Side.HOOK_LOADER, HookCatalog.entries(), new InstalledGame(tracker),
                () -> new ModFileClassSource(true), warnings);
    }

    /** Which side of the verdict hand-over a production admission serves. */
    enum Side {
        /** Inside Mixin, while the hook list is prepared: its choices are the hooks that end up in the game. */
        HOOK_LOADER,
        /** The recomputation when a server is about to start. */
        SERVER_START
    }

    /**
     * The production wiring of both sides (h review-02 MINOR-3): the hook list's {@link #ADAPTERS_DEFAULT_REQUIRE}, and
     * the sink into {@link HookVerdicts}. Only the table, the game and the class source come from the caller, so a
     * test runs the same sinks the hook loader and the server start use.
     *
     * <ul>
     *   <li>Hook loader: every warning goes to {@code warnings}; every stop and the Integrated Terminals variant go to
     *       {@link HookVerdicts}; it marks {@link HookVerdicts#markHookLoaderDecided()}.</li>
     *   <li>Server start: the per-hook warnings are dropped (the hook loader already wrote them); a stop that is new
     *       (the hook loader had not stopped that family) writes one warning to {@code warnings} naming the hook, the
     *       family and the reason (h review-02 MINOR-2). The Integrated Terminals variant never replaces the one the
     *       hook loader recorded: if the server start chose another, the variant is kept and Integrated Terminals
     *       stops, with that one warning (h review-02 MINOR-1). A server-start {@code NONE} is not compared: the
     *       group is skipped and its own stops name the first Integrated member in table order, which is not
     *       necessarily the failing hook (h-2 recheck-01 NIT-1).</li>
     * </ul>
     */
    static HookAdmission wired(Side side, List<Entry> table, Inputs inputs, Supplier<? extends ClassSource> sources,
            Consumer<String> warnings) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(warnings, "warnings");
        if (side == Side.HOOK_LOADER) {
            HookVerdicts.markHookLoaderDecided();
            Sink loader = new Sink() {
                @Override
                public void warn(String message) {
                    warnings.accept(message);
                }

                @Override
                public void stop(AdapterKey key, String hookClass, String reason) {
                    HookVerdicts.stopFamily(key, hookClass, reason);
                }

                @Override
                public void integratedVariant(HookVerdicts.IntegratedVariant variant) {
                    HookVerdicts.recordIntegratedVariant(variant);
                }
            };
            return new HookAdmission(table, inputs, sources, loader, ADAPTERS_DEFAULT_REQUIRE);
        }
        Sink serverStart = new Sink() {
            @Override
            public void warn(String message) {
                // The hook loader already wrote one warning per skipped hook; a new stop warns in stop().
            }

            @Override
            public void stop(AdapterKey key, String hookClass, String reason) {
                stopAndWarn(key, hookClass, reason, "the optional-mod hook " + hookClass + " " + reason);
            }

            @Override
            public void integratedVariant(HookVerdicts.IntegratedVariant variant) {
                // NONE is no variant to compare (h-2 recheck-01 NIT-1): either nothing is installed, or the group
                // is skipped and its own stops, which follow, name the first Integrated member in table order.
                if (variant == HookVerdicts.IntegratedVariant.NONE) {
                    HookVerdicts.recordIntegratedVariantAtServerStart(variant);
                    return;
                }
                if (HookVerdicts.recordIntegratedVariantAtServerStart(variant)) return;
                String reason = "the server-start check chose a different Integrated Terminals hook variant ("
                        + variant + ") than the hook loader (" + HookVerdicts.integratedVariant()
                        + "), whose choice is kept";
                stopAndWarn(AdapterKey.INTEGRATED_TERMINALS, INTEGRATED_VARIANT_HOOK, reason, reason);
            }

            /** Stops the family; only a new stop writes its one warning. */
            private void stopAndWarn(AdapterKey key, String hook, String reason, String why) {
                if (HookVerdicts.stopFamily(key, hook, reason)) {
                    warnings.accept(TAG + "At server start, hook-based recording stopped until restart for "
                            + key.displayName() + " (" + key.name() + "): " + why + ".");
                }
            }
        };
        return new HookAdmission(table, inputs, sources, serverStart, ADAPTERS_DEFAULT_REQUIRE);
    }

    /** Whether the class tracker says a target class (dotted name) is loaded. */
    @FunctionalInterface
    public interface TargetTracker {
        boolean isLoaded(String dottedName) throws Exception;
    }

    /**
     * Whether the hook loader hands {@code hookClass} (relative to the mixin package) to Mixin for
     * {@code target}. Never throws; false for a hook the table does not name (fail closed).
     */
    public synchronized boolean shouldApply(String hookClass, String target) {
        try {
            Verdict verdict = decide(hookClass);
            if (verdict.decision() != Decision.APPLY) return false;
            List<String> declared = declaredTargets.get(hookClass);
            String internal = target == null ? null : target.replace('.', '/');
            if (declared != null && internal != null && declared.contains(internal)) return true;
            if (warnedTargets.add(hookClass + " " + internal)) {
                warnQuietly(TAG + "EventTrail left the optional-mod hook " + hookClass + " out for "
                        + (internal == null ? "an unnamed target" : internal.replace('/', '.'))
                        + ": that target is not one the hook declares.");
            }
            return false;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Decides every hook of the table, in table order. Never throws. */
    public synchronized Map<String, Verdict> decideAll() {
        Map<String, Verdict> all = new LinkedHashMap<>();
        for (String hookClass : catalog.keySet()) all.put(hookClass, decide(hookClass));
        return Collections.unmodifiableMap(all);
    }

    /** Drops the class source and its cache; later decisions read again. Never throws. */
    public synchronized void release() {
        ClassSource held = source;
        source = null;
        if (held instanceof ModFileClassSource modFiles) {
            try {
                modFiles.clear();
            } catch (Throwable ignored) {
                // The reference is dropped either way.
            }
        }
    }

    /** Whether a class source is currently held; the hook loader's release obligation is checked through this. */
    public synchronized boolean holdsSource() {
        return source != null;
    }

    /**
     * The runtime side of the verdict hand-over (work map R4-wire point 3): recomputes the same check at server start
     * with its own class source, records every family stop in {@link HookVerdicts} (a stop the hook loader already
     * recorded is kept as it is; a new one writes one warning), then drops the source. The Integrated Terminals
     * variant the hook loader recorded is never replaced. It cannot see a target that was already loaded when the hook
     * list was prepared, so that case reaches the runtime only through the hook loader's own stop. Never throws.
     *
     * @param warnings one warning per family this side newly stops
     * @param errors   one error if the recomputation itself fails
     * @return the verdicts, or an empty map if the recomputation itself failed (then every family with an installed
     *         mod that owns a hook is stopped, the safe direction)
     */
    public static Map<String, Verdict> recomputeAtServerStart(Consumer<String> warnings, Consumer<String> errors) {
        HookAdmission admission = null;
        try {
            admission = wired(Side.SERVER_START, HookCatalog.entries(), new InstalledGame(name -> false),
                    () -> new ModFileClassSource(true), warnings);
            return admission.decideAll();
        } catch (Throwable failure) {
            stopEveryInstalledFamily(failure);
            try {
                errors.accept(TAG + "The optional-mod hook check at server start failed ("
                        + failure.getClass().getSimpleName() + "); hook-based recording stopped for every installed"
                        + " family until restart.");
            } catch (Throwable ignored) {
                // A failing logger never turns the recomputation into an exception.
            }
            return Map.of();
        } finally {
            if (admission != null) admission.release();
        }
    }

    private static void stopEveryInstalledFamily(Throwable failure) {
        try {
            String reason = "the server-start hook check failed: " + failure.getClass().getSimpleName();
            for (Entry entry : HookCatalog.entries()) {
                for (AdapterKey key : entry.keys()) {
                    if (installed(key)) HookVerdicts.stopFamily(key, entry.hookClass(), reason);
                }
            }
        } catch (Throwable ignored) {
            // Nothing more can be done safely here.
        }
    }

    /** Whether FML lists a loaded mod with this id. False when FML has no mod list (unit tests). */
    public static boolean installed(String modId) {
        LoadingModList mods = LoadingModList.get();
        if (mods == null || modId == null) return false;
        for (var mod : mods.getMods()) {
            if (modId.equals(mod.getModId())) return true;
        }
        return false;
    }

    /**
     * Whether a key is installed: its mod is installed and, for a key with a package marker, its mod's file holds
     * that package ({@link AdapterKeyPresence}; unreadable counts as installed).
     */
    public static boolean installed(AdapterKey key) {
        return key != null && installed(key.modId()) && !AdapterKeyPresence.lacksPackage(key, AdapterKeyPresence.FML);
    }

    // ------------------------------------------------------------------ deciding

    private Verdict decide(String hookClass) {
        Verdict known = verdicts.get(hookClass);
        if (known != null) return known;
        Entry entry = hookClass == null ? null : catalog.get(hookClass);
        if (entry == null) {
            Verdict unknown = new Verdict(String.valueOf(hookClass), Decision.SKIPPED,
                    "the hook-to-key table has no row for it");
            verdicts.put(unknown.hookClass(), unknown);
            warnQuietly(TAG + "EventTrail skipped the optional-mod hook " + unknown.hookClass()
                    + ": it did not pass the check (" + unknown.reason() + ").");
            return unknown;
        }
        Map<String, Verdict> decided = switch (entry.group()) {
            case NONE, ARS_WATCHER -> Map.of(entry.hookClass(), single(entry));
            case INTEGRATED_BOTH, INTEGRATED_LEGACY, INTEGRATED_CURRENT -> integrated();
            case ARS_POSITIVE, ARS_DENIAL -> ars();
        };
        for (Verdict each : decided.values()) remember(each);
        Verdict verdict = verdicts.get(hookClass);
        return verdict != null ? verdict : new Verdict(hookClass, Decision.SKIPPED, "no decision was made");
    }

    /** Keeps the first verdict of a hook; a skipped hook gets its one warning and its family stops here. */
    private void remember(Verdict verdict) {
        if (verdicts.containsKey(verdict.hookClass())) return;
        verdicts.put(verdict.hookClass(), verdict);
        if (verdict.decision() != Decision.SKIPPED) return;
        Entry entry = catalog.get(verdict.hookClass());
        if (entry != null && entry.group().denialOnly()) {
            warnQuietly(TAG + "EventTrail skipped the optional-mod hook " + verdict.hookClass()
                    + ": it did not pass the check (" + verdict.reason() + "). Until restart, vanilla last-hit"
                    + " credit for the damage it watches is no longer denied; no recording is stopped.");
            return;
        }
        List<String> stopped = new ArrayList<>();
        if (entry != null) {
            for (AdapterKey key : entry.keys()) {
                if (!keyInstalled(key)) continue;
                stopped.add(key.displayName());
                try {
                    sink.stop(key, verdict.hookClass(), "did not pass the check: " + verdict.reason());
                } catch (Throwable ignored) {
                    // A failing sink never turns a decision into an exception.
                }
            }
        }
        warnQuietly(TAG + "EventTrail skipped the optional-mod hook " + verdict.hookClass()
                + ": it did not pass the check (" + verdict.reason() + "). Hook-based recording stopped until"
                + " restart for: " + (stopped.isEmpty() ? "no installed family" : String.join(", ", stopped)) + ".");
    }

    private void warnQuietly(String message) {
        try {
            sink.warn(message);
        } catch (Throwable ignored) {
            // A failing logger never turns a decision into an exception.
        }
    }

    private void variantQuietly(HookVerdicts.IntegratedVariant variant) {
        try {
            sink.integratedVariant(variant);
        } catch (Throwable ignored) {
            // A failing sink never turns a decision into an exception.
        }
    }

    private Verdict single(Entry entry) {
        Own own = own(entry);
        return switch (own.state()) {
            case NOT_INSTALLED -> new Verdict(entry.hookClass(), Decision.NOT_INSTALLED, "");
            case PASSED -> new Verdict(entry.hookClass(), Decision.APPLY, "");
            case FAILED -> new Verdict(entry.hookClass(), Decision.SKIPPED, own.reason());
        };
    }

    private Map<String, Verdict> integrated() {
        List<Entry> members = members(Group.INTEGRATED_BOTH, Group.INTEGRATED_LEGACY, Group.INTEGRATED_CURRENT);
        Map<String, Own> owns = owns(members);
        Map<String, Verdict> decided = new LinkedHashMap<>();
        if (noneInstalled(owns)) {
            for (Entry member : members) {
                decided.put(member.hookClass(), new Verdict(member.hookClass(), Decision.NOT_INSTALLED, ""));
            }
            variantQuietly(HookVerdicts.IntegratedVariant.NONE);
            return decided;
        }
        boolean shared = allPassed(members, owns, Group.INTEGRATED_BOTH);
        boolean current = shared && allPassed(members, owns, Group.INTEGRATED_CURRENT);
        boolean legacy = shared && allPassed(members, owns, Group.INTEGRATED_LEGACY);
        variantQuietly(current ? HookVerdicts.IntegratedVariant.CURRENT
                : legacy ? HookVerdicts.IntegratedVariant.LEGACY : HookVerdicts.IntegratedVariant.NONE);
        for (Entry member : members) {
            Own own = owns.get(member.hookClass());
            Decision decision;
            String reason = "";
            if (current) {
                decision = member.group() == Group.INTEGRATED_LEGACY ? Decision.NOT_CHOSEN : Decision.APPLY;
                if (decision == Decision.NOT_CHOSEN) reason = "the current Integrated Terminals hooks passed the check";
            } else if (legacy) {
                decision = member.group() == Group.INTEGRATED_CURRENT ? Decision.NOT_CHOSEN : Decision.APPLY;
                if (decision == Decision.NOT_CHOSEN) reason = "the legacy Integrated Terminals hooks passed the check";
            } else {
                decision = Decision.SKIPPED;
                reason = own.state() == OwnState.FAILED ? own.reason()
                        : "neither the current nor the legacy Integrated Terminals hooks passed the check as a whole";
            }
            decided.put(member.hookClass(), new Verdict(member.hookClass(), decision, reason));
        }
        return decided;
    }

    private Map<String, Verdict> ars() {
        List<Entry> positives = members(Group.ARS_POSITIVE);
        List<Entry> denials = members(Group.ARS_DENIAL);
        List<Entry> all = new ArrayList<>(positives);
        all.addAll(denials);
        Map<String, Verdict> decided = new LinkedHashMap<>();
        Map<String, Own> positiveOwns = owns(positives);
        if (noneInstalled(positiveOwns) && noneInstalled(owns(denials))) {
            for (Entry member : all) {
                decided.put(member.hookClass(), new Verdict(member.hookClass(), Decision.NOT_INSTALLED, ""));
            }
            return decided;
        }
        boolean positive = !positives.isEmpty() && allPassed(positives, positiveOwns, Group.ARS_POSITIVE);
        for (Entry member : positives) {
            Own own = positiveOwns.get(member.hookClass());
            if (positive) {
                decided.put(member.hookClass(), new Verdict(member.hookClass(), Decision.APPLY, ""));
            } else {
                String reason = own.state() == OwnState.FAILED ? own.reason()
                        : "another hook of the Ars Nouveau spell-casting group did not pass the check";
                decided.put(member.hookClass(), new Verdict(member.hookClass(), Decision.SKIPPED, reason));
            }
        }
        for (Entry denial : denials) {
            if (positive) {
                decided.put(denial.hookClass(), new Verdict(denial.hookClass(), Decision.NOT_CHOSEN,
                        "the Ars Nouveau spell-casting hooks passed the check"));
            } else {
                decided.put(denial.hookClass(), single(denial));
            }
        }
        return decided;
    }

    private List<Entry> members(Group... groups) {
        Set<Group> wanted = Set.of(groups);
        List<Entry> members = new ArrayList<>();
        for (Entry entry : catalog.values()) {
            if (wanted.contains(entry.group())) members.add(entry);
        }
        return members;
    }

    private Map<String, Own> owns(List<Entry> members) {
        Map<String, Own> owns = new LinkedHashMap<>();
        for (Entry member : members) owns.put(member.hookClass(), own(member));
        return owns;
    }

    private static boolean noneInstalled(Map<String, Own> owns) {
        for (Own own : owns.values()) {
            if (own.state() != OwnState.NOT_INSTALLED) return false;
        }
        return true;
    }

    private static boolean allPassed(List<Entry> members, Map<String, Own> owns, Group group) {
        for (Entry member : members) {
            if (member.group() == group && owns.get(member.hookClass()).state() != OwnState.PASSED) return false;
        }
        return true;
    }

    private enum OwnState { NOT_INSTALLED, PASSED, FAILED }

    /** The outcome of one hook's own check over all its declared targets. */
    private record Own(OwnState state, String reason) {
    }

    private Own own(Entry entry) {
        try {
            boolean installed = false;
            for (AdapterKey key : entry.keys()) installed |= keyInstalled(key);
            if (!installed) return new Own(OwnState.NOT_INSTALLED, "");
            byte[] bytes;
            try {
                bytes = inputs.hookBytes(entry.hookClass());
            } catch (Throwable unreadable) {
                return failed("the hook class could not be read from EventTrail's own file: " + describe(unreadable));
            }
            if (bytes == null) return failed("the hook class was not found in EventTrail's own file");
            HookModel model;
            try {
                model = HookReader.read(bytes, defaultRequire);
            } catch (UnsupportedHookException notUnderstood) {
                return failed("the pre-check does not understand the hook: " + notUnderstood.getMessage());
            } catch (Throwable unreadable) {
                return failed("the hook class could not be read: " + describe(unreadable));
            }
            if (model.targets().isEmpty()) return failed("the hook declares no target");
            declaredTargets.put(entry.hookClass(), model.targets());
            for (String target : model.targets()) {
                boolean loaded;
                try {
                    loaded = inputs.targetAlreadyLoaded(target);
                } catch (Throwable unknown) {
                    return failed("whether " + target.replace('/', '.') + " is already loaded could not be read: "
                            + describe(unknown));
                }
                if (loaded) {
                    return failed("the target " + target.replace('/', '.')
                            + " was already loaded before the hook list was prepared");
                }
                HookCheckResult result = HookTargetChecker.check(model, target, source());
                if (!result.passed()) return failed(result.toString());
            }
            return new Own(OwnState.PASSED, "");
        } catch (Throwable failure) {
            return failed("the check failed: " + describe(failure));
        }
    }

    private static Own failed(String reason) {
        return new Own(OwnState.FAILED, reason);
    }

    private boolean modInstalled(String modId) {
        try {
            return inputs.modInstalled(modId);
        } catch (Throwable unknown) {
            return false;
        }
    }

    /** The key's mod is installed and, for a key with a package marker, its file holds it (unreadable: it does). */
    private boolean keyInstalled(AdapterKey key) {
        return modInstalled(key.modId()) && !AdapterKeyPresence.lacksPackage(key, inputs::modFileHolds);
    }

    private ClassSource source() {
        if (source == null) source = sources.get();
        return source;
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** The running game: FML's mod list, EventTrail's own mod file, and the given class tracker. */
    private static final class InstalledGame implements Inputs {
        private final TargetTracker tracker;

        InstalledGame(TargetTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public boolean modInstalled(String modId) {
            return installed(modId);
        }

        @Override
        public byte[] hookBytes(String hookClass) throws Exception {
            String resource = HOOK_PACKAGE_PATH + hookClass.replace('.', '/') + ".class";
            LoadingModList mods = LoadingModList.get();
            var own = mods == null ? null : mods.getModFileById(OWN_MOD_ID);
            if (own != null) {
                Path path = own.getFile().findResource(resource);
                if (path != null && Files.isRegularFile(path)) return Files.readAllBytes(path);
            }
            ClassLoader loader = HookAdmission.class.getClassLoader();
            if (loader == null) return null;
            try (InputStream input = loader.getResourceAsStream(resource)) {
                return input == null ? null : input.readAllBytes();
            }
        }

        @Override
        public boolean targetAlreadyLoaded(String target) throws Exception {
            return tracker != null && tracker.isLoaded(target.replace('/', '.'));
        }

        @Override
        public Boolean modFileHolds(String modId, String resource) throws Exception {
            return AdapterKeyPresence.FML.holds(modId, resource);
        }
    }
}
