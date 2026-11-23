package io.github.betapetey.eventtrail.inventory.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Selects exactly one owner. Exact adapters always precede the final bounded
 * generic fallback, and uncertainty in the exact tier blocks speculation.
 *
 * <p>Build B R2-menu: optional adapters arrive wrapped in {@link GuardedMenuAdapter}, whose {@code match} never
 * throws and applies the ownership rule itself. For an exact adapter that is not wrapped, a failing {@code match}
 * is handed once to the failure reporter (in production the adapter fail-safe). If that switched the adapter off,
 * the disabled-adapter rule applies at once: its own menus are blocked and every other menu goes on to the
 * remaining adapters in this same selection. Otherwise this open is blocked, as before.</p>
 *
 * <p>GF-ADAPTERSPECS r2: a guarded adapter is asked through {@link GuardedMenuAdapter#answer}, the same answer as its
 * {@code match} plus an optional block reason; only a NO_MATCH blocked because a sibling of its mod yielded the menu
 * has one (it names that sibling's key). With one adapter per mod every reason stays the answer's own name.</p>
 */
public final class MenuTransactionAdapterRegistry {
    private static final String DISABLE_CHECK_FAILED = "runtime adapter disable check failed: ";
    private static final String ADAPTER_FAILURE = "adapter failure: ";
    private static final String DISABLE_CHECK_FAILED_SOE = DISABLE_CHECK_FAILED + "StackOverflowError";
    private static final String DISABLE_CHECK_FAILED_OOM = DISABLE_CHECK_FAILED + "OutOfMemoryError";
    private static final String ADAPTER_FAILURE_SOE = ADAPTER_FAILURE + "StackOverflowError";
    private static final String ADAPTER_FAILURE_OOM = ADAPTER_FAILURE + "OutOfMemoryError";

    private final List<MenuTransactionAdapter> exactAdapters;
    private final MenuTransactionAdapter genericFallback;
    private final Predicate<String> runtimeDisabled;
    private final BiPredicate<String, MenuAdapterContext> disabledAdapterOwnsContext;
    private final BiConsumer<String, Throwable> adapterFailureReporter;

    public MenuTransactionAdapterRegistry(
            List<? extends MenuTransactionAdapter> exactAdapters,
            MenuTransactionAdapter genericFallback) {
        this(exactAdapters, genericFallback, ignored -> false, (ignored, context) -> false);
    }

    public MenuTransactionAdapterRegistry(
            List<? extends MenuTransactionAdapter> exactAdapters,
            MenuTransactionAdapter genericFallback,
            Predicate<String> runtimeDisabled,
            BiPredicate<String, MenuAdapterContext> disabledAdapterOwnsContext) {
        this(exactAdapters, genericFallback, runtimeDisabled, disabledAdapterOwnsContext,
                (ignored, failure) -> { });
    }

    /** As the four-argument form; {@code adapterFailureReporter} receives each exact adapter's match failure once. */
    public MenuTransactionAdapterRegistry(
            List<? extends MenuTransactionAdapter> exactAdapters,
            MenuTransactionAdapter genericFallback,
            Predicate<String> runtimeDisabled,
            BiPredicate<String, MenuAdapterContext> disabledAdapterOwnsContext,
            BiConsumer<String, Throwable> adapterFailureReporter) {
        this.exactAdapters = List.copyOf(exactAdapters);
        this.genericFallback = Objects.requireNonNull(genericFallback, "genericFallback");
        this.runtimeDisabled = Objects.requireNonNull(runtimeDisabled, "runtimeDisabled");
        this.disabledAdapterOwnsContext = Objects.requireNonNull(
                disabledAdapterOwnsContext, "disabledAdapterOwnsContext");
        this.adapterFailureReporter = Objects.requireNonNull(adapterFailureReporter, "adapterFailureReporter");
        if (!this.exactAdapters.stream().allMatch(MenuTransactionAdapter::exact)) {
            throw new IllegalArgumentException("Exact adapter tier contains a generic adapter");
        }
        if (genericFallback.exact()) {
            throw new IllegalArgumentException("Generic fallback must not be exact");
        }
    }

    public Selection select(MenuAdapterContext context) {
        List<MenuTransactionAdapter> supported = new ArrayList<>();
        for (MenuTransactionAdapter adapter : exactAdapters) {
            String adapterId = adapter.adapterId();
            try {
                if (runtimeDisabled.test(adapterId)) {
                    if (disabledAdapterOwnsContext.test(adapterId, context)) {
                        return Selection.blocked(adapterId, "runtime adapter disabled");
                    }
                    continue;
                }
            } catch (Throwable failure) {
                // R2-menu (review-01 NIT-2): every Throwable, as the match catch below.
                return Selection.blocked(adapterId, reason(true, failure));
            }
            AdapterMatch match;
            // GF-ADAPTERSPECS r2 (review-01 MINOR-1): a guarded adapter's answer may carry its own block reason (a
            // NO_MATCH blocked because a sibling yielded the menu names that sibling); null keeps the answer's name.
            String blockReason = null;
            try {
                if (adapter instanceof GuardedMenuAdapter guarded) {
                    GuardedMenuAdapter.Answer answer = Objects.requireNonNull(guarded.answer(context), "adapter match");
                    match = answer.match();
                    blockReason = answer.reason();
                } else {
                    match = Objects.requireNonNull(adapter.match(context), "adapter match");
                }
            } catch (Throwable failure) {
                // R2-menu (lead decisions Q4 and F3-7 MINOR-1/3): reported once; if that switched this adapter off,
                // only its own menus are blocked and this same open goes on to the remaining adapters.
                reportAdapterFailure(adapterId, failure);
                if (disabledAfterFailure(adapterId)) {
                    if (ownedByDisabledAdapter(adapterId, context)) {
                        return Selection.blocked(adapterId, "runtime adapter disabled");
                    }
                    continue;
                }
                return Selection.blocked(adapterId, reason(false, failure));
            }

            if (match == AdapterMatch.SUPPORTED) {
                supported.add(adapter);
            } else if (match == AdapterMatch.UNSUPPORTED_VERSION || match == AdapterMatch.AMBIGUOUS) {
                return Selection.blocked(adapterId, blockReason == null ? match.name() : blockReason);
            }
        }

        if (supported.size() > 1) {
            String ids = supported.stream()
                    .map(MenuTransactionAdapter::adapterId)
                    .sorted()
                    .reduce((left, right) -> left + "," + right)
                    .orElse("unknown");
            return Selection.blocked(ids, "multiple exact adapters matched");
        }
        if (supported.size() == 1) {
            return Selection.selected(supported.getFirst());
        }

        try {
            AdapterMatch fallbackMatch = Objects.requireNonNull(
                    genericFallback.match(context), "fallback match");
            return switch (fallbackMatch) {
                case SUPPORTED -> Selection.selected(genericFallback);
                case NO_MATCH -> Selection.noMatch();
                case UNSUPPORTED_VERSION, AMBIGUOUS ->
                        Selection.blocked(genericFallback.adapterId(), fallbackMatch.name());
            };
        } catch (Throwable failure) {
            // R2-menu (review-01 NIT-2): every Throwable, as the exact adapters' match catch.
            return Selection.blocked(genericFallback.adapterId(), reason(false, failure));
        }
    }

    /**
     * The blocked reason for {@code failure}. At the edge of the stack or of memory nothing is built: as in the
     * landed fail-safe, a {@link StackOverflowError} or {@link OutOfMemoryError} gets a constant (the same text).
     */
    private static String reason(boolean disableCheck, Throwable failure) {
        if (failure instanceof StackOverflowError) {
            return disableCheck ? DISABLE_CHECK_FAILED_SOE : ADAPTER_FAILURE_SOE;
        }
        if (failure instanceof OutOfMemoryError) {
            return disableCheck ? DISABLE_CHECK_FAILED_OOM : ADAPTER_FAILURE_OOM;
        }
        return (disableCheck ? DISABLE_CHECK_FAILED : ADAPTER_FAILURE) + failure.getClass().getSimpleName();
    }

    private void reportAdapterFailure(String adapterId, Throwable failure) {
        try {
            adapterFailureReporter.accept(adapterId, failure);
        } catch (Throwable ignored) {
            // The selection result below stays fail-closed whatever the reporter did.
        }
    }

    private boolean disabledAfterFailure(String adapterId) {
        try {
            return runtimeDisabled.test(adapterId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean ownedByDisabledAdapter(String adapterId, MenuAdapterContext context) {
        try {
            return disabledAdapterOwnsContext.test(adapterId, context);
        } catch (Throwable ignored) {
            // Fail closed: ownership that cannot be decided blocks this open.
            return true;
        }
    }

    public enum Outcome {
        SELECTED,
        NO_MATCH,
        BLOCKED
    }

    public record Selection(
            Outcome outcome,
            MenuTransactionAdapter adapter,
            String adapterId,
            String reason) {
        private static Selection selected(MenuTransactionAdapter adapter) {
            return new Selection(Outcome.SELECTED, adapter, adapter.adapterId(), null);
        }

        private static Selection noMatch() {
            return new Selection(Outcome.NO_MATCH, null, null, null);
        }

        private static Selection blocked(String adapterId, String reason) {
            return new Selection(Outcome.BLOCKED, null, adapterId, reason);
        }

        public boolean selected() {
            return outcome == Outcome.SELECTED;
        }
    }
}
