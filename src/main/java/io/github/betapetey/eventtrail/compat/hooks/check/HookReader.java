package io.github.betapetey.eventtrail.compat.hooks.check;

import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.AccessorMethod;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.Counts;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.Injector;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.InjectorKind;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.Kind;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.LocalCapture;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.Point;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.PointKind;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.Reference;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.ReferenceKind;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.Selector;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.ShadowField;
import io.github.betapetey.eventtrail.compat.hooks.check.HookModel.ShadowMethod;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads what an optional-mod hook class asks of its targets from the hook's own class bytes (work map R4-check).
 *
 * <p>It models exactly the Mixin and MixinExtras features EventTrail's hooks use: {@code @Mixin} (classes or target
 * names), {@code @Pseudo}; {@code @Inject} at HEAD, RETURN or TAIL; {@code @WrapOperation} at INVOKE, with
 * {@code ordinal}, captured target arguments and {@code @Local} (implicit or {@code argsOnly});
 * {@code @WrapMethod}; method selectors by name or by name and descriptor, with or without an owner;
 * {@code @Shadow} fields and methods; {@code @Accessor} getters and setters; {@code @Unique}; {@code @Final};
 * EventTrail's {@code HookSite}. Anything else from Mixin or MixinExtras, and anything malformed, is refused with
 * {@link UnsupportedHookException}: a hook the reader does not understand is skipped, never guessed at.</p>
 *
 * <p>The counts come from {@code HookSite} when the handler carries one (a converted hook declares
 * {@code require = 0, expect = 0} to Mixin), otherwise from the Mixin annotation itself, with an unwritten
 * {@code require} taken from the hook list's {@code defaultRequire}.</p>
 *
 * <p>Links only to the JDK and ASM. It never loads the hook class or anything it names.</p>
 */
public final class HookReader {
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    static final String PSEUDO = "Lorg/spongepowered/asm/mixin/Pseudo;";
    static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    static final String FINAL = "Lorg/spongepowered/asm/mixin/Final;";
    static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    static final String WRAP_METHOD = "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";
    static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    static final String HOOK_SITE = "Lio/github/betapetey/eventtrail/compat/hooks/HookSite;";
    static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
    static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    static final String CALLBACK_INFO_RETURNABLE =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    /** Annotation namespaces whose every annotation must be modelled here. */
    private static final List<String> MODELLED_NAMESPACES = List.of(
            "Lorg/spongepowered/", "Lcom/llamalad7/mixinextras/", "Lio/github/betapetey/eventtrail/compat/hooks/");

    /**
     * Internal-name prefixes of the fixed platform: the JDK, Minecraft, NeoForge, Mixin, MixinExtras, ASM, the
     * libraries the game ships, and EventTrail itself. A reference into these does not depend on another mod's
     * version, so it is not checked.
     */
    static final List<String> PLATFORM_PREFIXES = List.of(
            "java/", "javax/", "jdk/", "sun/", "com/sun/",
            "net/minecraft/", "com/mojang/", "net/neoforged/",
            "org/spongepowered/", "com/llamalad7/mixinextras/", "org/objectweb/asm/",
            "org/joml/", "it/unimi/dsi/fastutil/", "com/google/", "org/apache/", "org/slf4j/", "io/netty/",
            "org/lwjgl/",
            "io/github/betapetey/eventtrail/");

    /** The JDK part of the platform: never readable through a {@link ClassSource}. */
    static final List<String> JDK_PREFIXES = List.of("java/", "javax/", "jdk/", "sun/", "com/sun/");

    private static final String TYPE = "(?:\\[*(?:[ZBCSIFJD]|L[^;.\\[()]+;))";
    private static final Pattern METHOD_DESCRIPTOR = Pattern.compile("\\(" + TYPE + "*\\)(?:V|" + TYPE + ")");

    private HookReader() {
    }

    /** Reads a hook from its class bytes. */
    public static HookModel read(byte[] classBytes, int defaultRequire) throws UnsupportedHookException {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return read(node, defaultRequire);
    }

