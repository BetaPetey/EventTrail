package io.github.betapetey.eventtrail.commands;

import io.github.betapetey.eventtrail.commands.investigation.PlayerInvestigationRegistry;

/** The single server-thread-owned page/session registry shared by inspect and lookup. */
final class InvestigationSessions {
    static final PlayerInvestigationRegistry<InspectUseCase.InspectSnapshot, ExtendedLookup.LookupSession> REGISTRY =
            new PlayerInvestigationRegistry<>();

    private InvestigationSessions() {
    }
}
