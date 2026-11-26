package io.github.betapetey.eventtrail.commands.presentation;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Builds the Design B lookup and inspect chat (Owner 11-22).
 *
 * <p>Lookup: the top rule "EventTrail » Lookup", "N results · Query: N ms", the filter line, the header rule, the
 * rows, then the bottom rule with the pager. Inspect: the top rule "EventTrail » Inspect", "N results · Query: N ms",
 * "@ x y z · dimension", the header rule, the rows (no coordinates), then the same pager. Header lines are
 * left-aligned and unpadded; nothing is ever shortened (a long row wraps).
 *
 * <p>Hovers and clicks sit only on: a thing's name (its ID, plus {@code Renamed: "X"}; the click copies the ID), the
 * coordinates ("Click to teleport", plus the dimension when no line on screen names it; the click runs the teleport
 * link, which checks the permission; a viewer with no teleport link gets no click, and a hover with only the
 * dimension when no line on screen names it), the time (the exact time, then "Click to copy"; the click copies the exact
 * time), the filter line ("Click: edit this search") and the pager. Player names, action words, counts and the summary
 * carry none.
 */
public final class AuditResultsComponentRenderer {
    public static final String BRAND = "EventTrail";
    public static final String LOOKUP_TITLE = "Lookup";
    public static final String INSPECT_TITLE = "Inspect";
    public static final String TELEPORT_HOVER = "Click to teleport";
    public static final String COPY_HOVER = "Click to copy";
    public static final String EDIT_SEARCH_HOVER = "Click: edit this search";
    public static final String RENAMED_PREFIX = "Renamed: \"";
    public static final int SKY_BLUE_RGB = 0x6FB0FF;

    private final PageNavigationComponentRenderer navigationRenderer;
    private final Supplier<ZoneId> zone;

    public AuditResultsComponentRenderer() {
        this(new PageNavigationComponentRenderer(), ZoneId::systemDefault);
    }

    /** A fixed display zone (tests and goldens, so the time hover never depends on the machine's zone). */
    public AuditResultsComponentRenderer(ZoneId zone) {
        this(new PageNavigationComponentRenderer(), () -> zone);
    }

    AuditResultsComponentRenderer(PageNavigationComponentRenderer navigationRenderer) {
        this(navigationRenderer, ZoneId::systemDefault);
    }

    AuditResultsComponentRenderer(PageNavigationComponentRenderer navigationRenderer, Supplier<ZoneId> zone) {
        this.navigationRenderer = Objects.requireNonNull(navigationRenderer, "navigationRenderer");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public List<Component> render(LookupResultsView view) {
        return render(view, List.of());
    }

    /**
     * The lookup screen, with the no-match lines (ExtendedLookup.noMatchLines, Q248 A) in the rows' place when the
     * page has no rows. The lines are shown as given, one chat line each, GRAY; with rows they are not shown.
     */
    public List<Component> render(LookupResultsView view, List<String> noMatchLines) {
        Objects.requireNonNull(view, "view");
        ZoneId displayZone = zone.get();
        List<Component> lines = new ArrayList<>();
        lines.add(titleRule(LOOKUP_TITLE));
        lines.add(summaryLine(view.summary().resultText(), view.summary().queryText()));
        lines.add(filterLine(view.summary()));
        lines.add(ChatRule.solid());
        for (AuditRowView row : view.rows()) lines.add(row(row, true, displayZone));
        if (view.rows().isEmpty() && noMatchLines != null) {
            for (String note : noMatchLines) lines.add(Component.literal(note).withStyle(ChatFormatting.GRAY));
        }
        lines.add(navigationRenderer.render(view.navigation()));
        return List.copyOf(lines);
    }

    public List<Component> renderInspect(InspectResultsView view) {
        Objects.requireNonNull(view, "view");
        ZoneId displayZone = zone.get();
        List<Component> lines = new ArrayList<>();
        lines.add(titleRule(INSPECT_TITLE));
        lines.add(summaryLine(view.resultText(), "Query: " + view.queryMillis() + " ms"));
        lines.add(positionLine(view));
        lines.add(ChatRule.solid());
        for (AuditRowView row : view.rows()) lines.add(row(row, false, displayZone));
        lines.add(navigationRenderer.render(view.navigation()));
        return List.copyOf(lines);
    }

    /** One lookup row, with its coordinates, in the server's zone. */
    public static Component rowComponent(AuditRowView view) {
        return row(view, true, ZoneId.systemDefault());
    }

    static MutableComponent titleRule(String title) {
        MutableComponent inner = Component.literal(" ")
                .append(Component.literal(BRAND).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(title).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" "));
        return ChatRule.ruled(inner);
    }