    /** Reads a hook from a parsed class. {@code defaultRequire} is the hook list's {@code injectors.defaultRequire}. */
    public static HookModel read(ClassNode node, int defaultRequire) throws UnsupportedHookException {
        String where = node.name;
        AnnotationNode mixin = null;
        boolean pseudo = false;
        for (AnnotationNode annotation : annotations(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (MIXIN.equals(annotation.desc)) {
                if (mixin != null) throw unsupported(where, "more than one @Mixin");
                mixin = annotation;
            } else if (PSEUDO.equals(annotation.desc)) {
                keys(annotation, where + " @Pseudo");
                pseudo = true;
            } else {
                rejectIfModelled(annotation, where);
            }
        }
        if (mixin == null) throw unsupported(where, "no @Mixin annotation");
        List<String> targets = targets(mixin, where);

        if (node.innerClasses != null) {
            for (InnerClassNode inner : node.innerClasses) {
                if (node.name.equals(inner.outerName) || inner.name.startsWith(node.name + "$")) {
                    throw unsupported(where, "declares the nested class " + inner.name);
                }
            }
        }

        List<ShadowField> shadowFields = new ArrayList<>();
        if (node.fields != null) {
            for (FieldNode field : node.fields) {
                readField(field, where, shadowFields);
            }
        }

        List<ShadowMethod> shadowMethods = new ArrayList<>();
        List<AccessorMethod> accessors = new ArrayList<>();
        List<Injector> injectors = new ArrayList<>();
        boolean onlyAccessors = true;
        if (node.methods != null) {
            for (MethodNode method : node.methods) {
                boolean accessor = readMethod(method, where, defaultRequire, shadowMethods, accessors, injectors);
                if (!accessor && !"<clinit>".equals(method.name)) onlyAccessors = false;
            }
        }

        Kind kind;
        if ((node.access & Opcodes.ACC_INTERFACE) == 0) {
            kind = Kind.STANDARD;
        } else {
            kind = onlyAccessors ? Kind.ACCESSOR : Kind.INTERFACE;
        }
        if (kind == Kind.STANDARD && !accessors.isEmpty()) {
            throw unsupported(where, "@Accessor in a class mixin");
        }

        return new HookModel(
                node.name,
                kind,
                pseudo,
                targets,
                node.superName,
                node.interfaces == null ? List.of() : node.interfaces,
                shadowFields,
                shadowMethods,
                accessors,
                injectors,
                references(node));
    }

    private static List<String> targets(AnnotationNode mixin, String where) throws UnsupportedHookException {
        Set<String> targets = new LinkedHashSet<>();
        List<Object> values = values(mixin);
        for (int index = 0; index + 1 < values.size(); index += 2) {
            String key = (String) values.get(index);
            Object value = values.get(index + 1);
            switch (key) {
                case "value" -> {
                    for (Object element : list(value, where, "@Mixin value")) {
                        if (!(element instanceof Type type) || type.getSort() != Type.OBJECT) {
                            throw unsupported(where, "@Mixin value is not a class: " + element);
                        }
                        targets.add(type.getInternalName());
                    }
                }
                case "targets" -> {
                    for (Object element : list(value, where, "@Mixin targets")) {
                        String target = string(element, where, "@Mixin targets").replace('.', '/');
                        if (target.isEmpty()) throw unsupported(where, "empty @Mixin target");
                        targets.add(target);
                    }
                }
                case "remap" -> bool(value, where, "@Mixin remap");
                case "priority" -> integer(value, where, "@Mixin priority");
                default -> throw unsupported(where, "@Mixin attribute '" + key + "'");
            }
        }
        if (targets.isEmpty()) throw unsupported(where, "@Mixin names no target");
        return List.copyOf(targets);
    }

    private static void readField(FieldNode field, String owner, List<ShadowField> shadowFields)
            throws UnsupportedHookException {
        String where = owner + "." + field.name;
        boolean shadow = false;
        boolean unique = false;
        boolean isFinal = false;
        for (AnnotationNode annotation : annotations(field.visibleAnnotations, field.invisibleAnnotations)) {
            switch (annotation.desc) {
                case SHADOW -> {
                    keys(annotation, where + " @Shadow", "remap");
                    shadow = true;
                }
                case UNIQUE -> {
                    keys(annotation, where + " @Unique");
                    unique = true;
                }
                case FINAL -> {
                    keys(annotation, where + " @Final");
                    isFinal = true;
                }
                default -> rejectIfModelled(annotation, where);
            }
        }
        if (shadow && unique) throw unsupported(where, "both @Shadow and @Unique");
        if (isFinal && !shadow) throw unsupported(where, "@Final without @Shadow");
        if (shadow) {
            shadowFields.add(new ShadowField(field.name, field.desc, (field.access & Opcodes.ACC_STATIC) != 0));
        }
    }

    /** Reads one method; returns whether it is an accessor. */
    private static boolean readMethod(
            MethodNode method,
            String owner,
            int defaultRequire,
            List<ShadowMethod> shadowMethods,
            List<AccessorMethod> accessors,
            List<Injector> injectors) throws UnsupportedHookException {
        String where = owner + "#" + method.name + method.desc;
        AnnotationNode injector = null;
        InjectorKind injectorKind = null;
        AnnotationNode site = null;
        AnnotationNode accessor = null;
        boolean shadow = false;
        boolean unique = false;
        for (AnnotationNode annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
            InjectorKind found = switch (annotation.desc) {
                case INJECT -> InjectorKind.INJECT;
                case WRAP_OPERATION -> InjectorKind.WRAP_OPERATION;
                case WRAP_METHOD -> InjectorKind.WRAP_METHOD;
                default -> null;
            };
            if (found != null) {
                if (injector != null) throw unsupported(where, "more than one injector annotation");
                injector = annotation;
                injectorKind = found;
                continue;
            }
            switch (annotation.desc) {
                case HOOK_SITE -> {
                    if (site != null) throw unsupported(where, "more than one @HookSite");
                    site = annotation;
                }
                case ACCESSOR -> accessor = annotation;
                case SHADOW -> {
                    keys(annotation, where + " @Shadow", "remap");
                    shadow = true;
                }
                case UNIQUE -> {
                    keys(annotation, where + " @Unique");
                    unique = true;
                }
                default -> rejectIfModelled(annotation, where);
            }
        }
        int roles = (injector != null ? 1 : 0) + (accessor != null ? 1 : 0) + (shadow ? 1 : 0) + (unique ? 1 : 0);
        if (roles > 1) throw unsupported(where, "more than one of injector, @Accessor, @Shadow and @Unique");
        if (site != null && injector == null) throw unsupported(where, "@HookSite without an injector");

        List<LocalCapture> locals = locals(method, where);
        if (injector == null && !locals.isEmpty()) throw unsupported(where, "@Local outside an injector");

        if (injector != null) {
            injectors.add(injector(method, where, injectorKind, injector, site, locals, defaultRequire));
        }
        if (shadow) {
            shadowMethods.add(new ShadowMethod(method.name, method.desc, (method.access & Opcodes.ACC_STATIC) != 0));
        }
        if (accessor != null) {
            accessors.add(accessor(method, where, accessor));
            return true;
        }
        return false;
    }

    private static Injector injector(
            MethodNode method,
            String where,
            InjectorKind kind,
            AnnotationNode annotation,
            AnnotationNode site,
            List<LocalCapture> locals,
            int defaultRequire) throws UnsupportedHookException {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw unsupported(where, "an injector handler without code");
        }
        List<Object> selectorValues = null;
        List<Object> atValues = null;
        Integer require = null;
        Integer expect = null;
        Integer allow = null;
        List<Object> values = values(annotation);
        for (int index = 0; index + 1 < values.size(); index += 2) {
            String key = (String) values.get(index);
            Object value = values.get(index + 1);
            switch (key) {
                case "method" -> selectorValues = list(value, where, "method");
                case "at" -> {
                    if (kind == InjectorKind.WRAP_METHOD) throw unsupported(where, "@WrapMethod with 'at'");
                    atValues = list(value, where, "at");
                }
                case "require" -> require = integer(value, where, "require");
                case "expect" -> expect = integer(value, where, "expect");
                case "allow" -> allow = integer(value, where, "allow");
                case "remap" -> bool(value, where, "remap");
                default -> throw unsupported(where, "injector attribute '" + key + "'");
            }
        }
        if (selectorValues == null || selectorValues.isEmpty()) throw unsupported(where, "no method selector");
        List<Selector> selectors = new ArrayList<>();
        for (Object selector : selectorValues) {
            Selector parsed = selector(string(selector, where, "method"), where);
            if ("<clinit>".equals(parsed.name())) throw unsupported(where, "targets a static initialiser");
            if (kind == InjectorKind.WRAP_METHOD && "<init>".equals(parsed.name())) {
                throw unsupported(where, "@WrapMethod on a constructor");
            }
            selectors.add(parsed);
        }

        List<Point> points = new ArrayList<>();
        if (kind != InjectorKind.WRAP_METHOD) {
            if (atValues == null || atValues.isEmpty()) throw unsupported(where, "no @At");
            for (Object at : atValues) {
                if (!(at instanceof AnnotationNode atNode) || !AT.equals(atNode.desc)) {
                    throw unsupported(where, "'at' holds something other than @At");
                }
                Point point = point(atNode, where);
                boolean allowed = kind == InjectorKind.INJECT
                        ? point.kind() != PointKind.INVOKE
                        : point.kind() == PointKind.INVOKE;
                if (!allowed) throw unsupported(where, point.kind() + " in " + kind);
                if (point.kind() == PointKind.HEAD) {
                    for (Selector selector : selectors) {
                        if ("<init>".equals(selector.name())) throw unsupported(where, "HEAD in a constructor");
                    }
                }
                points.add(point);
            }
        }

        Counts counts = site != null ? siteCounts(site, where) : mixinCounts(require, expect, allow, defaultRequire, where);

        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type returnType = Type.getReturnType(method.desc);
        int base;
        int captured;
        if (kind == InjectorKind.INJECT) {
            if (returnType.getSort() != Type.VOID) throw unsupported(where, "@Inject handler does not return void");
            if (!locals.isEmpty()) throw unsupported(where, "@Local on an @Inject handler");
            if (arguments.length == 0) throw unsupported(where, "@Inject handler without a CallbackInfo");
            String last = arguments[arguments.length - 1].getDescriptor();
            if (!CALLBACK_INFO.equals(last) && !CALLBACK_INFO_RETURNABLE.equals(last)) {
                throw unsupported(where, "@Inject handler does not end with a CallbackInfo");
            }
            for (int index = 0; index < arguments.length - 1; index++) {
                if (isMixinCarrier(arguments[index].getDescriptor())) {
                    throw unsupported(where, "a CallbackInfo or Operation before the last parameter");
                }
            }
            base = arguments.length - 1;
            captured = 0;
        } else {
            int operation = -1;
            for (int index = 0; index < arguments.length; index++) {
                String descriptor = arguments[index].getDescriptor();
                if (OPERATION.equals(descriptor)) {
                    if (operation >= 0) throw unsupported(where, "more than one Operation parameter");
                    operation = index;
                } else if (isMixinCarrier(descriptor)) {
                    throw unsupported(where, "a CallbackInfo parameter on a wrap handler");
                }
            }
            if (operation < 0) throw unsupported(where, "no Operation parameter");
            int firstLocal = arguments.length;
            for (LocalCapture local : locals) {
                if (local.parameter() < operation) throw unsupported(where, "@Local before the Operation");
                firstLocal = Math.min(firstLocal, local.parameter());
            }
            if (!locals.isEmpty() && arguments.length - firstLocal != locals.size()) {
                throw unsupported(where, "@Local parameters are not the trailing parameters");
            }
            base = operation;
            captured = firstLocal - operation - 1;
            if (kind == InjectorKind.WRAP_METHOD && (captured != 0 || !locals.isEmpty())) {
                throw unsupported(where, "@WrapMethod handler with parameters after the Operation");
            }
        }

        return new Injector(
                kind,
                method.name,
                method.desc,
                (method.access & Opcodes.ACC_STATIC) != 0,
                selectors,
                points,
                counts,
                base,
                captured,
                locals);
    }

