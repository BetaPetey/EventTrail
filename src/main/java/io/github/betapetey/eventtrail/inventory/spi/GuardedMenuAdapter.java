package io.github.betapetey.eventtrail.inventory.spi;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.compat.ContainerLabelFallbacks;
import io.github.betapetey.eventtrail.compat.FailureClassifier;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Build B R2-menu: the fail-safe decorator of one optional menu adapter. Every call EventTrail makes on the
 * adapter, and every callback the adapter hands back (the three deferred guards, the slot reader and the backing
 * reader), runs inside a catch of {@link Throwable} and goes through {@link AdapterFailSafe}:
 * <ul>
 *   <li>a missing member switches the adapter's key OFF until the server restarts (one warning naming it);</li>
 *   <li>any other failure, and a {@code null} answer, skips just that call: the caller gets the method's own
 *       "nothing" value (an empty {@link Optional}, {@link InventorySlotRole#UNKNOWN}, a {@code null} reader result,
 *       a {@code false} guard), and the warning is de-duplicated. The adapter stays on.</li>
 * </ul>
 * Nothing is rethrown. Each failure is also counted on the calling thread ({@link #failuresOnThisThread()}), so the
 * tracker can tell that an open or action met an adapter failure and record nothing for it (design 2.4).
 *
 * <p>The adapter's metadata ({@code adapterId}, {@code observationStrategy} and the {@code uses*} flags) is read
 * once, when the adapter is built, and then only returned (design 2.1 B, the adapter descriptor). The one exception
 * is a check, not a re-read: before a SUPPORTED {@code match} answer is passed on, the delegate's live id must equal
 * the built one, or the key goes OFF (c-3 r6; {@link #ID_CHECK_SITE}). While the key is
 * OFF no call reaches the adapter. The hook family gate is not consulted here: a stopped hook family does not
 * change which adapter owns a menu (design 4.1).</p>
 *
 * <p>{@code match} follows the ownership rule (design 4.2 item 1): when it fails, the open is blocked only if the
 * adapter's own name test (the {@code recognizer}, which links no optional class) owns the menu; otherwise the
 * adapter is ignored for that selection.</p>
 *
 * <p>GF-ADAPTERSPECS: a mod may have several adapters (sibling specifications, each with its own key). A menu that
 * the shared claim predicate gives to a sibling ({@code claimedBySibling}) is answered NO_MATCH before anything else,
 * whatever the adapter itself would answer (an AMBIGUOUS for its mod's menus it does not support would otherwise block
 * the sibling's open). With one adapter per mod the predicate is always false, and nothing changes.</p>
 *
 * <p>GF-ADAPTERSPECS r2 (review-01 MINOR-1): the other way round, a built adapter's NO_MATCH for a menu that another
 * adapter of its mod yielded to it ({@code yieldedToMe}: the other's own recognition owns the menu and the claim
 * predicate gives it to this one) would let the generic physical adapter take a menu its mod owns. That NO_MATCH is
 * answered AMBIGUOUS (blocked), and the registry's block reason names the yielding adapter's key ({@link #answer}).
 * With one adapter per mod nothing is ever yielded, and nothing changes.</p>
 *
 * <p>Construction: an adapter that could not be built at server start for any reason other than a missing member
 * is registered as a {@link #retrying} handle. It answers exactly like the OFF blocker it replaces (same id, same
 * answer), and before it answers for a menu its recognizer owns it tries to build the adapter again. A missing
 * member is never retried: a member missing at server start registers the plain OFF blocker (no handle), a retry
 * that meets a missing member drops the builder for good (the key is OFF), and no retry starts while the key is
 * OFF. After {@value #MAX_FAILED_RETRIES} failed retries the handle drops its builder too (one warning); its key
 * stays on and its menus stay blocked until the server restarts.</p>
 */
public final class GuardedMenuAdapter implements MenuTransactionAdapter {
    /** Fail-safe site of a construction retry (the server-start construction site is the loader's). */
    public static final String RETRY_SITE = "adapter construction retry";
    /** Lead decision Q1 (review-01): failed construction retries one handle makes before it stops retrying. */
    public static final int MAX_FAILED_RETRIES = 3;
    static final String MATCH_SITE = "menu match";
    static final String NULL_RESULT = "null-result";
    /** SOPH-LABELS: the fallback-count site of {@link #physicalContainerLabel} (never a fail-safe site). */
    static final String LABEL_SITE = "menu physicalContainerLabel";
    /** Fail-safe site of the build-time id check before a SUPPORTED match is passed on (c-3 r6). */
    public static final String ID_CHECK_SITE = "adapter id check";
    /** What the id check looks for (the OFF warning's detail starts with it). */
    public static final String ONE_PATH = "one behaviour path for the whole server run";

    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final ThreadLocal<long[]> THREAD_FAILURES = ThreadLocal.withInitial(() -> new long[1]);
    /** The claim predicate of the only adapter of its mod: no sibling claims any menu. */
    private static final Predicate<MenuAdapterContext> NO_SIBLING = context -> false;
    /** The yield check of the only adapter of its mod: no sibling yields any menu to it. */
    private static final Function<MenuAdapterContext, AdapterKey> NO_YIELD = context -> null;
    /** The registry's block reason for a NO_MATCH answered AMBIGUOUS (r2, MINOR-1), before the yielding key's name. */
    static final String YIELDED_REASON = "AMBIGUOUS: answered NO_MATCH for a menu yielded to it by ";
    /** The registry's block reason when the yield check itself could not answer (fail closed; no allocation). */
    static final String YIELD_CHECK_FAILED_REASON = "AMBIGUOUS: sibling yield check failed";

    private final AdapterKey key;
    private final MenuTransactionAdapter recognizer;
    private final String unbuiltId;
    private final Consumer<GuardedMenuAdapter> onBuilt;
    private final Consumer<String> retryWarnings;
    private final Predicate<MenuAdapterContext> claimedBySibling;
    private final Function<MenuAdapterContext, AdapterKey> yieldedToMe;
    private volatile Built built;
    private volatile Callable<? extends MenuTransactionAdapter> builder;
    /** Failed retries so far; read and written only under the handle's lock ({@link #buildForOwnedUse}). */
    private int failedRetries;

    private GuardedMenuAdapter(
            AdapterKey key,
            MenuTransactionAdapter recognizer,
            Built built,
            Callable<? extends MenuTransactionAdapter> builder,
            Consumer<GuardedMenuAdapter> onBuilt,
            Consumer<String> retryWarnings,
            Predicate<MenuAdapterContext> claimedBySibling,
            Function<MenuAdapterContext, AdapterKey> yieldedToMe) {
        this.key = Objects.requireNonNull(key, "key");
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        if (recognizer instanceof GuardedMenuAdapter) {
            throw new IllegalArgumentException("A recognizer must be a plain name test");
        }
        this.unbuiltId = Objects.requireNonNull(recognizer.adapterId(), "recognizer id");
        this.built = built;
        this.builder = builder;
        this.onBuilt = onBuilt;
        this.retryWarnings = Objects.requireNonNull(retryWarnings, "retryWarnings");
        this.claimedBySibling = Objects.requireNonNull(claimedBySibling, "claimedBySibling");
        this.yieldedToMe = Objects.requireNonNull(yieldedToMe, "yieldedToMe");
    }

    /** Guards an adapter built at server start. Reads its metadata now; a failure here is a construction failure. */
    public static GuardedMenuAdapter built(
            AdapterKey key, MenuTransactionAdapter delegate, MenuTransactionAdapter recognizer) {
        return built(key, delegate, recognizer, NO_SIBLING, NO_YIELD);
    }

    /**
     * As {@link #built(AdapterKey, MenuTransactionAdapter, MenuTransactionAdapter)}, for one of several adapters of a
     * mod: {@code claimedBySibling} (the loader's shared claim predicate) names the menus a sibling owns, and
     * {@code yieldedToMe} the key of a sibling that yielded this menu to this adapter (null when none did).
     */
    public static GuardedMenuAdapter built(
            AdapterKey key,
            MenuTransactionAdapter delegate,
            MenuTransactionAdapter recognizer,
            Predicate<MenuAdapterContext> claimedBySibling,
            Function<MenuAdapterContext, AdapterKey> yieldedToMe) {
        return new GuardedMenuAdapter(key, recognizer, describe(delegate), null, null, LOGGER::warn,
                claimedBySibling, yieldedToMe);
    }

    /**
     * A handle for an adapter that could not be built (not a missing member). {@code onBuilt} runs once, after a
     * retry built it; a failure there is ignored. After {@value #MAX_FAILED_RETRIES} failed retries the handle stops
     * retrying (one warning) and keeps blocking its own menus until the server restarts.
     */
    public static GuardedMenuAdapter retrying(
            AdapterKey key,
            MenuTransactionAdapter recognizer,
            Callable<? extends MenuTransactionAdapter> builder,
            Consumer<GuardedMenuAdapter> onBuilt) {
        return retrying(key, recognizer, builder, onBuilt, LOGGER::warn);
    }

    /**
     * As {@link #retrying(AdapterKey, MenuTransactionAdapter, Callable, Consumer)}, for one of several adapters of a
     * mod: {@code claimedBySibling} (the loader's shared claim predicate) names the menus a sibling owns, and
     * {@code yieldedToMe} the key of a sibling that yielded this menu to this adapter (null when none did).
     */
    public static GuardedMenuAdapter retrying(
            AdapterKey key,
            MenuTransactionAdapter recognizer,
            Predicate<MenuAdapterContext> claimedBySibling,
            Function<MenuAdapterContext, AdapterKey> yieldedToMe,
            Callable<? extends MenuTransactionAdapter> builder,
            Consumer<GuardedMenuAdapter> onBuilt) {
        return new GuardedMenuAdapter(key, recognizer, null, Objects.requireNonNull(builder, "builder"), onBuilt,
                LOGGER::warn, claimedBySibling, yieldedToMe);
    }

    /** As {@link #retrying(AdapterKey, MenuTransactionAdapter, Callable, Consumer)}, with the warning sink given. */
    static GuardedMenuAdapter retrying(
            AdapterKey key,
            MenuTransactionAdapter recognizer,
            Callable<? extends MenuTransactionAdapter> builder,
            Consumer<GuardedMenuAdapter> onBuilt,
            Consumer<String> retryWarnings) {
        return new GuardedMenuAdapter(key, recognizer, null, Objects.requireNonNull(builder, "builder"), onBuilt,
                retryWarnings, NO_SIBLING, NO_YIELD);
    }

    public AdapterKey key() {
        return key;
    }

    /** True once the adapter is built (at server start or by a retry). */
    public boolean isBuilt() {
        return built != null;
    }

    /** The key of a guarded adapter, or null for any other adapter. */
    public static AdapterKey keyOf(MenuTransactionAdapter adapter) {
        return adapter instanceof GuardedMenuAdapter guarded ? guarded.key : null;
    }

    /**
     * The class name of the adapter's implementation: the guarded adapter's own class once it is built, this class
     * while it is not, and the class of any other adapter. Source-owned allowlists compare this instead of the
     * decorator's class.
     */
    public static String implementationClassName(MenuTransactionAdapter adapter) {
        if (adapter == null) return null;
        if (adapter instanceof GuardedMenuAdapter guarded) {
            Built current = guarded.built;
            return current == null ? GuardedMenuAdapter.class.getName() : current.implementationClassName();
        }
        return adapter.getClass().getName();
    }

    /** Adapter failures counted on this thread so far (or -1 if the count cannot be read). Never throws. */
    public static long failuresOnThisThread() {
        try {
            return THREAD_FAILURES.get()[0];
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** Counts one adapter failure on this thread (the tracker's integrity checks count theirs here too). */
    public static void countFailureOnThisThread() {
        try {
            THREAD_FAILURES.get()[0]++;
        } catch (Throwable ignored) {
            // At the edge of memory or stack: the fail-safe has already counted the failure itself.
        }
    }

    @Override
    public String adapterId() {
        Built current = built;
        return current == null ? unbuiltId : current.adapterId();
    }

    @Override
    public boolean exact() {
        return true;
    }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        return answer(context).match();
    }

    /**
     * The {@link #match} answer together with the registry's block reason ({@code null}: the answer's own name, as
     * for every adapter today). Only a NO_MATCH answered AMBIGUOUS because a sibling yielded the menu (r2, MINOR-1)
     * carries a reason of its own, naming the yielding sibling's key.
     */
    Answer answer(MenuAdapterContext context) {
        flushDeferredWarnings();
        // GF-ADAPTERSPECS: a sibling's menu is never this adapter's (shared claim predicate), ON, OFF or unbuilt.
        if (claimedBySibling(context)) return Answer.NO_MATCH;
        Built current = built;
        if (current == null) {
            if (!owns(context)) return Answer.NO_MATCH;
            current = buildForOwnedUse();
            if (current == null) return Answer.UNSUPPORTED_VERSION;
        }
        if (switchedOff()) return owns(context) ? Answer.UNSUPPORTED_VERSION : Answer.NO_MATCH;
        AdapterMatch match;
        try {
            match = current.delegate().match(context);
        } catch (Throwable failure) {
            failed(MATCH_SITE, failure);
            return owns(context) ? Answer.AMBIGUOUS : Answer.NO_MATCH;
        }
        if (match == null) {
            nullResult(MATCH_SITE);
            return owns(context) ? Answer.AMBIGUOUS : Answer.NO_MATCH;
        }
        // c-3 r6 (re-check r5 MINOR-1): SUPPORTED is the only answer that lets a session, and so a row, start; its
        // rows carry the id read at build. A delegate whose id has changed since is blocked here (fail closed).
        if (match == AdapterMatch.SUPPORTED && !sameIdAsBuilt(current)) return Answer.AMBIGUOUS;
        // GF-ADAPTERSPECS r2 (review-01 MINOR-1): a NO_MATCH for a menu a sibling yielded to this adapter is blocked.
        if (match == AdapterMatch.NO_MATCH) return yieldCheck(context);
        return Answer.of(match);
    }

    /**
     * GF-ADAPTERSPECS r2 (review-01 MINOR-1): the built adapter answered NO_MATCH. When a sibling of its mod yielded
     * this menu to it (the sibling's own recognition owns the menu, and the shared claim predicate gives it to this
     * adapter, so the sibling answers NO_MATCH too), passing NO_MATCH on would let the generic physical adapter take
     * a menu the mod owns. It is answered AMBIGUOUS instead (blocked), with a reason naming the yielding key. A yield
     * check that cannot answer fails closed the same way: failing open would let the generic adapter in. With one
     * adapter per mod the check is {@link #NO_YIELD} and NO_MATCH is passed on as before.
     */
    private Answer yieldCheck(MenuAdapterContext context) {
        AdapterKey yielding;
        try {
            yielding = yieldedToMe.apply(context);
        } catch (Throwable failure) {
            return Answer.YIELD_CHECK_FAILED;
        }
        return yielding == null ? Answer.NO_MATCH : Answer.yieldedBy(yielding);
    }

    /**
     * c-3 r6 (re-check r5 MINOR-1): the id read once at build is the {@code providerId} of every row this adapter's
     * sessions write. A delegate that answers with another id now (a Sophisticated path probed again after a
     * VirtualMachineError at start-up left the build-time choice uncached) would record under the wrong id. So the
     * live id must equal the built one before SUPPORTED is passed on. A different, missing or unreadable id is that
     * adapter's failure: its key goes OFF until restart with one warning naming both ids, and nothing is recorded.
     * A VirtualMachineError while reading it is not locked in: it skips this open only (counted), as the
     * fail-safe sorts it.
     */
    private boolean sameIdAsBuilt(Built current) {
        String live;
        try {
            live = current.delegate().adapterId();
        } catch (VirtualMachineError transientFailure) {
            failed(ID_CHECK_SITE, transientFailure);
            return false;
        } catch (Throwable failure) {
            idChanged(current.adapterId(), "unreadable (" + failure.getClass().getName() + ")");
            return false;
        }
        if (current.adapterId().equals(live)) return true;
        idChanged(current.adapterId(), live == null ? "null" : EventTrailDiagnostics.sanitizeLogValue(live, 96));
        return false;
    }

    private void idChanged(String builtId, String liveId) {
        countFailureOnThisThread();
        try {
            AdapterFailSafe.conceptNotFound(key, ID_CHECK_SITE, ONE_PATH + " (its id was " + builtId
                    + " when it was built and is " + liveId + " now)");
        } catch (Throwable ignored) {
            // Never rethrown; this open is blocked whatever happened here.
        }
    }

    @Override
    public ObservationStrategy observationStrategy() {
        Built current = built;
        return current == null ? ObservationStrategy.ACTION_SCOPED_PHYSICAL : current.strategy();
    }

    @Override
    public boolean usesHandlerBackedView() {
        Built current = built;
        return current != null && current.handlerBackedView();
    }

    @Override
    public boolean usesIndependentBackingTarget() {
        Built current = built;
        return current != null && current.independentBackingTarget();
    }

    @Override
    public boolean usesSharedChannelBacking() {
        Built current = built;
        return current != null && current.sharedChannelBacking();
    }

    @Override
    public boolean usesCapturedAccessAuthority() {
        Built current = built;
        return current != null && current.capturedAccessAuthority();
    }

    @Override
    public boolean usesMovingBackingTarget() {
        Built current = built;
        return current != null && current.movingBackingTarget();
    }

    @Override
    public Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        String site = "menu describePhysicalBacking";
        Optional<MenuPhysicalBacking.Description> described =
                guarded(site, delegate -> delegate.describePhysicalBacking(context), Optional.empty());
        if (described.isEmpty()) return described;
        try {
            return Optional.of(guardedDescription(described.orElseThrow()));
        } catch (Throwable failure) {
            failed(site, failure);
            return Optional.empty();
        }
    }

    @Override
    public Optional<BooleanSupplier> captureAccessGuard(MenuAdapterContext context) {
        return guarded("menu captureAccessGuard", delegate -> delegate.captureAccessGuard(context),
                Optional.<BooleanSupplier>empty()).map(guard -> guardedGuard("menu access guard", guard));
    }

    @Override
    public Optional<BooleanSupplier> captureAccessPointGuard(MenuAdapterContext context) {
        return guarded("menu captureAccessPointGuard", delegate -> delegate.captureAccessPointGuard(context),
                Optional.<BooleanSupplier>empty()).map(guard -> guardedGuard("menu access point guard", guard));
    }

    @Override
    public Optional<BooleanSupplier> captureActionGuard(MenuAdapterContext context) {
        return guarded("menu captureActionGuard", delegate -> delegate.captureActionGuard(context),
                Optional.<BooleanSupplier>empty()).map(guard -> guardedGuard("menu action guard", guard));
    }

    @Override
    public Optional<BooleanSupplier> captureMovingAccessGuard(MenuAdapterContext context) {
        return guarded("menu captureMovingAccessGuard", delegate -> delegate.captureMovingAccessGuard(context),
                Optional.<BooleanSupplier>empty()).map(guard -> guardedGuard("menu moving access guard", guard));
    }

    @Override
    public Optional<ContainerTarget> resolveMenuBackingTarget(MenuAdapterContext context) {
        return guarded("menu resolveMenuBackingTarget", delegate -> delegate.resolveMenuBackingTarget(context),
                Optional.empty());
    }

    @Override
    public Optional<MenuPhysicalBlockTarget> resolvePhysicalBlockTarget(MenuAdapterContext context) {
        return guarded("menu resolvePhysicalBlockTarget", delegate -> delegate.resolvePhysicalBlockTarget(context),
                Optional.empty());
    }

    /**
     * SOPH-LABELS (review-01 MAJOR-1): a label-only forwarder, deliberately not {@link #guarded}. A name is never
     * counted as an adapter failure on this thread and never reported to the fail-safe, so it can neither skip the
     * open nor switch the key off. Empty while the adapter is not built or its key is off. A throw, a null or a blank
     * answer gives empty (the tracker keeps its own label) and is counted as one name recorded with the block's own
     * name ({@link ContainerLabelFallbacks}); a stack or memory error is only counted.
     */
    @Override
    public Optional<String> physicalContainerLabel(MenuAdapterContext context) {
        Built current = built;
        if (current == null || switchedOff()) return Optional.empty();
        try {
            Optional<String> label = current.delegate().physicalContainerLabel(context);
            if (label != null && label.isEmpty()) return Optional.empty();
            if (label != null && !label.orElseThrow().isBlank()) return Optional.of(label.orElseThrow());
            ContainerLabelFallbacks.fellBack(key.displayName(), LABEL_SITE, null);
        } catch (Throwable failure) {
            ContainerLabelFallbacks.fellBack(key.displayName(), LABEL_SITE, failure);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ContainerTarget> resolveAuthoritativeTarget(MenuAdapterContext context) {
        return guarded("menu resolveAuthoritativeTarget", delegate -> delegate.resolveAuthoritativeTarget(context),
                Optional.empty());
    }

    @Override
    public Optional<MenuAccessTarget> resolveContainerAccessTarget(MenuAdapterContext context) {
        return guarded("menu resolveContainerAccessTarget",
                delegate -> delegate.resolveContainerAccessTarget(context), Optional.empty());
    }

    @Override
    public InventorySlotRole classifySlot(MenuAdapterContext context, int menuSlotIndex, Slot slot) {
        return guarded("menu classifySlot", delegate -> delegate.classifySlot(context, menuSlotIndex, slot),
                InventorySlotRole.UNKNOWN);
    }

    @Override
    public Optional<MenuSlotAccess> resolveSlotAccess(MenuAdapterContext context, int menuSlotIndex, Slot slot) {
        String site = "menu resolveSlotAccess";
        Optional<MenuSlotAccess> access =
                guarded(site, delegate -> delegate.resolveSlotAccess(context, menuSlotIndex, slot), Optional.empty());
        if (access.isEmpty()) return access;
        try {
            return Optional.of(guardedAccess(access.orElseThrow()));
        } catch (Throwable failure) {
            failed(site, failure);
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code null} when the adapter failed or answered {@code null}: the tracker's observed-slot check
     * rejects a {@code null} list, so the action records nothing instead of seeing fewer slots than exist.</p>
     */
    @Override
    public List<MenuObservedSlot> additionalObservedSlots(MenuAdapterContext context) {
        return guarded("menu additionalObservedSlots", delegate -> delegate.additionalObservedSlots(context), null);
    }

    private <T> T guarded(String site, Function<MenuTransactionAdapter, T> work, T skipped) {
        Built current = built;
        if (current == null || switchedOff()) return skipped;
        T value;
        try {
            value = work.apply(current.delegate());
        } catch (Throwable failure) {
            failed(site, failure);
            return skipped;
        }
        if (value == null) {
            nullResult(site);
            return skipped;
        }
        return value;
    }

    private BooleanSupplier guardedGuard(String site, BooleanSupplier guard) {
        // A failed guard stays false for the rest of its session (design 2.4, SESSION_GUARD; work map R2-menu 2).
        AtomicBoolean broken = new AtomicBoolean();
        return () -> {
            if (broken.get()) return false;
            if (switchedOff()) {
                broken.set(true);
                return false;
            }
            try {
                return guard.getAsBoolean();
            } catch (Throwable failure) {
                broken.set(true);
                failed(site, failure);
                return false;
            }
        };
    }

    private MenuPhysicalBacking.Description guardedDescription(MenuPhysicalBacking.Description description) {
        List<MenuPhysicalBacking.Backing> backings = new ArrayList<>(description.backings().size());
        for (MenuPhysicalBacking.Backing backing : description.backings()) {
            backings.add(new MenuPhysicalBacking.Backing(
                    backing.identity(), backing.size(), guardedReader(backing.reader())));
        }
        List<MenuPhysicalBacking.DeclaredSlot> slots = new ArrayList<>(description.slots().size());
        for (MenuPhysicalBacking.DeclaredSlot slot : description.slots()) {
            slots.add(new MenuPhysicalBacking.DeclaredSlot(
                    slot.menuSlotIndex(), slot.slot(), slot.role(), slot.access().map(this::guardedAccess)));
        }
        // Identities (owner, members, backings, slots) are kept, so the backing's same-binding checks still hold.
        return new MenuPhysicalBacking.Description(description.ownerIdentity(), backings, slots,
                description.durableAccessIdentity(), description.ownershipMembers());
    }

    private IntFunction<ItemStack> guardedReader(IntFunction<ItemStack> reader) {
        // A null read is the view's existing "slot read failed": that capture is invalid and records nothing.
        return index -> {
            if (switchedOff()) return null;
            try {
                return reader.apply(index);
            } catch (Throwable failure) {
                failed("menu backing reader", failure);
                return null;
            }
        };
    }

    private MenuSlotAccess guardedAccess(MenuSlotAccess access) {
        Supplier<ItemStack> reader = access.stackReader();
        return new MenuSlotAccess(access.backingIdentity(), access.backingSlotIndex(), () -> {
            if (switchedOff()) return null;
            try {
                return reader.get();
            } catch (Throwable failure) {
                failed("menu slot reader", failure);
                return null;
            }
        });
    }

    /**
     * GF-ADAPTERSPECS: true when the shared claim predicate gives this menu to a sibling adapter of the same mod.
     * A predicate that cannot answer gives false: the menu then goes through this adapter's own rule below (its
     * recognizer and its answer), and the sibling's own guard still answers for it, so nothing is let in unseen.
     */
    private boolean claimedBySibling(MenuAdapterContext context) {
        try {
            return claimedBySibling.test(context);
        } catch (Throwable failure) {
            return false;
        }
    }

    private boolean owns(MenuAdapterContext context) {
        try {
            AdapterMatch answer = recognizer.match(context);
            return answer != null && answer != AdapterMatch.NO_MATCH;
        } catch (Throwable failure) {
            // Fail closed: a name test that cannot answer blocks this open rather than letting generic tracking in.
            return true;
        }
    }

    private synchronized Built buildForOwnedUse() {
        Built current = built;
        if (current != null) return current;
        Callable<? extends MenuTransactionAdapter> attempt = builder;
        if (attempt == null) return null;
        if (switchedOff()) {
            // OFF lasts until the next server start, and server start builds a fresh handle: never retried.
            builder = null;
            return null;
        }
        try {
            Built next = describe(attempt.call());
            built = next;
            builder = null;
            notifyBuilt();
            return next;
        } catch (Throwable failure) {
            boolean missing = missing(failure);
            failed(RETRY_SITE, failure);
            // A missing member is final: the fail-safe switched the key OFF; the builder is dropped for good.
            if (missing || switchedOff()) {
                builder = null;
                return null;
            }
            // Lead decision Q1 (review-01): at most MAX_FAILED_RETRIES failed retries per handle (a handle lives one
            // server start). Then the builder is dropped here, under the handle's lock, with one warning; the key is
            // not switched off (the handle already blocks its own menus) and the status stays ADAPTER_UNAVAILABLE.
            if (++failedRetries >= MAX_FAILED_RETRIES) {
                builder = null;
                warnRetriesExhausted();
            }
            return null;
        }
    }

    private void warnRetriesExhausted() {
        try {
            retryWarnings.accept(OptionalIntegrationCompatibility.LOG_MARKER + " " + key.displayName() + " ("
                    + key.modId() + "): EventTrail's adapter could not be built after " + MAX_FAILED_RETRIES
                    + " attempts at later uses; its menus stay blocked until the server restarts.");
        } catch (Throwable ignored) {
            // The builder is dropped whatever the warning did.
        }
    }

    private void notifyBuilt() {
        if (onBuilt == null) return;
        try {
            onBuilt.accept(this);
        } catch (Throwable ignored) {
            // Status only: the adapter is built and guarded whatever the status update did.
        }
    }

    /**
     * c-3 r7 (Advisor r6 M-2): a StackOverflowError or OutOfMemoryError met in a guarded call is only counted where
     * it is caught; its warning is deferred. Neither this decorator, the registry nor the tracker calls
     * {@code AdapterFailSafe.active} or {@code contain}, which write it, so every match (once per adapter per menu
     * selection, never inside a catch) writes a deferred warning that is due. Warning only: no answer changes.
     */
    private static void flushDeferredWarnings() {
        try {
            AdapterFailSafe.flushDeferred();
        } catch (Throwable ignored) {
            // Never throws once the fail-safe is initialised; the counts stay and a later flush writes them.
        }
    }

    private boolean switchedOff() {
        try {
            return AdapterFailSafe.switchedOff(key);
        } catch (Throwable failure) {
            // Fail closed: when the fail-safe cannot answer, no adapter call is made.
            return true;
        }
    }

    private void failed(String site, Throwable failure) {
        countFailureOnThisThread();
        try {
            AdapterFailSafe.report(key, site, failure);
        } catch (Throwable ignored) {
            // Never rethrown (the fail-safe itself never throws once initialised).
        }
    }

    private void nullResult(String site) {
        countFailureOnThisThread();
        try {
            AdapterFailSafe.invalid(key, site, NULL_RESULT);
        } catch (Throwable ignored) {
            // Never rethrown.
        }
    }

    private static boolean missing(Throwable failure) {
        try {
            return FailureClassifier.classify(failure).missing();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Built describe(MenuTransactionAdapter delegate) {
        Objects.requireNonNull(delegate, "optional adapter");
        if (delegate instanceof GuardedMenuAdapter) {
            throw new IllegalArgumentException("Optional adapter is already guarded");
        }
        if (!delegate.exact()) throw new IllegalStateException("Optional adapter is not exact");
        return new Built(
                delegate,
                Objects.requireNonNull(delegate.adapterId(), "adapterId"),
                Objects.requireNonNull(delegate.observationStrategy(), "observationStrategy"),
                delegate.usesHandlerBackedView(),
                delegate.usesIndependentBackingTarget(),
                delegate.usesSharedChannelBacking(),
                delegate.usesCapturedAccessAuthority(),
                delegate.usesMovingBackingTarget(),
                delegate.getClass().getName());
    }

    /** The built adapter and its metadata, read once. */
    private record Built(
            MenuTransactionAdapter delegate,
            String adapterId,
            ObservationStrategy strategy,
            boolean handlerBackedView,
            boolean independentBackingTarget,
            boolean sharedChannelBacking,
            boolean capturedAccessAuthority,
            boolean movingBackingTarget,
            String implementationClassName) {
    }

    /**
     * GF-ADAPTERSPECS r2 (review-01 MINOR-1): a {@link #match} answer and the registry's block reason ({@code null}:
     * the answer's own name). All answers are shared constants, so answering allocates nothing.
     */
    record Answer(AdapterMatch match, String reason) {
        static final Answer NO_MATCH = new Answer(AdapterMatch.NO_MATCH, null);
        static final Answer SUPPORTED = new Answer(AdapterMatch.SUPPORTED, null);
        static final Answer UNSUPPORTED_VERSION = new Answer(AdapterMatch.UNSUPPORTED_VERSION, null);
        static final Answer AMBIGUOUS = new Answer(AdapterMatch.AMBIGUOUS, null);
        static final Answer YIELD_CHECK_FAILED = new Answer(AdapterMatch.AMBIGUOUS, YIELD_CHECK_FAILED_REASON);
        private static final Map<AdapterKey, Answer> YIELDED = yieldedAnswers();

        Answer {
            Objects.requireNonNull(match, "match");
        }

        static Answer of(AdapterMatch match) {
            return switch (match) {
                case NO_MATCH -> NO_MATCH;
                case SUPPORTED -> SUPPORTED;
                case UNSUPPORTED_VERSION -> UNSUPPORTED_VERSION;
                case AMBIGUOUS -> AMBIGUOUS;
            };
        }

        static Answer yieldedBy(AdapterKey yielding) {
            return YIELDED.get(yielding);
        }

        private static Map<AdapterKey, Answer> yieldedAnswers() {
            Map<AdapterKey, Answer> answers = new EnumMap<>(AdapterKey.class);
            for (AdapterKey yielding : AdapterKey.values()) {
                answers.put(yielding, new Answer(AdapterMatch.AMBIGUOUS, YIELDED_REASON + yielding.name()));
            }
            return Map.copyOf(answers);
        }
    }
}
