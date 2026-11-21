package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;

import java.util.function.Function;

/**
 * Presence probes that choose a Sophisticated behaviour path without reading a version (build B, R1d/Row A).
 *
 * <p>Each probe only looks a member up (no class initialisation, no call). Only the three "not there" outcomes,
 * {@link ClassNotFoundException}, {@link NoSuchFieldException} and {@link NoSuchMethodException}, mean absent.
 * Anything else (a {@link LinkageError} such as {@code NoClassDefFoundError} while the lookup resolves the class's
 * member types, a {@link RuntimeException}, a VM error) propagates to the adapter's path choice, which treats it as
 * present-but-unusable: the key goes OFF with a warning naming the failure, never a laxer path or a borrowed id.
 * These are presence probes, so they never report to the fail-safe themselves.</p>
 *
 * <p>Besides marking the legacy path, the pre-linked factory has one more use (Advisor F1-3a-1): the LINKED_CORE
 * paths need it together with {@code ILinkedStorageEndpointProvider}, so Core 1.5.17.2426, 1.5.25.2451 and 1.5.32.2464,
 * which lack it, are OFF there. The pair alone does not single out the reviewed Core 1.5.1.2341: released Core
 * 1.5.5.2363, 1.5.12.2400 and 1.5.14.2413 carry both, and their menu-base custody classes differ from 1.5.1's. So
 * LINKED_CORE also needs {@link #REVIEWED_LINKED_CORE_MARKER} (build B SOPH-LINKED-VERSIONS); without it the
 * adapters go OFF with {@link #UNREVIEWED_LINKED_CORE_LOOKED_FOR}.</p>
 *
 * <p>Row A evidence (class-file member tables read with a zip reader; NOTES-f1-3a section 2): the pre-linked
 * factory is present in Core 1.4.86.2259, 1.4.90.2299 and 1.5.1.2341 and absent from Core 1.5.17.2426, 1.5.25.2451
 * and 1.5.32.2464; both concept markers are absent from 1.4.86.2259 and 1.4.90.2299 and present in 1.5.1.2341,
 * 1.5.17.2426, 1.5.25.2451 and 1.5.32.2464. Core 1.5.1.2341 has both, so the concept is always tested first.
 * Build B SOPH-LINKED-VERSIONS (javap; NOTES-slv): the factory and {@code ILinkedStorageEndpointProvider} are also
 * present in Core 1.5.5.2363, 1.5.12.2400 and 1.5.14.2413, the factory is also present in 1.4.87.2270, and
 * {@code ILinkedStorageContentsBinding} is present in 1.5.1.2341 only, absent from 1.5.5.2363, 1.5.12.2400,
 * 1.5.14.2413, 1.5.15.2417, 1.5.17.2426, 1.5.25.2451, 1.5.32.2464 and 1.5.35.2473.</p>
 *
 * <p>Public (build B F1-3b) only so the Sophisticated Create mounted and Storage in Motion adapters, in other
 * packages, use the same Core probes.</p>
 */
