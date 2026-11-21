package io.github.betapetey.eventtrail.permissions;

import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;

import java.util.Locale;

/**
 * Chooses the one source that decides EventTrail player permissions for a
 * server run. Exactly one source is chosen per server start and a decision
 * never consults a second source.
 *
 * <p>The server setting {@value #SETTING_KEY} picks the source. Its default,
 * {@code auto}, applies this fixed order:</p>
 *
 * <ol>
 *   <li>LuckPerms, when it is installed or is NeoForge's active permission
 *       handler. It decides every node; an unset node is denied.</li>
 *   <li>Another NeoForge permission handler (any active handler other than
 *       {@value #DEFAULT_HANDLER_ID}, for example one an admin pinned in the
 *       NeoForge server config). It decides through NeoForge's permission API.
 *       If EventTrail's nodes are not registered with NeoForge, the handler
 *       cannot answer and the operator check is used instead.</li>
 *   <li>FTB Ranks, when it is installed and its bridge resolved. A node that
 *       an FTB Ranks rank sets exactly uses that value; any other node uses the
 *       operator check.</li>
 *   <li>The operator check ({@link EventTrailOpFallback}).</li>
 * </ol>
 *
 * <p>Any other value names one source. If that source is not installed (or
 * cannot be used), the operator check is used with a warning; EventTrail
 * never falls back to another permission mod on its own. An unknown value
 * also uses the operator check with a warning.</p>
 *
 * <p>This class is pure: it reads no game state, so every order, every
 * setting and every startup line can be tested without a server.</p>
 */
final class EventTrailPermissionSelection {
    static final String DEFAULT_HANDLER_ID = "neoforge:default_handler";
    static final String LUCKPERMS_NAMESPACE = "luckperms";
    static final String STARTUP_PREFIX = "EventTrail permissions: ";
    /** The key in {@code eventtrail-server.properties}; must equal {@code EventTrailConfig.PERMISSIONS_SOURCE_KEY}. */
    static final String SETTING_KEY = "permissions.source";
    static final String ALLOWED_VALUES = "auto, luckperms, neoforge, ftbranks, operator";
    private static final int MAX_HANDLER_ID_LENGTH = 128;
    private static final int MAX_SETTING_VALUE_LENGTH = 64;
    private static final String FTB_NOT_USED_NOTE = "; FTB Ranks is installed but not used by EventTrail, and FTB Ranks"
            + " itself still applies any command.eventtrail values its ranks set to the command tree";
    private static final String HANDLER_UNKNOWN_NOTE = "; NeoForge's active permission handler is unknown";

    private EventTrailPermissionSelection() {}

    enum Source {
        LUCKPERMS,
        NEOFORGE_HANDLER,
        FTB_RANKS,
        OPERATOR
    }

    /** The values of {@value #SETTING_KEY}. */
    enum Setting {
        AUTO("auto", "auto"),
        LUCKPERMS("luckperms", "LuckPerms"),
        NEOFORGE("neoforge", "NeoForge's permission handler"),
        FTBRANKS("ftbranks", "FTB Ranks"),
        OPERATOR("operator", "the operator check");

        final String value;
        final String displayName;

        Setting(String value, String displayName) {
            this.value = value;
            this.displayName = displayName;
        }
    }

    /** Why a source named by the setting could not be used. */
    enum Unavailable {
        LUCKPERMS_NOT_INSTALLED,
        HANDLER_IS_DEFAULT,
        HANDLER_UNKNOWN,
        HANDLER_NODES_NOT_REGISTERED,
        FTB_RANKS_NOT_INSTALLED,
        FTB_RANKS_BRIDGE_OFF
    }

    /**
     * The parsed setting.
     *
     * @param setting the value, or null for an unknown value
     * @param value   the normalized value, or the trimmed text of an unknown value
     */
    record Choice(Setting setting, String value) {
        static final Choice AUTO = new Choice(Setting.AUTO, Setting.AUTO.value);

        /**
         * Matches case-insensitively after trimming. An absent key or a blank
         * value is {@code auto}; anything else that is not one of the
         * {@link Setting} values is unknown.
         */
        static Choice parse(String raw) {
            if (raw == null) return AUTO;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) return AUTO;
            String normalized = trimmed.toLowerCase(Locale.ROOT);
            for (Setting setting : Setting.values()) {
                if (setting.value.equals(normalized)) return setting == Setting.AUTO ? AUTO : new Choice(setting, setting.value);
            }
            return new Choice(null, trimmed);
        }

