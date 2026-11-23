package io.github.betapetey.eventtrail.compat.hooks;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The intended injection counts of one optional-mod hook handler.
 *
 * <p>Optional-mod injectors are declared with {@code require = 0, expect = 0}
 * and no {@code allow}, so Mixin itself can never fail fatally on a count. The
 * counts the hook was written for move here, and the pre-apply check compares
 * them with the other mod's class bytes before the hook is handed to Mixin. A
 * hook whose counts do not hold is skipped with one warning.</p>
 *
 * <p>Class retention: the check reads the annotation from EventTrail's own
 * class bytes; nothing reads it reflectively at run time, and no class is
 * loaded for it.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface HookSite {
    /** Fewest matching injection points the hook needs (Mixin's {@code require}). */
    int require() default 1;

    /** Expected matching injection points (Mixin's {@code expect}). */
    int expect() default 1;

    /** Most matching injection points the hook accepts; -1 means no upper bound (Mixin's {@code allow}). */
    int allow() default -1;
}
