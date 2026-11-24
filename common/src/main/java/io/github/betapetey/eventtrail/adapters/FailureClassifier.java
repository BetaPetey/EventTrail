package io.github.betapetey.eventtrail.adapters;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe.FailureKind;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;

/**
 * Sorts one failure of EventTrail adapter work into the fail-safe's two
 * rules: {@link FailureKind#MISSING_MEMBER} (the adapter is switched OFF until
 * restart) or {@link FailureKind#OTHER} (just that action is skipped).
 *
 * <p>Rules, in this order (adapter fail-safe design 2.3 rows 1, 2, 3 and 5;
 * work map R2-core):</p>
 * <ol>
 *   <li>{@link StackOverflowError}: OTHER, tested first, before any cause walk
 *       or allocation.</li>
 *   <li>EventTrail's own {@link MissingAdapterMemberException}, directly or in
 *       the first {@value #MAX_CAUSE_DEPTH} causes: MISSING_MEMBER with its
 *       exact member text.</li>
 *   <li>Otherwise one {@link InvocationTargetException} or
 *       {@link UndeclaredThrowableException} at the top is unwrapped once, and
 *       an {@link ExceptionInInitializerError} is sorted by its cause (an
 *       initializer error without a cause is OTHER).</li>
 *   <li>A {@link LinkageError} whose <em>deciding frame</em> is EventTrail's:
 *       MISSING_MEMBER. The deciding frame is the first stack frame whose
 *       class name starts with none of {@link #SKIPPED_FRAME_PREFIXES} (JDK
 *       frames and the class-loading or transforming frames of the runtime).
 *       It is EventTrail's when its class is an EventTrail class, or when its
 *       method name carries EventTrail's handler marker
 *       {@value #HANDLER_MARKER}: Mixin merges a hook handler and the lambdas
 *       javac names after it ({@code lambda$eventtrail$...}) into the mod's
 *       target class, and every name Mixin gives them keeps the marker
 *       (sponge-mixin 0.15.2 {@code MethodMapper.getHandlerName} and
 *       {@code getUniqueName}; review-02 MINOR-2). If that frame is in the
 *       mod's own code, the linkage error is the mod's own failure: OTHER. A
 *       linkage error without any frame left after skipping is OTHER (a
 *       missing prefix can only make a case OTHER, never wrongly OFF).</li>
 *   <li>{@code NoClassDefFoundError "Could not initialize class X"} (an earlier
 *       static initialiser failure of X): MISSING_MEMBER only when X is an
 *       EventTrail class and the deciding frame is EventTrail's; OTHER for a
 *       mod class X, whose initialiser failure is the mod's own. There is no
 *       table of earlier initialiser failures (work map row K), so an
 *       EventTrail class X whose initialiser first failed for an ordinary
 *       reason switches its adapter OFF, loudly, at the next use: the accepted
 *       corner (it records less, never a wrong row).</li>
 *   <li>Anything else, including {@link OutOfMemoryError}, {@link InternalError}
 *       and checked exceptions: OTHER.</li>
 * </ol>
 *
 * <p>The frame prefix list is pinned by {@code FailureClassifierTest}; which
 * frames NeoForge's module class loader really puts on the stack is
 * UNVERIFIED by a run ({@code AdapterLinkageClassLoaderTest} proves the JDK
 * class loader's shapes only). Pure and stateless; safe from any thread.</p>
 */
public final class FailureClassifier {
    /** Bound of every cause walk. */
    static final int MAX_CAUSE_DEPTH = 8;
    /** The package of every EventTrail class. */
    static final String EVENTTRAIL_PREFIX = "io.github.betapetey.eventtrail.";
    /**
     * EventTrail's hook handler marker. Every converted handler is named {@code eventtrail$...}; the
     * Mixin-merged handler and lambda names keep it, inside the mod's target class (the merged-name shape
     * on a running server is UNVERIFIED by a run).
     */
    static final String HANDLER_MARKER = "eventtrail$";
    /** Frames that never decide: the JDK and the runtime's class-loading and transforming frames. */
    public static final List<String> SKIPPED_FRAME_PREFIXES = List.of(
            "java.",
            "jdk.",
            "sun.",
            "com.sun.",
            "cpw.mods.",
            "net.neoforged.fml.loading.",
            "org.spongepowered.asm.",
            "com.llamalad7.mixinextras.");
    static final String UNINITIALISED_PREFIX = "Could not initialize class ";
    private static final Classification OTHER = new Classification(FailureKind.OTHER, null);

