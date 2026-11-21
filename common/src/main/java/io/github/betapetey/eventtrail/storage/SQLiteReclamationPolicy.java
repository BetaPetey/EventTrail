package io.github.betapetey.eventtrail.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Fixed, conservative policy bookkeeping; never audit data or permission to enter native SQLite. */
final class SQLiteReclamationPolicy {
    static final int PAGE_SIZE = 4096;
    static final long MIN_FREE_PAGES = 16_384; // 64 MiB
    static final int MAX_UNIT_PAGES = 64;
    static final int MAX_GROUP_UNITS = 32;
    static final int MAX_GROUP_PAGES = MAX_UNIT_PAGES * MAX_GROUP_UNITS;
    static final int MAX_SLICE_UNITS = 256;
    static final long SLICE_NANOS = 2_000_000_000L;
    static final long SLOW_NANOS = 50_000_000L;
    static final long MAX_PAGES = Long.MAX_VALUE / PAGE_SIZE;
    private static final int MAGIC = 0x45545243;
    private static final int VERSION = 1;

    enum Phase { BASELINED, ACTIVE, COMPLETED }

    record Episode(String binding, Phase phase, long initialFree, long reserve, long remaining,
                   long baseline, int reserved, long forfeited, long confirmedReleased,
                   int unitCap, boolean positiveRetention, boolean checkpointPending) {
        Episode {
            if (binding == null || !binding.matches("[0-9a-f]{64}") || phase == null
                    || !pages(initialFree) || !pages(reserve) || !pages(remaining) || !pages(baseline)
                    || !pages(forfeited) || !pages(confirmedReleased)
                    || reserved < 0 || reserved > MAX_GROUP_PAGES
                    || unitCap != 64 && unitCap != 16 && unitCap != 1
                    || reserved > 0 && (phase != Phase.ACTIVE || !checkpointPending)) {
                throw new IllegalArgumentException("Invalid SQLite reclamation policy state");
            }
            if (phase == Phase.BASELINED) {
                if (initialFree != 0 || reserve != 0 || remaining != 0 || reserved != 0
                        || forfeited != 0 || confirmedReleased != 0 || checkpointPending) {
                    throw new IllegalArgumentException("Invalid SQLite reclamation baseline");
                }
            } else {
                long budget = initialFree / 2;
                if (reserve != initialFree / 2 + initialFree % 2
                        || Math.addExact(Math.addExact(remaining, reserved),
                            Math.addExact(forfeited, confirmedReleased)) > budget
                        || phase == Phase.COMPLETED && (remaining != 0 || reserved != 0)) {
                    throw new IllegalArgumentException("Invalid SQLite reclamation allowance");
                }
            }
        }

        Episode retention(RetentionResult result) {
            if (result.outcome() != RetentionResult.Outcome.EXHAUSTED || result.deletedCount() == 0
                    || positiveRetention || phase == Phase.ACTIVE) return this;
            return copy(phase, initialFree, reserve, remaining, baseline, reserved, forfeited,
                    confirmedReleased, unitCap, true, checkpointPending);
        }

        Episode begin(long pageCount, long currentFree) {
            validatePages(PAGE_SIZE, pageCount, currentFree);
            if (phase == Phase.ACTIVE || !positiveRetention || !costGate(pageCount, currentFree)
                    || currentFree < baseline || currentFree - baseline < MIN_FREE_PAGES) return this;
            return copy(Phase.ACTIVE, currentFree, currentFree / 2 + currentFree % 2,
                    currentFree / 2, baseline, 0, 0, 0, MAX_UNIT_PAGES, false, checkpointPending);
        }

        Episode reserveGroup() {
            if (phase != Phase.ACTIVE || reserved != 0 || remaining == 0)
                throw new IllegalStateException("No unreserved SQLite reclamation episode");
            int amount = (int) Math.min(remaining, (long) unitCap * MAX_GROUP_UNITS);
            return copy(phase, initialFree, reserve, remaining - amount, baseline, amount,
                    forfeited, confirmedReleased, unitCap, false, true);
        }

        /** Only called after every admitted operation and its complete teardown returned normally. */
        Episode settleGroup(int confirmedPages, int nextCap) {
            if (reserved == 0 || confirmedPages < 0 || confirmedPages > reserved || nextCap > unitCap)
                throw new IllegalArgumentException("Unproven SQLite reclamation refund");
            return copy(phase, initialFree, reserve, remaining + reserved - confirmedPages, baseline, 0,
                    forfeited, confirmedReleased + confirmedPages, nextCap, false, checkpointPending);
        }

        /** Restart or an ambiguous return forfeits the ENTIRE outstanding group; native work is never replayed. */
        Episode recoverUncertainGroup() {
            if (reserved == 0) return this;
            return copy(phase, initialFree, reserve, remaining, baseline, 0, forfeited + reserved,
                    confirmedReleased, 1, false, true);
        }

        Episode checkpointCompleted() {
            return copy(phase, initialFree, reserve, remaining, baseline, reserved, forfeited,
                    confirmedReleased, unitCap, positiveRetention, false);
        }

        Episode complete(long currentFree) {
            if (phase != Phase.ACTIVE || reserved != 0 || !pages(currentFree))
                throw new IllegalStateException("Unsettled SQLite reclamation episode");
            return copy(Phase.COMPLETED, initialFree, reserve, 0, currentFree, 0, forfeited,
                    confirmedReleased, unitCap, false, checkpointPending);
        }

        private Episode copy(Phase p, long f, long r, long q, long b, int group, long lost, long done,
                             int cap, boolean positive, boolean checkpoint) {
            return new Episode(binding, p, f, r, q, b, group, lost, done, cap, positive, checkpoint);
        }
    }

