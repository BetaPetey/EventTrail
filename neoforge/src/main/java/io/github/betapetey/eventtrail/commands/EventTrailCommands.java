package io.github.betapetey.eventtrail.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.permissions.EventTrailPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Owns EventTrail's one canonical Brigadier tree and best-effort short alias. */
public final class EventTrailCommands {
    public static final String CANONICAL_ROOT = "eventtrail";
    public static final String ALIAS_ROOT = "et";
    public static final String CANONICAL_PREFIX = "/" + CANONICAL_ROOT;

    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    private static final Predicate<CommandSourceStack> ROOT_REQUIREMENT =
            EventTrailPermissions::hasRootPermission;

    private EventTrailCommands() {}

    /** Registers a fresh command tree for one Minecraft command-tree rebuild. */
    public static LiteralCommandNode<CommandSourceStack> register(
            CommandDispatcher<CommandSourceStack> dispatcher) {
        return register(dispatcher, warning -> LOGGER.warn(warning));
    }

    static LiteralCommandNode<CommandSourceStack> register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            Consumer<String> aliasCollisionWarning) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(aliasCollisionWarning, "aliasCollisionWarning");

        if (dispatcher.getRoot().getChild(CANONICAL_ROOT) != null) {
            String message = "Cannot register /eventtrail because its canonical command root is already occupied.";
            LOGGER.error(message);
            throw new IllegalStateException(message);
        }

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(CANONICAL_ROOT)
                .requires(ROOT_REQUIREMENT);
        InspectCommand.attach(root);
        ExtendedLookup.attach(root);
        PurgeCommand.attach(root);

        LiteralCommandNode<CommandSourceStack> canonical = dispatcher.register(root);

        if (dispatcher.getRoot().getChild(ALIAS_ROOT) == null) {
            dispatcher.register(Commands.literal(ALIAS_ROOT)
                    .requires(ROOT_REQUIREMENT)
                    .redirect(canonical));
        } else {
            aliasCollisionWarning.accept(
                    "EventTrail did not register /et because that command is already occupied; /eventtrail remains available.");
        }

        return canonical;
    }
}
