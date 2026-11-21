package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.permissions.EventTrailPermissions;
import io.github.betapetey.eventtrail.storage.EventTrailQueryException;
import net.minecraft.commands.CommandSourceStack;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Permission-aware on-demand guidance; not an administrator broadcast. */
final class EventTrailAvailabilityMessages {
    private EventTrailAvailabilityMessages() {}

    static String unavailable(boolean statusAuthorized) {
        return statusAuthorized
                ? "EventTrail is unavailable. Check /eventtrail status for the safe reason and recovery guidance."
                : "EventTrail is unavailable. Contact a server administrator.";
    }

    static String unavailable(CommandSourceStack source) {
        return unavailable(EventTrailPermissions.hasExactPermission(
                source, EventTrailPermissions.STATUS_PERMISSION));
    }

    static boolean sendStatusIfAuthorized(BooleanSupplier authorized, Consumer<String> output, String line) {
        if (!authorized.getAsBoolean()) return false;
        output.accept(line);
        return true;
    }

    static boolean isUnavailable(Throwable failure) {
        EventTrailQueryException typed = EventTrailQueryException.find(failure);
        return typed != null && typed.getCategory() == EventTrailQueryException.Category.STORAGE_UNAVAILABLE;
    }
}