    private static boolean isMixinCarrier(String descriptor) {
        return OPERATION.equals(descriptor) || CALLBACK_INFO.equals(descriptor)
                || CALLBACK_INFO_RETURNABLE.equals(descriptor);
    }

    private static List<LocalCapture> locals(MethodNode method, String where) throws UnsupportedHookException {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        List<LocalCapture> locals = new ArrayList<>();
        List<AnnotationNode>[][] tables = parameterTables(method);
        for (List<AnnotationNode>[] table : tables) {
            if (table == null) continue;
            if (table.length != arguments.length) throw unsupported(where, "parameter annotations do not line up");
        }
        for (int parameter = 0; parameter < arguments.length; parameter++) {
            boolean seen = false;
            for (List<AnnotationNode>[] table : tables) {
                if (table == null || table[parameter] == null) continue;
                for (AnnotationNode annotation : table[parameter]) {
                    if (LOCAL.equals(annotation.desc)) {
                        if (seen) throw unsupported(where, "two @Local on one parameter");
                        seen = true;
                        boolean argsOnly = false;
                        List<Object> values = values(annotation);
                        for (int index = 0; index + 1 < values.size(); index += 2) {
                            String key = (String) values.get(index);
                            if (!"argsOnly".equals(key)) throw unsupported(where, "@Local attribute '" + key + "'");
                            argsOnly = bool(values.get(index + 1), where, "@Local argsOnly");
                        }
                        locals.add(new LocalCapture(parameter, arguments[parameter].getDescriptor(), argsOnly));
                    } else {
                        rejectIfModelled(annotation, where + " parameter " + parameter);
                    }
                }
            }
        }
        return locals;
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationNode>[][] parameterTables(MethodNode method) {
        return new List[][] {method.visibleParameterAnnotations, method.invisibleParameterAnnotations};
    }

    private static Counts siteCounts(AnnotationNode site, String where) throws UnsupportedHookException {
        int require = 1;
        int expect = 1;
        int allow = -1;
        List<Object> values = values(site);
        for (int index = 0; index + 1 < values.size(); index += 2) {
            String key = (String) values.get(index);
            int value = integer(values.get(index + 1), where, "@HookSite " + key);
            switch (key) {
                case "require" -> require = value;
                case "expect" -> expect = value;
                case "allow" -> allow = value;
                default -> throw unsupported(where, "@HookSite attribute '" + key + "'");
            }
        }
        if (require < 0 || expect < 0 || allow < -1) throw unsupported(where, "a negative @HookSite count");
        return new Counts(require, expect, allow, true);
    }

    private static Counts mixinCounts(Integer require, Integer expect, Integer allow, int defaultRequire, String where)
            throws UnsupportedHookException {
        if (require != null && require < 0 || expect != null && expect < 0 || allow != null && allow < 0) {
            throw unsupported(where, "a negative injector count");
        }
        return new Counts(
                require != null ? require : Math.max(0, defaultRequire),
                expect != null ? expect : 1,
                allow != null ? allow : -1,
                false);
    }

    private static Point point(AnnotationNode at, String where) throws UnsupportedHookException {
        String value = null;
        String target = null;
        Integer ordinal = null;
        List<Object> values = values(at);
        for (int index = 0; index + 1 < values.size(); index += 2) {
            String key = (String) values.get(index);
            Object element = values.get(index + 1);
            switch (key) {
                case "value" -> value = string(element, where, "@At value");
                case "target" -> target = string(element, where, "@At target");
                case "ordinal" -> ordinal = integer(element, where, "@At ordinal");
                case "remap" -> bool(element, where, "@At remap");
                default -> throw unsupported(where, "@At attribute '" + key + "'");
            }
        }
        if (value == null) throw unsupported(where, "@At without a value");
        switch (value) {
            case "HEAD", "RETURN", "TAIL" -> {
                if (target != null || ordinal != null) throw unsupported(where, "@At(" + value + ") with a target or ordinal");
                return new Point(PointKind.valueOf(value), null, null, null, -1);
            }
            case "INVOKE" -> {
                if (target == null) throw unsupported(where, "@At(INVOKE) without a target");
                Selector call = selector(target, where);
                if (call.owner() == null || call.desc() == null) {
                    throw unsupported(where, "@At(INVOKE) target without an owner and a descriptor: " + target);
                }
                if ("<init>".equals(call.name())) throw unsupported(where, "@At(INVOKE) on a constructor call");
                if (ordinal != null && ordinal < -1) throw unsupported(where, "@At ordinal below -1");
                return new Point(PointKind.INVOKE, call.owner(), call.name(), call.desc(), ordinal == null ? -1 : ordinal);
            }
            default -> throw unsupported(where, "@At(\"" + value + "\")");
        }
    }

    /**
     * Parses a selector the way Mixin's {@code MemberInfo.parse} does for the forms the reader models: an owner given
     * as {@code Lowner;} or as a dotted prefix, a name, and an optional method descriptor. Quantifiers, field
     * descriptors, regex and dynamic selectors are refused.
     */
    static Selector selector(String raw, String where) throws UnsupportedHookException {
        String name = raw.replaceAll("\\s", "");
        if (name.isEmpty() || name.contains("->") || name.startsWith("@") || name.startsWith("/")) {
            throw unsupported(where, "selector '" + raw + "'");
        }
        String owner = null;
        String desc = null;
        int lastDot = name.lastIndexOf('.');
        int semicolon = name.indexOf(';');
        if (lastDot > -1) {
            owner = name.substring(0, lastDot).replace('.', '/');
            name = name.substring(lastDot + 1);
        } else if (semicolon > -1 && name.startsWith("L")) {
            owner = name.substring(1, semicolon).replace('.', '/');
            name = name.substring(semicolon + 1);
        }
        int paren = name.indexOf('(');
        if (paren > -1) {
            desc = name.substring(paren);
            name = name.substring(0, paren);
        } else if (name.indexOf(':') > -1) {
            throw unsupported(where, "field selector '" + raw + "'");
        }
        if (name.isEmpty() || name.indexOf('/') > -1 || name.indexOf('*') > -1 || name.indexOf('+') > -1
                || name.indexOf('{') > -1 || name.indexOf('}') > -1 || name.indexOf(';') > -1) {
            throw unsupported(where, "selector '" + raw + "'");
        }
        if (owner != null && owner.isEmpty()) throw unsupported(where, "selector '" + raw + "'");
        if (desc != null && !METHOD_DESCRIPTOR.matcher(desc).matches()) {
            throw unsupported(where, "selector descriptor '" + raw + "'");
        }
        return new Selector(owner, name, desc);
    }

    private static AccessorMethod accessor(MethodNode method, String where, AnnotationNode annotation)
            throws UnsupportedHookException {
        String field = null;
        List<Object> values = values(annotation);
        for (int index = 0; index + 1 < values.size(); index += 2) {
            String key = (String) values.get(index);
            Object value = values.get(index + 1);
            switch (key) {
                case "value" -> field = string(value, where, "@Accessor value");
                case "remap" -> bool(value, where, "@Accessor remap");
                default -> throw unsupported(where, "@Accessor attribute '" + key + "'");
            }
        }
        if (field == null || field.isEmpty()) throw unsupported(where, "@Accessor without an explicit field name");
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type returnType = Type.getReturnType(method.desc);
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        if (arguments.length == 0 && returnType.getSort() != Type.VOID) {
            return new AccessorMethod(method.name, method.desc, field, true, isStatic);
        }
        if (arguments.length == 1 && returnType.getSort() == Type.VOID) {
            return new AccessorMethod(method.name, method.desc, field, false, isStatic);
        }
        throw unsupported(where, "@Accessor that is neither a getter nor a setter");
    }

    /** Every class and member the hook's own instructions name outside the fixed platform. */
    private static List<Reference> references(ClassNode node) {
        Set<Reference> references = new LinkedHashSet<>();
        if (node.superName != null) addClass(references, node, node.superName);
        if (node.interfaces != null) {
            for (String itf : node.interfaces) addClass(references, node, itf);
        }
        if (node.methods == null) return List.copyOf(references);
        for (MethodNode method : node.methods) {
            if (method.instructions != null) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call) {
                        addMember(references, node, ReferenceKind.METHOD, call.owner, call.name, call.desc);
                    } else if (insn instanceof FieldInsnNode field) {
                        addMember(references, node, ReferenceKind.FIELD, field.owner, field.name, field.desc);
                    } else if (insn instanceof TypeInsnNode type) {
                        addClass(references, node, type.desc);
                    } else if (insn instanceof MultiANewArrayInsnNode array) {
                        addType(references, node, Type.getType(array.desc));
                    } else if (insn instanceof LdcInsnNode constant && constant.cst instanceof Type type) {
                        addType(references, node, type);
                    } else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                        addHandle(references, node, dynamic.bsm);
                        if (dynamic.bsmArgs != null) {
                            for (Object argument : dynamic.bsmArgs) {
                                if (argument instanceof Handle handle) {
                                    addHandle(references, node, handle);
                                } else if (argument instanceof Type type) {
                                    addType(references, node, type);
                                }
                            }
                        }
                    }
                }
            }
            if (method.tryCatchBlocks != null) {
                for (TryCatchBlockNode block : method.tryCatchBlocks) {
                    if (block.type != null) addClass(references, node, block.type);
                }
            }
        }
        return List.copyOf(references);
    }

    private static void addHandle(Set<Reference> references, ClassNode node, Handle handle) {
        if (handle == null) return;
        ReferenceKind kind = handle.getTag() <= Opcodes.H_PUTSTATIC ? ReferenceKind.FIELD : ReferenceKind.METHOD;
        addMember(references, node, kind, handle.getOwner(), handle.getName(), handle.getDesc());
    }

    private static void addType(Set<Reference> references, ClassNode node, Type type) {
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (element.getSort() == Type.OBJECT) addClass(references, node, element.getInternalName());
    }

    private static void addClass(Set<Reference> references, ClassNode node, String nameOrArray) {
        if (nameOrArray.startsWith("[")) {
            addType(references, node, Type.getType(nameOrArray));
            return;
        }
        if (nameOrArray.equals(node.name) || isPlatform(nameOrArray)) return;
        references.add(new Reference(ReferenceKind.CLASS, nameOrArray, null, null));
    }

    private static void addMember(
            Set<Reference> references, ClassNode node, ReferenceKind kind, String owner, String name, String desc) {
        if (owner.startsWith("[")) {
            addType(references, node, Type.getType(owner));
            return;
        }
        if (owner.equals(node.name) || isPlatform(owner)) return;
        references.add(new Reference(ReferenceKind.CLASS, owner, null, null));
        references.add(new Reference(kind, owner, name, desc));
    }

    static boolean isPlatform(String internalName) {
        for (String prefix : PLATFORM_PREFIXES) {
            if (internalName.startsWith(prefix)) return true;
        }
        return false;
    }

    static boolean isJdk(String internalName) {
        for (String prefix : JDK_PREFIXES) {
            if (internalName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static List<Object> values(AnnotationNode annotation) {
        return annotation.values == null ? List.of() : annotation.values;
    }

    private static void keys(AnnotationNode annotation, String where, String... allowed) throws UnsupportedHookException {
        List<Object> values = values(annotation);
        for (int index = 0; index + 1 < values.size(); index += 2) {
            String key = (String) values.get(index);
            boolean known = false;
            for (String name : allowed) {
                if (name.equals(key)) {
                    known = true;
                    break;
                }
            }
            if (!known) throw unsupported(where, "attribute '" + key + "'");
            if ("remap".equals(key)) bool(values.get(index + 1), where, "remap");
        }
    }

    private static void rejectIfModelled(AnnotationNode annotation, String where) throws UnsupportedHookException {
        for (String namespace : MODELLED_NAMESPACES) {
            if (annotation.desc.startsWith(namespace)) {
                throw unsupported(where, "annotation " + annotation.desc);
            }
        }
    }

    private static String string(Object value, String where, String what) throws UnsupportedHookException {
        if (value instanceof String text) return text;
        throw unsupported(where, what + " is not a string");
    }

    private static int integer(Object value, String where, String what) throws UnsupportedHookException {
        if (value instanceof Integer number) return number;
        throw unsupported(where, what + " is not an int");
    }

    private static boolean bool(Object value, String where, String what) throws UnsupportedHookException {
        if (value instanceof Boolean flag) return flag;
        throw unsupported(where, what + " is not a boolean");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String where, String what) throws UnsupportedHookException {
        if (value instanceof List<?> elements) return (List<Object>) elements;
        throw unsupported(where, what + " is not an array");
    }

    private static UnsupportedHookException unsupported(String where, String what) {
        return new UnsupportedHookException(where.replace('/', '.') + ": " + what);
    }
}
