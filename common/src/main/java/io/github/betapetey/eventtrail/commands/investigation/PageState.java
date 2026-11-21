package io.github.betapetey.eventtrail.commands.investigation;

import java.util.Objects;

/**
 * The one tagged owner of a player's {@code /eventtrail page} continuation.
 *
 * <p>A pending request deliberately owns no pageable results. Its request ID
 * prevents a late completion from restoring state after a toggle, logout, or
 * permission-driven invalidation.</p>
 */
public sealed interface PageState<I, L>
        permits PageState.Empty, PageState.Pending, PageState.InspectResults,
                PageState.LookupResults {

    /** The operation whose admitted asynchronous result may replace this state. */
    enum RequestKind {
        INSPECT_POSITION,
        LOOKUP_INITIAL,
        LOOKUP_PAGE
    }

    /** The current pageable-result owner, if any. */
    enum Owner {
        NONE,
        INSPECT,
        LOOKUP
    }

    Owner owner();

    static <I, L> PageState<I, L> empty() {
        return new Empty<>();
    }

    record Empty<I, L>() implements PageState<I, L> {
        @Override
        public Owner owner() {
            return Owner.NONE;
        }
    }

    record Pending<I, L>(RequestKind kind, long requestId) implements PageState<I, L> {
        public Pending {
            Objects.requireNonNull(kind, "kind");
            if (requestId <= 0L) {
                throw new IllegalArgumentException("Pending request IDs must be positive");
            }
        }

        @Override
        public Owner owner() {
            return Owner.NONE;
        }
    }

    record InspectResults<I, L>(I snapshot) implements PageState<I, L> {
        public InspectResults {
            Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public Owner owner() {
            return Owner.INSPECT;
        }
    }

    record LookupResults<I, L>(L context) implements PageState<I, L> {
        public LookupResults {
            Objects.requireNonNull(context, "context");
        }

        @Override
        public Owner owner() {
            return Owner.LOOKUP;
        }
    }
}
