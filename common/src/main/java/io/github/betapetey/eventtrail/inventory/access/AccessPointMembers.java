package io.github.betapetey.eventtrail.inventory.access;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Access rows B65/B66, the member probe: whether every access-only member a family's access-point resolver calls is
 * present in the running mod build. A family computes it once, when its adapter is built, and keeps it in a static
 * flag. When it is false the resolver refuses with {@code member-missing} (the OPEN text) and calls none of those
 * members, so a missing access-only member never switches the family's key OFF and never stops item rows. Members the
 * item paths already call are proven by those paths and are not listed here.
 */
public final class AccessPointMembers {
    private AccessPointMembers() {
    }

    /** One method: its class's binary name, its name and the binary names of its parameter types. */
    public record Member(String className, String methodName, List<String> parameterClassNames) {
        public Member {
            Objects.requireNonNull(className, "className");
            Objects.requireNonNull(methodName, "methodName");
            parameterClassNames = List.copyOf(parameterClassNames);
        }

        public static Member of(String className, String methodName, String... parameterClassNames) {
            return new Member(className, methodName, List.of(parameterClassNames));
        }
    }

    /**
     * Whether every member is present, looked up without initialising any class: the public methods of the class
     * (inherited ones included), then the methods declared on the class and its superclasses. Nothing it meets
     * escapes, short of a {@link VirtualMachineError}; a failed lookup is "not present".
     */
    public static boolean present(ClassLoader loader, List<Member> members) {
        if (members == null) return false;
        try {
            for (Member member : members) {
                if (member == null || !present(loader, member)) return false;
            }
            return true;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static boolean present(ClassLoader loader, Member member) throws ClassNotFoundException {
        Class<?> owner = Class.forName(member.className(), false, loader);
        Class<?>[] parameters = new Class<?>[member.parameterClassNames().size()];
        for (int index = 0; index < parameters.length; index++) {
            parameters[index] = type(member.parameterClassNames().get(index), loader);
        }
        try {
            owner.getMethod(member.methodName(), parameters);
            return true;
        } catch (NoSuchMethodException notPublic) {
            for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals(member.methodName())
                            && java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "char" -> char.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> Class.forName(name, false, loader);
        };
    }
}
