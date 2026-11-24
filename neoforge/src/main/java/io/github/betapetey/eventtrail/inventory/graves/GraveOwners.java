package io.github.betapetey.eventtrail.inventory.graves;

import io.github.betapetey.eventtrail.audit.ShownCause;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.inventory.graves.api.GraveOwnerFacts;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The owner forms of graves design r6, 1.2's table. Every stored owner is built through
 * {@link ShownCause.Owner#capture}, never through the constructor. A capture that throws is counted as
 * {@code grave-read-failed} and stored as no owner. Every result whose attribution can only be UNKNOWN (no valid UUID)
 * is counted once through {@link AdapterFailSafe#invalid(AdapterKey, String, String)} under the caller's key and site,
 * with the table's reason.
 *
 * <p>EventTrail never looks a name up elsewhere: a valid UUID without a stored name is shown by its UUID text.</p>
 */
public final class GraveOwners {
    private GraveOwners() {
    }

    /**
     * The owner a read found: a valid UUID (null when the mod stored none, or none that parsed) and the stored name
     * (null or blank when the mod stored none).
     * <ul>
     *   <li>a UUID and a name: {@code capture(name, uuidText, VALIDATED)}, no reason;</li>
     *   <li>a UUID, no name: {@code capture(uuidText, uuidText, VALIDATED)}, no reason;</li>
     *   <li>a name, no UUID: {@code capture(name, "", NOT_VALIDATED)}, counted {@code grave-owner-missing};</li>
     *   <li>neither: no owner, counted {@code grave-owner-missing}.</li>
     * </ul>
     */
    public static GraveOwnerFacts read(AdapterKey key, String site, UUID uuid, String name) {
        boolean named = name != null && !name.isBlank();
        if (uuid != null) {
            String uuidText = uuid.toString();
            return captured(key, site, () -> ShownCause.Owner.capture(named ? name : uuidText, uuidText,
                    ShownCause.OwnerState.VALIDATED), null);
        }
        if (named) {
            return captured(key, site, () -> ShownCause.Owner.capture(name, "", ShownCause.OwnerState.NOT_VALIDATED),
                    GraveOwnerFacts.Reason.GRAVE_OWNER_MISSING);
        }
        return none(key, site, GraveOwnerFacts.Reason.GRAVE_OWNER_MISSING);
    }

    /**
     * The mod's own "no owner set" (for example Tombstone's absent {@code owner_id}): {@code capture(null, null,
     * DEFAULT)}, counted {@code grave-owner-empty}. Only for a value the mod itself writes to mean "no owner"; a read
     * that failed is {@link #none}.
     */
    public static GraveOwnerFacts noOwnerSet(AdapterKey key, String site) {
        return captured(key, site, () -> ShownCause.Owner.capture(null, null, ShownCause.OwnerState.DEFAULT),
                GraveOwnerFacts.Reason.GRAVE_OWNER_EMPTY);
    }

    /**
     * No owner: the read found none (missing key, malformed key, failed read, missing colony), so the cause's owner is
     * null and the record never claims the mod had no owner. Counted with {@code reason}, which must not be
     * {@code grave-owner-empty} (that is {@link #noOwnerSet}).
     */
    public static GraveOwnerFacts none(AdapterKey key, String site, GraveOwnerFacts.Reason reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == GraveOwnerFacts.Reason.GRAVE_OWNER_EMPTY) {
            throw new IllegalArgumentException("The mod's own no-owner is noOwnerSet, not none");
        }
        GraveOwnerFacts facts = new GraveOwnerFacts(null, reason);
        count(key, site, reason);
        return facts;
    }

    /**
     * Runs a mod-specific owner read. A read that throws (any Throwable but a VirtualMachineError, which is rethrown)
     * or returns null is counted {@code grave-read-failed} and gives no owner. The read itself counts its result through
     * this class's other factories.
     */
    public static GraveOwnerFacts guarded(AdapterKey key, String site, Supplier<GraveOwnerFacts> read) {
        Objects.requireNonNull(read, "read");
        GraveOwnerFacts facts;
        try {
            facts = read.get();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            facts = null;
        }
        return facts != null ? facts : none(key, site, GraveOwnerFacts.Reason.GRAVE_READ_FAILED);
    }

    private static GraveOwnerFacts captured(AdapterKey key, String site, Supplier<ShownCause.Owner> capture,
            GraveOwnerFacts.Reason reason) {
        GraveOwnerFacts facts;
        try {
            facts = new GraveOwnerFacts(capture.get(), reason);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            // 1.2: any capture throw is counted as grave-read-failed and stored as no owner.
            return none(key, site, GraveOwnerFacts.Reason.GRAVE_READ_FAILED);
        }
        if (reason != null) count(key, site, reason);
        return facts;
    }

    private static void count(AdapterKey key, String site, GraveOwnerFacts.Reason reason) {
        AdapterFailSafe.invalid(key, site, reason.code());
    }
}
