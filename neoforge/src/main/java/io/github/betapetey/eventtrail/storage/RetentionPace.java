package io.github.betapetey.eventtrail.storage;

/**
 * How one bounded retention pass deletes: rows per batch, the pause between batches and the pass's work budget.
 * The pass always runs on the retention worker, never on the server thread.
 */
public record RetentionPace(int batchSize, long pauseMillis, long budgetMillis) {
    /** Startup, idle and an empty-server purge: today's batch, pause and budget, unchanged. */
    public static final RetentionPace STANDARD = new RetentionPace(AbstractJdbcStorage.RETENTION_DELETE_BATCH_SIZE,
            AbstractJdbcStorage.RETENTION_BATCH_PAUSE_MILLIS, AbstractJdbcStorage.RETENTION_WORK_BUDGET_MILLIS);
    /**
     * While players are online: a quarter of the standard batch and a pause 25 times longer, so the delete holds
     * the database only briefly and the writer gets it between batches (at most about 1,000 rows a second).
     */
    public static final RetentionPace GENTLE = new RetentionPace(250, 250L,
            AbstractJdbcStorage.RETENTION_WORK_BUDGET_MILLIS);

    public RetentionPace {
        if (batchSize <= 0 || batchSize > AbstractJdbcStorage.RETENTION_DELETE_BATCH_SIZE) {
            throw new IllegalArgumentException("Retention batch size must be 1.." + AbstractJdbcStorage.RETENTION_DELETE_BATCH_SIZE);
        }
        if (pauseMillis < 0L || pauseMillis > 60_000L) {
            throw new IllegalArgumentException("Retention batch pause must be 0..60000 ms");
        }
        if (budgetMillis <= 0L || budgetMillis > AbstractJdbcStorage.RETENTION_WORK_BUDGET_MILLIS) {
            throw new IllegalArgumentException("Retention work budget must be 1.." + AbstractJdbcStorage.RETENTION_WORK_BUDGET_MILLIS + " ms");
        }
    }
}
