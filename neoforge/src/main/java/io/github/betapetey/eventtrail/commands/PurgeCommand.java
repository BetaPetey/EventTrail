package io.github.betapetey.eventtrail.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.betapetey.eventtrail.EventTrailConfig;
import io.github.betapetey.eventtrail.core.EventTrailMaintenance;
import io.github.betapetey.eventtrail.core.EventTrailManager;
import io.github.betapetey.eventtrail.permissions.EventTrailPermissions;
import io.github.betapetey.eventtrail.storage.ILogStorage;
import io.github.betapetey.eventtrail.storage.RetentionResult;
import io.github.betapetey.eventtrail.storage.TableShrink;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /eventtrail purge [days]}, {@code /eventtrail purge confirm}, {@code /eventtrail purge shrink},
 * {@code /eventtrail purge shrink confirm} and {@code /eventtrail purge shrink confirm-space}.
 *
 * <p>The command thread only checks permission, keeps the confirm step and hands the work to the retention worker;
 * the reply comes back on the server thread. Nothing the command does is recorded as an EventTrail event: it writes
 * no rows, only server log lines.</p>
 */
final class PurgeCommand {
    private static final Logger LOGGER = LogManager.getLogger("eventtrail");
    static final String PURGE_PERMISSION = EventTrailPermissions.PURGE_PERMISSION;
    static final PurgeConfirmations CONFIRMATIONS = new PurgeConfirmations(System::nanoTime);
    private static final String YELLOW = "§e";
    private static final String GRAY = "§7";
    private static final String RED = "§c";
    private static final String GREEN = "§a";
    private static final String WHITE = "§f";

    private PurgeCommand() {}

