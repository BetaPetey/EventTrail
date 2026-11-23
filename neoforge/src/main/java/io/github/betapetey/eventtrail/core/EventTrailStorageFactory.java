package io.github.betapetey.eventtrail.core;

import io.github.betapetey.eventtrail.storage.ILogStorage;

import java.nio.file.Path;
import java.util.Objects;

/** Creates one configured storage candidate without publishing it. */
@FunctionalInterface
interface EventTrailStorageFactory {
    StorageCandidate create(Path eventTrailDirectory) throws Exception;

    default String configuredBackendName() {
        return "Storage";
    }

    record StorageCandidate(ILogStorage storage, String backendName) {
        public StorageCandidate {
            Objects.requireNonNull(storage, "storage");
            if (backendName == null || backendName.isBlank()) backendName = "Storage";
        }
    }
}
