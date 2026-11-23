package io.github.betapetey.eventtrail.compat.hooks.check;

import org.objectweb.asm.tree.ClassNode;

/**
 * Supplies class bytes as a {@link ClassNode} without loading the class. The pre-apply check reads every class it
 * needs through this, so it never initialises or links another mod's code.
 */
@FunctionalInterface
public interface ClassSource {
    /**
     * The class named by {@code internalName} ({@code a/b/C}), parsed with its code and debug information
     * (the local-variable table is needed for {@code @Local}), or null when no such class is visible.
     *
     * @throws Exception when the class exists but cannot be read; the caller treats the hook as unverifiable
     */
    ClassNode find(String internalName) throws Exception;
}
