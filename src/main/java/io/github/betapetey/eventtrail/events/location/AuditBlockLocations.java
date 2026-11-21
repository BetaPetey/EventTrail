package io.github.betapetey.eventtrail.events.location;

import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.util.RateLimitedDiagnosticGate;
import org.apache.logging.log4j.LogManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Explicit native-address audit boundary. Gameplay keeps its original address;
 * each caller freezes a physical location only when its action is committed.
 */
public final class AuditBlockLocations {
    /** The fail-closed provider installed when the location provider itself could not be loaded (GF-WARNINGS B47). */
    private static final Provider UNAVAILABLE = (level, nativePosition) -> Optional.empty();
    private static volatile Provider provider = AuditBlockLocations::stationary;
    private static final Map<Cause, RateLimitedDiagnosticGate> FAILURES = new EnumMap<>(Cause.class);
    private static final Map<Cause, LongAdder> WITHHELD = new EnumMap<>(Cause.class);
    static {
        for (Cause cause : Cause.values()) {
            FAILURES.put(cause, new RateLimitedDiagnosticGate(60_000));
            WITHHELD.put(cause, new LongAdder());
        }
    }
    private static volatile Consumer<String> warnings = message -> LogManager.getLogger("eventtrail").warn(message);
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private AuditBlockLocations() { }

    /**
     * GF-WARNINGS (B47): why a physical audit location was withheld. Each cause has its own warning, rate-limited per
     * cause, and every withheld location is counted. A withheld location writes no row; nothing here changes which
     * locations are recorded.
     */
    public enum Cause {
        PROVIDER_FAILURE("provider failure"),
        PROVIDER_NOT_LOADED("location provider not loaded"),
        POSITION_NOT_PROVEN("position not proven");
        private final String text;
        Cause(String text) { this.text = text; }
        public String text() { return text; }
    }

    /** Loader discovery installs a qualified provider or a fail-closed recognizer. */
    public static void configure(Provider selected) { provider = Objects.requireNonNull(selected); }

    /** The provider to install when the location provider cannot be loaded: every address is withheld, by name. */
    public static Provider unavailableProvider() { return UNAVAILABLE; }

    /** Whether the installed provider is {@link #unavailableProvider()}; a display fact for the status command. */
    public static boolean providerUnavailable() { return provider == UNAVAILABLE; }

    public static Optional<Frame> capture(Level level, BlockPos nativePosition) {
        try {
            if (!(level instanceof ServerLevel serverLevel) || nativePosition == null
                    || serverLevel.getServer() == null || !serverLevel.getServer().isSameThread()) return Optional.empty();
            long generation = generation();
            Provider selected = provider;
            Optional<Frame> result = selected.capture(serverLevel, nativePosition.immutable())
                    .filter(frame -> frame.level() == serverLevel && nativePosition.equals(frame.nativePosition()))
                    .map(frame -> new GenerationFrame(frame, generation));
            if (result.isEmpty()) unavailable(selected == UNAVAILABLE ? Cause.PROVIDER_NOT_LOADED : Cause.POSITION_NOT_PROVEN);
            return result;
        } catch (RuntimeException | LinkageError failure) {
            report("capture", failure);
            unavailable(Cause.PROVIDER_FAILURE);
            return Optional.empty();
        }
    }

    /**
     * R2-inner: a failure of the configured provider or of one of its frames (the Sable provider and its moving
     * frames are the only providers that can fail; the stationary one cannot). Every site below keeps its
     * fail-closed answer; the failure is reported under {@code SABLE_FRAME}. The Sable provider reports a failure of
     * its recognizer half under {@code SABLE_RECOGNIZER} itself and answers empty, so only frame-half failures reach
     * this report (f2-7, f2-5 hand-off Q3).
     */
    private static void report(String site, Throwable failure) {
        AdapterFailSafe.report(AdapterKey.SABLE_FRAME, "bridge:AuditBlockLocations." + site, failure);
    }

    static void unavailable(Cause cause) {
        try {
            WITHHELD.get(cause).increment();
            FAILURES.get(cause).acquire(clock.getAsLong())
                    .ifPresent(emission -> warnings.accept(unavailableMessage(cause, emission.suppressedSinceLastEmission())));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // A warning never changes the fail-closed answer.
        }
    }

