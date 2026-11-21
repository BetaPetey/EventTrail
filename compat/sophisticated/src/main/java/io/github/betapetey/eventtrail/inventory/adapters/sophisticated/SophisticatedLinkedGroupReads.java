package io.github.betapetey.eventtrail.inventory.adapters.sophisticated;

import io.github.betapetey.eventtrail.compat.AdapterFailSafe;
import io.github.betapetey.eventtrail.compat.AdapterKey;
import net.minecraft.server.level.ServerLevel;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageWrapper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sophisticated part 4 (guard design 01, 1.3 and 1.4): the group-id reads R1-R4 of one linked member, the identity
 * pins of the group's host and handlers, and the refusal reasons of the linked adapters.
 *
 * <ul>
 *   <li>R1 member endpoint: the member's public {@code getLinkedStorageEndpointData()} (a chest or barrel block
 *       entity's field; Storage in Motion's entity holder reads its item's component), giving (group id, endpoint
 *       id). For a block member the block wrapper's {@code getLinkedStorageEndpoint()} must be {@code Optional.of}
 *       an equal record, the wrapper must be exactly {@code StorageBlockEntity$1}, and its {@code linkedStorage}
 *       field must be the host.</li>
 *   <li>R2 menu: the menu's {@code getLinkedStorageGroupId()}.</li>
 *   <li>R3 contents: {@code host.getContentsUuid()}, where the host is the {@code storageWrapper} field of the menu's
 *       actual inventory handler.</li>
 *   <li>R4 saved data: {@code manager.isEndpointMember(R1 group, R1 endpoint)} and
 *       {@code virtualHosts.get(R1 group) == host} (the private map, {@code get} only), on the manager pinned at
 *       open ({@link #managerOf}).</li>
 *   <li>Pins (both member kinds): the handler is exactly {@code StorageWrapper$4}, its {@code storageWrapper} is the
 *       host, the host is exactly {@code StorageLinkedStorageHostWrapper} and its {@code inventoryHandler} field is
 *       that handler, the handler's private {@code persistent} is true (a non-persistent handler would skip the
 *       commit), and the host's {@code upgradeHandler} field is exactly {@code StorageWrapper$3}. The host and both
 *       handler identities are handed back so the binding can pin them at open.</li>
 * </ul>
 *
 * <p>Agreement: R1 group == R2 == R3, the R1 endpoint a member of that group, the host the manager's cached host for
 * it. Outcomes: AGREE; DISAGREE (a read of another shape, a disagreement or a failed pin: that open or session is
 * refused with one warning, {@value #UNPROVEN}); UNREADABLE (a member is missing: the key goes OFF with one warning
 * naming it); FAILED (anything else: reported, refused). Never a row under a guessed key.</p>
 *
 * <p>Nothing here creates, loads or resolves anything native: fields are read, never the lazy getters
 * ({@code getInventoryHandler()}, {@code getUpgradeHandler()}, a holder's {@code getStorageWrapper()}) and never the
 * manager's {@code resolveVirtualHost}, so a read never grows the host cache or builds a new handler. The one
 * exception is {@link #managerOf}, called once at open (see there). Server thread only.</p>
 */
public final class SophisticatedLinkedGroupReads {
    /** Any read disagreed, had another shape, or a pin failed. */
    public static final String UNPROVEN = "linked-group-unproven";
    /** Part 1's upgrade policy refused the group's upgrades (applied by the linked adapters). */
    public static final String UPGRADE_REFUSED = "linked-group-upgrade-refused";
    /** A linked double chest: not one of the recorded kinds. */
    public static final String DOUBLE_CHEST = "linked-double-chest";
    /** A linked limited barrel (block or Storage in Motion cart): its own menu class and per-cell limits. */
    public static final String LIMITED_BARREL = "linked-limited-barrel";
    /** A linked cart or boat is no longer at the block position it was opened at. */
    public static final String CART_MOVED = "linked-cart-moved";

    static final String CORE_LINKED = "net.p3pp3rf1y.sophisticatedcore.linkedstorage.";
    static final String ENDPOINT_DATA_CLASS = CORE_LINKED + "LinkedStorageEndpointData";
    static final String MANAGER_CLASS = CORE_LINKED + "LinkedStorageGroupManager";
    static final String SAVED_DATA_CLASS = CORE_LINKED + "LinkedStorageGroupsSavedData";
    static final String STORAGE_BLOCK = "net.p3pp3rf1y.sophisticatedstorage.block.";
    static final String HOST_CLASS = STORAGE_BLOCK + "StorageLinkedStorageHostWrapper";
    static final String HOST_HANDLER_CLASS = STORAGE_BLOCK + "StorageWrapper$4";
    static final String HOST_UPGRADES_CLASS = STORAGE_BLOCK + "StorageWrapper$3";
    static final String BLOCK_WRAPPER_CLASS = STORAGE_BLOCK + "StorageBlockEntity$1";
    static final String MEMBER_ENDPOINT = "getLinkedStorageEndpointData";
    static final String MENU_GROUP = "getLinkedStorageGroupId";

    /** What one read found. */
    public enum Outcome {
        AGREE,
        /** Another shape, a disagreement or a failed pin: refused with one warning; the key stays ON. */
        DISAGREE,
        /** A member is missing or could not be opened: the key goes OFF. */
        UNREADABLE,
        /** Any other failure: reported; refused. */
        FAILED
    }

    /**
     * One read. On AGREE: the group and endpoint ids, and the host, inventory handler and upgrade handler objects
     * (to pin). Otherwise {@code detail} names the read, pin or member, and {@code failure} is set for FAILED.
     */
    public record Reading(Outcome outcome, UUID groupId, UUID endpointId, Object host, Object inventoryHandler,
            Object upgradeHandler, String detail, Throwable failure) {
        public boolean agrees() {
            return outcome == Outcome.AGREE;
        }

        static Reading disagree(String detail) {
            return new Reading(Outcome.DISAGREE, null, null, null, null, null, detail, null);
        }

        static Reading unreadable(String lookedFor) {
            return new Reading(Outcome.UNREADABLE, null, null, null, null, null, lookedFor, null);
        }

        static Reading failed(String detail, Throwable failure) {
            return new Reading(Outcome.FAILED, null, null, null, null, null, detail, failure);
        }
    }

    /**
     * The values the agreement rule looks at. {@code blockWrapperRead} is false for a member with no block wrapper
     * (a cart); a null id means that read had no value of the declared shape.
     */
    public record Facts(UUID memberGroup, UUID memberEndpoint, boolean blockWrapperRead, boolean blockWrapperAgrees,
            UUID menuGroup, UUID hostGroup, boolean endpointMember, boolean hostCached) {
    }

    private static volatile Handles handles;

    private SophisticatedLinkedGroupReads() {
    }

    /** The agreement rule (pure): null when every read agrees, else the first read that does not. */
    public static String disagreement(Facts facts) {
        if (facts == null) return "no reads";
        if (facts.memberGroup() == null || facts.memberEndpoint() == null) return "R1 member endpoint";
        if (facts.blockWrapperRead() && !facts.blockWrapperAgrees()) return "R1 block wrapper endpoint";
        if (!facts.memberGroup().equals(facts.menuGroup())) return "R2 menu group";
        if (!facts.memberGroup().equals(facts.hostGroup())) return "R3 host contents";
        if (!facts.endpointMember()) return "R4 endpoint member";
        if (!facts.hostCached()) return "R4 cached host";
        return null;
    }

    /**
     * R1-R4 and the pins for one member. {@code blockWrapper} is the block member's storage wrapper (null for a
     * cart); {@code handler} is the menu's actual inventory handler; {@code manager} is the manager pinned at open.
     * Never throws for a non-VM failure.
     */
    public static Reading read(Object member, Object blockWrapper, Object menu, Object handler, Object manager) {
        try {
            Handles h = handles();
            if (handler == null || handler.getClass() != h.hostHandler) return Reading.disagree("pin: handler class");
            Object host = h.handlerWrapper.get(handler);
            if (host == null || host.getClass() != h.host) return Reading.disagree("pin: host class");
            if (h.wrapperInventory.get(host) != handler) return Reading.disagree("pin: host inventory handler");
            if (!h.handlerPersistent.getBoolean(handler)) return Reading.disagree("pin: handler not persistent");
            Object upgrades = h.wrapperUpgrades.get(host);
            if (upgrades == null || upgrades.getClass() != h.hostUpgrades) {
                return Reading.disagree("pin: host upgrade handler");
            }
            if (manager == null || manager.getClass() != h.manager) return Reading.disagree("pin: manager class");

            Object endpoint = noArg(member, MEMBER_ENDPOINT);
            if (!h.endpointData.isInstance(endpoint)) return Reading.disagree("R1 member endpoint");
            UUID group = uuid(h.endpointGroup.invoke(endpoint));
            UUID endpointId = uuid(h.endpointId.invoke(endpoint));
            boolean wrapperRead = blockWrapper != null;
            boolean wrapperAgrees = false;
            if (wrapperRead) {
                if (blockWrapper.getClass() != h.blockWrapper) return Reading.disagree("pin: block wrapper class");
                if (h.wrapperLinkedStorage.get(blockWrapper) != host) return Reading.disagree("pin: block wrapper host");
                Object own = h.wrapperEndpoint.invoke(blockWrapper);
                wrapperAgrees = own instanceof Optional<?> present && present.isPresent()
                        && endpoint.equals(present.get());
            }
            UUID menuGroup = optionalUuid(noArg(menu, MENU_GROUP));
            UUID hostGroup = optionalUuid(h.hostContents.invoke(host));
            boolean endpointMember = false;
            boolean hostCached = false;
            if (group != null && endpointId != null) {
                endpointMember = Boolean.TRUE.equals(h.isEndpointMember.invoke(manager, group, endpointId));
                if (!(h.virtualHosts.get(manager) instanceof Map<?, ?> hosts)) return Reading.disagree("R4 host cache");
                hostCached = hosts.get(group) == host;
            }
            String why = disagreement(new Facts(group, endpointId, wrapperRead, wrapperAgrees, menuGroup, hostGroup,
                    endpointMember, hostCached));
            if (why != null) return Reading.disagree(why);
            return new Reading(Outcome.AGREE, group, endpointId, host, handler, upgrades, "", null);
        } catch (NoSuchMethodException | NoSuchFieldException | ClassNotFoundException missing) {
            return Reading.unreadable(String.valueOf(missing.getMessage()));
        } catch (InvocationTargetException thrown) {
            Throwable cause = thrown.getCause() == null ? thrown : thrown.getCause();
            return Reading.failed("a read threw", cause);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Reading.failed("a read failed", failure);
        }
    }

    /**
     * Applies the rule to one read: true (refuse the open, the action or the rest of the session) unless it agreed.
     * DISAGREE writes the one warning per server start and counts ({@value #UNPROVEN}); UNREADABLE switches the key
     * OFF naming the member; FAILED is reported. Never throws.
     */
    public static boolean refused(AdapterKey key, String site, Reading reading) {
        if (reading != null && reading.agrees()) return false;
        if (reading == null || reading.outcome() == Outcome.DISAGREE) {
            AdapterFailSafe.refusedOnce(key, site, UNPROVEN);
        } else if (reading.outcome() == Outcome.UNREADABLE) {
            AdapterFailSafe.conceptNotFound(key, site, reading.detail());
        } else {
            AdapterFailSafe.report(key, site, reading.failure());
        }
        return true;
    }

    /**
     * The group manager of the server, {@code LinkedStorageGroupsSavedData.get(level).manager()}, or null when the
     * answer has another shape. Call once at open, on the server thread, after the member's own read says it is
     * linked, and pin the result. {@code get} is Core's {@code computeIfAbsent} on the Overworld's saved data: while a
     * linked member's menu is open the data is already loaded (its host was resolved through it), so nothing is made.
     * Throws what the lookup or the call throws.
     */
    public static Object managerOf(ServerLevel level) throws ReflectiveOperationException {
        Handles h = handles();
        Object data = h.savedDataGet.invoke(null, level);
        if (!h.savedData.isInstance(data)) return null;
        Object manager = h.savedDataManager.invoke(data);
        return h.manager.isInstance(manager) ? manager : null;
    }

    /** The host's {@code inventoryHandler} field (never the lazy getter), for the binding's identity re-checks. */
    public static Object inventoryHandlerOf(Object host) throws ReflectiveOperationException {
        Handles h = handles();
        return host != null && host.getClass() == h.host ? h.wrapperInventory.get(host) : null;
    }

    /** The host's {@code upgradeHandler} field (never the lazy getter), for the binding's identity re-checks. */
    public static Object upgradeHandlerOf(Object host) throws ReflectiveOperationException {
        Handles h = handles();
        return host != null && host.getClass() == h.host ? h.wrapperUpgrades.get(host) : null;
    }

    /** The host's cached entry for {@code groupId} in {@code manager}'s private map ({@code get} only). */
    public static Object cachedHost(Object manager, UUID groupId) throws ReflectiveOperationException {
        Handles h = handles();
        if (manager == null || manager.getClass() != h.manager || groupId == null) return null;
        return h.virtualHosts.get(manager) instanceof Map<?, ?> hosts ? hosts.get(groupId) : null;
    }

    static Handles handles() throws ReflectiveOperationException {
        Handles current = handles;
        if (current != null) return current;
        current = Handles.resolve();
        handles = current;
        return current;
    }

    private static Object noArg(Object target, String name) throws ReflectiveOperationException {
        if (target == null) return null;
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static UUID uuid(Object value) {
        return value instanceof UUID id ? id : null;
    }

    private static UUID optionalUuid(Object value) {
        return value instanceof Optional<?> present && present.isPresent() && present.get() instanceof UUID id
                ? id : null;
    }

    /** The members every read uses, looked up once per JVM (a failed lookup is retried, never cached). */
    record Handles(Class<?> endpointData, Method endpointGroup, Method endpointId, Class<?> manager,
            Method isEndpointMember, Field virtualHosts, Class<?> savedData, Method savedDataGet,
            Method savedDataManager, Class<?> host, Method hostContents, Class<?> hostHandler, Class<?> hostUpgrades,
            Class<?> blockWrapper, Field wrapperInventory, Field wrapperUpgrades, Field wrapperLinkedStorage,
            Method wrapperEndpoint, Field handlerWrapper, Field handlerPersistent) {
        static Handles resolve() throws ReflectiveOperationException {
            ClassLoader core = StorageContainerMenuBase.class.getClassLoader();
            ClassLoader storage = StorageBlockEntity.class.getClassLoader();
            Class<?> endpointData = Class.forName(ENDPOINT_DATA_CLASS, false, core);
            Class<?> manager = Class.forName(MANAGER_CLASS, false, core);
            Class<?> savedData = Class.forName(SAVED_DATA_CLASS, false, core);
            Class<?> host = Class.forName(HOST_CLASS, false, storage);
            return new Handles(endpointData, endpointData.getMethod("groupId"), endpointData.getMethod("endpointId"),
                    manager, manager.getMethod("isEndpointMember", UUID.class, UUID.class),
                    field(manager, "virtualHosts"), savedData, savedData.getMethod("get", ServerLevel.class),
                    savedData.getMethod("manager"), host, host.getMethod("getContentsUuid"),
                    Class.forName(HOST_HANDLER_CLASS, false, storage), Class.forName(HOST_UPGRADES_CLASS, false, storage),
                    Class.forName(BLOCK_WRAPPER_CLASS, false, storage), field(StorageWrapper.class, "inventoryHandler"),
                    field(StorageWrapper.class, "upgradeHandler"), field(StorageWrapper.class, "linkedStorage"),
                    StorageWrapper.class.getMethod("getLinkedStorageEndpoint"),
                    field(InventoryHandler.class, "storageWrapper"), field(InventoryHandler.class, "persistent"));
        }

        /** A declared field the reads can open; one they cannot open counts as missing (as F1-4). */
        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
            Field field = owner.getDeclaredField(name);
            if (!field.trySetAccessible()) throw new NoSuchFieldException(owner.getName() + "." + name + " (not open)");
            return field;
        }
    }
}