    private FailureClassifier() {
    }

    /** The sorting of one failure; {@code member} names what is missing and is null for OTHER. */
    public record Classification(FailureKind kind, String member) {
        public boolean missing() {
            return kind == FailureKind.MISSING_MEMBER;
        }
    }

    /** Sorts {@code failure}; never throws for a well-formed throwable, and null is OTHER. */
    public static Classification classify(Throwable failure) {
        if (failure == null || failure instanceof StackOverflowError) return OTHER;
        MissingAdapterMemberException typed = typedMissing(failure);
        if (typed != null) return new Classification(FailureKind.MISSING_MEMBER, typed.member());
        Throwable decided = failure;
        if (decided instanceof InvocationTargetException || decided instanceof UndeclaredThrowableException) {
            decided = decided.getCause();
        }
        for (int depth = 0; decided instanceof ExceptionInInitializerError && depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable cause = decided.getCause();
            if (cause == decided) return OTHER;
            decided = cause;
        }
        if (decided == null || decided instanceof StackOverflowError) return OTHER;
        if (!(decided instanceof LinkageError) || decided instanceof ExceptionInInitializerError) return OTHER;
        LinkageError linkage = (LinkageError) decided;
        if (!eventTrailFrame(decidingFrame(linkage))) return OTHER;
        String uninitialised = uninitialisedClass(linkage);
        if (uninitialised != null && !uninitialised.startsWith(EVENTTRAIL_PREFIX)) return OTHER;
        return new Classification(FailureKind.MISSING_MEMBER, memberText(linkage, uninitialised));
    }

    /** The first frame whose class is not skipped, or null when every frame is skipped. */
    static StackTraceElement decidingFrame(Throwable failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        if (frames == null) return null;
        for (StackTraceElement frame : frames) {
            if (frame == null) continue;
            if (!skipped(frame.getClassName())) return frame;
        }
        return null;
    }

    static boolean skipped(String className) {
        if (className == null) return true;
        for (String prefix : SKIPPED_FRAME_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * An EventTrail class, or an EventTrail hook handler or lambda that Mixin merged into a mod class
     * (method name carrying {@value #HANDLER_MARKER}). The mod's own method that fails is the top frame
     * itself, so its linkage error stays OTHER.
     */
    static boolean eventTrailFrame(StackTraceElement frame) {
        if (frame == null) return false;
        String className = frame.getClassName();
        if (className != null && className.startsWith(EVENTTRAIL_PREFIX)) return true;
        String methodName = frame.getMethodName();
        return methodName != null && methodName.contains(HANDLER_MARKER);
    }

    private static MissingAdapterMemberException typedMissing(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof MissingAdapterMemberException missing) return missing;
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return null;
    }

    /** X of a {@code NoClassDefFoundError "Could not initialize class X"}, with dots; else null. */
    private static String uninitialisedClass(LinkageError linkage) {
        if (!(linkage instanceof NoClassDefFoundError)) return null;
        String message = linkage.getMessage();
        if (message == null || !message.startsWith(UNINITIALISED_PREFIX)) return null;
        return message.substring(UNINITIALISED_PREFIX.length()).trim().replace('/', '.');
    }

    /**
     * Operator text for the OFF warning: {@code class X} for a missing class (internal names converted),
     * {@code class X (failed to initialise)} for a failed initialiser, otherwise the error's simple name and
     * the JDK message (which names the member).
     */
    private static String memberText(LinkageError linkage, String uninitialised) {
        if (uninitialised != null) return "class " + safe(uninitialised) + " (failed to initialise)";
        String message = linkage.getMessage();
        if (linkage instanceof NoClassDefFoundError) {
            return "class " + safe(message == null ? null : message.replace('/', '.'));
        }
        return safe(linkage.getClass().getSimpleName() + (message == null ? "" : " " + message));
    }

    private static String safe(String value) {
        return EventTrailDiagnostics.sanitizeLogValue(value, MissingAdapterMemberException.MAX_MEMBER_TEXT);
    }
}