    private static MutableComponent summaryLine(String results, String query) {
        return Component.empty()
                .append(Component.literal(results).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(query).withStyle(ChatFormatting.GRAY));
    }

    /** Keys GRAY, values WHITE, the commas between ORed values GRAY; " in <world>" for a radius search. */
    static MutableComponent filterLine(LookupSummaryView summary) {
        MutableComponent line = Component.empty();
        List<String> tokens = summary.filterTokens();
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            String lead = index == 0 ? "" : " ";
            int colon = token.indexOf(':');
            if (colon < 0) {
                line.append(Component.literal(lead + token).withStyle(ChatFormatting.WHITE));
                continue;
            }
            line.append(Component.literal(lead + token.substring(0, colon + 1)).withStyle(ChatFormatting.GRAY));
            List<String> values = splitValues(token.substring(colon + 1));
            for (int value = 0; value < values.size(); value++) {
                if (value > 0) line.append(Component.literal(",").withStyle(ChatFormatting.GRAY));
                line.append(Component.literal(values.get(value)).withStyle(ChatFormatting.WHITE));
            }
        }
        if (summary.scopeWorld() != null) {
            line.append(Component.literal(" in ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(summary.scopeWorld()).withStyle(ChatFormatting.WHITE));
        }
        summary.editCommand().ifPresent(command -> line.withStyle(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal(EDIT_SEARCH_HOVER).withStyle(ChatFormatting.YELLOW)))
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))));
        return line;
    }

    /** Splits a filter value on commas outside double quotes (the filter line shows each ORed value). */
    static List<String> splitValues(String value) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\\' && quoted && index + 1 < value.length()) {
                current.append(ch).append(value.charAt(++index));
                continue;
            }
            if (ch == '"') quoted = !quoted;
            if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static MutableComponent positionLine(InspectResultsView view) {
        MutableComponent where = Component.literal("@ " + view.coordinates()).withStyle(ChatFormatting.AQUA);
        view.positionCommand().ifPresent(command -> where.withStyle(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal(TELEPORT_HOVER).withStyle(ChatFormatting.YELLOW)))
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))));
        return Component.empty()
                .append(where)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(view.dimensionId().toString()).withStyle(ChatFormatting.GRAY));
    }

    static MutableComponent row(AuditRowView view, boolean withCoordinates, ZoneId displayZone) {
        MutableComponent line = Component.empty()
                .append(age(view.relativeAge(), view.occurredAt(), displayZone))
                .append(Component.literal(" "))
                .append(party(view.indexedParty()))
                .append(Component.literal(" "))
                .append(verb(view.verb()));
        for (RowPart part : view.parts()) line.append(part(part));
        if (withCoordinates) {
            line.append(Component.literal(" ")).append(coordinates(view.location()));
        }
        return line;
    }

    /** The exact time a row's time hover shows and its click copies. */
    public static String exactTime(Instant occurredAt, ZoneId displayZone) {
        return DisplayTimeFormatter.format(occurredAt, displayZone);
    }

    private static MutableComponent age(String relativeAge, Optional<Instant> occurredAt, ZoneId displayZone) {
        MutableComponent age = Component.literal(relativeAge).withStyle(ChatFormatting.GRAY);
        occurredAt.ifPresent(at -> {
            String exact = exactTime(at, displayZone);
            Component hover = Component.literal(exact).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("\n" + COPY_HOVER).withStyle(ChatFormatting.YELLOW));
            age.withStyle(style -> style
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, exact)));
        });
        return age;
    }

    /** The row's player. No hover and no click (Q243 B: no click on player names or action words). */
    private static MutableComponent party(String indexedParty) {
        return Component.literal(indexedParty).withStyle(ChatFormatting.WHITE);
    }

    /** The action word in its colour. No hover and no click (Q243 B). */
    private static MutableComponent verb(AuditRowView.Verb verb) {
        return Component.literal(verb.text()).withStyle(style -> style.withColor(colour(verb.tone())));
    }

    static TextColor colour(AuditRowView.Tone tone) {
        return switch (tone) {
            case RED -> TextColor.fromLegacyFormat(ChatFormatting.RED);
            case GREEN -> TextColor.fromLegacyFormat(ChatFormatting.GREEN);
            case YELLOW -> TextColor.fromLegacyFormat(ChatFormatting.YELLOW);
            case GOLD -> TextColor.fromLegacyFormat(ChatFormatting.GOLD);
            case SKY_BLUE -> TextColor.fromRgb(SKY_BLUE_RGB);
            case DARK_AQUA -> TextColor.fromLegacyFormat(ChatFormatting.DARK_AQUA);
            case GRAY -> TextColor.fromLegacyFormat(ChatFormatting.GRAY);
        };
    }

    private static MutableComponent part(RowPart part) {
        return switch (part) {
            case RowPart.Count count -> Component.literal(count.plainText()).withStyle(ChatFormatting.WHITE);
            case RowPart.Joiner joiner -> Component.literal(joiner.text()).withStyle(ChatFormatting.GRAY);
            case RowPart.Note note -> Component.literal(note.text()).withStyle(ChatFormatting.GRAY);
            case RowPart.Name name -> Component.literal(name.text()).withStyle(ChatFormatting.WHITE);
            case RowPart.Thing thing -> thing(thing);
        };
    }

    /** {@code Dirt "Diamond"}: the real name, then a renamed thing's custom name in italics, sharing one hover and click. */
    private static MutableComponent thing(RowPart.Thing thing) {
        MutableComponent name = interactive(Component.literal(thing.realName()).withStyle(ChatFormatting.WHITE), thing);
        if (!thing.renamed()) return name;
        MutableComponent out = Component.empty().append(name)
                .append(Component.literal(" \"").withStyle(ChatFormatting.GRAY))
                .append(interactive(Component.literal(thing.customName())
                        .withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC), thing))
                .append(Component.literal("\"").withStyle(ChatFormatting.GRAY));
        return out;
    }

    /** A thing with an ID: hover [ID, Renamed: "X"], click copies the ID. A thing with no ID gets neither. */
    private static MutableComponent interactive(MutableComponent text, RowPart.Thing thing) {
        if (thing.id() == null) return text;
        MutableComponent hover = Component.literal(thing.id()).withStyle(ChatFormatting.GRAY);
        if (thing.renamed()) {
            hover.append(Component.literal("\n" + RENAMED_PREFIX).withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(thing.customName()).withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC))
                    .append(Component.literal("\"").withStyle(ChatFormatting.GRAY));
        }
        return text.withStyle(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, thing.id())));
    }

    /**
     * "x y z" AQUA with no "@"; the teleport click and hover only when the row has a teleport link. With no link, a
     * row whose dimension no other line names gets a hover with only the GRAY dimension id, and no click.
     */
    private static MutableComponent coordinates(AuditRowView.RowLocation location) {
        MutableComponent coordinates = Component.literal(location.coordinates()).withStyle(ChatFormatting.AQUA);
        if (location.navigationCommand().isEmpty()) {
            if (location.showDimension()) {
                Component hover = Component.literal(location.dimensionId().toString()).withStyle(ChatFormatting.GRAY);
                coordinates.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
            }
            return coordinates;
        }
        location.navigationCommand().ifPresent(command -> {
            MutableComponent hover = Component.literal(TELEPORT_HOVER).withStyle(ChatFormatting.YELLOW);
            if (location.showDimension()) {
                hover.append(Component.literal("\n" + location.dimensionId()).withStyle(ChatFormatting.GRAY));
            }
            coordinates.withStyle(style -> style
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
        });
        return coordinates;
    }
}
