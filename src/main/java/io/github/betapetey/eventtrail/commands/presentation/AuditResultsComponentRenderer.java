package io.github.betapetey.eventtrail.commands.presentation;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Builds Minecraft components for the approved structured lookup hierarchy. */
public final class AuditResultsComponentRenderer {
    public static final String DIVIDER = "────────────────────────────────────────";
    public static final String HEADER = "EventTrail » Lookup Results";
    private final PageNavigationComponentRenderer navigationRenderer;

    public AuditResultsComponentRenderer() {
        this(new PageNavigationComponentRenderer());
    }

    AuditResultsComponentRenderer(PageNavigationComponentRenderer navigationRenderer) {
        this.navigationRenderer = Objects.requireNonNull(navigationRenderer, "navigationRenderer");
    }

    public List<Component> render(LookupResultsView view) {
        Objects.requireNonNull(view, "view");
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(DIVIDER).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("EventTrail").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" » ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("Lookup Results").withStyle(ChatFormatting.WHITE)));
        lines.add(Component.literal(summary(view.summary())).withStyle(ChatFormatting.GRAY));
        if (!view.rows().isEmpty()) lines.add(Component.empty());
        for (AuditRowView row : view.rows()) lines.add(rowComponent(row));
        if (!view.rows().isEmpty()) lines.add(Component.empty());
        lines.add(navigationRenderer.render(view.navigation()));
        lines.add(Component.literal(DIVIDER).withStyle(ChatFormatting.DARK_GRAY));
        return List.copyOf(lines);
    }

    public List<Component> renderInspect(InspectResultsView view) {
        Objects.requireNonNull(view, "view");
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(DIVIDER).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("EventTrail").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" » ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("Block History").withStyle(ChatFormatting.WHITE)));
        lines.add(Component.literal(String.format(Locale.ROOT, "@ %d %d %d • %s",
                        view.position().getX(), view.position().getY(), view.position().getZ(),
                        view.dimensionId()))
                .withStyle(ChatFormatting.GRAY));
        if (!view.rows().isEmpty()) lines.add(Component.empty());
        view.rows().stream().map(AuditResultsComponentRenderer::rowComponent).forEach(lines::add);
        if (!view.rows().isEmpty()) lines.add(Component.empty());
        lines.add(navigationRenderer.render(view.navigation()));
        lines.add(Component.literal(DIVIDER).withStyle(ChatFormatting.DARK_GRAY));
        return List.copyOf(lines);
    }

    static String summary(LookupSummaryView view) {
        StringBuilder text = new StringBuilder()
                .append(view.resultCount())
                .append(view.resultCount() == 1L ? " result" : " results");
        if (view.radius() != null) text.append(" • Radius: ").append(view.radius());
        text.append(" • Time: ").append(view.timeLabel());
        text.append(" • World: ").append(view.worldLabel());
        text.append(" • Query: ").append(view.queryMillis()).append(" ms");
        return text.toString();
    }

    public static Component rowComponent(AuditRowView view) {
        MutableComponent line = Component.literal("[#" + view.ordinal() + "]  ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(view.relativeAge() + "   ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(view.indexedParty() + "   ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(view.actionLabel() + "    ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(view.detail() + "    ").withStyle(ChatFormatting.WHITE));
        AuditRowView.RowLocation location = view.location();
        if (location.showDimension()) {
            line.append(Component.literal(location.dimensionId() + "   ")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        String coordinates = String.format(Locale.ROOT, "@ %d %d %d",
                location.position().getX(),
                location.position().getY(),
                location.position().getZ());
        MutableComponent coordinateComponent = Component.literal(coordinates)
                .withStyle(ChatFormatting.AQUA);
        location.navigationCommand().ifPresent(command -> coordinateComponent.withStyle(style ->
                style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))));
        return line.append(coordinateComponent);
    }

}
