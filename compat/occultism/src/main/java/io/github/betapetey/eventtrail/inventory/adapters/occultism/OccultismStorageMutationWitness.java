package io.github.betapetey.eventtrail.inventory.adapters.occultism;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Both mutation boundaries and quiescence are independently observed; never locks gameplay. */
public final class OccultismStorageMutationWitness {
    private final AtomicLong revision = new AtomicLong();
    private final AtomicInteger writers = new AtomicInteger();
    public long begin() { writers.incrementAndGet(); return revision.incrementAndGet(); }
    public long end() { long completed = revision.incrementAndGet(); writers.decrementAndGet(); return completed; }
    public long revision() { return revision.get(); }
    public int writers() { return writers.get(); }
    public long quiescentRevision() {
        if (writers.get() != 0) return -1;
        long value = revision.get();
        return writers.get() == 0 && revision.get() == value ? value : -1;
    }
}
