package io.github.betapetey.eventtrail.events.spells;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Optional-link-free cleanup for the distinct synchronous item family.
 *
 * <p>R2-inner lifecycle rule: clear and logout always run, whatever the state of {@code ARS_ITEM}, because they
 * drop per-player and in-flight state; each step is contained, so a failure of one is reported to the fail-safe
 * and the remaining steps still run. This family has no tick.</p>
 */
public final class ArsItemAuditRuntime {
    private static volatile Runnable clear;
    private static volatile Consumer<UUID> logout;
    private ArsItemAuditRuntime() { }
    public static synchronized void register(Runnable stop, Consumer<UUID> disconnected) {
        if (clear != null) throw new IllegalStateException("Ars item observer already registered");
        clear = stop; logout = disconnected;
    }
    public static void clear() {
        Runnable work = clear; if (work != null) safe("lifecycle:ArsItemAuditRuntime.clear", work);
        safe("lifecycle:ArsItemAuditRuntime.clearItemOperation",
                io.github.betapetey.eventtrail.events.platform.ArsItemEvidenceBridge::clearPendingOperation);
        safe("lifecycle:ArsItemAuditRuntime.clearDepositOperation",
                io.github.betapetey.eventtrail.events.platform.ArsHandlerDepositEvidenceBridge::clearPendingOperation);
    }
    public static void onLogout(UUID id) {
        Consumer<UUID> work = logout; if (work != null) safe("lifecycle:ArsItemAuditRuntime.logout", () -> work.accept(id));
        safe("lifecycle:ArsItemAuditRuntime.logoutDepositOperation",
                () -> io.github.betapetey.eventtrail.events.platform.ArsHandlerDepositEvidenceBridge.onPlayerLogout(id));
    }
    private static void safe(String site, Runnable work) { AdapterFailSafe.contain(AdapterKey.ARS_ITEM, site, work); }
}
