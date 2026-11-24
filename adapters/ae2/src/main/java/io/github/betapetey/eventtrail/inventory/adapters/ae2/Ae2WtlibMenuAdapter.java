package io.github.betapetey.eventtrail.inventory.adapters.ae2;

import appeng.menu.me.common.MEStorageMenu;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.spi.AdapterMatch;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.spi.MenuAccessTarget;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapter;
import io.github.betapetey.eventtrail.inventory.spi.ObservationStrategy;

import java.util.Optional;

/**
 * Exact AE2WTLib 19.5.1 ownership for its wireless crafting terminal.
 *
 * <p>Optional addon classes are named only by string. Keeping them out of
 * signatures and imports is an invariant: a server without AE2WTLib must be
 * able to load EventTrail normally. No advance contract check is made; the two
 * facts this adapter relies on (the terminal menu is an AE2 storage menu at its
 * known name, and its host is an AE2 wireless terminal host) are looked up by
 * name once per JVM, at the first AE2 menu this adapter sees. If either is not
 * found, AE2WTLib is switched off until restart with one warning naming it,
 * instead of every terminal action going unrecorded silently.</p>
 */
public final class Ae2WtlibMenuAdapter implements MenuTransactionAdapter {
    static final String WCT_MENU = Ae2MenuAdapter.AE2WTLIB_WCT_MENU;
    static final String WCT_MENU_HOST = "de.mari_023.ae2wtlib.wct.WCTMenuHost";
    static final String AE2_WIRELESS_HOST = "appeng.helpers.WirelessTerminalMenuHost";
    static final String LABEL = "Wireless ME Crafting Terminal";
    static final String SITE = "adapter:Ae2WtlibMenuAdapter.match";
    static final String MENU_CONCEPT = WCT_MENU + " as an AE2 MEStorageMenu";
    static final String HOST_CONCEPT = WCT_MENU_HOST + " as an AE2 WirelessTerminalMenuHost";

    /** Finds a class by name without initialising it; {@code null} when it is not present. */
    @FunctionalInterface
    interface ClassLookup {
        Class<?> find(String name) throws Exception;
    }

    @Override
    public String adapterId() {
        return "ae2wtlib-19.5.1-exact-operation";
    }

    @Override
    public boolean exact() {
        return true;
    }

    @Override
    public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof MEStorageMenu)) {
            return AdapterMatch.NO_MATCH;
        }
        return decide(classifyMenuClassName(context.menu().getClass().getName()), Probe.MISSING);
    }

    @Override
    public ObservationStrategy observationStrategy() {
        return ObservationStrategy.AUTHORITATIVE_OPERATION;
    }

    @Override
    public Optional<ContainerTarget> resolveAuthoritativeTarget(MenuAdapterContext context) {
        if (match(context) != AdapterMatch.SUPPORTED) {
            return Optional.empty();
        }
        // WCTMenuHost counts as wireless only when it is a real AE2 wireless
        // host at use (resolveProvenTarget); target attribution therefore comes
        // from current operation evidence, never an owner, placer, or
        // last-player heuristic. match is SUPPORTED only after the first-use
        // probe found WCTMenuHost to be such a host.
        return Ae2MenuAdapter.resolveProvenTarget(context, LABEL);
    }

    @Override
    public Optional<MenuAccessTarget> resolveContainerAccessTarget(
            MenuAdapterContext context) {
        if (match(context) != AdapterMatch.SUPPORTED) return Optional.empty();
        return Ae2MenuAdapter.resolveProvenAccessTarget(context, LABEL);
    }

    static AdapterMatch classifyMenuClassName(String menuClassName) {
        return WCT_MENU.equals(menuClassName)
                ? AdapterMatch.SUPPORTED
                : AdapterMatch.NO_MATCH;
    }

    /**
     * With both facts found, the name decides. Otherwise AE2WTLib goes OFF
     * until restart (one warning; the call is idempotent) and its own menu is
     * still owned (AMBIGUOUS: blocked, no row, no generic fallback).
     */
    static AdapterMatch decide(AdapterMatch byName, Optional<String> missing) {
        if (missing.isEmpty()) return byName;
        AdapterFailSafe.conceptNotFound(AdapterKey.AE2WTLIB, SITE, missing.get());
        return byName == AdapterMatch.SUPPORTED ? AdapterMatch.AMBIGUOUS : AdapterMatch.NO_MATCH;
    }

    /**
     * The presence probe. It reports nothing itself; a lookup that throws counts
     * as not found (present but unusable = missing), never as found.
     */
    static Optional<String> missingConcept(ClassLookup lookup) {
        try {
            Class<?> storageMenu = lookup.find(Ae2MenuAdapter.STORAGE_MENU_BASE);
            Class<?> menu = lookup.find(WCT_MENU);
            if (storageMenu == null || menu == null || !storageMenu.isAssignableFrom(menu)) {
                return Optional.of(MENU_CONCEPT);
            }
            Class<?> wirelessHost = lookup.find(AE2_WIRELESS_HOST);
            Class<?> host = lookup.find(WCT_MENU_HOST);
            if (wirelessHost == null || host == null || !wirelessHost.isAssignableFrom(host)) {
                return Optional.of(HOST_CONCEPT);
            }
            return Optional.empty();
        } catch (Throwable failure) {
            return Optional.of(MENU_CONCEPT + " and " + HOST_CONCEPT
                    + " (the lookup failed: " + failure.getClass().getSimpleName() + ")");
        }
    }

    /** The production lookup: this class's loader, no initialisation; absent is {@code null}. */
    static Class<?> runtimeClass(String name) {
        try {
            return Class.forName(name, false, Ae2WtlibMenuAdapter.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    /** Computed once per JVM, at first use (class initialisation of this holder), never at start-up. */
    private static final class Probe {
        static final Optional<String> MISSING = missingConcept(Ae2WtlibMenuAdapter::runtimeClass);
    }
}