    static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("purge")
                .requires(source -> EventTrailPermissions.hasExactPermission(source, PURGE_PERMISSION))
                .executes(context -> askPurge(context.getSource(), EventTrailConfig.LOG_RETENTION_DAYS.get()))
                .then(Commands.literal("confirm").executes(context -> confirmPurge(context.getSource())))
                .then(Commands.argument("days", IntegerArgumentType.integer(1, 36500))
                        .executes(context -> askPurge(context.getSource(),
                                IntegerArgumentType.getInteger(context, "days"))))
                .then(Commands.literal("shrink")
                        .executes(context -> askShrink(context.getSource()))
                        .then(Commands.literal("confirm").executes(context -> confirmShrink(context.getSource())))
                        .then(Commands.literal("confirm-space")
                                .executes(context -> confirmShrinkSpace(context.getSource())))));
    }

    // ---- purge ------------------------------------------------------------------------------------------------------

    private static int askPurge(CommandSourceStack source, int days) {
        if (!allowed(source)) return 0;
        String needsDays = purgeNeedsDaysText(days);
        if (needsDays != null) {
            fail(source, needsDays);
            return 0;
        }
        long generation = EventTrailMaintenance.lifecycleGenerationOrNone();
        if (generation < 0L || storage() == null) {
            fail(source, unavailableText());
            return 0;
        }
        CONFIRMATIONS.offer(key(source), PurgeConfirmations.Step.PURGE, days, null, generation);
        sendNow(source, purgePromptLines(days));
        return 1;
    }

    private static int confirmPurge(CommandSourceStack source) {
        if (!allowed(source)) return 0;
        long generation = EventTrailMaintenance.lifecycleGenerationOrNone();
        PurgeConfirmations.Pending step = CONFIRMATIONS.take(key(source), PurgeConfirmations.Step.PURGE, generation);
        if (step == null) {
            fail(source, "Nothing to confirm. Type /eventtrail purge first; a confirm is valid for 60 seconds.");
            return 0;
        }
        MinecraftServer server = source.getServer();
        String who = source.getTextName();
        int days = step.days();
        EventTrailMaintenance.Admission admission = EventTrailMaintenance.requestPurge(storage(), days,
                result -> {
                    LOGGER.info("EventTrail purge requested by {} ended: {} records older than {} days deleted ({})",
                            who, result.deletedCount(), days, result.outcome());
                    deliver(server, source, purgeDoneLines(result));
                });
        if (!admitted(source, admission)) return 0;
        LOGGER.info("EventTrail purge of records older than {} days confirmed by {}", days, who);
        sendNow(source, List.of(GRAY + "EventTrail purge queued: records older than " + days + " days. It runs in "
                + "small steps on EventTrail's maintenance worker, gently while players are online. You get a "
                + "message when it ends."));
        return 1;
    }

    // ---- shrink -----------------------------------------------------------------------------------------------------

    private static int askShrink(CommandSourceStack source) {
        if (!allowed(source)) return 0;
        long generation = EventTrailMaintenance.lifecycleGenerationOrNone();
        if (generation < 0L || storage() == null) {
            fail(source, unavailableText());
            return 0;
        }
        MinecraftServer server = source.getServer();
        String key = key(source);
        EventTrailMaintenance.Admission admission = EventTrailMaintenance.requestShrinkPlan(storage(),
                plan -> server.execute(() -> {
                    if (plan.refused()) {
                        sendNow(source, shrinkRefusedLines(plan));
                        return;
                    }
                    CONFIRMATIONS.offer(key, PurgeConfirmations.Step.SHRINK, 0, plan, generation);
                    sendNow(source, shrinkPromptLines(plan));
                }));
        if (!admitted(source, admission)) return 0;
        sendNow(source, List.of(GRAY + "Measuring what the shrink needs..."));
        return 1;
    }

    private static int confirmShrink(CommandSourceStack source) {
        if (!allowed(source)) return 0;
        long generation = EventTrailMaintenance.lifecycleGenerationOrNone();
        PurgeConfirmations.Decision decision = CONFIRMATIONS.confirmShrink(key(source), generation);
        switch (decision.next()) {
            case NOTHING -> {
                fail(source, "Nothing to confirm. Type /eventtrail purge shrink first; a confirm is valid for 60 seconds.");
                return 0;
            }
            case ASK_SPACE -> {
                // MySQL and PostgreSQL start only after a second confirm about the database server's free space.
                sendNow(source, shrinkSpacePromptLines(decision.plan()));
                return 1;
            }
            default -> {
                return startShrink(source, decision.plan());
            }
        }
    }

    private static int confirmShrinkSpace(CommandSourceStack source) {
        if (!allowed(source)) return 0;
        long generation = EventTrailMaintenance.lifecycleGenerationOrNone();
        PurgeConfirmations.Decision decision = CONFIRMATIONS.confirmShrinkSpace(key(source), generation);
        if (decision.next() != PurgeConfirmations.Next.START) {
            fail(source, "Nothing to confirm, so nothing starts. Type /eventtrail purge shrink first.");
            return 0;
        }
        return startShrink(source, decision.plan());
    }

    private static int startShrink(CommandSourceStack source, TableShrink.Plan plan) {
        MinecraftServer server = source.getServer();
        String who = source.getTextName();
        EventTrailMaintenance.Admission admission = EventTrailMaintenance.requestShrink(storage(), plan, result -> {
            LOGGER.info("EventTrail shrink requested by {} ended: ran={}, before={}, after={}{}", who, result.ran(),
                    TableShrink.mib(result.beforeBytes()), TableShrink.mib(result.afterBytes()),
                    result.failure() == null ? "" : ", " + result.failure());
            deliver(server, source, shrinkDoneLines(plan, result));
        });
        if (!admitted(source, admission)) return 0;
        LOGGER.info("EventTrail {} shrink confirmed by {} (needs {}{})", plan.backend(), who,
                TableShrink.mib(plan.neededDiskBytes()), plan.needsAdminSpaceConfirm()
                        ? ", the admin confirmed " + TableShrink.mib(plan.confirmSpaceBytes()) + " free" : "");
        sendNow(source, List.of(GRAY + "EventTrail shrink queued. You get a message when it ends."));
        return 1;
    }

    // ---- texts (no Minecraft types, so tests read them directly) ----------------------------------------------------

    /**
     * {@code /eventtrail purge} with no day count uses retention.days. At retention.days = 0 (keep forever) it asks
     * for a day count instead; a one-time {@code /eventtrail purge <days>} works there too, and nothing is deleted automatically.
     */
    static String purgeNeedsDaysText(int days) {
        return days > 0 ? null : "retention.days is 0 here (keep forever), so give a day count for a one-time "
                + "purge: /eventtrail purge <days>, for example /eventtrail purge 30.";
    }

    static List<String> purgePromptLines(int days) {
        return List.of(
                YELLOW + "This deletes every EventTrail record older than " + days + " days on this server. "
                        + "Deleted records cannot be restored.",
                YELLOW + "Type " + WHITE + "/eventtrail purge confirm" + YELLOW + " within 60 seconds to start.");
    }

    static List<String> purgeDoneLines(RetentionResult result) {
        long deleted = result.deletedCount();
        return switch (result.outcome()) {
            case EXHAUSTED -> List.of(GREEN + "EventTrail purge finished: " + deleted + " records deleted.",
                    GRAY + "The database file or table keeps its size until a shrink: /eventtrail purge shrink");
            case INTERRUPTED, YIELDED -> List.of(YELLOW + "EventTrail purge stopped after " + deleted
                    + " records because EventTrail is stopping. Run it again later.");
            case BUDGET_EXHAUSTED, FAILED -> List.of(RED + "EventTrail purge failed after " + deleted
                    + " records. See the server log.");
        };
    }

    static List<String> shrinkRefusedLines(TableShrink.Plan plan) {
        List<String> lines = new ArrayList<>();
        lines.add(RED + "EventTrail will not start the shrink: " + plan.refusal() + ".");
        if (plan.backend() == TableShrink.Backend.SQLITE && plan.neededDiskBytes() >= 0L) {
            lines.add(GRAY + "It needs " + TableShrink.mib(plan.neededDiskBytes()) + " free next to the database file ("
                    + free(plan.freeDiskBytes()) + " free) and " + TableShrink.mib(plan.neededMemoryBytes())
                    + " free memory (" + free(plan.freeMemoryBytes()) + " free).");
        }
        return lines;
    }

    static List<String> shrinkPromptLines(TableShrink.Plan plan) {
        List<String> lines = new ArrayList<>();
        switch (plan.backend()) {
            case SQLITE -> {
                lines.add(YELLOW + "Shrink the EventTrail database file with SQLite VACUUM. The file is "
                        + TableShrink.mib(plan.tableBytes()) + " now.");
                lines.add(YELLOW + "It needs " + TableShrink.mib(plan.neededDiskBytes()) + " free next to the database "
                        + "file (" + free(plan.freeDiskBytes()) + " free) and " + TableShrink.mib(plan.neededMemoryBytes())
                        + " free memory (" + free(plan.freeMemoryBytes()) + " free).");
                lines.add(YELLOW + "While it runs, new records wait in EventTrail's spool and are written afterwards; "
                        + "lookups wait too.");
                lines.add(YELLOW + outOfSpaceText(plan.backend()));
                lines.add(YELLOW + "Type " + WHITE + "/eventtrail purge shrink confirm" + YELLOW
                        + " within 60 seconds to start.");
            }
            case MYSQL, POSTGRESQL -> {
                lines.add(YELLOW + "Shrink the EventTrail table with " + statementName(plan.backend())
                        + ". The table is " + TableShrink.mib(plan.tableBytes()) + " now.");
                lines.add(YELLOW + "The database server needs up to " + TableShrink.mib(plan.confirmSpaceBytes())
                        + " free disk space for it (" + TableShrink.mib(plan.neededDiskBytes()) + " measured now, plus "
                        + "room for records written meanwhile). EventTrail cannot see that disk, so you confirm it in "
                        + "the next step.");
                lines.add(YELLOW + lockText(plan.backend()));
                lines.add(YELLOW + "Type " + WHITE + "/eventtrail purge shrink confirm" + YELLOW
                        + " within 60 seconds to go on.");
            }
            case UNSUPPORTED -> lines.add(RED + "This storage cannot shrink its table.");
        }
        return lines;
    }

    static List<String> shrinkSpacePromptLines(TableShrink.Plan plan) {
        return List.of(
                YELLOW + "Check that the database server has at least " + TableShrink.mib(plan.confirmSpaceBytes())
                        + " free disk space. Just before it starts, EventTrail measures again and does not start if "
                        + "the shrink then needs more than that.",
                YELLOW + outOfSpaceText(plan.backend()),
                YELLOW + "If it has that much free, type " + WHITE + "/eventtrail purge shrink confirm-space" + YELLOW
                        + " within 60 seconds to start. Without it, nothing starts.");
    }

    static List<String> shrinkDoneLines(TableShrink.Plan plan, TableShrink.Result result) {
        if (result.ran()) {
            return List.of(GREEN + "EventTrail shrink finished: " + TableShrink.mib(result.beforeBytes()) + " before, "
                    + TableShrink.mib(result.afterBytes()) + " after.");
        }
        if (result.beforeBytes() < 0L) {
            return List.of(RED + "EventTrail did not start the shrink: " + result.failure() + ".");
        }
        return List.of(RED + "EventTrail shrink stopped: " + result.failure() + ".",
                YELLOW + outOfSpaceText(plan.backend()));
    }

    /** What happens when space runs out during the shrink, per backend. */
    static String outOfSpaceText(TableShrink.Backend backend) {
        return switch (backend) {
            case SQLITE -> "If the disk fills anyway, SQLite stops the shrink and keeps the old file unchanged. "
                    + "New records then wait as they do whenever the disk is full: in memory, and in the spool "
                    + "while it has room.";
            case MYSQL -> "If the database server runs out of space, MySQL stops the rebuild and keeps the old table "
                    + "unchanged. EventTrail keeps retrying the waiting records, in memory and in the spool while it "
                    + "has room, until the database accepts them again.";
            case POSTGRESQL -> "If the database server runs out of space, PostgreSQL stops VACUUM FULL and keeps the "
                    + "old table unchanged. If the disk holding its write-ahead log fills, PostgreSQL shuts itself "
                    + "down until space is freed; saved records are kept. EventTrail's new records wait in memory and "
                    + "in the spool while it has room.";
            case UNSUPPORTED -> "This storage cannot shrink its table.";
        };
    }

    private static String lockText(TableShrink.Backend backend) {
        return backend == TableShrink.Backend.MYSQL
                ? "Every server that writes to this table shares it. MySQL locks the table briefly at the start and "
                        + "the end; new records meanwhile wait in EventTrail's spool."
                : "Every server that writes to this table shares it. PostgreSQL locks the table for the whole shrink; "
                        + "new records meanwhile wait in EventTrail's spool, and lookups wait.";
    }

    private static String statementName(TableShrink.Backend backend) {
        return backend == TableShrink.Backend.MYSQL ? "MySQL OPTIMIZE TABLE" : "PostgreSQL VACUUM FULL";
    }

    private static String free(java.util.OptionalLong bytes) {
        return bytes.isPresent() ? TableShrink.mib(bytes.getAsLong()) : "unknown";
    }

    private static String unavailableText() {
        return "EventTrail's maintenance worker is not running (it runs while the database is ready).";
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------------

    /** The node is checked again when the command runs, as the other EventTrail commands do. */
    private static boolean allowed(CommandSourceStack source) {
        return EventTrailPermissions.hasExactPermission(source, PURGE_PERMISSION);
    }

    private static ILogStorage storage() {
        return EventTrailManager.getInstance().getStorage();
    }

    private static boolean admitted(CommandSourceStack source, EventTrailMaintenance.Admission admission) {
        return switch (admission) {
            case QUEUED -> true;
            case BUSY -> {
                fail(source, "An EventTrail purge or shrink is already waiting or running. Try again when it ends.");
                yield false;
            }
            case UNAVAILABLE -> {
                fail(source, unavailableText());
                yield false;
            }
        };
    }

    /** One pending step per player, or per named non-player source (the console). */
    static String key(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? "player:" + player.getUUID() : "source:" + source.getTextName();
    }

    private static void deliver(MinecraftServer server, CommandSourceStack source, List<String> lines) {
        server.execute(() -> sendNow(source, lines));
    }

    private static void sendNow(CommandSourceStack source, List<String> lines) {
        for (String line : lines) source.sendSystemMessage(Component.literal(line));
    }

    private static void fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(RED + text));
    }
}
