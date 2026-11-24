package io.github.betapetey.eventtrail.adapters.hooks;

import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide outcome of the optional-mod hook check, per adapter key.
 *
 * <p>A hook that the pre-apply check skips stops the hook-based recording of
 * every family that owns it until the JVM restarts (Owner risk 3): hooks are
 * applied once per process, so a stopped family stays stopped across server
 * restarts in the same JVM. The adapter itself stays registered, so its menus
 * never fall to generic tracking.</p>
 *
 * <p>The read API ({@link #familyRunning}, {@link #stopOf},
 * {@link #stoppedFamilies}) is frozen at B0; the fail-safe's family gate reads
 * it. The hook batch owns how verdicts are computed and recorded. State is one
 * immutable map replaced copy-on-write, so every read is one volatile read.</p>
 */
public final class HookVerdicts {
    static final int MAX_TEXT = 160;
    private static final AtomicReference<Map<AdapterKey, Stop>> STOPPED =
            new AtomicReference<>(Map.of());
    private static final AtomicReference<VariantChoice> INTEGRATED =
            new AtomicReference<>(VariantChoice.UNSET);
    private static final AtomicBoolean HOOK_LOADER_DECIDED = new AtomicBoolean();

    private HookVerdicts() {
    }

    /** Why a family's hook-based recording stopped: the first skipped hook wins. */
    public record Stop(AdapterKey key, String hook, String reason) {
        public Stop {
            Objects.requireNonNull(key, "key");
            hook = EventTrailDiagnostics.sanitizeLogValue(hook, MAX_TEXT);
            reason = EventTrailDiagnostics.sanitizeLogValue(reason, MAX_TEXT);
        }
    }

    /**
     * Whether the hook-based recording of {@code key}'s family may run. A null
     * key is never running, so a caller without a key records nothing.
     */
    public static boolean familyRunning(AdapterKey key) {
        return key != null && !STOPPED.get().containsKey(key);
    }

    /** The stop recorded for {@code key}, if its family's hooks are stopped. */
    public static Optional<Stop> stopOf(AdapterKey key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(STOPPED.get().get(key));
    }

    /** Every stopped family, in key order. */
    public static Map<AdapterKey, Stop> stoppedFamilies() {
        Map<AdapterKey, Stop> current = STOPPED.get();
        if (current.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new EnumMap<>(current));
    }

    /**
     * Records that {@code hook} was skipped and stops {@code key}'s family until
     * restart. The first stop of a key is kept; later stops of the same key are
     * ignored. Returns whether this call stopped the family.
     *
     * <p>Not frozen at B0: the hook batch H owns this write path and may change
     * it. In B0 it is the starting point for H and the test seam of the public
     * guard path.</p>
     */
    public static boolean stopFamily(AdapterKey key, String hook, String reason) {
        Objects.requireNonNull(key, "key");
        Stop stop = new Stop(key, hook, reason);
        while (true) {
            Map<AdapterKey, Stop> current = STOPPED.get();
            if (current.containsKey(key)) return false;
            EnumMap<AdapterKey, Stop> updated = new EnumMap<>(AdapterKey.class);
            updated.putAll(current);
            updated.put(key, stop);
            if (STOPPED.compareAndSet(current, Map.copyOf(updated))) return true;
        }
    }

    /**
     * Which Integrated Terminals hook variant passed the check (work map R1c: the Integrated adapter picks its path
     * from this, not from a version). The current variant wins when both pass.
     */
    public enum IntegratedVariant {
        /** Integrated Terminals is not installed, neither variant passed the check, or the check has not run. */
        NONE,
        /** The legacy variant's hooks passed the check and are handed to Mixin. */
        LEGACY,
        /** The current variant's hooks passed the check and are handed to Mixin. */
        CURRENT
    }

    /** The recorded variant, and whether the hook loader recorded it (the server-start side never replaces that). */
    private record VariantChoice(IntegratedVariant variant, boolean byHookLoader) {
        static final VariantChoice UNSET = new VariantChoice(IntegratedVariant.NONE, false);
    }

    /** The Integrated Terminals variant the hook check chose; {@link IntegratedVariant#NONE} until it has run. */
    public static IntegratedVariant integratedVariant() {
        return INTEGRATED.get().variant();
    }

    /**
     * Records the Integrated Terminals variant the hook loader chose: the variant whose hooks it handed to Mixin. Not
     * frozen at B0: H's write path. The server-start side never replaces it
     * ({@link #recordIntegratedVariantAtServerStart}).
     */
    public static void recordIntegratedVariant(IntegratedVariant variant) {
        INTEGRATED.set(new VariantChoice(Objects.requireNonNull(variant, "variant"), true));
    }

    /**
     * Records the Integrated Terminals variant the server-start recomputation chose, unless the hook loader already
     * recorded one: the hooks in the game are the ones the hook loader handed to Mixin, so its choice is kept.
     *
     * @return whether the recorded variant now equals {@code variant} (false: the hook loader chose another one)
     */
    public static boolean recordIntegratedVariantAtServerStart(IntegratedVariant variant) {
        VariantChoice wanted = new VariantChoice(Objects.requireNonNull(variant, "variant"), false);
        while (true) {
            VariantChoice current = INTEGRATED.get();
            if (current.byHookLoader()) return current.variant() == variant;
            if (INTEGRATED.compareAndSet(current, wanted)) return true;
        }
    }

    /** Marks that the hook loader's production admission ran in the process that holds this class. */
    public static void markHookLoaderDecided() {
        HOOK_LOADER_DECIDED.set(true);
    }

    /**
     * Whether the hook loader's production admission ran against this same class. Mixin asks about every listed
     * optional-mod hook in every launch, so false at server start means the hook loader wrote into another
     * {@code HookVerdicts} (a separate class instance), and its stops never reached the runtime.
     */
    public static boolean hookLoaderDecided() {
        return HOOK_LOADER_DECIDED.get();
    }

    /** Test seam: verdicts are process-wide, so tests restore the empty state. */
    static void resetForTests() {
        STOPPED.set(Map.of());
        INTEGRATED.set(VariantChoice.UNSET);
        HOOK_LOADER_DECIDED.set(false);
    }
}