public final class SophisticatedCoreMarkers {
    static final String CORE_DATA_COMPONENTS = "net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents";
    static final String LINKED_ENDPOINT_PROVIDER_CLASS =
            "net.p3pp3rf1y.sophisticatedcore.linkedstorage.ILinkedStorageEndpointProvider";
    static final String STORAGE_WRAPPER_REPOSITORY = "net.p3pp3rf1y.sophisticatedcore.inventory.StorageWrapperRepository";
    /** Concept marker: the linked-storage endpoint data component of Core's linked-storage generation. */
    static final String LINKED_ENDPOINT_COMPONENT = "ModCoreDataComponents.LINKED_STORAGE_ENDPOINT";
    /** Concept marker: Core's linked-storage endpoint interface (the 1.5.1-style Storage path checks it). */
    public static final String LINKED_ENDPOINT_PROVIDER = "ILinkedStorageEndpointProvider";
    /**
     * Legacy marker: the item-wrapper factory of the Core generation before linked storage. It is also in Core
     * 1.5.1.2341, so it selects the legacy path only when the concept markers are absent; on the linked-Core branch it is
     * the pre-linked factory that LINKED_CORE also requires (Advisor F1-3a-1). It is in Core 1.5.5, 1.5.12 and 1.5.14
     * too, so on that branch it is never enough alone ({@link #REVIEWED_LINKED_CORE_MARKER}).
     */
    public static final String PRE_LINKED_REPOSITORY = "StorageWrapperRepository.instantiateWrapper(ItemStack, Function)";
    static final String LINKED_CONTENTS_BINDING_CLASS =
            "net.p3pp3rf1y.sophisticatedcore.linkedstorage.ILinkedStorageContentsBinding";
    /**
     * Reviewed-linked-Core marker (build B SOPH-LINKED-VERSIONS): the linked-contents binding interface of Core
     * 1.5.1.2341, the one linked-storage Core whose custody classes the LINKED_CORE paths were reviewed on. Core 1.5.5
     * and later replace it ({@code ILinkedStorageContents}).
     */
    public static final String REVIEWED_LINKED_CORE_MARKER = "ILinkedStorageContentsBinding";
    /**
     * The OFF detail for a LINKED_CORE choice on a Core without {@link #REVIEWED_LINKED_CORE_MARKER} (Core 1.5.5,
     * 1.5.12, 1.5.14 or any other linked Core with the pre-linked factory and without the binding interface). At most
     * 160 characters, the fail-safe's member-text cap.
     */
    public static final String UNREVIEWED_LINKED_CORE_LOOKED_FOR = REVIEWED_LINKED_CORE_MARKER + " (Core 1.5.1) with "
            + LINKED_ENDPOINT_PROVIDER + " and StorageWrapperRepository.instantiateWrapper; linked Core unreviewed";
    /** Prefix of the OFF detail when a probe failed for a reason other than "not there". */
    public static final String PROBE_FAILED = "path probe failed: ";

    private SophisticatedCoreMarkers() {
    }

    /** Core's linked-storage endpoint component field is declared (public static). Not-there only is false. */
    static boolean linkedEndpointComponentPresent() {
        try {
            return Class.forName(CORE_DATA_COMPONENTS, false, coreLoader())
                    .getField("LINKED_STORAGE_ENDPOINT") != null;
        } catch (ClassNotFoundException | NoSuchFieldException absent) {
            return false;
        }
    }

    /** Core's linked-storage endpoint interface exists (Core 1.5.x; absent from 1.4.86 and 1.4.90). */
    public static boolean linkedEndpointProviderPresent() {
        try {
            return Class.forName(LINKED_ENDPOINT_PROVIDER_CLASS, false, coreLoader()) != null;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    /**
     * The pre-linked item-wrapper factory is declared (private static in Core 1.4.86, 1.4.87, 1.4.90, 1.5.1, 1.5.5,
     * 1.5.12 and 1.5.14 among the builds read; absent from 1.5.15 and later).
     */
    public static boolean preLinkedRepositoryPresent() {
        try {
            return Class.forName(STORAGE_WRAPPER_REPOSITORY, false, coreLoader())
                    .getDeclaredMethod("instantiateWrapper", ItemStack.class, Function.class) != null;
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            return false;
        }
    }

    /**
     * Core 1.5.1's linked-contents binding interface exists (build B SOPH-LINKED-VERSIONS; the one reviewed linked
     * Core among the builds read). Not-there only is false. Read only for a LINKED_CORE choice.
     */
    public static boolean reviewedLinkedCoreMarkerPresent() {
        try {
            return Class.forName(LINKED_CONTENTS_BINDING_CLASS, false, coreLoader()) != null;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    /**
     * The OFF detail for a probe that failed: its class and message, behind {@link #PROBE_FAILED}. The fail-safe
     * bounds and sanitises the detail. Never throws for a well-formed throwable.
     */
    public static String probeFailed(Throwable failure) {
        String message = failure.getMessage();
        return PROBE_FAILED + failure.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static ClassLoader coreLoader() {
        return StorageContainerMenuBase.class.getClassLoader();
    }
}
