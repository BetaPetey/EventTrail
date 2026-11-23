package io.github.betapetey.eventtrail.permissions;

import io.github.betapetey.eventtrail.compat.FailureClassifier;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Reads EventTrail node values from FTB Ranks by reflection only (no compile
 * dependency). It is resolved once per server start.
 *
 * <p>Only a value that a rank sets for exactly the node being checked counts.
 * FTB Ranks itself also lets a parent node decide for its children (for
 * example {@code command.eventtrail} for {@code command.eventtrail.teleport},
 * and a rank-file entry ending in {@code .*} is stored as its parent). The
 * bridge does not use that inheritance, so granting the root node never
 * grants an inner capability, and a broad {@code false} never removes access
 * an operator has today. For the exact node, the bridge asks the player's
 * active ranks in FTB Ranks' own order ({@code RankManager.getRanks}, highest
 * power first) and the first rank with a value decides, as FTB Ranks does.</p>
 *
 * <p>Fail-safe, following the adapter fail-safe rules: when FTB Ranks is
 * installed but a class or method the bridge uses is missing, or a call fails
 * because a member the bridge uses is missing or returns the wrong shape, the
 * bridge switches OFF until the next server start with one warning, and
 * EventTrail uses the operator check instead. A call failure is sorted by the
 * project's own {@link FailureClassifier}: a linkage error counts as a missing
 * member only when its deciding frame is the bridge's own call; one raised
 * inside FTB Ranks or another mod is that mod's own failure. Any other failure
 * affects only that one check, which uses the operator check; the bridge stays
 * on and the warning is rate-limited to one a minute with a count. A null
 * manager (FTB Ranks not ready), no rank
 * with a value, or a value that is not a boolean means "no value", which also
 * uses the operator check.</p>
 *
 * <p>The members used: static {@code FTBRanksAPI.manager()},
 * {@code RankManager.getRanks(ServerPlayer)}, {@code Rank.getPermission(String)},
 * {@code PermissionValue.isEmpty()} and {@code PermissionValue.asBoolean()}
 * returning an {@code Optional}.</p>
 */
final class FtbRanksPermissionBridge {
    static final String MARKER_CLASS = "dev.ftb.mods.ftbranks.FTBRanks";
    static final String API_CLASS = "dev.ftb.mods.ftbranks.api.FTBRanksAPI";
    static final String MANAGER_CLASS = "dev.ftb.mods.ftbranks.api.RankManager";
    static final String RANK_CLASS = "dev.ftb.mods.ftbranks.api.Rank";
    static final String VALUE_CLASS = "dev.ftb.mods.ftbranks.api.PermissionValue";
    static final long QUERY_WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_DETAIL_LENGTH = 160;

    /** Class lookup seam; production uses {@code Class.forName(name, false, loader)}. */
    @FunctionalInterface
    interface ClassLookup {
        Class<?> find(String name) throws ClassNotFoundException;
    }

    private static final FtbRanksPermissionBridge ABSENT =
            new FtbRanksPermissionBridge(false, null, message -> { }, System::nanoTime);

    private final boolean installed;
    private final Members members;
    private final Consumer<String> warn;
    private final LongSupplier clock;
    private final AtomicBoolean off = new AtomicBoolean();
    private final Object queryWarningLock = new Object();
    private boolean queryWarningWritten;
    private long lastQueryWarning;
    private long failedQueriesSinceWarning;

    /** Every resolved class and method; all non-null. */
    private record Members(
            Method manager,
            Class<?> rankType,
            Method getRanks,
            Method getPermission,
            Class<?> valueType,
            Method isEmpty,
            Method asBoolean) {}

    private FtbRanksPermissionBridge(boolean installed, Members members, Consumer<String> warn, LongSupplier clock) {
        this.installed = installed;
        this.members = members;
        this.warn = Objects.requireNonNull(warn, "warn");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (members == null) off.set(true);
    }

    /** A bridge for a server without FTB Ranks: never ready, never warns. */
    static FtbRanksPermissionBridge absent() {
        return ABSENT;
    }

    /** FTB Ranks is installed: its main class or its API class can be found. */
    static boolean installed(ClassLookup classes) {
        return present(classes, MARKER_CLASS) || present(classes, API_CLASS);
    }

