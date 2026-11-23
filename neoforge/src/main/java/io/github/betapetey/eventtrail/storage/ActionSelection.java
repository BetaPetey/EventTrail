package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPhase;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable structured action predicate for one EventTrail lookup.
 *
 * <p>An unrestricted selection is explicit. A restricted selection must retain
 * at least one action, so malformed or empty user input cannot accidentally
 * broaden a query into an all-action lookup.</p>
 */
public record ActionSelection(boolean restricted, List<AuditAction> actions,
                              List<ContainerAccessPhase> containerAccessPhases) {
    private static final ActionSelection UNRESTRICTED =
            new ActionSelection(false, List.of());

    public ActionSelection {
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(containerAccessPhases, "containerAccessPhases");
        if (actions.size() > AuditAction.catalog().size()) {
            throw new IllegalArgumentException("Action selection exceeds the action inventory");
        }

        LinkedHashSet<AuditAction> unique = new LinkedHashSet<>();
        for (AuditAction action : actions) {
            unique.add(Objects.requireNonNull(action, "action"));
        }
        actions = unique.stream()
                .sorted(Comparator.comparingInt(AuditAction::catalogOrder))
                .toList();

        if (restricted && actions.isEmpty()) {
            throw new IllegalArgumentException("A restricted action selection must not be empty");
        }
        if (!restricted && !actions.isEmpty()) {
            throw new IllegalArgumentException("An unrestricted action selection must be empty");
        }
        if (containerAccessPhases.size() > ContainerAccessPhase.values().length) {
            throw new IllegalArgumentException("Container access selection exceeds the phase inventory");
        }
        for (ContainerAccessPhase phase : containerAccessPhases) Objects.requireNonNull(phase, "phase");
        containerAccessPhases = containerAccessPhases.stream().distinct()
                .sorted(Comparator.comparingInt(phase -> phase == ContainerAccessPhase.OPEN ? 0 : 1))
                .toList();
        if (!containerAccessPhases.isEmpty() && (!restricted || !actions.contains(AuditAction.CONTAINER_ACCESS))) {
            throw new IllegalArgumentException("A container phase requires the container-access family");
        }
    }

    /** Internal family selection; an empty phase list leaves that family unrestricted. */
    public ActionSelection(boolean restricted, List<AuditAction> actions) {
        this(restricted, actions, List.of());
    }

    public static ActionSelection unrestricted() {
        return UNRESTRICTED;
    }

    public static ActionSelection restricted(Collection<AuditAction> actions) {
        Objects.requireNonNull(actions, "actions");
        return new ActionSelection(true, List.copyOf(actions));
    }

    /** Phase alternatives constrain only CONTAINER_ACCESS, never another selected action. */
    public static ActionSelection restricted(Collection<AuditAction> actions,
                                             Collection<ContainerAccessPhase> containerAccessPhases) {
        return new ActionSelection(true, List.copyOf(actions), List.copyOf(containerAccessPhases));
    }

    public static ActionSelection only(AuditAction action) {
        return restricted(List.of(Objects.requireNonNull(action, "action")));
    }

    public boolean isOnly(AuditAction action) {
        return restricted && actions.size() == 1 && actions.get(0) == action;
    }

    public AuditAction singleActionOrNull() {
        return actions.size() == 1 ? actions.get(0) : null;
    }
}
