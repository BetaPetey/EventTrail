package io.github.betapetey.eventtrail.events.gadgets;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Bounded process-local evidence ownership, not a world model or task scheduler. */
final class DeferredEffectLedger<T> {
    private final int maximum, perActor;
    private final long byteBudget;
    private final BiConsumer<? super T, ? super Throwable> predicateFailure;
    private final IdentityHashMap<Object, Entry<T>> entries = new IdentityHashMap<>();
    private final Map<UUID, Integer> counts = new HashMap<>();
    private long bytes;

    DeferredEffectLedger(int maximum, int perActor, long byteBudget) {
        this(maximum, perActor, byteBudget, null);
    }

    /**
     * @param predicateFailure told of every failure of a validity predicate (the entry's value and the failure);
     *                         may be null. The entry is treated as not valid whatever the failure was.
     */
    DeferredEffectLedger(int maximum, int perActor, long byteBudget, BiConsumer<? super T, ? super Throwable> predicateFailure) {
        if (maximum < 1 || perActor < 1 || perActor > maximum || byteBudget < 1) throw new IllegalArgumentException("limits");
        this.maximum = maximum; this.perActor = perActor; this.byteBudget = byteBudget;
        this.predicateFailure = predicateFailure;
    }

    synchronized boolean put(Object key, UUID actor, T value, long weight, BooleanSupplier valid) {
        if (key == null || actor == null || value == null || valid == null || weight < 0) return false;
        // Reusing an exact effect object does not transfer the former operation's authority.
        if (entries.containsKey(key)) { remove(key); return false; }
        if (!safe(valid, value) || entries.size() >= maximum || counts.getOrDefault(actor, 0) >= perActor
                || weight > byteBudget - bytes) return false;
        entries.put(key, new Entry<>(actor, value, weight, valid));
        counts.merge(actor, 1, Integer::sum); bytes += weight;
        return true;
    }

    synchronized T get(Object key) {
        Entry<T> entry = entries.get(key);
        if (entry == null) return null;
        if (!safe(entry.valid, entry.value)) { remove(key); return null; }
        return entry.value;
    }

    synchronized T take(Object key) { T value = get(key); remove(key); return value; }
    synchronized void remove(Object key) {
        Entry<T> entry = entries.remove(key);
        if (entry == null) return;
        bytes -= entry.weight;
        counts.compute(entry.actor, (id, count) -> count == null || count <= 1 ? null : count - 1);
    }
    synchronized void removeActor(UUID actor) {
        for (Object key : entries.entrySet().stream().filter(e -> e.getValue().actor.equals(actor)).map(Map.Entry::getKey).toList()) remove(key);
    }
    synchronized void prune() {
        for (Object key : entries.entrySet().stream().filter(e -> !safe(e.getValue().valid, e.getValue().value)).map(Map.Entry::getKey).toList()) remove(key);
    }
    /** Drops every entry without completing it; returns how many were dropped (af-3: the owner counts them). */
    synchronized int clear() { int dropped = entries.size(); entries.clear(); counts.clear(); bytes = 0; return dropped; }
    synchronized int size() { return entries.size(); }
    synchronized long bytes() { return bytes; }
    /**
     * R2-inner: a validity predicate that fails for any reason, including an {@link Error}, is "not valid", so the
     * entry is dropped (fail-conservative) and the failure is reported through {@code predicateFailure}.
     */
    private boolean safe(BooleanSupplier predicate, T value) {
        try { return predicate.getAsBoolean(); } catch (Throwable failure) {
            if (predicateFailure != null) {
                try { predicateFailure.accept(value, failure); } catch (Throwable ignored) { /* A report never throws. */ }
            }
            return false;
        }
    }
    private record Entry<T>(UUID actor, T value, long weight, BooleanSupplier valid) {}
}