    static FtbRanksPermissionBridge resolve(ClassLookup classes, Consumer<String> warn) {
        return resolve(classes, warn, System::nanoTime);
    }

    /**
     * Resolves every class and method the bridge uses. When FTB Ranks is not
     * installed this returns {@link #absent()} without a warning. When it is
     * installed and something is missing or has the wrong shape, it returns an
     * OFF bridge after one warning.
     */
    static FtbRanksPermissionBridge resolve(ClassLookup classes, Consumer<String> warn, LongSupplier clock) {
        Objects.requireNonNull(warn, "warn");
        Objects.requireNonNull(clock, "clock");
        if (classes == null || !installed(classes)) return absent();
        String member = "class " + API_CLASS;
        try {
            Class<?> api = classes.find(API_CLASS);
            member = "class " + MANAGER_CLASS;
            Class<?> managerType = classes.find(MANAGER_CLASS);
            member = "class " + RANK_CLASS;
            Class<?> rankType = classes.find(RANK_CLASS);
            member = "class " + VALUE_CLASS;
            Class<?> valueType = classes.find(VALUE_CLASS);
            member = API_CLASS + ".manager()";
            Method manager = method(api, true, managerType, "manager");
            member = MANAGER_CLASS + ".getRanks(ServerPlayer)";
            Method getRanks = method(managerType, false, List.class, "getRanks", ServerPlayer.class);
            member = RANK_CLASS + ".getPermission(String)";
            Method getPermission = method(rankType, false, valueType, "getPermission", String.class);
            member = VALUE_CLASS + ".isEmpty()";
            Method isEmpty = method(valueType, false, boolean.class, "isEmpty");
            member = VALUE_CLASS + ".asBoolean()";
            Method asBoolean = method(valueType, false, Optional.class, "asBoolean");
            return new FtbRanksPermissionBridge(true,
                    new Members(manager, rankType, getRanks, getPermission, valueType, isEmpty, asBoolean), warn, clock);
        } catch (Throwable missing) {
            FtbRanksPermissionBridge bridge = new FtbRanksPermissionBridge(true, null, warn, clock);
            bridge.emitOff("missing " + member);
            return bridge;
        }
    }

    boolean installed() {
        return installed;
    }

    /** Every member resolved and no missing-member or shape failure since this server start. */
    boolean ready() {
        return installed && !off.get();
    }

    /**
     * FTB Ranks' boolean value for exactly {@code node}.
     * {@link EventTrailPermissions.Result#UNDEFINED} means no value, a failed
     * query, or an OFF bridge; the caller then uses the operator check.
     * Never throws.
     */
    EventTrailPermissions.Result query(ServerPlayer player, String node) {
        if (!ready()) return EventTrailPermissions.Result.UNDEFINED;
        Members m = members;
        try {
            Object rankManager = m.manager().invoke(null);
            if (rankManager == null) return EventTrailPermissions.Result.UNDEFINED;
            Object ranks = m.getRanks().invoke(rankManager, player);
            if (!(ranks instanceof List<?> activeRanks)) return shapeFailure("getRanks() returned no list");
            for (Object rank : activeRanks) {
                if (!m.rankType().isInstance(rank)) return shapeFailure("getRanks() returned a non-rank element");
                Object value = m.getPermission().invoke(rank, node);
                if (value == null) continue;
                if (!m.valueType().isInstance(value)) return shapeFailure("getPermission() returned a non-value");
                if (Boolean.TRUE.equals(m.isEmpty().invoke(value))) continue;
                Object optional = m.asBoolean().invoke(value);
                if (!(optional instanceof Optional<?> present)) return shapeFailure("asBoolean() returned no Optional");
                if (present.isEmpty()) return EventTrailPermissions.Result.UNDEFINED;
                Object decided = present.get();
                if (decided instanceof Boolean bool) {
                    return bool ? EventTrailPermissions.Result.TRUE : EventTrailPermissions.Result.FALSE;
                }
                return shapeFailure("asBoolean() returned a non-boolean value");
            }
            return EventTrailPermissions.Result.UNDEFINED;
        } catch (InvocationTargetException thrownByFtbRanks) {
            Throwable cause = thrownByFtbRanks.getCause() == null ? thrownByFtbRanks : thrownByFtbRanks.getCause();
            if (isMissingMember(thrownByFtbRanks)) {
                switchOff("missing member, " + EventTrailDiagnostics.safeFailureSummary(cause));
            } else {
                queryFailed(cause);
            }
            return EventTrailPermissions.Result.UNDEFINED;
        } catch (Throwable reflectionFailure) {
            boolean missing = isMissingMember(reflectionFailure);
            if (missing || isShapeFailure(reflectionFailure)) {
                switchOff((missing ? "missing member, " : "wrong result shape, ")
                        + EventTrailDiagnostics.safeFailureSummary(reflectionFailure));
            } else {
                queryFailed(reflectionFailure);
            }
            return EventTrailPermissions.Result.UNDEFINED;
        }
    }

