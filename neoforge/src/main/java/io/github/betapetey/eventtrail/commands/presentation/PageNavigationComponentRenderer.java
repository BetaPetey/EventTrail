package io.github.betapetey.eventtrail.commands.presentation;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.Objects;
import java.util.Optional;

/**
 * The bottom rule with the one pager: " ◀ Prev | Page p/t | Next ▶ " centred in a 320 px rule (Owner Q217 B arrows).
 * An active arrow is GREEN, runs "/eventtrail page N" and has the one-line hover "Click: page N"; an inactive arrow is
 * DARK_GRAY with no hover and no click; "Page p/t" puts "/eventtrail page " in the chat box, hover
 * "Click: type a page number" (Owner 9:51 PM 11-22 and lead calls under D-314).
 */
public final class PageNavigationComponentRenderer {
    public static final String ARROW_LEFT = "◀";
    public static final String ARROW_RIGHT = "▶";
    public static final String PAGE_COMMAND_PREFIX = "/eventtrail page ";
    public static final String PAGE_HOVER = "Click: type a page number";

    public Component render(NavigationView view) {
        Objects.requireNonNull(view, "view");
        MutableComponent inner = Component.literal(" ")
                .append(arrow(ARROW_LEFT + " Prev", view.previousCommand(), view.page() - 1))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("Page " + view.page() + "/" + view.totalPages()).withStyle(style -> style
                        .withColor(ChatFormatting.GRAY)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal(PAGE_HOVER).withStyle(ChatFormatting.YELLOW)))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, PAGE_COMMAND_PREFIX))))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
                .append(arrow("Next " + ARROW_RIGHT, view.nextCommand(), view.page() + 1))
                .append(Component.literal(" "));
        return ChatRule.ruled(inner);
    }

    private static MutableComponent arrow(String text, Optional<String> command, int target) {
        if (command.isEmpty()) return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
        return Component.literal(text).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click: page " + target).withStyle(ChatFormatting.YELLOW)))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command.get())));
    }
}
