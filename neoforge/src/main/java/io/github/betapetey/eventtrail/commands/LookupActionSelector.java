package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPhase;
import io.github.betapetey.eventtrail.storage.ActionSelection;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Public query vocabulary, not persisted actions or a schema-v1 extension. */
public enum LookupActionSelector {
    BREAK("break", AuditAction.BLOCK_BREAK, null),
    PLACE("place", AuditAction.BLOCK_PLACE, null),
    CONTAINER_OPEN("container_open", AuditAction.CONTAINER_ACCESS, ContainerAccessPhase.OPEN),
    CONTAINER_CLOSED("container_closed", AuditAction.CONTAINER_ACCESS, ContainerAccessPhase.CLOSE),
    CONTAINER_DEPOSIT("container_deposit", AuditAction.CONTAINER_DEPOSIT, null),
    CONTAINER_WITHDRAWAL("container_withdrawal", AuditAction.CONTAINER_WITHDRAWAL, null),
    KILL("kill", AuditAction.ENTITY_KILL, null),
    DEATH("death", AuditAction.ENTITY_DEATH, null),
    DROP("drop", AuditAction.ITEM_DROP, null),
    PICKUP("pickup", AuditAction.ITEM_PICKUP, null),
    EXPLOSION("explosion", AuditAction.EXPLOSION, null);

    private static final List<LookupActionSelector> CATALOG = List.of(values());
    private final String token;
    private final AuditAction action;
    private final ContainerAccessPhase phase;

    LookupActionSelector(String token, AuditAction action, ContainerAccessPhase phase) {
        this.token = token;
        this.action = action;
        this.phase = phase;
    }

    public String token() { return token; }
    public AuditAction action() { return action; }
    public ContainerAccessPhase phase() { return phase; }
    public static List<LookupActionSelector> catalog() { return CATALOG; }

    public static LookupActionSelector parse(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "break", "blockbreak", "block_break" -> BREAK;
            case "place", "blockplace", "block_place" -> PLACE;
            case "container_open" -> CONTAINER_OPEN;
            case "container_closed" -> CONTAINER_CLOSED;
            case "container_deposit" -> CONTAINER_DEPOSIT;
            case "container_withdrawal" -> CONTAINER_WITHDRAWAL;
            case "kill", "entitykill", "entity_kill" -> KILL;
            case "death", "entitydeath", "entity_death" -> DEATH;
            case "drop", "itemdrop", "item_drop" -> DROP;
            case "pickup", "itempickup", "item_pickup" -> PICKUP;
            case "explosion" -> EXPLOSION;
            default -> null;
        };
    }

    public static ActionSelection selection(Collection<LookupActionSelector> selectors) {
        Objects.requireNonNull(selectors, "selectors");
        if (selectors.size() > CATALOG.size()) throw new IllegalArgumentException("Too many lookup selectors");
        LinkedHashSet<AuditAction> actions = new LinkedHashSet<>();
        LinkedHashSet<ContainerAccessPhase> phases = new LinkedHashSet<>();
        for (LookupActionSelector selector : selectors) {
            Objects.requireNonNull(selector, "selector");
            actions.add(selector.action);
            if (selector.phase != null) phases.add(selector.phase);
        }
        return ActionSelection.restricted(actions, phases);
    }

    public boolean selectedBy(ActionSelection selection) {
        return selection.actions().contains(action) && (phase == null
                || selection.containerAccessPhases().isEmpty() || selection.containerAccessPhases().contains(phase));
    }

    public static String canonical(ActionSelection selection) {
        return String.join(",", CATALOG.stream().filter(selector -> selector.selectedBy(selection))
                .map(LookupActionSelector::token).toList());
    }
}
