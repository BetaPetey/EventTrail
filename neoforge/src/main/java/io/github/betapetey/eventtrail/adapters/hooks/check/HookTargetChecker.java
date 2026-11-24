package io.github.betapetey.eventtrail.adapters.hooks.check;

import io.github.betapetey.eventtrail.adapters.hooks.check.HookCheckResult.Outcome;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.AccessorMethod;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.Counts;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.Injector;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.LocalCapture;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.Point;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.Reference;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.ReferenceKind;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.Selector;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.ShadowField;
import io.github.betapetey.eventtrail.adapters.hooks.check.HookModel.ShadowMethod;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The pre-apply check of one optional-mod hook class against one target class (work map R4-check).
 *
 * <p>It checks, against the other mod's class bytes as read before Mixin runs, what Mixin 0.15.2 and MixinExtras
 * 0.5.3 need in order to apply the hook cleanly:</p>
 * <ul>
 *   <li>the target class is present, and of the kind the mixin needs (a class mixin needs a class, an interface
 *       mixin an interface; an accessor mixin takes either), and has the mixin's superclass in its chain;</li>
 *   <li>every {@code @Shadow} field and every {@code @Shadow} method is declared by the target class itself, never
 *       only by a superclass, with the same descriptor and static modifier ({@code TargetClassContext.findMethod}
 *       searches only the target's own methods); every {@code @Accessor} field by the target;</li>
 *   <li>every method selector, with Mixin's default quantifier: the first method of the target, in class-file order,
 *       whose name (and descriptor, if given) is equal;</li>
 *   <li>the handler of each selected method: static modifier, signature (target arguments, {@code CallbackInfo} or
 *       {@code CallbackInfoReturnable}, wrapped-call operands and receiver, {@code Operation}, captured target
 *       arguments), a delegate constructor call in constructor targets, code present, and a unique {@code @Local};</li>
 *   <li>the number of injection points (HEAD, RETURN, TAIL, INVOKE with ordinal) over all selected methods, within
 *       the hook's {@code require} and {@code allow};</li>
 *   <li>every class the hook's own instructions name outside the fixed platform is present (Mixin's attach stage
 *       stops the server otherwise), and every member it names is present where the class hierarchy can be read.</li>
 * </ul>
 *
 * <p>It never throws: any failure while reading or checking makes only this hook on this target
 * {@link Outcome#UNVERIFIABLE}. It cannot see what another mod's transformer or Mixin does after the read (Owner
 * risk 2). Links only to the JDK and ASM.</p>
 */
public final class HookTargetChecker {
    static final String OBJECT = "java/lang/Object";
    private static final Type OPERATION_TYPE = Type.getType(HookReader.OPERATION);

    /** The methods every class has from {@code java.lang.Object}, which the source never reads. */
    private static final Set<String> OBJECT_METHODS = Set.of(
            "<init>()V", "equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;",
            "getClass()Ljava/lang/Class;", "notify()V", "notifyAll()V", "wait()V", "wait(J)V", "wait(JI)V",
            "clone()Ljava/lang/Object;", "finalize()V");

    private HookTargetChecker() {
    }

    /**
     * Reads the hook class and checks it against {@code target}. Never throws.
     *
     * @param hookBytes      the hook class's bytes, from EventTrail's own jar
     * @param target         the target class name, dotted or internal
     * @param source         where the target and the classes it needs are read from
     * @param defaultRequire the hook list's {@code injectors.defaultRequire}
     */
    public static HookCheckResult check(byte[] hookBytes, String target, ClassSource source, int defaultRequire) {
        String targetName = target == null ? "?" : target.replace('.', '/');
        String hookName = "?";
        HookModel hook;
        try {
            hookName = new ClassReader(hookBytes).getClassName();
            hook = HookReader.read(hookBytes, defaultRequire);
        } catch (UnsupportedHookException notUnderstood) {
            return HookCheckResult.of(hookName, targetName, Outcome.UNVERIFIABLE,
                    "the pre-check does not understand the hook: " + notUnderstood.getMessage());
        } catch (Throwable unreadable) {
            return HookCheckResult.of(hookName, targetName, Outcome.UNVERIFIABLE,
                    "the hook class could not be read: " + describe(unreadable));
        }
        return check(hook, targetName, source);
    }

