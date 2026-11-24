package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents;

import java.util.function.Supplier;

/**
 * Build B F1-4: the refusal of a linked Sophisticated storage (lead's fail-safe rule: what is untested is not recorded,
 * with a warning, never silently). Temporary: it stays until the linked-group recording path exists (Owner option B,
 * a separate item).
 *
 * <p>A linked chest, barrel, mounted chest, backpack or cart (a Core link-group member or endpoint) has no reviewed
 * recording path. Before F1-4 such an open recorded nothing and wrote no warning. The F1 adapters now refuse it: the
 * adapter does not take the open, so nothing in it is recorded. EventTrail never closes or cancels the menu; the
 * storage opens and works normally for the player. {@link AdapterFailSafe#refusedOnce} writes exactly one warning per
 * adapter key, reason and site after each server start (Owner, about 11:02 AM ET 11-14); every later refusal is only
 * counted. The key stays ON, so unlinked storage is unchanged.</p>
 *
 * <p>Outcomes of one read ({@link #refusedOpen}):</p>
 * <ul>
 *   <li>a detection member is missing (a {@code NoSuch*} or {@code ClassNotFoundException} lookup, a field the adapter
 *       could not open, a supplier of another shape): {@link Linkage#UNREADABLE}, the key goes OFF until restart with
 *       one warning naming what was looked for ({@link AdapterFailSafe#conceptNotFound});</li>
 *   <li>one object answers with a value of another shape (null where an {@code Optional} is declared, not a
 *       {@code Boolean}): {@link Linkage#STATE_UNREADABLE}, that open is refused with the one warning, the key stays
 *       ON;</li>
 *   <li>any other failure while reading is reported ({@link AdapterFailSafe#report}) and that open is refused.</li>
 * </ul>
 *
 * <p>Every check reads only members that the adapter's own unlinked checks already read.</p>
 */
public final class SophisticatedLinkedStorage {
    /** The reason of a refused linked open, as the warning names it. */
    public static final String REASON = "linked-storage-not-recorded";
    /** The reason of an open refused because one object's link state had another shape (review-01 m-2). */
    public static final String STATE_REASON = "linked-state-unreadable";

    /** What one read found. */
    public enum Linkage {
        UNLINKED,
        LINKED,
        /** One object's value has another shape: that open is refused, the key stays ON. */
        STATE_UNREADABLE,
        /** A detection member is missing or has another shape: the key goes OFF. */
        UNREADABLE
    }

    /** One adapter's link-state read; it may throw what its reflective lookups throw. */
    @FunctionalInterface
    public interface Reader {
        Linkage read() throws ReflectiveOperationException;
    }

    private SophisticatedLinkedStorage() {
    }

    /**
     * Reads one open's link state and applies the rule: true (refuse the open) unless it is UNLINKED. A missing lookup
     * ({@code NoSuchMethodException}, {@code NoSuchFieldException}, {@code ClassNotFoundException}) is UNREADABLE
     * (review-01 m-1); any other failure is reported and refuses the open. Never throws for a non-VM failure.
     */
    public static boolean refusedOpen(AdapterKey key, String site, String lookedFor, Reader reader) {
        Linkage linkage;
        try {
            linkage = reader.read();
        } catch (NoSuchMethodException | NoSuchFieldException | ClassNotFoundException missing) {
            linkage = Linkage.UNREADABLE;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AdapterFailSafe.report(key, site, failure);
            return true;
        }
        return refused(key, site, linkage, lookedFor);
    }

    /**
     * Applies the rule to one read: true (refuse the open) unless it was UNLINKED. LINKED and STATE_UNREADABLE write
     * the one warning per server start and count the open (the key stays ON); UNREADABLE switches the key OFF naming
     * {@code lookedFor}. Never throws.
     */
    public static boolean refused(AdapterKey key, String site, Linkage linkage, String lookedFor) {
        if (linkage == Linkage.UNLINKED) return false;
        if (linkage == Linkage.LINKED) AdapterFailSafe.refusedOnce(key, site, REASON);
        else if (linkage == Linkage.STATE_UNREADABLE) AdapterFailSafe.refusedOnce(key, site, STATE_REASON);
        else AdapterFailSafe.conceptNotFound(key, site, lookedFor);
        return true;
    }

    /**
     * The wrapper is a Core linked-storage endpoint provider: the instance check that the LINKED_CORE paths (Storage
     * 1.5.91 on Core 1.5.1) already make, with the interface looked up through Core's own loader. Throws what the
     * lookup throws; {@link #refusedOpen} sorts it.
     */
    public static boolean endpointProvider(Object wrapper) throws ClassNotFoundException {
        return wrapper != null && Class.forName(SophisticatedCoreMarkers.LINKED_ENDPOINT_PROVIDER_CLASS, false,
                StorageContainerMenuBase.class.getClassLoader()).isInstance(wrapper);
    }

    /**
     * The stack carries Core's linked-storage endpoint component: the read that the current Backpacks and
     * Sophisticated Create checks already make. UNREADABLE when the component is not a {@link DataComponentType}.
     * Throws what the lookup throws; {@link #refusedOpen} sorts it.
     */
    public static Linkage stackLinkage(ItemStack stack) throws ReflectiveOperationException {
        Object supplier = ModCoreDataComponents.class.getField("LINKED_STORAGE_ENDPOINT").get(null);
        if (!(supplier instanceof Supplier<?> source) || !(source.get() instanceof DataComponentType<?> endpoint)) {
            return Linkage.UNREADABLE;
        }
        return stack != null && stack.has(endpoint) ? Linkage.LINKED : Linkage.UNLINKED;
    }
}
