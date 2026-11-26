package io.github.betapetey.eventtrail.storage;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Owner Q378 A (9:01 AM 11-25): the garbage collector the server runs, worked out from what Java reports about itself
 * (the collector beans' names) and never from the server's command-line arguments, and how much of an {@code -Xmx}
 * each collector reports back as its usable maximum memory ({@link Runtime#maxMemory()}). The spool's memory advice
 * ({@link DiskSpool#xmxMibAdvisedFor}) uses it, so that setting {@code -Xmx} to the advised figure clears the warning.
 *
 * <p>{@link #leastMaxMemoryAt} is a lower bound of what {@code Runtime.maxMemory()} reports at a given {@code -Xmx}
 * with the collector's default generation sizing. G1, ZGC and Shenandoah report the whole heap (rounded up to their
 * region or granule size). Parallel and Serial leave one survivor space out: with the defaults (NewRatio 2, so the
 * young generation is at most a third of the heap; Parallel's survivor at most a third of that, MinSurvivorRatio 3;
 * Serial's a tenth, SurvivorRatio 8) that is at most 1/9 of the heap for Parallel and 1/30 for Serial. A collector not
 * named here keeps the safe margin of 1/4. Measured on Temurin 21.0.12: at -Xmx1024m Parallel reports 954,728,448
 * bytes (just above 8/9) and Serial 1,037,959,168 (just above 29/30), because the survivor space is rounded down to
 * the alignment; G1, ZGC and Shenandoah report the whole 1 GiB.</p>
 */
enum HeapCollector {
    G1(Set.of("G1 Young Generation", "G1 Old Generation", "G1 Concurrent GC"), 1L, 1L),
    PARALLEL(Set.of("PS Scavenge", "PS MarkSweep"), 8L, 9L),
    SERIAL(Set.of("Copy", "MarkSweepCompact"), 29L, 30L),
    /** ZGC, both the single-generation and the generational one (Java 21's -XX:+ZGenerational, the only one from 23). */
    ZGC(Set.of("ZGC Cycles", "ZGC Pauses", "ZGC Minor Cycles", "ZGC Minor Pauses", "ZGC Major Cycles",
            "ZGC Major Pauses"), 1L, 1L),
    SHENANDOAH(Set.of("Shenandoah Cycles", "Shenandoah Pauses"), 1L, 1L),
    /** No collector bean, beans of more than one collector, or a name not listed: the safe margin. */
    UNKNOWN(Set.of(), 3L, 4L);

    private final Set<String> beanNames;
    private final long usableNumerator;
    private final long usableDenominator;

    HeapCollector(Set<String> beanNames, long usableNumerator, long usableDenominator) {
        this.beanNames = beanNames;
        this.usableNumerator = usableNumerator;
        this.usableDenominator = usableDenominator;
    }

    /**
     * The least {@link Runtime#maxMemory()} this collector reports at an {@code -Xmx} of {@code xmxBytes}: the heap
     * less the part the collector keeps aside, rounded down to the byte.
     */
    long leastMaxMemoryAt(long xmxBytes) {
        if (xmxBytes <= 0L) return 0L;
        // xmxBytes / d * n + (xmxBytes % d) * n / d is floor(xmxBytes * n / d) without overflow for any long.
        return xmxBytes / usableDenominator * usableNumerator
                + (xmxBytes % usableDenominator) * usableNumerator / usableDenominator;
    }

    /** The least -Xmx, in whole MiB, whose {@link #leastMaxMemoryAt} is at least {@code usableMib} MiB (rounded up). */
    long leastXmxMibReporting(long usableMib) {
        if (usableMib <= 0L) return 0L;
        return (usableMib * usableDenominator + usableNumerator - 1L) / usableNumerator;
    }

    /**
     * The collector whose bean names these are: every name must belong to one collector named here, else
     * {@link #UNKNOWN}.
     */
    static HeapCollector fromBeanNames(List<String> names) {
        if (names == null || names.isEmpty()) return UNKNOWN;
        for (String name : names) if (name == null) return UNKNOWN;
        for (HeapCollector c : values()) {
            if (c != UNKNOWN && c.beanNames.containsAll(names)) return c;
        }
        return UNKNOWN;
    }

    /**
     * The collector this server runs, from its collector beans' names ({@link ManagementFactory#getGarbageCollectorMXBeans}).
     * Never reads the command line (Owner Q378 A). {@link #UNKNOWN} when the beans cannot be read.
     */
    static HeapCollector current() {
        try {
            List<String> names = new ArrayList<>();
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) names.add(bean.getName());
            return fromBeanNames(names);
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }
}