    /** Checks an already-read hook against {@code target}. Never throws. */
    public static HookCheckResult check(HookModel hook, String target, ClassSource source) {
        String hookName = hook == null ? "?" : hook.mixinClass();
        String targetName = target == null ? "?" : target.replace('.', '/');
        try {
            if (hook == null || source == null || target == null) {
                return HookCheckResult.of(hookName, targetName, Outcome.UNVERIFIABLE, "nothing to check");
            }
            return new Run(hook, targetName, source).check();
        } catch (Throwable failure) {
            return HookCheckResult.of(hookName, targetName, Outcome.UNVERIFIABLE,
                    "the check failed while reading: " + describe(failure));
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Thrown inside one run to stop at the first unmet condition. */
    private static final class Unmet extends Exception {
        private static final long serialVersionUID = 1L;
        private final Outcome outcome;

        Unmet(Outcome outcome, String reason) {
            super(reason, null, false, false);
            this.outcome = outcome;
        }
    }

    /** Whether a member could be found in a class hierarchy. */
    private enum Presence { PRESENT, ABSENT, UNKNOWN }

    /** One check of one hook on one target, with a per-run cache of the classes read. */
    private static final class Run {
        private final HookModel hook;
        private final String targetName;
        private final ClassSource source;
        private final Map<String, Optional<ClassNode>> classes = new HashMap<>();

        Run(HookModel hook, String targetName, ClassSource source) {
            this.hook = hook;
            this.targetName = targetName;
            this.source = source;
        }

        HookCheckResult check() throws Exception {
            try {
                ClassNode target = find(targetName);
                if (target == null) {
                    throw new Unmet(Outcome.TARGET_ABSENT, "the target class is not present");
                }
                checkKind(target);
                checkSuperclass(target);
                checkShadows(target);
                checkAccessors(target);
                for (Injector injector : hook.injectors()) {
                    checkInjector(target, injector);
                }
                checkReferences();
                return HookCheckResult.pass(hook.mixinClass(), targetName);
            } catch (Unmet unmet) {
                return HookCheckResult.of(hook.mixinClass(), targetName, unmet.outcome, unmet.getMessage());
            }
        }

        private ClassNode find(String name) throws Exception {
            if (HookReader.isJdk(name)) return null;
            Optional<ClassNode> known = classes.get(name);
            if (known != null) return known.orElse(null);
            ClassNode node = source.find(name);
            classes.put(name, Optional.ofNullable(node));
            return node;
        }

        private void checkKind(ClassNode target) throws Unmet {
            boolean targetIsInterface = (target.access & Opcodes.ACC_INTERFACE) != 0;
            switch (hook.kind()) {
                case STANDARD -> {
                    if (targetIsInterface) throw failed("the hook is a class mixin but the target is an interface");
                }
                case INTERFACE -> {
                    if (!targetIsInterface) throw failed("the hook is an interface mixin but the target is a class");
                }
                case ACCESSOR -> {
                }
            }
        }

        private void checkSuperclass(ClassNode target) throws Exception {
            String wanted = hook.superName();
            if (hook.kind() != HookModel.Kind.STANDARD || wanted == null || OBJECT.equals(wanted)) return;
            Set<String> seen = new HashSet<>();
            String current = target.superName;
            while (current != null && seen.add(current)) {
                if (current.equals(wanted)) return;
                if (OBJECT.equals(current)) {
                    throw failed("the hook extends " + dotted(wanted) + ", which is not a superclass of the target");
                }
                ClassNode node = find(current);
                if (node == null) {
                    throw new Unmet(Outcome.UNVERIFIABLE,
                            "the target's superclass " + dotted(current) + " could not be read");
                }
                current = node.superName;
            }
            throw failed("the hook extends " + dotted(wanted) + ", which is not a superclass of the target");
        }

        /**
         * Both kinds of {@code @Shadow} must be declared by the target class itself. Mixin 0.15.2 looks a shadow up
         * with {@code TargetClassContext.findMethod} / {@code findField} (and the remapped name with
         * {@code findAliasedMethod} / {@code findAliasedField}), which search only the target's own members and the
         * members earlier mixins added to it; a superclass is never searched, and a miss is an
         * {@code InvalidMixinException} ({@code MixinPreProcessorStandard.attachSpecialMethod}, {@code attachFields}).
         * Members added by other mixins cannot be seen here, so the check is stricter than Mixin for them, and for a
         * {@code shadow$}-prefixed method name, which the reader does not strip.
         */
        private void checkShadows(ClassNode target) throws Unmet {
            for (ShadowField shadow : hook.shadowFields()) {
                FieldNode field = field(target, shadow.name(), shadow.desc());
                if (field == null) {
                    throw failed("the @Shadow field " + shadow.name() + " " + shadow.desc() + " is missing");
                }
                if (isStatic(field.access) != shadow.isStatic()) {
                    throw failed("the @Shadow field " + shadow.name() + " differs in its static modifier");
                }
            }
            for (ShadowMethod shadow : hook.shadowMethods()) {
                MethodNode found = method(target, shadow.name(), shadow.desc());
                if (found == null) {
                    throw failed("the @Shadow method " + shadow.name() + shadow.desc()
                            + " is missing from the target class");
                }
                if (isStatic(found.access) != shadow.isStatic()) {
                    throw failed("the @Shadow method " + shadow.name() + shadow.desc()
                            + " differs in its static modifier");
                }
            }
        }

        private void checkAccessors(ClassNode target) throws Unmet {
            for (AccessorMethod accessor : hook.accessors()) {
                Type[] arguments = Type.getArgumentTypes(accessor.desc());
                String fieldDesc = accessor.getter()
                        ? Type.getReturnType(accessor.desc()).getDescriptor()
                        : arguments[0].getDescriptor();
                FieldNode field = field(target, accessor.field(), fieldDesc);
                if (field == null) {
                    throw failed("the @Accessor field " + accessor.field() + " " + fieldDesc + " is missing");
                }
                if (isStatic(field.access) != accessor.isStatic()) {
                    throw failed("the @Accessor field " + accessor.field() + " differs in its static modifier");
                }
            }
        }

        /**
         * Checks one injector on every method its selectors take. Within one selected method an instruction counts
         * once, however many of the injector's {@code @At} select it, as Mixin's {@code Injector.findTargetNodes}
         * keys its target nodes by instruction. When an {@code @Inject} selects more than one method, Mixin's
         * {@code CallbackInjector} skips a selected method whose handler descriptor does not fit and lets the counts
         * decide; this check is stricter on purpose and fails the whole hook instead.
         */
        private void checkInjector(ClassNode target, Injector injector) throws Unmet {
            String handler = injector.handler() + injector.handlerDesc();
            List<MethodNode> selected = new ArrayList<>();
            for (Selector selector : injector.selectors()) {
                for (MethodNode method : target.methods) {
                    if (matches(selector, target.name, method)) {
                        selected.add(method);
                        break;
                    }
                }
            }
            int injections = 0;
            for (MethodNode method : selected) {
                String where = handler + " in " + method.name + method.desc;
                switch (injector.kind()) {
                    case WRAP_METHOD -> {
                        if (isStatic(method.access) != injector.handlerStatic()) {
                            throw failed(where + ": the static modifier differs from the target");
                        }
                        if (!hasCode(method)) throw failed(where + ": the target method has no code");
                        String expected = Type.getMethodDescriptor(Type.getReturnType(method.desc),
                                append(Type.getArgumentTypes(method.desc), OPERATION_TYPE));
                        if (!expected.equals(injector.handlerDesc())) {
                            throw failed(where + ": the handler signature should be " + expected);
                        }
                        injections++;
                    }
                    case INJECT -> {
                        if (!injector.handlerStatic() && isStatic(method.access)) {
                            throw failed(where + ": an instance handler cannot target a static method");
                        }
                        if (!hasCode(method)) throw failed(where + ": the target method has no code");
                        Type returnType = Type.getReturnType(method.desc);
                        String info = returnType.getSort() == Type.VOID
                                ? HookReader.CALLBACK_INFO
                                : HookReader.CALLBACK_INFO_RETURNABLE;
                        String withArguments = "(" + method.desc.substring(1, method.desc.indexOf(')')) + info + ")V";
                        String withoutArguments = "(" + info + ")V";
                        if (!withArguments.equals(injector.handlerDesc())
                                && !withoutArguments.equals(injector.handlerDesc())) {
                            throw failed(where + ": the handler signature should be " + withArguments
                                    + " or " + withoutArguments);
                        }
                        if ("<init>".equals(method.name) && !hasDelegateConstructorCall(target, method)) {
                            throw failed(where + ": the constructor has no delegate constructor call");
                        }
                        Set<AbstractInsnNode> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
                        for (Point point : injector.points()) {
                            List<AbstractInsnNode> found = injectionPoints(point, method);
                            if (found == null) throw failed(where + ": TAIL found no return instruction");
                            for (AbstractInsnNode insn : found) {
                                if (chosen.add(insn)) injections++;
                            }
                        }
                    }
                    case WRAP_OPERATION -> {
                        Set<AbstractInsnNode> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
                        for (Point point : injector.points()) {
                            for (MethodInsnNode call : calls(point, method)) {
                                if (!chosen.add(call)) continue;
                                checkWrappedCall(target, method, call, injector, where);
                                injections++;
                            }
                        }
                    }
                }
            }
            Counts counts = injector.counts();
            if (injections < counts.require() || injections > counts.maximum()) {
                String bound = counts.maximum() == Integer.MAX_VALUE ? "or more" : "to " + counts.maximum();
                throw failed(handler + ": found " + injections + " injection point(s); it was written for "
                        + counts.require() + " " + bound);
            }
        }

        private void checkWrappedCall(ClassNode target, MethodNode method, MethodInsnNode call, Injector injector,
                String where) throws Unmet {
            if (!injector.handlerStatic() && isStatic(method.access)) {
                throw failed(where + ": an instance handler cannot target a static method");
            }
            List<Type> expected = new ArrayList<>();
            switch (call.getOpcode()) {
                case Opcodes.INVOKESTATIC -> {
                }
                case Opcodes.INVOKESPECIAL -> expected.add(Type.getObjectType(target.name));
                default -> expected.add(Type.getObjectType(call.owner));
            }
            expected.addAll(List.of(Type.getArgumentTypes(call.desc)));
            expected.add(OPERATION_TYPE);
            Type[] handlerArguments = Type.getArgumentTypes(injector.handlerDesc());
            Type handlerReturn = Type.getReturnType(injector.handlerDesc());
            String wanted = Type.getMethodDescriptor(Type.getReturnType(call.desc), expected.toArray(new Type[0]));
            if (!handlerReturn.equals(Type.getReturnType(call.desc))
                    || injector.baseArguments() + 1 != expected.size()) {
                throw failed(where + ": the wrapped call needs a handler shaped " + wanted);
            }
            for (int index = 0; index < expected.size(); index++) {
                if (!handlerArguments[index].equals(expected.get(index))) {
                    throw failed(where + ": the wrapped call needs a handler shaped " + wanted);
                }
            }
            Type[] targetArguments = Type.getArgumentTypes(method.desc);
            int captured = injector.capturedTargetArguments();
            if (captured > targetArguments.length) {
                throw failed(where + ": the handler captures " + captured + " target argument(s) but the target has "
                        + targetArguments.length);
            }
            for (int index = 0; index < captured; index++) {
                if (!handlerArguments[expected.size() + index].equals(targetArguments[index])) {
                    throw failed(where + ": captured target argument " + index + " should be "
                            + targetArguments[index].getDescriptor());
                }
            }
            for (LocalCapture local : injector.locals()) {
                int candidates = localCandidates(local, method, call);
                if (candidates != 1) {
                    throw failed(where + ": @Local " + local.desc() + " needs exactly one candidate but found "
                            + (candidates < 0 ? "no local variable table" : String.valueOf(candidates)));
                }
            }
        }

        private void checkReferences() throws Exception {
            for (Reference reference : hook.references()) {
                if (reference.kind() == ReferenceKind.CLASS) {
                    if (find(reference.owner()) == null) {
                        throw failed("the hook's code names " + dotted(reference.owner()) + ", which is not present");
                    }
                    continue;
                }
                Presence presence = memberPresence(reference);
                if (presence == Presence.ABSENT) {
                    throw failed("the hook's code names " + reference + ", which is not present");
                }
            }
        }

        /**
         * Searches the owner, its superclasses and its interfaces. ABSENT only when every class of the hierarchy was
         * read (or is {@code java.lang.Object}); UNKNOWN when part of it could not be read. A constructor
         * ({@code <init>}) is not inherited, so it is searched on the owner only.
         */
        private Presence memberPresence(Reference reference) throws Exception {
            boolean isMethod = reference.kind() == ReferenceKind.METHOD;
            if (isMethod && "<init>".equals(reference.name())) {
                ClassNode owner = find(reference.owner());
                if (owner == null) return Presence.UNKNOWN;
                return method(owner, reference.name(), reference.desc()) != null ? Presence.PRESENT : Presence.ABSENT;
            }
            boolean unknown = false;
            Deque<String> pending = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            pending.add(reference.owner());
            while (!pending.isEmpty()) {
                String name = pending.removeFirst();
                if (!seen.add(name)) continue;
                if (OBJECT.equals(name)) {
                    if (isMethod && OBJECT_METHODS.contains(reference.name() + reference.desc())) {
                        return Presence.PRESENT;
                    }
                    continue;
                }
                ClassNode node = find(name);
                if (node == null) {
                    unknown = true;
                    continue;
                }
                if (isMethod ? method(node, reference.name(), reference.desc()) != null
                        : field(node, reference.name(), reference.desc()) != null) {
                    return Presence.PRESENT;
                }
                if (node.superName != null) pending.add(node.superName);
                if (node.interfaces != null) pending.addAll(node.interfaces);
            }
            return unknown ? Presence.UNKNOWN : Presence.ABSENT;
        }

        private Unmet failed(String reason) {
            return new Unmet(Outcome.FAILED, reason);
        }
    }

    /** Mixin's {@code MemberInfo.matches} for a method of the target, exact matches only. */
    static boolean matches(Selector selector, String targetName, MethodNode method) {
        if (selector.desc() != null && !selector.desc().equals(method.desc)) return false;
        if (selector.owner() != null && !selector.owner().equals(targetName)) return false;
        return selector.name().equals(method.name);
    }

    /**
     * The instructions one {@code @At} of an {@code @Inject} selects in one method, as Mixin's {@code MethodHead}
     * (the first instruction), {@code BeforeReturn} (every return) and {@code BeforeFinalReturn} (the last return)
     * find them. Returns null for a TAIL with no return instruction. INVOKE is not modelled for {@code @Inject} (the
     * reader refuses it), so it selects nothing.
     */
    static List<AbstractInsnNode> injectionPoints(Point point, MethodNode method) {
        int returnOpcode = Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN);
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InsnNode && insn.getOpcode() == returnOpcode) returns.add(insn);
        }
        return switch (point.kind()) {
            case HEAD -> method.instructions.size() > 0 ? List.of(method.instructions.getFirst()) : List.of();
            case RETURN -> returns;
            case TAIL -> returns.isEmpty() ? null : List.of(returns.get(returns.size() - 1));
            case INVOKE -> List.of();
        };
    }

    /** The calls one INVOKE {@code @At} selects in one method, as Mixin's {@code BeforeInvoke} finds them. */
    static List<MethodInsnNode> calls(Point point, MethodNode method) {
        List<MethodInsnNode> found = new ArrayList<>();
        int ordinal = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(point.owner())
                    && call.name.equals(point.name())
                    && call.desc.equals(point.desc())) {
                if (point.ordinal() < 0 || point.ordinal() == ordinal) found.add(call);
                ordinal++;
            }
        }
        return found;
    }

    /**
     * Candidates for one {@code @Local} at one call, after MixinExtras' implicit discriminator: argsOnly counts the
     * target's arguments of that type; otherwise the arguments and the local variables in scope at the call, from the
     * local-variable table (an approximation of Mixin's {@code Locals.getLocalsAt}). {@code this} never counts.
     * Returns -1 when an implicit capture is asked of a method with no local-variable table.
     */
    static int localCandidates(LocalCapture local, MethodNode method, AbstractInsnNode at) {
        boolean isStatic = isStatic(method.access);
        Type[] arguments = Type.getArgumentTypes(method.desc);
        int count = 0;
        if (local.argsOnly()) {
            for (Type argument : arguments) {
                if (argument.getDescriptor().equals(local.desc())) count++;
            }
            return count;
        }
        if (method.localVariables == null || method.localVariables.isEmpty()) return -1;
        int first = isStatic ? 0 : 1;
        Map<Integer, String> slots = new HashMap<>();
        int slot = first;
        for (Type argument : arguments) {
            slots.put(slot, argument.getDescriptor());
            slot += argument.getSize();
        }
        int position = method.instructions.indexOf(at);
        for (LocalVariableNode variable : method.localVariables) {
            int start = method.instructions.indexOf(variable.start);
            int end = method.instructions.indexOf(variable.end);
            if (start <= position && position < end) slots.put(variable.index, variable.desc);
        }
        for (Map.Entry<Integer, String> entry : slots.entrySet()) {
            if (entry.getKey() >= first && entry.getValue().equals(local.desc())) count++;
        }
        return count;
    }

    /** Whether a constructor calls {@code super(...)} or {@code this(...)}, as Mixin's delegate lookup needs. */
    static boolean hasDelegateConstructorCall(ClassNode target, MethodNode constructor) {
        for (AbstractInsnNode insn : constructor.instructions) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name)
                    && (call.owner.equals(target.superName) || call.owner.equals(target.name))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCode(MethodNode method) {
        return (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0 && method.instructions.size() > 0;
    }

    private static FieldNode field(ClassNode node, String name, String desc) {
        if (node.fields == null) return null;
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) return field;
        }
        return null;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        if (node.methods == null) return null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static boolean isStatic(int access) {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    private static Type[] append(Type[] types, Type last) {
        Type[] all = new Type[types.length + 1];
        System.arraycopy(types, 0, all, 0, types.length);
        all[types.length] = last;
        return all;
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }
}
