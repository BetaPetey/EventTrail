package io.github.betapetey.eventtrail.storage;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Marks JDBC work owned by EventTrail so sensitive third-party JDBC diagnostics can
 * be filtered without changing a process-wide driver setting. The marker is
 * deliberately scoped to the executing thread and restored after every use.
 */
final class EventTrailJdbcLogScope {
    static final String CONTEXT_KEY = "eventtrail.jdbc.owner";
    static final String CONTEXT_VALUE = "eventtrail";
    private static final String MYSQL_LOGGER = "com.mysql.cj.log";
    private static final String HIKARI_CONFIG_LOGGER = "com.zaxxer.hikari.HikariConfig";
    private static final String HIKARI_POOL_LOGGER = "com.zaxxer.hikari.pool.HikariPool";
    private static final String HIKARI_POOL_BASE_LOGGER = "com.zaxxer.hikari.pool.PoolBase";
    private static final Object INSTALL_LOCK = new Object();
    private static final Set<Configuration> INSTALLED_CONFIGURATIONS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<LoggerContext> OBSERVED_CONTEXTS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private EventTrailJdbcLogScope() {}

    /**
     * Installs a deny-first Log4j Core global filter. It denies recurring raw
     * Connector/J/Hikari connection diagnostics only while the event carries
     * EventTrail's JDBC ownership marker, then delegates every other decision to
     * the pre-existing global filter.
     */
    static void installSensitiveJdbcFilter() {
        org.apache.logging.log4j.spi.LoggerContext context = LogManager.getContext(false);
        if (!(context instanceof LoggerContext coreContext)) {
            throw new IllegalStateException(
                    "EventTrail requires Log4j Core to scope JDBC diagnostics safely");
        }
        installSensitiveJdbcFilter(coreContext);
    }

    static void installSensitiveJdbcFilter(LoggerContext coreContext) {
        boolean changed;
        synchronized (INSTALL_LOCK) {
            if (OBSERVED_CONTEXTS.add(coreContext)) {
                coreContext.addPropertyChangeListener(event -> {
                    if (!LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                            || !(event.getNewValue() instanceof Configuration configuration)) {
                        return;
                    }
                    boolean reconfigured;
                    synchronized (INSTALL_LOCK) {
                        reconfigured = installOnConfiguration(configuration);
                    }
                    if (reconfigured) coreContext.updateLoggers(configuration);
                });
            }
            changed = installOnConfiguration(coreContext.getConfiguration());
        }
        if (changed) coreContext.updateLoggers();
    }

    private static boolean installOnConfiguration(Configuration configuration) {
        if (INSTALLED_CONFIGURATIONS.contains(configuration)) return false;

        Filter existing = configuration.getFilter();
        Filter installed = orderedSensitiveJdbcFilter(existing);
        installed.start();
        if (existing != null) configuration.removeFilter(existing);
        configuration.addFilter(installed);
        INSTALLED_CONFIGURATIONS.add(configuration);
        return true;
    }

    static Filter orderedSensitiveJdbcFilter(Filter existing) {
        Filter eventTrail = new SensitiveJdbcLogFilter();
        return existing == null ? eventTrail : new DenyFirstGlobalFilter(eventTrail, existing);
    }

    static Scope open() {
        String previous = ThreadContext.get(CONTEXT_KEY);
        ThreadContext.put(CONTEXT_KEY, CONTEXT_VALUE);
        return () -> {
            if (previous == null) ThreadContext.remove(CONTEXT_KEY);
            else ThreadContext.put(CONTEXT_KEY, previous);
        };
    }

    static ThreadFactory wrap(ThreadFactory delegate) {
        return task -> delegate.newThread(() -> {
            try (Scope ignored = open()) {
                task.run();
            }
        });
    }

    static Filter.Result filterResult(String loggerName, String owner) {
        return isSensitiveJdbcLogger(loggerName) && CONTEXT_VALUE.equals(owner)
                ? Filter.Result.DENY
                : Filter.Result.NEUTRAL;
    }

