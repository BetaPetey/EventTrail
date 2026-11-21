package io.github.betapetey.eventtrail;

import java.util.Locale;
import java.util.Properties;

/** Current property input with the established effective-value duplicate checks. */
final class EventTrailConfigInput {
    private EventTrailConfigInput() {}

    static Properties inputProperties() {
        return new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                String normalized = normalizedCurrentValue((String) key, (String) value);
                if (containsKey(key) && normalized != null
                        && !normalized.equals(normalizedCurrentValue((String) key, getProperty((String) key)))) {
                    throw new EventTrailConfigException("Conflicting duplicate configuration property: " + key
                            + "; reconcile its assignments before restarting. Original file unchanged.");
                }
                return super.put(key, value);
            }
        };
    }

    private static String normalizedCurrentValue(String key, String raw) {
        return switch (key) {
            case "retention.days" -> Integer.toString(EventTrailConfig.LOG_RETENTION_DAYS.normalize(raw));
            case "inspect.pageSize" -> Integer.toString(EventTrailConfig.PAGE_SIZE.normalize(raw));
            case "lookup.maxRadiusBlocks" -> Integer.toString(EventTrailConfig.MAX_SEARCH_RADIUS.normalize(raw));
            case "recording.blocks.multiPlace.enabled", "recording.blocks.explosions.enabled",
                    "recording.containers.access.enabled", "recording.containers.deposits.enabled",
                    "recording.containers.withdrawals.enabled", "recording.entities.kills.enabled",
                    "recording.players.deaths.enabled", "recording.items.drops.enabled",
                    "recording.items.pickups.enabled" -> {
                Properties one = new Properties();
                one.setProperty(key, raw);
                yield Boolean.toString(EventTrailConfig.parseBooleanProperty(one, key, true, ignored -> {}));
            }
            // The permission source decides who may use EventTrail: two different assignments fail loudly
            // instead of the last one winning silently. Matched as the selection matches it: surrounding
            // spaces and case do not count, and a blank value is auto. Unknown values compare as trimmed
            // lower-case text.
            case EventTrailConfig.PERMISSIONS_SOURCE_KEY -> {
                String source = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
                yield source.isEmpty() ? "auto" : source;
            }
            // Other settings retain their existing parser semantics. Unknown
            // properties are not normalized or assigned special meaning here.
            default -> null;
        };
    }
}
