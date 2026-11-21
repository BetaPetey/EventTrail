package io.github.betapetey.eventtrail.compat;

import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;

import java.util.Objects;

/**
 * Thrown by EventTrail's own reflective adapter helpers when a class, method or
 * field that the adapter uses is not present, or is present but cannot be
 * accessed, in the installed optional mod.
 *
 * <p>The fail-safe sorts this exception, directly or as the cause of an
 * EventTrail wrapper, as a missing member: the adapter is switched off until
 * the server restarts, with one warning naming the member. Reflective
 * exceptions are converted into this type at their source; adapter code must
 * not turn a missing member into a silent {@code null} or {@code false}.</p>
 *
 * <p>The message names only classes and members, never player or world data.</p>
 */
public final class MissingAdapterMemberException extends RuntimeException {
    static final int MAX_MEMBER_TEXT = 160;

    /** Whether the member is absent or present but not accessible. */
    public enum Kind {
        MISSING,
        INACCESSIBLE
    }

    private final Kind kind;
    private final String owner;
    private final String name;
    private final String descriptor;

    public MissingAdapterMemberException(
            Kind kind, String owner, String name, String descriptor, Throwable cause) {
        super(describe(kind, owner, name, descriptor), cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.owner = ownerText(owner);
        this.name = name == null ? "" : text(name);
        this.descriptor = descriptor == null ? "" : text(descriptor);
    }

    /** A class the adapter names is not present. */
    public static MissingAdapterMemberException missingClass(String className, Throwable cause) {
        return new MissingAdapterMemberException(Kind.MISSING, className, null, null, cause);
    }

    /** A method or field the adapter uses is not present on {@code owner}. */
    public static MissingAdapterMemberException missing(
            String owner, String name, String descriptor, Throwable cause) {
        return new MissingAdapterMemberException(Kind.MISSING, owner, name, descriptor, cause);
    }

    /** A method or field the adapter uses is present but cannot be accessed. */
    public static MissingAdapterMemberException inaccessible(
            String owner, String name, String descriptor, Throwable cause) {
        return new MissingAdapterMemberException(Kind.INACCESSIBLE, owner, name, descriptor, cause);
    }

    public Kind kind() {
        return kind;
    }

    public String owner() {
        return owner;
    }

    /** Member name, or empty when the missing part is the class itself. */
    public String name() {
        return name;
    }

    /** Member descriptor, or empty when not known. */
    public String descriptor() {
        return descriptor;
    }

    /**
     * Operator-facing text of the missing part: {@code owner#name descriptor},
     * or {@code class owner} when the class itself is missing.
     */
    public String member() {
        return member(owner, name, descriptor);
    }

    private static String describe(Kind kind, String owner, String name, String descriptor) {
        String state = kind == Kind.INACCESSIBLE ? "inaccessible" : "missing";
        return "EventTrail adapter member " + state + ": " + member(ownerText(owner),
                name == null ? "" : text(name), descriptor == null ? "" : text(descriptor));
    }

    private static String member(String owner, String name, String descriptor) {
        if (name.isEmpty()) return "class " + owner;
        String member = owner + "#" + name;
        return descriptor.isEmpty() ? member : member + " " + descriptor;
    }

    private static String ownerText(String owner) {
        return text(owner == null ? null : owner.replace('/', '.'));
    }

    private static String text(String value) {
        return EventTrailDiagnostics.sanitizeLogValue(value, MAX_MEMBER_TEXT);
    }
}