    static Episode baseline(String binding, long free) {
        return new Episode(binding, Phase.BASELINED, 0, 0, 0, free, 0, 0, 0, MAX_UNIT_PAGES, false, false);
    }

    static void validatePages(long pageSize, long pageCount, long free) {
        if (pageSize != PAGE_SIZE || pageCount <= 0 || !pages(pageCount) || !pages(free) || free > pageCount)
            throw new IllegalArgumentException("Unqualified SQLite reclamation page observation");
        Math.multiplyExact(pageCount, pageSize);
        Math.multiplyExact(free, pageSize);
    }

    static boolean costGate(long pageCount, long free) {
        validatePages(PAGE_SIZE, pageCount, free);
        long quarterCeiling = pageCount / 4 + (pageCount % 4 == 0 ? 0 : 1);
        return free >= MIN_FREE_PAGES && free >= quarterCeiling;
    }

    static int nextUnit(long currentFree, long reserve, long remainingIncludingGroup, int group, int cap) {
        if (!pages(currentFree) || !pages(reserve) || !pages(remainingIncludingGroup)
                || group < 0 || group > MAX_GROUP_PAGES || cap != 64 && cap != 16 && cap != 1)
            throw new IllegalArgumentException("Invalid SQLite incremental admission operands");
        if (currentFree <= reserve) return 0;
        return (int) Math.min(Math.min(currentFree - reserve, remainingIncludingGroup), Math.min(group, cap));
    }

    static int downshift(int cap) {
        return switch (cap) { case 64 -> 16; case 16, 1 -> 1; default -> throw new IllegalArgumentException("Invalid unit cap"); };
    }

    static byte[] encode(Episode state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(state.binding()); out.writeInt(PAGE_SIZE);
            out.writeInt(state.phase().ordinal()); out.writeLong(state.initialFree()); out.writeLong(state.reserve());
            out.writeLong(state.remaining()); out.writeLong(state.baseline()); out.writeInt(state.reserved());
            out.writeLong(state.forfeited()); out.writeLong(state.confirmedReleased()); out.writeInt(state.unitCap());
            out.writeBoolean(state.positiveRetention()); out.writeBoolean(state.checkpointPending());
        }
        byte[] body = bytes.toByteArray();
        bytes.writeBytes(digest(body));
        return bytes.toByteArray();
    }

    static Episode decode(byte[] encoded, String binding) throws IOException {
        if (encoded == null || encoded.length < 32 || encoded.length > 4096)
            throw new IOException("Missing or oversized SQLite reclamation state");
        byte[] body = Arrays.copyOf(encoded, encoded.length - 32);
        if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(encoded, body.length, encoded.length)))
            throw new IOException("Invalid SQLite reclamation state integrity");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || !in.readUTF().equals(binding)
                    || in.readInt() != PAGE_SIZE) throw new IOException("SQLite reclamation state binding mismatch");
            int phase = in.readInt();
            if (phase < 0 || phase >= Phase.values().length) throw new IOException("Unknown reclamation phase");
            Episode state = new Episode(binding, Phase.values()[phase], in.readLong(), in.readLong(), in.readLong(),
                    in.readLong(), in.readInt(), in.readLong(), in.readLong(), in.readInt(), in.readBoolean(), in.readBoolean());
            if (in.available() != 0) throw new IOException("Trailing SQLite reclamation state");
            return state;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid SQLite reclamation state", invalid);
        }
    }

    private static boolean pages(long value) { return value >= 0 && value <= MAX_PAGES; }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private SQLiteReclamationPolicy() {}
}