        boolean auto() {
            return setting == Setting.AUTO;
        }

        boolean unknown() {
            return setting == null;
        }

        String safeValue() {
            return EventTrailDiagnostics.sanitizeLogValue(value, MAX_SETTING_VALUE_LENGTH);
        }
    }

    /**
     * One server run's choice.
     *
     * @param source                   who decides player permissions
     * @param viaNeoForgeApi           the decision goes through NeoForge's permission API
     * @param handlerId                NeoForge's active permission handler, or null when unknown
     * @param ftbRanksInstalled        FTB Ranks is installed (whether or not it is used)
     * @param ftbBridgeOff             FTB Ranks would have been chosen, but its bridge did not resolve
     * @param luckPermsInstalled       LuckPerms is installed (whether or not it is used)
     * @param handlerNodesUnregistered another NeoForge handler would have been chosen, but EventTrail's
     *                                 nodes are not registered, so it cannot answer
     * @param choice                   the {@value #SETTING_KEY} setting that was applied
     * @param unavailable              why the source the setting named could not be used, or null
     */
    record Selection(
            Source source,
            boolean viaNeoForgeApi,
            String handlerId,
            boolean ftbRanksInstalled,
            boolean ftbBridgeOff,
            boolean luckPermsInstalled,
            boolean handlerNodesUnregistered,
            Choice choice,
            Unavailable unavailable) {

        /**
         * Whether a node the source has no value for falls back to the
         * operator check. Only LuckPerms keeps today's "unset is no".
         */
        boolean unsetFallsBackToOperator() {
            return source != Source.LUCKPERMS;
        }

        /** The setting named a source rather than {@code auto}. */
        boolean chosenBySetting() {
            return !choice.auto();
        }

        /** The one INFO line written at server start. */
        String startupLine() {
            String id = safeHandlerId(handlerId);
            StringBuilder line = new StringBuilder(STARTUP_PREFIX);
            switch (source) {
                case LUCKPERMS -> {
                    line.append("LuckPerms");
                    if (viaNeoForgeApi) line.append(" through NeoForge handler ").append(id);
                    line.append(" (unset nodes are denied");
                    if (!viaNeoForgeApi && isOtherHandler(handlerId) && !isLuckPermsHandler(handlerId)) {
                        line.append("; NeoForge handler ").append(id).append(" is not used");
                    }
                    line.append(')');
                }
                case NEOFORGE_HANDLER -> line.append("NeoForge handler ").append(id)
                        .append(" (unset nodes fall back to the operator check)");
                case FTB_RANKS -> line.append("FTB Ranks (unset nodes fall back to the operator check)");
                case OPERATOR -> {
                    line.append("operator check");
                    if (choice.auto() && ftbBridgeOff) {
                        line.append(" (FTB Ranks bridge switched off)");
                    } else if (choice.auto() && handlerNodesUnregistered) {
                        line.append(" (NeoForge handler ").append(id)
                                .append(" is not used: EventTrail's permission nodes are not registered)");
                    }
                }
            }
            appendChooser(line, id);
            if (luckPermsInstalled && source != Source.LUCKPERMS) {
                line.append("; LuckPerms is installed but not used by EventTrail");
            }
            if (isOtherHandler(handlerId) && !isLuckPermsHandler(handlerId) && source != Source.NEOFORGE_HANDLER
                    && source != Source.LUCKPERMS && !handlerNodesUnregistered) {
                line.append("; NeoForge handler ").append(id).append(" is not used");
            }
            if (choice.auto() && handlerId == null) {
                // auto treats an unreadable handler id as "no other handler"; say so (recheck-02 NIT-3).
                line.append(HANDLER_UNKNOWN_NOTE);
            }
            if (ftbRanksInstalled && source != Source.FTB_RANKS && !ftbBridgeOff) {
                line.append(FTB_NOT_USED_NOTE);
            }
            return line.toString();
        }

        /** The one startup WARN this selection needs, or null. */
        String warning() {
            String id = safeHandlerId(handlerId);
            if (choice.unknown()) {
                return "EventTrail: " + SETTING_KEY + " has an unknown value \"" + choice.safeValue()
                        + "\"; the allowed values are " + ALLOWED_VALUES + ". EventTrail uses the operator check"
                        + " and does not fall back to another permission mod. Fix the value and restart the server.";
            }
            if (unavailable != null) {
                return "EventTrail: " + SETTING_KEY + "=" + choice.value() + " chose "
                        + choice.setting().displayName + ", but " + reason(unavailable, id)
                        + ". EventTrail uses the operator check and does not fall back to another permission mod."
                        + " Install or fix that source, or change " + SETTING_KEY + " (" + ALLOWED_VALUES
                        + "), then restart the server.";
            }
            if (handlerNodesUnregistered) {
                return "EventTrail permission nodes are not registered with NeoForge this run, so NeoForge handler "
                        + id + " cannot answer EventTrail's checks; EventTrail uses the operator check until the next"
                        + " server start.";
            }
            return null;
        }

        private void appendChooser(StringBuilder line, String id) {
            if (choice.auto()) {
                line.append(", chosen by auto");
            } else if (choice.unknown()) {
                line.append(", because setting ").append(SETTING_KEY).append('=').append(choice.safeValue())
                        .append(" is not a known value");
            } else if (unavailable != null) {
                line.append(", because setting ").append(SETTING_KEY).append('=').append(choice.value())
                        .append(" chose ").append(choice.setting().displayName).append(" but ")
                        .append(reason(unavailable, id));
            } else {
                line.append(", chosen by setting ").append(SETTING_KEY).append('=').append(choice.value());
            }
        }
    }

    private static String reason(Unavailable unavailable, String id) {
        return switch (unavailable) {
            case LUCKPERMS_NOT_INSTALLED -> "LuckPerms is not installed";
            case HANDLER_IS_DEFAULT -> "NeoForge's permission handler is " + DEFAULT_HANDLER_ID
                    + ", with no permission mod behind it";
            case HANDLER_UNKNOWN -> "NeoForge's active permission handler is unknown";
            case HANDLER_NODES_NOT_REGISTERED -> "NeoForge handler " + id
                    + " cannot answer because EventTrail's permission nodes are not registered";
            case FTB_RANKS_NOT_INSTALLED -> "FTB Ranks is not installed";
            case FTB_RANKS_BRIDGE_OFF -> "the FTB Ranks bridge is switched off";
        };
    }

    private static String safeHandlerId(String handlerId) {
        return handlerId == null ? "unknown" : EventTrailDiagnostics.sanitizeLogValue(handlerId, MAX_HANDLER_ID_LENGTH);
    }

    /** {@link #select(Choice, boolean, String, boolean, boolean, boolean)} with the default {@code auto}. */
    static Selection select(
            boolean luckPermsPresent,
            String activeHandlerId,
            boolean nodesRegistered,
            boolean ftbRanksInstalled,
            boolean ftbBridgeReady) {
        return select(Choice.AUTO, luckPermsPresent, activeHandlerId, nodesRegistered, ftbRanksInstalled,
                ftbBridgeReady);
    }

    /**
     * Applies the setting.
     *
     * @param choice            the parsed {@value #SETTING_KEY} setting (null means auto)
     * @param luckPermsPresent  the LuckPerms API is installed
     * @param activeHandlerId   NeoForge's active permission handler id, or null when unknown
     * @param nodesRegistered   EventTrail's nodes were registered with NeoForge this run
     * @param ftbRanksInstalled FTB Ranks is installed
     * @param ftbBridgeReady    the FTB Ranks bridge resolved every class and method it uses
     */
    static Selection select(
            Choice choice,
            boolean luckPermsPresent,
            String activeHandlerId,
            boolean nodesRegistered,
            boolean ftbRanksInstalled,
            boolean ftbBridgeReady) {
        Choice applied = choice == null ? Choice.AUTO : choice;
        String handler = normalize(activeHandlerId);
        boolean luckPermsHandler = isLuckPermsHandler(handler);
        boolean luckPermsInstalled = luckPermsPresent || luckPermsHandler;
        boolean otherHandler = isOtherHandler(handler) && !luckPermsHandler;
        Facts facts = new Facts(handler, luckPermsHandler, luckPermsPresent, nodesRegistered, ftbRanksInstalled,
                applied);
        if (applied.unknown()) return facts.operator(false, false, null);
        return switch (applied.setting()) {
            case AUTO -> {
                if (luckPermsInstalled) yield facts.luckPerms();
                if (otherHandler) yield nodesRegistered ? facts.neoForgeHandler() : facts.operator(false, true, null);
                if (ftbRanksInstalled && ftbBridgeReady) yield facts.ftbRanks();
                yield facts.operator(ftbRanksInstalled, false, null);
            }
            case LUCKPERMS -> luckPermsInstalled
                    ? facts.luckPerms()
                    : facts.operator(false, false, Unavailable.LUCKPERMS_NOT_INSTALLED);
            case NEOFORGE -> {
                // The admin chose whatever NeoForge's permission system is set to use.
                if (luckPermsHandler) yield facts.luckPerms();
                if (otherHandler) {
                    yield nodesRegistered
                            ? facts.neoForgeHandler()
                            : facts.operator(false, true, Unavailable.HANDLER_NODES_NOT_REGISTERED);
                }
                yield facts.operator(false, false,
                        handler == null ? Unavailable.HANDLER_UNKNOWN : Unavailable.HANDLER_IS_DEFAULT);
            }
            case FTBRANKS -> {
                if (!ftbRanksInstalled) yield facts.operator(false, false, Unavailable.FTB_RANKS_NOT_INSTALLED);
                yield ftbBridgeReady
                        ? facts.ftbRanks()
                        : facts.operator(true, false, Unavailable.FTB_RANKS_BRIDGE_OFF);
            }
            case OPERATOR -> facts.operator(false, false, null);
        };
    }

    /** The inputs every selection records, so each branch builds its record the same way. */
    private record Facts(
            String handler,
            boolean luckPermsHandler,
            boolean luckPermsPresent,
            boolean nodesRegistered,
            boolean ftbRanksInstalled,
            Choice choice) {

        Selection luckPerms() {
            // Through the API only when LuckPerms itself answers it and EventTrail's
            // nodes are registered; otherwise LuckPerms is asked directly, as before.
            return new Selection(Source.LUCKPERMS, luckPermsHandler && nodesRegistered, handler, ftbRanksInstalled,
                    false, true, false, choice, null);
        }

        Selection neoForgeHandler() {
            return new Selection(Source.NEOFORGE_HANDLER, true, handler, ftbRanksInstalled, false,
                    luckPermsPresent, false, choice, null);
        }

        Selection ftbRanks() {
            return new Selection(Source.FTB_RANKS, false, handler, true, false, luckPermsPresent, false, choice,
                    null);
        }

        Selection operator(boolean ftbBridgeOff, boolean handlerNodesUnregistered, Unavailable unavailable) {
            return new Selection(Source.OPERATOR, false, handler, ftbRanksInstalled, ftbBridgeOff,
                    luckPermsPresent || luckPermsHandler, handlerNodesUnregistered, choice, unavailable);
        }
    }

    /** Whether FTB Ranks would be chosen if its bridge resolves, so its bridge is resolved only then. */
    static boolean wouldChooseFtbRanks(
            Choice choice, boolean luckPermsPresent, String activeHandlerId, boolean ftbRanksInstalled) {
        Choice applied = choice == null ? Choice.AUTO : choice;
        if (!ftbRanksInstalled || applied.unknown()) return false;
        String handler = normalize(activeHandlerId);
        return switch (applied.setting()) {
            case AUTO -> !luckPermsPresent && !isLuckPermsHandler(handler) && !isOtherHandler(handler);
            case FTBRANKS -> true;
            default -> false;
        };
    }

    static boolean isOtherHandler(String handlerId) {
        String handler = normalize(handlerId);
        return handler != null && !handler.equals(DEFAULT_HANDLER_ID);
    }

    static boolean isLuckPermsHandler(String handlerId) {
        String handler = normalize(handlerId);
        return handler != null && handler.startsWith(LUCKPERMS_NAMESPACE + ":");
    }

    private static String normalize(String handlerId) {
        if (handlerId == null) return null;
        String trimmed = handlerId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
