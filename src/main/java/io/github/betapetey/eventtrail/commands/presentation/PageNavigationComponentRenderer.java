package io.github.betapetey.eventtrail.commands.presentation;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Objects;

/** Renders the one canonical previous/current/next page interaction. */
public final class PageNavigationComponentRenderer {
    public Component render(NavigationView view) {
        Objects.requireNonNull(view, "view");
        MutableComponent line = Component.empty();
        view.previousCommand().ifPresent(command -> line.append(clickable(
                "[Previous Page]", command)).append(Component.literal(" ")));
        line.append(Component.literal("[Page " + view.page() + "/" + view.totalPages() + "]")
                .withStyle(ChatFormatting.GRAY));
        view.nextCommand().ifPresent(command -> line.append(Component.literal(" ")).append(clickable(
                "[Next Page]", command)));
        return line;
    }

    private static MutableComponent clickable(String text, String command) {
        return Component.literal(text).withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }
}
