package io.github.betapetey.eventtrail.adapters.hooks.check;

import java.util.List;
import java.util.Objects;

/**
 * What one optional-mod hook class asks of the classes it targets, as read from its own class bytes by
 * {@link HookReader}. Every name is a JVM internal name ({@code a/b/C}) and every type a JVM descriptor.
 *
 * <p>The model holds only the Mixin and MixinExtras features EventTrail's hooks use. A hook that uses anything else is
 * not modelled: the reader refuses it with {@link UnsupportedHookException} instead of guessing.</p>
 */
public record HookModel(
        String mixinClass,
        Kind kind,
        boolean pseudo,
        List<String> targets,
        String superName,
        List<String> interfaces,
        List<ShadowField> shadowFields,
        List<ShadowMethod> shadowMethods,
        List<AccessorMethod> accessors,
        List<Injector> injectors,
        List<Reference> references) {

    public HookModel {
        Objects.requireNonNull(mixinClass, "mixinClass");
        Objects.requireNonNull(kind, "kind");
        targets = List.copyOf(targets);
        interfaces = List.copyOf(interfaces);
        shadowFields = List.copyOf(shadowFields);
        shadowMethods = List.copyOf(shadowMethods);
        accessors = List.copyOf(accessors);
        injectors = List.copyOf(injectors);
        references = List.copyOf(references);
    }

    /**
     * Mixin's own sorting of a mixin class: a class is STANDARD; an interface whose methods are all accessors is
     * ACCESSOR (it may target a class or an interface); any other interface is INTERFACE (it needs an interface target).
     */
    public enum Kind { STANDARD, INTERFACE, ACCESSOR }

    /** The injector annotations the reader models. */
    public enum InjectorKind { INJECT, WRAP_OPERATION, WRAP_METHOD }

    /** The {@code @At} values the reader models. */
    public enum PointKind { HEAD, RETURN, TAIL, INVOKE }

    /** A {@code @Shadow} field: the target must declare it with this descriptor and static modifier. */
    public record ShadowField(String name, String desc, boolean isStatic) {
    }

    /**
     * A {@code @Shadow} method: the target class itself must declare it with this descriptor and static modifier; a
     * superclass does not count.
     */
    public record ShadowMethod(String name, String desc, boolean isStatic) {
    }

    /**
     * An {@code @Accessor}: the target must declare {@code field} with the accessor's value type and static modifier.
     * A getter takes no argument; a setter takes the value and returns void.
     */
    public record AccessorMethod(String method, String desc, String field, boolean getter, boolean isStatic) {
    }

    /**
     * A target-method selector. {@code owner} and {@code desc} are null when the selector does not name them. Mixin
     * gives a selector without a quantifier one match: the first method of the target, in class-file order, whose name
     * (and descriptor, if given) is equal.
     */
    public record Selector(String owner, String name, String desc) {
        public Selector {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String toString() {
            return (owner == null ? "" : "L" + owner + ";") + name + (desc == null ? "" : desc);
        }
    }

    /**
     * One {@code @At}. For INVOKE, {@code owner}, {@code name} and {@code desc} name the call and {@code ordinal} picks
     * one matching call (-1: every matching call). For HEAD, RETURN and TAIL they are null and {@code ordinal} is -1.
     */
    public record Point(PointKind kind, String owner, String name, String desc, int ordinal) {
        public Point {
            Objects.requireNonNull(kind, "kind");
        }

        @Override
        public String toString() {
            return kind == PointKind.INVOKE
                    ? "INVOKE L" + owner + ";" + name + desc + (ordinal < 0 ? "" : " ordinal " + ordinal)
                    : kind.name();
        }
    }

    /**
     * The counts a hook was written for. {@code allow} below zero means no upper bound. {@code fromHookSite} says
     * whether they came from EventTrail's {@code HookSite} or from the Mixin annotation itself.
     */
    public record Counts(int require, int expect, int allow, boolean fromHookSite) {
        /** The most injection points Mixin accepts for these counts ({@code InjectionInfo.parseRequirements}). */
        public int maximum() {
            return allow < 0 ? Integer.MAX_VALUE : Math.max(Math.max(require, 1), allow);
        }
    }

    /**
     * A MixinExtras {@code @Local} parameter of a handler: {@code parameter} is its index in the handler's
     * descriptor, {@code desc} its type.
     */
    public record LocalCapture(int parameter, String desc, boolean argsOnly) {
    }

    /**
     * One injector handler.
     *
     * <p>{@code baseArguments} is the number of handler parameters before the trailing ones Mixin fills itself: for
     * {@code @Inject} the parameters before the {@code CallbackInfo}; for the two wrap injectors the parameters before
     * the {@code Operation}. {@code capturedTargetArguments} is the number of parameters after the {@code Operation}
     * that are not {@code @Local}; Mixin fills them with the target method's first arguments.</p>
     */
    public record Injector(
            InjectorKind kind,
            String handler,
            String handlerDesc,
            boolean handlerStatic,
            List<Selector> selectors,
            List<Point> points,
            Counts counts,
            int baseArguments,
            int capturedTargetArguments,
            List<LocalCapture> locals) {

        public Injector {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(handlerDesc, "handlerDesc");
            selectors = List.copyOf(selectors);
            points = List.copyOf(points);
            Objects.requireNonNull(counts, "counts");
            locals = List.copyOf(locals);
        }

        /** {@code Class#handler(desc)}, for warnings. */
        public String describe(String mixinClass) {
            return mixinClass.replace('/', '.') + "#" + handler + handlerDesc;
        }
    }

    /** What a reference in the hook's own code names. */
    public enum ReferenceKind { CLASS, METHOD, FIELD }

    /**
     * A class or member that an instruction in the hook class names. For CLASS, {@code name} and {@code desc} are
     * null. Only references to classes outside the fixed platform (the JDK, Minecraft, NeoForge, Mixin and their
     * bundled libraries, and EventTrail itself) are kept: those are the ones another mod can change.
     */
    public record Reference(ReferenceKind kind, String owner, String name, String desc) {
        public Reference {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(owner, "owner");
        }

        @Override
        public String toString() {
            return switch (kind) {
                case CLASS -> owner;
                case METHOD -> owner + "." + name + desc;
                case FIELD -> owner + "." + name + ":" + desc;
            };
        }
    }
}