    static String unavailableMessage(Cause cause, long suppressed) {
        return "Physical audit location unavailable (cause: " + cause.text()
                + "); affected unproven native-address evidence is withheld and no row is written for it"
                + (suppressed > 0 ? "; " + suppressed + " more with this cause since the last warning" : "");
    }

    /** Withheld locations by cause since this process started, non-zero causes only, in cause order. */
    public static Map<Cause, Long> withheldCounts() {
        Map<Cause, Long> counts = new EnumMap<>(Cause.class);
        for (Cause cause : Cause.values()) {
            long count = WITHHELD.get(cause).sum();
            if (count > 0) counts.put(cause, count);
        }
        return counts;
    }

    /** Test seam: replace the warning sink and clock, and clear the counts. */
    static void useForTests(Consumer<String> sink, LongSupplier time) {
        warnings = Objects.requireNonNull(sink);
        clock = Objects.requireNonNull(time);
        for (Cause cause : Cause.values()) {
            FAILURES.put(cause, new RateLimitedDiagnosticGate(60_000));
            WITHHELD.get(cause).reset();
        }
    }

    /** Test seam: restore the log sink and the wall clock. */
    static void restoreForTests() {
        useForTests(message -> LogManager.getLogger("eventtrail").warn(message), System::currentTimeMillis);
    }

    /** Only absent integration or a proven outside-plot address may use identity mapping. */
    public static Optional<Frame> stationary(ServerLevel level, BlockPos nativePosition) {
        return Optional.of(new StationaryFrame(level, nativePosition.immutable()));
    }

    private static long generation() { return EventTrailManager.getInstance().recordingSnapshot().generation(); }

    @FunctionalInterface public interface Provider {
        Optional<Frame> capture(ServerLevel level, BlockPos nativePosition);
    }

    public interface Frame {
        BlockPos nativePosition();
        ServerLevel level();
        boolean moving();
        Object frameIdentity();
        String stableContextKey();
        boolean isCurrent();
        Optional<AuditLocation> currentLocation();
        /** Entity-specific sources use their actual point, not a block cell center. */
        Optional<AuditLocation> currentPointLocation(Vec3 nativePoint);
        /** Sticky original access authority, independent of transaction motion. */
        BooleanSupplier accessGuard();
    }

    private record GenerationFrame(Frame nativeFrame, long generation) implements Frame {
        public BlockPos nativePosition() { return nativeFrame.nativePosition(); }
        public ServerLevel level() { return nativeFrame.level(); }
        public boolean moving() { return nativeFrame.moving(); }
        public Object frameIdentity() { return nativeFrame.frameIdentity(); }
        public String stableContextKey() { return nativeFrame.stableContextKey(); }
        public boolean isCurrent() {
            try { return level().getServer().isSameThread() && generation == AuditBlockLocations.generation() && nativeFrame.isCurrent(); }
            catch (RuntimeException | LinkageError failure) { report("isCurrent", failure); return false; }
        }
        public Optional<AuditLocation> currentLocation() {
            try { return isCurrent() ? nativeFrame.currentLocation() : Optional.empty(); }
            catch (RuntimeException | LinkageError failure) { report("currentLocation", failure); return Optional.empty(); }
        }
        public Optional<AuditLocation> currentPointLocation(Vec3 point) {
            try { return isCurrent() ? nativeFrame.currentPointLocation(point) : Optional.empty(); }
            catch (RuntimeException | LinkageError failure) { report("currentPointLocation", failure); return Optional.empty(); }
        }
        public BooleanSupplier accessGuard() {
            BooleanSupplier guard = nativeFrame.accessGuard();
            return () -> {
                try { return isCurrent() && guard.getAsBoolean(); }
                catch (RuntimeException | LinkageError failure) { report("accessGuard", failure); return false; }
            };
        }
    }

    private record StationaryFrame(ServerLevel level, BlockPos nativePosition) implements Frame {
        public boolean moving() { return false; }
        public Object frameIdentity() { return level; }
        public String stableContextKey() { return ""; }
        public boolean isCurrent() { return level.getServer().isSameThread(); }
        public Optional<AuditLocation> currentLocation() {
            return isCurrent() ? Optional.of(new AuditLocation(level.dimension().location(), nativePosition)) : Optional.empty();
        }
        public Optional<AuditLocation> currentPointLocation(Vec3 point) {
            return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z)
                    && BlockPos.containing(point).equals(nativePosition) ? currentLocation() : Optional.empty();
        }
        public BooleanSupplier accessGuard() { return this::isCurrent; }
    }
}
