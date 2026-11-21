package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import net.p3pp3rf1y.sophisticatedstorage.entity.MovingStorageWrapper;

/**
 * Sophisticated Storage presence probes for the adapters that run on top of Storage (Sophisticated Create mounted
 * storage and Storage in Motion; build B F1-3b, R1d/Row A). Same rules as {@link SophisticatedCoreMarkers}: each
 * probe only looks a member up (no class initialisation, no call); anything else propagates to the adapter's path
 * choice, which goes OFF naming the failure. They never report to the fail-safe themselves. For the 1.5.91 marker
 * (a positive marker: absent means OFF) {@link ClassNotFoundException} and {@link NoSuchFieldException} mean
 * absent. For the linked-storage field (a negative guard: absent means the older path continues) only
 * {@link NoSuchFieldException} on an existing class means absent; a missing class is a
 * {@link NoClassDefFoundError}, so it can never pass the guard.
 *
 * <p>Row A evidence (class-file member tables read with a zip reader; NOTES-f1-3b section 2): the linked-storage
 * field is absent from Storage 1.5.85.2077 and 1.5.91.2127 and present in 1.6.8.2196, 1.6.15.2219 and 1.6.16.2222;
 * the 1.5.91 marker is absent from 1.5.85.2077 and present in 1.5.91.2127, 1.6.8.2196, 1.6.15.2219 and
 * 1.6.16.2222. Together (marker present, field absent) they single out the 1.5.91 generation among those builds.
 * {@code SophisticatedStorageMenuAdapter} keeps its own copies of the same two lookups (F1-3a).</p>
 */
public final class SophisticatedStorageMarkers {
    static final String STORAGE_WRAPPER = "net.p3pp3rf1y.sophisticatedstorage.block.StorageWrapper";
    static final String PAINTBRUSH_ITEM = "net.p3pp3rf1y.sophisticatedstorage.item.PaintbrushItem";
    /** Storage's own linked-storage concept (Storage 1.6.x). */
    public static final String LINKED_STORAGE_FIELD = "StorageWrapper.linkedStorage";
    /** Added in Storage 1.5.91.2127 (absent from 1.5.85.2077). */
    public static final String STORAGE_1_5_91_MARKER = "PaintbrushItem.MAX_REMAINING_PARTS";

    private SophisticatedStorageMarkers() {
    }

    /**
     * Storage's wrapper declares its own linked-storage field (1.6.x). This is a negative guard (field present
     * means OFF, "no field" lets the older path continue), so only {@link NoSuchFieldException} on a wrapper class
     * that exists is false. A missing wrapper class is a failure, not "no field": it throws
     * {@link NoClassDefFoundError}, which the adapter's path choice turns into OFF naming it (build B F1-3b r2).
     */
    public static boolean linkedStorageFieldPresent() {
        return declaredFieldPresent(STORAGE_WRAPPER, "linkedStorage");
    }

    /**
     * The class declares the field. Only {@link NoSuchFieldException} is false. A class that cannot be found
     * throws {@link NoClassDefFoundError} (unchecked, with the {@link ClassNotFoundException} as its cause).
     */
    static boolean declaredFieldPresent(String className, String fieldName) {
        Class<?> owner;
        try {
            owner = Class.forName(className, false, storageLoader());
        } catch (ClassNotFoundException missing) {
            throw (NoClassDefFoundError) new NoClassDefFoundError(className).initCause(missing);
        }
        try {
            return owner.getDeclaredField(fieldName) != null;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    /** The constant added in Storage 1.5.91.2127 is declared. Not-there only is false. */
    public static boolean storage1591MarkerPresent() {
        try {
            return Class.forName(PAINTBRUSH_ITEM, false, storageLoader()).getDeclaredField("MAX_REMAINING_PARTS") != null;
        } catch (ClassNotFoundException | NoSuchFieldException absent) {
            return false;
        }
    }

    private static ClassLoader storageLoader() {
        return MovingStorageWrapper.class.getClassLoader();
    }
}