    /**
     * The adapter rules' "a member the adapter uses is missing", decided by the project's own
     * {@link FailureClassifier} (it unwraps one {@link InvocationTargetException}): a linkage error switches the
     * bridge off for the run only when its deciding frame is the bridge's own call (EventTrail's frame). A
     * linkage error whose deciding frame is inside FTB Ranks or another mod (for example a rank condition) is
     * that mod's own failure: OTHER, so only that one check uses the operator check and the bridge stays on.
     */
    static boolean isMissingMember(Throwable failure) {
        return FailureClassifier.classify(failure).missing();
    }

    /** Reflection itself rejected the call: FTB Ranks returned something of the wrong shape. */
    private static boolean isShapeFailure(Throwable failure) {
        return failure instanceof IllegalArgumentException
                || failure instanceof IllegalAccessException
                || failure instanceof ClassCastException
                || failure instanceof NullPointerException;
    }

    private EventTrailPermissions.Result shapeFailure(String reason) {
        switchOff("wrong result shape, " + reason);
        return EventTrailPermissions.Result.UNDEFINED;
    }

    private void switchOff(String reason) {
        if (off.compareAndSet(false, true)) emitOff(reason);
    }

    private void emitOff(String reason) {
        emit("EventTrail: the FTB Ranks permission bridge is switched off until the next server start ("
                + EventTrailDiagnostics.sanitizeLogValue(reason, MAX_DETAIL_LENGTH)
                + "); EventTrail uses the operator check instead.");
    }

    /** One failed check: the operator check applies to it, the bridge stays on, the warning is rate-limited. */
    private void queryFailed(Throwable cause) {
        String message = null;
        synchronized (queryWarningLock) {
            failedQueriesSinceWarning++;
            long now = clock.getAsLong();
            if (!queryWarningWritten || now - lastQueryWarning >= QUERY_WARNING_INTERVAL_NANOS) {
                long failed = failedQueriesSinceWarning;
                message = "EventTrail: an FTB Ranks permission query failed ("
                        + EventTrailDiagnostics.sanitizeLogValue(
                                EventTrailDiagnostics.safeFailureSummary(cause), MAX_DETAIL_LENGTH)
                        + "); that check used the operator check instead and the FTB Ranks bridge stays on ("
                        + failed + (failed == 1 ? " failed query" : " failed queries")
                        + " since the last warning of this kind; at most one such warning a minute).";
                queryWarningWritten = true;
                lastQueryWarning = now;
                failedQueriesSinceWarning = 0;
            }
        }
        if (message != null) emit(message);
    }

    private void emit(String message) {
        try {
            warn.accept(message);
        } catch (Throwable ignored) {
            // A warning sink must never break a permission check.
        }
    }

    private static Method method(Class<?> owner, boolean isStatic, Class<?> returns, String name,
                                 Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (Modifier.isStatic(method.getModifiers()) != isStatic) {
            throw new NoSuchMethodException(name + (isStatic ? " is not static" : " is static"));
        }
        if (!returns.isAssignableFrom(method.getReturnType())) {
            throw new NoSuchMethodException(name + " returns " + method.getReturnType().getName());
        }
        return method;
    }

    private static boolean present(ClassLookup classes, String name) {
        try {
            return classes.find(name) != null;
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (Throwable linkageProblem) {
            // Present enough to resolve the name; let resolve() report what is missing.
            return true;
        }
    }
}
