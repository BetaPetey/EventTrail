package io.github.betapetey.eventtrail.inventory.adapters.integrated;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.MissingAdapterMemberException;
import io.github.betapetey.eventtrail.adapters.hooks.HookVerdicts;
import org.cyclops.commoncapabilities.api.ingredient.storage.IIngredientComponentStorage;
import org.cyclops.integrateddynamics.core.network.IngredientChannelIndexed;

/** Only the two authenticated native call shapes; never recursively unwraps arbitrary storage. */
final class IntegratedStorageAuthority {
    static final String COUNTING = "org.cyclops.integratedterminals.core.terminalstorage.IngredientComponentStorageCounting";
    private IntegratedStorageAuthority() { }

    /** What the fail-safe's warning says was not found when no Integrated Terminals hook variant passed the check. */
    static final String NO_VARIANT = "an Integrated Terminals hook variant that passed the hook check";
    static final String NO_VARIANT_SITE = "adapter:IntegratedStorageAuthority.indexed";
    /** What the fail-safe's warning says was not found when the chosen variant does not fit the item handler's storage. */
    static final String MISFIT = "a channel storage of the shape the chosen Integrated Terminals hook variant uses";
    static final String MISFIT_SITE = "adapter:IntegratedStorageAuthority.transferChannel";
    /** The fail-safe's reason code for a misfit after an own call has already fitted in this server run (M-1). */
    static final String MISFIT_AFTER_FIT = "integrated-storage-misfit-after-fit";

    /**
     * The first-fit latch (Advisor M-1): an own call's storage has fitted the chosen variant since this server start.
     * Server thread only; volatile so that the reset at server start is seen. Started fresh by {@link #startRun()}.
     */
    private static volatile boolean fitted;

    /**
     * Starts the first-fit latch fresh for a new server run. The loader builds {@link IntegratedTerminalsMenuAdapter}
     * once per server start, right after {@code AdapterFailSafe.resetAtServerStart()}
     * ({@code UniversalContainerTracker.startRuntimeCompatibility}), so the adapter's constructor calls this and the
     * latch starts with the fresh OFF table.
     */
    static void startRun() { fitted = false; }

    /** Whether an own call's storage has fitted the chosen variant since the last {@link #startRun()}. */
    static boolean fittedThisRun() { return fitted; }

    /**
     * The channel behind {@code storage} under the variant the hook check chose
     * ({@link HookVerdicts#integratedVariant()}). With no variant chosen nothing is guessed: the family goes OFF
     * until restart with one warning, and no channel is returned.
     */
    static IngredientChannelIndexed<?, ?> indexed(IIngredientComponentStorage<?, ?> storage) {
        HookVerdicts.IntegratedVariant variant = HookVerdicts.integratedVariant();
        if (storage != null && variant == HookVerdicts.IntegratedVariant.NONE) {
            AdapterFailSafe.conceptNotFound(AdapterKey.INTEGRATED_TERMINALS, NO_VARIANT_SITE, NO_VARIANT);
        }
        return indexed(storage, variant);
    }

    /**
     * The channel behind the storage the item handler was given inside this adapter's own terminal action (the caller
     * has already matched the action's handler, menu and binding). On top of {@link #indexed(IIngredientComponentStorage)},
     * when a variant was chosen but its shape does not fit that storage (the first-fit latch, Advisor M-1):
     * <ul>
     *   <li>while no own call has fitted since server start, the variant has not proved itself on this build, and every
     *       transfer of the build may be dropped, so the family goes OFF until restart with one warning;</li>
     *   <li>once an own call has fitted, the variant is proved, and a misfit is taken as an untried terminal shape (for
     *       example channel -1, another channel, or a quick-move), so only that action is dropped: it is reported through
     *       {@link AdapterFailSafe#invalid} (counted, warned once per key, cause and site, repeats summarized), and the
     *       key stays ON.</li>
     * </ul>
     * Neither case writes a row, and nothing is guessed: no channel is returned. With no variant chosen the no-variant
     * state above is the one reported.
     */
    static IngredientChannelIndexed<?, ?> transferChannel(IIngredientComponentStorage<?, ?> storage) {
        IngredientChannelIndexed<?, ?> indexed = indexed(storage);
        if (indexed != null) {
            if (!fitted) fitted = true;
        } else if (storage != null && HookVerdicts.integratedVariant() != HookVerdicts.IntegratedVariant.NONE) {
            if (fitted) AdapterFailSafe.invalid(AdapterKey.INTEGRATED_TERMINALS, MISFIT_SITE, MISFIT_AFTER_FIT);
            else AdapterFailSafe.conceptNotFound(AdapterKey.INTEGRATED_TERMINALS, MISFIT_SITE, MISFIT);
        }
        return indexed;
    }

    static IngredientChannelIndexed<?, ?> indexed(IIngredientComponentStorage<?, ?> storage,
            HookVerdicts.IntegratedVariant variant) {
        if (storage == null) return null;
        if (variant == HookVerdicts.IntegratedVariant.LEGACY)
            return storage.getClass() == IngredientChannelIndexed.class ? (IngredientChannelIndexed<?, ?>) storage : null;
        if (variant != HookVerdicts.IntegratedVariant.CURRENT || storage.getClass() != CountingType.TYPE
                || !(storage instanceof IntegratedCountingStorageAccess access)) return null;
        return countedDelegate(access);
    }

    /** Called only after the exact decorator class and the chosen variant have been authenticated. */
    static IngredientChannelIndexed<?, ?> countedDelegate(IntegratedCountingStorageAccess access) {
        if (access == null || !access.eventtrail$integratedRead() || !access.eventtrail$integratedInsert()
                || !access.eventtrail$integratedExtract()) return null;
        IIngredientComponentStorage<?, ?> delegate = access.eventtrail$integratedDelegate();
        return delegate != null && delegate.getClass() == IngredientChannelIndexed.class
                ? (IngredientChannelIndexed<?, ?>) delegate : null;
    }

    // Deferred until the current variant is the chosen one: this type is absent from the legacy variant's jars.
    private static final class CountingType {
        static final Class<?> TYPE = resolve();
        private static Class<?> resolve() {
            try { return Class.forName(COUNTING, false, IngredientChannelIndexed.class.getClassLoader()); }
            catch (ClassNotFoundException failure) { throw missingCounting(failure); }
        }
    }

    /**
     * The authenticated counting decorator class is absent: report it under INTEGRATED_TERMINALS as a missing class (the
     * key goes OFF until restart, one warning) and keep the fail-closed throw, with the same type and message as before.
     * The typed missing-member exception is the thrown error's cause, so a caller's own report of it sorts the same way.
     */
    static IllegalStateException missingCounting(ClassNotFoundException failure) {
        MissingAdapterMemberException missing = MissingAdapterMemberException.missingClass(COUNTING, failure);
        AdapterFailSafe.report(AdapterKey.INTEGRATED_TERMINALS, "adapter:IntegratedStorageAuthority.CountingType", missing);
        return new IllegalStateException("Missing authenticated counting storage", missing);
    }
}
