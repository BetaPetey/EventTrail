package io.github.betapetey.eventtrail.commands.presentation;

import java.util.List;
import java.util.Objects;

/** Complete immutable structured-lookup presentation. */
public record LookupResultsView(
        LookupSummaryView summary,
        List<AuditRowView> rows,
        NavigationView navigation) {
    public LookupResultsView {
        summary = Objects.requireNonNull(summary, "summary");
        rows = List.copyOf(rows);
        navigation = Objects.requireNonNull(navigation, "navigation");
    }
}