    private static boolean isSensitiveJdbcLogger(String loggerName) {
        return loggerName != null && (loggerName.equals(HIKARI_CONFIG_LOGGER)
                || loggerName.equals(HIKARI_POOL_LOGGER)
                || loggerName.equals(HIKARI_POOL_BASE_LOGGER)
                || loggerName.equals(MYSQL_LOGGER)
                || loggerName.startsWith(MYSQL_LOGGER + "."));
    }

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final class SensitiveJdbcLogFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            String owner = event == null || event.getContextData() == null
                    ? null
                    : event.getContextData().getValue(CONTEXT_KEY);
            return filterResult(event == null ? null : event.getLoggerName(), owner);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, Message message, Throwable throwable) {
            return filterCurrentThread(logger);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, Object message, Throwable throwable) {
            return filterCurrentThread(logger);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message, Object... parameters) {
            return filterCurrentThread(logger);
        }

        private static Result filterCurrentThread(Logger logger) {
            return filterResult(
                    logger == null ? null : logger.getName(),
                    ThreadContext.get(CONTEXT_KEY));
        }
    }

    /** Keeps EventTrail's ownership denial ahead of every pre-existing decision. */
    private static final class DenyFirstGlobalFilter extends AbstractFilter {
        private final Filter eventTrail;
        private final Filter existing;

        private DenyFirstGlobalFilter(Filter eventTrail, Filter existing) {
            this.eventTrail = eventTrail;
            this.existing = existing;
        }

        @Override
        public Result filter(LogEvent event) {
            Result eventTrailDecision = eventTrail.filter(event);
            return eventTrailDecision == Result.DENY ? Result.DENY : existing.filter(event);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, Message message, Throwable throwable) {
            Result eventTrailDecision = eventTrail.filter(logger, level, marker, message, throwable);
            return eventTrailDecision == Result.DENY
                    ? Result.DENY
                    : existing.filter(logger, level, marker, message, throwable);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, Object message, Throwable throwable) {
            Result eventTrailDecision = eventTrail.filter(logger, level, marker, message, throwable);
            return eventTrailDecision == Result.DENY
                    ? Result.DENY
                    : existing.filter(logger, level, marker, message, throwable);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message, Object... parameters) {
            Result eventTrailDecision = eventTrail.filter(logger, level, marker, message, parameters);
            return eventTrailDecision == Result.DENY
                    ? Result.DENY
                    : existing.filter(logger, level, marker, message, parameters);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message, Object p0) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message, p0);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message, Object p0, Object p1) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message, p0, p1);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message,
                Object p0, Object p1, Object p2) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message, p0, p1, p2);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message,
                Object p0, Object p1, Object p2, Object p3) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message, p0, p1, p2, p3);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message,
                Object p0, Object p1, Object p2, Object p3, Object p4) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message, p0, p1, p2, p3, p4);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message,
                Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message,
                Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message,
                Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message, p0, p1, p2, p3, p4, p5, p6, p7);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message,
                Object p0, Object p1, Object p2, Object p3, Object p4,
                Object p5, Object p6, Object p7, Object p8) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message,
                    p0, p1, p2, p3, p4, p5, p6, p7, p8);
        }

        @Override
        public Result filter(
                Logger logger, Level level, Marker marker, String message,
                Object p0, Object p1, Object p2, Object p3, Object p4,
                Object p5, Object p6, Object p7, Object p8, Object p9) {
            if (isSensitiveJdbcEvent(logger)) return Result.DENY;
            return existing.filter(logger, level, marker, message,
                    p0, p1, p2, p3, p4, p5, p6, p7, p8, p9);
        }

        private static boolean isSensitiveJdbcEvent(Logger logger) {
            return filterResult(
                    logger == null ? null : logger.getName(),
                    ThreadContext.get(CONTEXT_KEY)) == Result.DENY;
        }

        @Override
        public void start() {
            if (!eventTrail.isStarted()) eventTrail.start();
            if (!existing.isStarted()) existing.start();
            super.start();
        }

        @Override
        public boolean stop(long timeout, TimeUnit timeUnit) {
            boolean stopped = super.stop(timeout, timeUnit);
            eventTrail.stop();
            existing.stop();
            return stopped;
        }
    }
}
