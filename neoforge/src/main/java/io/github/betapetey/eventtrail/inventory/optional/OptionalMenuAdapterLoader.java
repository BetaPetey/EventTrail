package io.github.betapetey.eventtrail.inventory.optional;

import io.github.betapetey.eventtrail.EventTrailIds;
import io.github.betapetey.eventtrail.adapters.AdapterFailSafe;
import io.github.betapetey.eventtrail.adapters.AdapterKey;
import io.github.betapetey.eventtrail.adapters.AdapterKeyPresence;
import io.github.betapetey.eventtrail.adapters.FailureClassifier;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility.ContractState;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility.IntegrationState;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility.RuntimeDisposition;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility.Snapshot;
import io.github.betapetey.eventtrail.inventory.optional.OptionalIntegrationCompatibility.WarningReason;
import io.github.betapetey.eventtrail.inventory.spi.AdapterMatch;
import io.github.betapetey.eventtrail.inventory.spi.GuardedMenuAdapter;
import io.github.betapetey.eventtrail.inventory.spi.MenuAdapterContext;
import io.github.betapetey.eventtrail.inventory.spi.MenuTransactionAdapter;
import io.github.betapetey.eventtrail.inventory.spi.ObservationStrategy;
import io.github.betapetey.eventtrail.util.EventTrailDiagnostics;
import net.neoforged.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Loads the optional-mod adapters of the installed mods (presence by mod id) without linking their classes when
 * absent. Installed versions are read for display only: the loader compares no version and builds the adapter of
 * every installed mod; a member found missing switches that adapter off until restart. Since build B F1-4 no
 * adapter's own constructor refuses an untested version or artifact: every adapter is built on any installed
 * version, and its own presence and shape checks decide what it records (a constructor that still throws is
 * ADAPTER_UNAVAILABLE with a blocker).
 */
public final class OptionalMenuAdapterLoader {
    private static final Logger LOGGER = LogManager.getLogger(EventTrailIds.MOD_ID);
    static final String UNREADABLE_VERSION = "<unreadable>";
    private static final String CONTAINER_FAMILY = "CONTAINER_TRANSACTION_FAMILY";
    private static final String BRIDGE_FAMILY = "OPTIONAL_MOD_BRIDGE";
    private static final String LOCATION_FAMILY = "OPTIONAL_LOCATION_PROVIDER";
    private static final String PRESENCE_CONTRACT = "used while the mod is installed (no version check)";
    /** The id suffix of a presence-chosen adapter whose behaviour path is OFF (Sophisticated, build B R1d). */
    static final String OFF_PATH_ID_SUFFIX = "-off";
    /** Fail-safe site of a member found missing while an adapter is built at server start. */
    static final String CONSTRUCTION_SITE = "adapter construction";
    /** Fail-safe site of a match failure the registry reports for an exact adapter it holds unwrapped. */
    static final String MATCH_FAILURE_SITE = "menu match";
    // Retained Lootr .124/.126/.127 storage/provider contracts, not instrumentation.
    // This is denial/ownership evidence only; it switches no adapter on.
    private static final Set<String> LOOTR_STORAGE_CONTRACTS = Set.of(
            "noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory",
            "noobanidus.mods.lootr.common.api.data.ILootrInfoProvider");
    /**
     * GF-ADAPTERSPECS: the mods whose ownership rule is a special case ({@link #ownRecognition} and
     * {@link OffAdapterBlocker#match}) rather than their specification's base names alone. Each keeps one
     * specification ({@link SpecTable} refuses a sibling), so their mod-id special cases stay that specification's.
     */
    private static final Set<String> SPECIAL_RECOGNITION = Set.of(
            "lootr", "enderstorage", "pocketstorage", "integratedterminals", "occultism", "rftoolsstorage", "create",
            "sophisticatedstoragecreateintegration", "sophisticatedstorageinmotion", "sophisticatedstorage");
    /** The claim predicate of the only specification of a mod: no sibling claims any menu. */
    private static final Predicate<MenuAdapterContext> NO_SIBLING = context -> false;
    /** GF-ADAPTERSPECS r2: the yield check of the only specification of a mod: no sibling yields any menu to it. */
    private static final Function<MenuAdapterContext, AdapterKey> NO_YIELD = context -> null;
    private static final List<AdapterSpec> SPECS = List.of(
            new AdapterSpec(
                    AdapterKey.LOOTR,
                    "lootr",
                    "Lootr",
                    "1.21.1-1.11.38.124",
                    Map.of(),
                    "Exact Lootr 1.21.1-1.11.38.124, .126 or .127 personal block inventories and manual menu custody; .126/.127 require team loot disabled",
                    Set.of("noobanidus.mods.lootr.common.data.LootrInventory", "noobanidus.mods.lootr.common.api.data.inventory.ILootrInventory"),
                    false,
                    "lootr-",
                    "io.github.betapetey.eventtrail.inventory.adapters.lootr.LootrMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.SOPHISTICATED_BACKPACKS,
                    "sophisticatedbackpacks",
                    "Sophisticated Backpacks",
                    "3.25.78",
                    Map.of("sophisticatedcore", "1.4.90"),
                    "Sophisticated Backpacks 3.25.78.2107/Core 1.4.90.2299; exact 3.26.3.2158/Core 1.5.1.2341 direct unlinked item menus only",
                    Set.of("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer"),
                    false,
                    "sophisticatedbackpacks-",
                    "io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedBackpacksMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.SOPHISTICATED_STORAGE,
                    "sophisticatedstorage",
                    "Sophisticated Storage",
                    "1.5.85",
                    Map.of("sophisticatedcore", "1.4.86"),
                    "Sophisticated Storage/Core exact pairs 1.5.85/1.4.86, 1.5.91/1.4.90, 1.5.91/1.5.1; exact 1.6.8.2196/1.5.17.2426 direct unlinked upgrade-free stationary storage",
                    Set.of("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase"),
                    true,
                    "sophisticated-",
                    "io.github.betapetey.eventtrail.inventory.adapters.sophisticated.SophisticatedStorageMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.CREATE,
                    "create", "Create mounted storage", "6.0.10", Map.of(),
                    "Exact Create 6.0.10 native mounted menu/member custody", Set.of(), false,
                    "create-mounted-", "io.github.betapetey.eventtrail.inventory.adapters.create.CreateMountedMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.ENDER_STORAGE,
                    "enderstorage", "Ender Storage", "2.13.0.191", Map.of("codechickenlib", "4.6.1.529"),
                    "Exact Ender Storage 2.13.0.191 with CodeChickenLib 4.6.1.529 shared item custody",
                    Set.of("codechicken.enderstorage.container.ContainerEnderItemStorage"), false,
                    "enderstorage-", "io.github.betapetey.eventtrail.inventory.adapters.enderstorage.EnderStorageMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.INTEGRATED_TERMINALS,
                    "integratedterminals", "Integrated Terminals", "1.8.0",
                    Map.of("integrateddynamics", "1.35.0", "integratedtunnels", "1.10.0",
                            "commoncapabilities", "2.11.5", "cyclopscore", "1.29.3"),
                    "Exact Terminals/Dynamics/Tunnels/CC/Cyclops tuples 1.8.0/1.35.0/1.10.0/2.11.5/1.29.3 or 1.10.0/1.37.1/1.12.0/2.11.6/1.30.0; finite item operations only",
                    Set.of("org.cyclops.integratedterminals.inventory.container.ContainerTerminalStorageBase"), false,
                    "integratedterminals-", "io.github.betapetey.eventtrail.inventory.adapters.integrated.IntegratedTerminalsMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.OCCULTISM,
                    "occultism", "Occultism dimensional storage", "1.224.3",
                    Map.of("modonomicon", "1.120.4", "curios", "9.5.1+1.21.1", "geckolib", "4.9.2", "smartbrainlib", "1.16.11"),
                    "Exact Occultism 1.224.3/GeckoLib 4.9.2 or 1.224.4/GeckoLib 4.9.3 item map operations and retained dependencies",
                    Set.of("com.klikli_dev.occultism.common.container.storage.StorageControllerContainerBase"), false,
                    "occultism-", "io.github.betapetey.eventtrail.inventory.adapters.occultism.OccultismStorageMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.RFTOOLS_STORAGE,
                    "rftoolsstorage", "RFTools Storage scanner", "1.21-6.0.5",
                    Map.of("rftoolsbase", "1.21-6.0.11", "mcjtylib", "1.21-9.0.21"),
                    "Exact RFTools Storage 6.0.5 placed scanner existing-item delivery",
                    Set.of("mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerContainer"), false,
                    "rftoolsstorage-", "io.github.betapetey.eventtrail.inventory.adapters.rftools.RftoolsStorageMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.POCKET_STORAGE,
                    "pocketstorage", "Pocket Storage", "1.2.5", Map.of(),
                    "Exact Pocket Storage 1.2.5+1.21.1-b3 item-backed committed operations",
                    Set.of("com.flanks255.pocketstorage.gui.PSUContainer"), false,
                    "pocketstorage-", "io.github.betapetey.eventtrail.inventory.adapters.pocketstorage.PocketStorageMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.SOPHISTICATED_STORAGE_CREATE,
                    "sophisticatedstoragecreateintegration", "Sophisticated Storage Create Integration", "0.1.21",
                    Map.of("create", "6.0.10", "sophisticatedstorage", "1.5.91", "sophisticatedcore", "1.4.90"),
                    "Exact mounted 0.1.21.209 + Create 6.0.10 + Storage 1.5.91.2127/Core 1.4.90.2299, or Core 1.5.1.2341 single unlinked upgrade-free Iron Chest; exact 0.1.23.246/Storage 1.6.8.2196/Core 1.5.17.2426 single local unlinked upgrade-free Iron Chest on a native bearing",
                    Set.of("net.p3pp3rf1y.sophisticatedcore.compat.create.MountedStorageContainerMenuBase"), false,
                    "sophisticatedcreate-", "io.github.betapetey.eventtrail.inventory.adapters.create.SophisticatedCreateMountedMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.STORAGE_IN_MOTION,
                    "sophisticatedstorageinmotion", "Sophisticated Storage in Motion", "0.10.34",
                    Map.of("sophisticatedstorage", "1.5.91", "sophisticatedcore", "1.4.90"),
                    "Motion 0.10.34.358 (Storage 1.5.91/Core 1.4.90, 1.5.1) or 0.10.38.395 (Storage 1.6.15-16/Core 1.5.25-32); Core 1.5.1 and 0.10.38: unlinked plain iron chest",
                    Set.of("net.p3pp3rf1y.sophisticatedstorageinmotion.common.gui.MovingStorageContainerMenu"), false,
                    "storagemotion-", "io.github.betapetey.eventtrail.inventory.adapters.storagemotion.StorageInMotionMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.MEKANISM,
                    "mekanism",
                    "Mekanism",
                    "10.7.19",
                    Map.of(),
                    "Mekanism 10.7.19",
                    Set.of("mekanism.common.inventory.container.MekanismContainer"),
                    true,
                    "mekanism-",
                    "io.github.betapetey.eventtrail.inventory.adapters.mekanism.MekanismMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.AE2,
                    "ae2",
                    "Applied Energistics 2",
                    "19.2.17",
                    Map.of(),
                    "Applied Energistics 2 19.2.17 committed item operations",
                    Set.of("appeng.menu.me.common.MEStorageMenu"),
                    false,
                    "ae2-",
                    "io.github.betapetey.eventtrail.inventory.adapters.ae2.Ae2MenuAdapter"),
            new AdapterSpec(
                    AdapterKey.AE2WTLIB,
                    "ae2wtlib",
                    "AE2WTLib",
                    "19.5.1",
                    Map.of("ae2", "19.2.17"),
                    "AE2WTLib 19.5.1 wireless crafting operations with AE2 19.2.17",
                    Set.of("de.mari_023.ae2wtlib.wct.WCTMenu"),
                    false,
                    "ae2wtlib-",
                    "io.github.betapetey.eventtrail.inventory.adapters.ae2.Ae2WtlibMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.REFINED_STORAGE,
                    "refinedstorage",
                    "Refined Storage",
                    "2.0.9",
                    Map.of(),
                    "Refined Storage 2.0.9 committed item operations",
                    Set.of("com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu"),
                    false,
                    "refinedstorage-",
                    "io.github.betapetey.eventtrail.inventory.adapters.refinedstorage.RefinedStorageMenuAdapter"),
            new AdapterSpec(
                    AdapterKey.METAL_BARRELS,
                    "metalbarrels",
                    "Metal Barrels",
                    "7",
                    Map.of(),
                    "Metal Barrels placed barrels (8 blocks, 6 sizes): exact MetalBarrelMenu over the barrel's own handler, re-proven at every action",
                    Set.of("tfar.metalbarrels.menu.MetalBarrelMenu"),
                    false,
                    "metalbarrels-",
                    "io.github.betapetey.eventtrail.inventory.adapters.metalbarrels.MetalBarrelsMenuAdapter"));
    /** GF-ADAPTERSPECS: the specifications above, checked at class load (own keys, exact non-overlapping ids). */
    private static final SpecTable PRODUCTION = SpecTable.production(SPECS);
    private static volatile Snapshot currentSnapshot = PRODUCTION.absentSnapshot();
    /**
     * GF-WARNINGS (B49): keys whose built adapter found no supported behaviour path (its id ends with
     * {@link #OFF_PATH_ID_SUFFIX}). A display fact for the status command only; never read by the capture gate.
     */
    private static volatile Set<AdapterKey> offPathKeys = Set.of();

    private OptionalMenuAdapterLoader() {
    }

    public static LoadResult loadInstalled() {
        return load(installedVersions(), OptionalMenuAdapterLoader::instantiate);
    }

    /**
     * Publishes a completed startup decision and emits its process-bounded warnings. A member found
     * missing while an adapter was built switches that key OFF in the fail-safe, which writes the one
     * warning naming the member, so the compatibility warning of that key is not written twice.
     */
    public static void activate(LoadResult result) {
        Objects.requireNonNull(result, "result");
        Set<AdapterKey> offPath = offPathKeys(result.adapters());
        // GF-WARNINGS r3 (review-01 NIT-6): both published facts change under the lock markBuiltOnRetry holds, so its
        // read-modify-write of offPathKeys cannot interleave with a new load's publish and keep a stale set.
        synchronized (OptionalMenuAdapterLoader.class) {
            currentSnapshot = result.compatibility();
            offPathKeys = offPath;
        }
        result.missingAtConstruction().forEach((key, failure) ->
                AdapterFailSafe.report(key, CONSTRUCTION_SITE, failure));
        OptionalIntegrationCompatibility.emitProcessWarnings(
                withoutKeys(result.compatibility(), result.missingAtConstruction().keySet()));
    }

    /**
     * Read-only current state of all adapter keys (the 16 menu keys and the bridge and location keys):
     * the published startup decision with every key the fail-safe switched OFF since server start merged
     * in as its OFF state. Obtaining it never emits warnings.
     */
    public static Snapshot compatibilitySnapshot() {
        return withFailSafeState(currentSnapshot, AdapterFailSafe.offStates());
    }

    /**
     * True once a selected exact adapter has failed and is no longer safe to invoke, or its key is OFF
     * in the fail-safe. The same answer as {@link #compatibilitySnapshot()} gives, without building the
     * merged copy on this per-selection path.
     */
    public static boolean isRuntimeAdapterDisabled(String adapterId) {
        return PRODUCTION.isRuntimeAdapterDisabledNow(currentSnapshot, adapterId);
    }

    /** GF-WARNINGS (B49): whether {@code key}'s built adapter found no supported behaviour path (display only). */
    static boolean offPath(AdapterKey key) {
        return key != null && offPathKeys.contains(key);
    }

    /** GF-WARNINGS (B49): the keys of the off-path adapters in a load result; an id that cannot be read is skipped. */
    static Set<AdapterKey> offPathKeys(List<MenuTransactionAdapter> adapters) {
        if (adapters == null || adapters.isEmpty()) return Set.of();
        EnumSet<AdapterKey> keys = EnumSet.noneOf(AdapterKey.class);
        for (MenuTransactionAdapter adapter : adapters) {
            try {
                String adapterId = adapter == null ? null : adapter.adapterId();
                AdapterKey key = adapterId != null && adapterId.endsWith(OFF_PATH_ID_SUFFIX)
                        ? PRODUCTION.keyForAdapter(adapterId) : null;
                if (key != null) keys.add(key);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // A display fact; an unreadable id leaves the status line as the published entry.
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    /**
     * {@code base} with the OFF state of every key in {@code off}: that key's entry is replaced (or
     * appended when the base has none); every other entry is unchanged.
     */
    static Snapshot withFailSafeState(Snapshot base, Map<AdapterKey, AdapterFailSafe.OffState> off) {
        Objects.requireNonNull(base, "base");
        if (off == null || off.isEmpty()) return base;
        Snapshot merged = base;
        for (AdapterFailSafe.OffState state : off.values()) {
            merged = merged.replace(offState(merged.find(state.key()), state));
        }
        return merged;
    }

    private static IntegrationState offState(IntegrationState current, AdapterFailSafe.OffState off) {
        AdapterKey key = off.key();
        return new IntegrationState(
                key,
                key.modId(),
                current == null ? key.displayName() : current.displayName(),
                current == null ? "" : current.detectedVersion(),
                current == null ? familyOf(key) : current.family(),
                current == null ? PRESENCE_CONTRACT : current.validatedContract(),
                ContractState.INCOMPATIBLE,
                RuntimeDisposition.DISABLED,
                off.reason());
    }

    private static Snapshot withoutKeys(Snapshot snapshot, Set<AdapterKey> keys) {
        if (keys.isEmpty()) return snapshot;
        return new Snapshot(snapshot.integrations().stream()
                .filter(state -> state.key() == null || !keys.contains(state.key()))
                .toList());
    }

    /**
     * Classifies the failed adapter's own hierarchy without invoking the broken
     * optional implementation, so its menus remain fail-closed. GF-ADAPTERSPECS: by the
     * disabled adapter's own specification (exact id mapping), never by its mod id, and
     * never for a menu the shared claim predicate gives to a sibling of the same mod.
     */
    public static boolean runtimeDisabledAdapterOwnsContext(
            String adapterId, MenuAdapterContext context) {
        return PRODUCTION.runtimeDisabledAdapterOwnsContext(currentSnapshot, adapterId, context);
    }

    /**
     * The menus one specification's adapter owns by its own recognition alone (the rule below was the whole
     * ownership rule before GF-ADAPTERSPECS, and still is for the only specification of a mod). The special cases
     * belong to mods with one specification ({@link SpecTable} refuses siblings for them).
     */
    private static boolean ownRecognition(AdapterSpec spec, MenuAdapterContext context) {
        if (context == null || context.menu() == null) return false;
        if (spec != null && "lootr".equals(spec.modId())) return isLootrMenu(context);
        if (spec != null && "enderstorage".equals(spec.modId())) return isEnderStorageMenu(context.menu().getClass());
        if (spec != null && "pocketstorage".equals(spec.modId())) return isPocketStorageMenu(context.menu().getClass());
        if (spec != null && "integratedterminals".equals(spec.modId())) return isIntegratedStorageMenu(context.menu().getClass());
        if (spec != null && "occultism".equals(spec.modId())) return isOccultismStorageMenu(context.menu().getClass());
        if (spec != null && "rftoolsstorage".equals(spec.modId())) return isRftoolsScannerMenu(context.menu().getClass());
        if (spec != null && "create".equals(spec.modId())) return isCreateMountedMenu(context);
        if (spec != null && "sophisticatedstoragecreateintegration".equals(spec.modId())) return isSophisticatedCreateMountedMenu(context.menu().getClass());
        if (spec != null && "sophisticatedstorageinmotion".equals(spec.modId())) return isStorageInMotionMenu(context.menu().getClass());
        if (spec != null && "sophisticatedstorage".equals(spec.modId())
                && isNonStationarySophisticatedMenu(context.menu().getClass())) return false;
        return spec != null && OffAdapterBlocker.belongsToSpecializedHierarchy(
                context.menu().getClass(), spec.specializedMenuBaseNames());
    }

    /** Disjoint ownership of two optional integrations sharing the same Core superclass. */
    public static boolean isSophisticatedBackpackMenu(Class<?> type) {
        return OffAdapterBlocker.belongsToSpecializedHierarchy(type,
                Set.of("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer"));
    }

    public static boolean isSophisticatedCreateMountedMenu(Class<?> type) {
        return namedHierarchy(type, "net.p3pp3rf1y.sophisticatedstoragecreateintegration.")
                || namedHierarchy(type, "net.p3pp3rf1y.sophisticatedcore.compat.create.Mounted")
                || OffAdapterBlocker.belongsToSpecializedHierarchy(type,
                Set.of("net.p3pp3rf1y.sophisticatedcore.compat.create.MountedStorageContainerMenuBase"));
    }

    public static boolean isStorageInMotionMenu(Class<?> type) {
        return namedHierarchy(type, "net.p3pp3rf1y.sophisticatedstorageinmotion.")
                || OffAdapterBlocker.belongsToSpecializedHierarchy(type,
                Set.of("net.p3pp3rf1y.sophisticatedstorageinmotion.common.gui.MovingStorageContainerMenu"));
    }

    public static boolean isNonStationarySophisticatedMenu(Class<?> type) {
        return isSophisticatedBackpackMenu(type) || isSophisticatedCreateMountedMenu(type) || isStorageInMotionMenu(type);
    }

    /** Namespace/base recognition only denies fallback; it never switches an adapter on. */
    public static boolean isEnderStorageMenu(Class<?> type) {
        return namedHierarchy(type, "codechicken.enderstorage.container.")
                || OffAdapterBlocker.belongsToSpecializedHierarchy(type,
                        Set.of("codechicken.enderstorage.container.ContainerEnderItemStorage"));
    }

    public static boolean isPocketStorageMenu(Class<?> type) {
        return namedHierarchy(type, "com.flanks255.pocketstorage.gui.")
                || OffAdapterBlocker.belongsToSpecializedHierarchy(type,
                        Set.of("com.flanks255.pocketstorage.gui.PSUContainer"));
    }

    public static boolean isIntegratedStorageMenu(Class<?> type) {
        return namedHierarchy(type, "org.cyclops.integratedterminals.inventory.container.")
                || OffAdapterBlocker.belongsToSpecializedHierarchy(type,
                        Set.of("org.cyclops.integratedterminals.inventory.container.ContainerTerminalStorageBase"));
    }

    public static boolean isOccultismStorageMenu(Class<?> type) {
        return namedHierarchy(type, "com.klikli_dev.occultism.common.container.storage.")
                || OffAdapterBlocker.belongsToSpecializedHierarchy(type,
                        Set.of("com.klikli_dev.occultism.common.container.storage.StorageControllerContainerBase"));
    }

    public static boolean isRftoolsScannerMenu(Class<?> type) {
        return namedHierarchy(type, "mcjty.rftoolsstorage.modules.scanner.blocks.")
                || OffAdapterBlocker.belongsToSpecializedHierarchy(type,
                        Set.of("mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerContainer"));
    }

    private static boolean namedHierarchy(Class<?> type, String prefix) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().startsWith(prefix)) return true;
        }
        return false;
    }

    /** Namespace recognition denies unsafe fallback; it does not admit Create or a binary version. */
    public static boolean isCreateMountedMenu(MenuAdapterContext context) {
        if (context == null || context.menu() == null
                || context.menu().slots.size() > io.github.betapetey.eventtrail.inventory.core.MenuInventoryLimits.MAX_MENU_DESCRIPTORS) return false;
        for (var slot : context.menu().slots) {
            if (slot != null && slot.container != null && OffAdapterBlocker.belongsToSpecializedHierarchy(
                    slot.container.getClass(), Set.of("com.simibubi.create.api.contraption.storage.item.menu.StorageInteractionWrapper"))) return true;
        }
        return false;
    }

    /**
     * Lootr deliberately uses vanilla ChestMenu. Identify its owned slot backing,
     * not just the menu class, without linking any optional Lootr classes.
     * Concrete Lootr storage hierarchies and verified semantic contracts deny
     * unsafe fallback; arbitrary injected interface namespaces do not transfer
     * ownership. Recognition never admits an optional version.
     */
    public static boolean isLootrMenu(MenuAdapterContext context) {
        if (context == null || context.menu() == null) return false;
        if (isLootrType(context.menu().getClass())) return true;
        for (var slot : context.menu().slots) {
            if (slot != null && (isLootrType(slot.getClass())
                    || (slot.container != null && isLootrType(slot.container.getClass())))) return true;
        }
        return false;
    }

    private static boolean isLootrType(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (!current.isInterface()
                    && current.getName().startsWith("noobanidus.mods.lootr.")) return true;
            for (Class<?> contract : current.getInterfaces()) {
                if (isLootrStorageContract(contract)) return true;
            }
        }
        return false;
    }

    private static boolean isLootrStorageContract(Class<?> contract) {
        if (LOOTR_STORAGE_CONTRACTS.contains(contract.getName())) return true;
        for (Class<?> parent : contract.getInterfaces()) {
            if (isLootrStorageContract(parent)) return true;
        }
        return false;
    }

    /**
     * Converts a lazy exact-adapter failure into one process-bounded warning.
     * Adapters already off at startup were reported when that decision was activated.
     */
    public static synchronized boolean reportRuntimeAdapterFailure(String adapterId) {
        Snapshot previous = currentSnapshot;
        Snapshot failed = runtimeFailureSnapshot(previous, adapterId);
        if (failed == null) return false;
        currentSnapshot = failed;
        List<IntegrationState> changed = failed.integrations().stream()
                .filter(state -> !previous.integrations().contains(state))
                .toList();
        OptionalIntegrationCompatibility.emitProcessWarnings(new Snapshot(changed));
        return true;
    }

    /** Pure transition used by the runtime reporter and deterministic compatibility tests. */
    static IntegrationState runtimeFailureState(Snapshot snapshot, String adapterId) {
        Snapshot failed = runtimeFailureSnapshot(snapshot, adapterId);
        if (failed == null) return null;
        AdapterSpec spec = PRODUCTION.specFor(adapterId);
        return spec == null ? null : failed.find(spec.key());
    }

    /**
     * Disables the failed exact adapter only. There is no cascade (adapter fail-safe B: switch THAT
     * adapter off): an adapter that depends on the failed one keeps its state and goes off only when it
     * meets a failure itself.
     */
    static Snapshot runtimeFailureSnapshot(Snapshot snapshot, String adapterId) {
        return PRODUCTION.runtimeFailureSnapshot(snapshot, adapterId);
    }

    static boolean isRuntimeAdapterDisabled(Snapshot snapshot, String adapterId) {
        return PRODUCTION.isRuntimeAdapterDisabled(snapshot, adapterId);
    }

    static LoadResult load(
            Map<String, String> installedVersions,
            AdapterFactory factory) {
        return load(installedVersions, factory, AdapterKeyPresence.FML);
    }

    /** {@link #load(Map, AdapterFactory)} with the mod files a package-marker key is checked against. */
    static LoadResult load(
            Map<String, String> installedVersions,
            AdapterFactory factory,
            AdapterKeyPresence.ModFiles modFiles) {
        return PRODUCTION.load(installedVersions, factory, modFiles);
    }

    /** The startup decision over {@code table}'s specifications (production: {@link #PRODUCTION}). */
    private static LoadResult load(
            SpecTable table,
            Map<String, String> installedVersions,
            AdapterFactory factory,
            AdapterKeyPresence.ModFiles modFiles) {
        Objects.requireNonNull(installedVersions, "installedVersions");
        Objects.requireNonNull(factory, "factory");
        List<MenuTransactionAdapter> adapters = new ArrayList<>();
        List<IntegrationState> compatibility = new ArrayList<>(AdapterKey.values().length);
        Map<AdapterKey, Throwable> missingAtConstruction = new EnumMap<>(AdapterKey.class);

        for (AdapterSpec spec : table.specs()) {
            String version = installedVersions.get(spec.modId());
            if (version == null) {
                compatibility.add(absent(spec));
                continue;
            }

            // Presence by mod id: an installed mod's adapter is built whatever its version or the versions of
            // the mods around it; the version is kept for display only. A blocker is registered only for an
            // adapter that is off (below). GF-ADAPTERSPECS: every specification of the mod is built, each under
            // its own key, recognizer and claim predicate.
            try {
                MenuTransactionAdapter adapter = buildExact(factory, spec);
                // R2-menu: every call on the adapter and every callback it hands back goes through the fail-safe.
                GuardedMenuAdapter guarded = GuardedMenuAdapter.built(spec.key(), adapter, table.recognizer(spec),
                        table.claimedBySibling(spec), table.yieldedToMe(spec));
                String adapterId = guarded.adapterId();
                // GF-ADAPTERSPECS r2 (review-01 MINOR-2): the built id must map to this specification, or its failures,
                // its OFF state and its rows' key would be another specification's. Otherwise this is a construction
                // failure: this specification's own blocker under its own key, with the warning (never retried: the
                // same adapter would report the same id).
                if (!table.ownsAdapterId(spec, adapterId)) {
                    AdapterSpec other = table.specFor(adapterId);
                    LOGGER.warn("{} {} ({}): EventTrail's adapter reported the id '{}', which maps to {}; its menus are"
                                    + " blocked until the server restarts",
                            OptionalIntegrationCompatibility.LOG_MARKER, spec.displayName(), spec.key(),
                            EventTrailDiagnostics.sanitizeLogValue(adapterId, 96),
                            other == null ? "no adapter specification" : other.key());
                    compatibility.add(exceptionalState(spec, version, WarningReason.ADAPTER_UNAVAILABLE));
                    adapters.add(table.blocker(spec, WarningReason.ADAPTER_UNAVAILABLE.name()));
                    continue;
                }
                adapters.add(guarded);
                compatibility.add(validated(spec, version));
                if (adapterId != null && adapterId.endsWith(OFF_PATH_ID_SUFFIX)) {
                    // Build B R1d: a presence-chosen adapter found no usable behaviour path. It owns its menus
                    // (blocked, no generic fallback) and switches its key OFF with a warning at first use.
                    LOGGER.info("Disabled exact EventTrail inventory adapter {} for {} {}: no supported behaviour"
                            + " path was found; its actions are not recorded", adapterId, spec.modId(), version);
                } else {
                    LOGGER.info("Enabled exact EventTrail inventory adapter {} for {} {}",
                            adapterId, spec.modId(), version);
                }
            } catch (Throwable failure) {
                // Sorted by the fail-safe's rule: a member EventTrail's adapter uses is missing -> OFF until
                // restart (the key is switched off when this result is activated) behind the plain blocker, which
                // keeps no builder, so it is never retried; anything else -> unavailable for now, and the
                // construction is retried at the next use of one of its own menus (R2-menu retry handle).
                if (FailureClassifier.classify(failure).missing()) {
                    compatibility.add(offAtConstruction(spec, version));
                    adapters.add(table.blocker(spec, WarningReason.MISSING_MEMBER.name()));
                    missingAtConstruction.put(spec.key(), failure);
                } else {
                    IntegrationState unavailable = exceptionalState(
                            spec, version, WarningReason.ADAPTER_UNAVAILABLE);
                    compatibility.add(unavailable);
                    adapters.add(GuardedMenuAdapter.retrying(
                            spec.key(),
                            table.blocker(spec, WarningReason.ADAPTER_UNAVAILABLE.name()),
                            table.claimedBySibling(spec),
                            table.yieldedToMe(spec),
                            () -> buildOwnId(table, factory, spec),
                            built -> markBuiltOnRetry(spec, version, unavailable, built.adapterId())));
                }
                LOGGER.debug("Optional EventTrail inventory adapter {} was unavailable ({})",
                        spec.modId(), failure.getClass().getSimpleName());
            }
        }
        compatibility.addAll(nonMenuStates(installedVersions, modFiles, table.keys()));
        return new LoadResult(adapters, new Snapshot(compatibility), missingAtConstruction);
    }

    /**
     * The status entries of the bridge and location keys, which have no menu specification: ON (in use)
     * while their mod is installed, by presence only; absent otherwise. OFF comes from the fail-safe
     * ({@link #compatibilitySnapshot()}). A key whose mod's file definitely lacks the key's package
     * ({@link AdapterKeyPresence}: the linked-groups key on a Core 1.4.x) is absent, as its hook is.
     * A key that a specification names ({@code specKeys}) has its entry from that specification: in production
     * every such key is a menu key, so this skips nothing more; a test table may stand a bridge key in for a
     * menu key not appended yet.
     */
    private static List<IntegrationState> nonMenuStates(
            Map<String, String> installedVersions, AdapterKeyPresence.ModFiles modFiles, Set<AdapterKey> specKeys) {
        List<IntegrationState> states = new ArrayList<>();
        for (AdapterKey key : AdapterKey.values()) {
            if (key.kind() == AdapterKey.Kind.MENU || specKeys.contains(key)) continue;
            String version = installedVersions.get(key.modId());
            if (version != null && AdapterKeyPresence.lacksPackage(key, modFiles)) version = null;
            states.add(new IntegrationState(
                    key,
                    key.modId(),
                    key.displayName(),
                    version == null ? "" : version,
                    familyOf(key),
                    PRESENCE_CONTRACT,
                    version == null ? ContractState.ABSENT : ContractState.VALIDATED,
                    version == null ? RuntimeDisposition.NOT_APPLICABLE : RuntimeDisposition.SPECIALIZED,
                    null));
        }
        return states;
    }

    private static String familyOf(AdapterKey key) {
        return switch (key.kind()) {
            case MENU -> CONTAINER_FAMILY;
            case BRIDGE -> BRIDGE_FAMILY;
            case LOCATION -> LOCATION_FAMILY;
        };
    }

    private static IntegrationState absent(AdapterSpec spec) {
        return new IntegrationState(
                spec.key(),
                spec.modId(),
                spec.displayName(),
                "",
                CONTAINER_FAMILY,
                spec.validatedContract(),
                ContractState.ABSENT,
                RuntimeDisposition.NOT_APPLICABLE,
                null);
    }

    private static IntegrationState validated(AdapterSpec spec, String version) {
        return new IntegrationState(
                spec.key(),
                spec.modId(),
                spec.displayName(),
                version,
                CONTAINER_FAMILY,
                spec.validatedContract(),
                ContractState.VALIDATED,
                RuntimeDisposition.SPECIALIZED,
                null);
    }

    /**
     * OFF for a reason other than a missing member (today: the adapter could not be built). Every OFF state is
     * INCOMPATIBLE; UNVALIDATED is no longer produced, because no version is compared.
     */
    private static IntegrationState exceptionalState(
            AdapterSpec spec, String version, WarningReason reason) {
        ContractState contract = ContractState.INCOMPATIBLE;
        RuntimeDisposition disposition = spec.authoritativeGenericFallback()
                ? RuntimeDisposition.GENERIC_FALLBACK
                : RuntimeDisposition.DISABLED;
        return new IntegrationState(
                spec.key(),
                spec.modId(),
                spec.displayName(),
                version,
                CONTAINER_FAMILY,
                spec.validatedContract(),
                contract,
                disposition,
                reason);
    }

    /** OFF until restart because a member was missing at construction: disabled, never a generic fallback. */
    private static IntegrationState offAtConstruction(AdapterSpec spec, String version) {
        return new IntegrationState(
                spec.key(),
                spec.modId(),
                spec.displayName(),
                version,
                CONTAINER_FAMILY,
                spec.validatedContract(),
                ContractState.INCOMPATIBLE,
                RuntimeDisposition.DISABLED,
                WarningReason.MISSING_MEMBER);
    }

    private static MenuTransactionAdapter buildExact(AdapterFactory factory, AdapterSpec spec)
            throws ReflectiveOperationException {
        MenuTransactionAdapter adapter = Objects.requireNonNull(
                factory.create(spec.adapterClassName()), "optional adapter");
        if (!adapter.exact()) {
            throw new IllegalStateException("Optional adapter is not exact");
        }
        return adapter;
    }

    /**
     * GF-ADAPTERSPECS r2 (review-01 MINOR-2): a construction retry's build, refused (a failed retry, counted by the
     * handle) when the adapter's id does not map to its own specification, as the server-start check refuses it.
     */
    private static MenuTransactionAdapter buildOwnId(SpecTable table, AdapterFactory factory, AdapterSpec spec)
            throws ReflectiveOperationException {
        MenuTransactionAdapter adapter = buildExact(factory, spec);
        if (!table.ownsAdapterId(spec, adapter.adapterId())) {
            throw new IllegalStateException("Optional adapter id does not map to its own specification " + spec.key());
        }
        return adapter;
    }

    /**
     * R2-menu: an adapter that could not be built at server start was built at a later use of one of its menus, so
     * its status shows it in use. Changes nothing unless the published entry of its key is still the unavailable
     * state of the same load (a load that was never activated, or a later server start, is left alone).
     */
    private static synchronized void markBuiltOnRetry(
            AdapterSpec spec, String version, IntegrationState unavailable, String adapterId) {
        Snapshot current = currentSnapshot;
        if (current.find(spec.key()) != unavailable) return;
        currentSnapshot = current.replace(validated(spec, version));
        // c-3 r7 (Advisor r6 N-2): a retry can build an adapter that found no usable behaviour path; its line says
        // so, as the server-start line does (same endsWith branch). Log text only: the entry is the same as before.
        if (adapterId != null && adapterId.endsWith(OFF_PATH_ID_SUFFIX)) {
            EnumSet<AdapterKey> keys = EnumSet.of(spec.key());
            keys.addAll(offPathKeys);
            offPathKeys = Collections.unmodifiableSet(keys);
            LOGGER.info("Disabled exact EventTrail inventory adapter {} for {} {} at a later use (it could not be "
                            + "built at server start): no supported behaviour path was found; its actions are not "
                            + "recorded",
                    adapterId, spec.modId(), version);
            return;
        }
        LOGGER.info("Enabled exact EventTrail inventory adapter {} for {} {} at a later use (it could not be built "
                        + "at server start)",
                adapterId, spec.modId(), version);
    }

    /** The adapter key of an exact adapter id (its specification's menu key), or null when no specification names it. */
    public static AdapterKey keyForAdapter(String adapterId) {
        return PRODUCTION.keyForAdapter(adapterId);
    }

    /** The production specifications (tests pin the one-specification-per-mod state through it). */
    static SpecTable specTable() {
        return PRODUCTION;
    }

    /**
     * Lead decision Q4 (build B c-3): a {@code match} failure of an exact adapter that is not wrapped in
     * {@link GuardedMenuAdapter}, reported by the registry. Sorted by the fail-safe: a missing member switches the key
     * OFF until restart; anything else skips that open with a de-duplicated warning. Ids no specification names are
     * dropped by the fail-safe (no key). Never throws.
     */
    public static void reportMatchFailure(String adapterId, Throwable failure) {
        PRODUCTION.reportMatchFailure(adapterId, failure);
    }

    private static Map<String, String> installedVersions() {
        ModList modList = ModList.get();
        if (modList == null) return Map.of();
        // Presence and display only: each adapter's own mod; no dependency version is read, none is compared.
        Map<String, String> versions = new LinkedHashMap<>();
        for (AdapterSpec spec : SPECS) {
            collectVersion(modList, versions, spec.modId());
        }
        for (AdapterKey key : AdapterKey.values()) {
            if (key.kind() != AdapterKey.Kind.MENU) collectVersion(modList, versions, key.modId());
        }
        return Map.copyOf(versions);
    }

    private static void collectVersion(
            ModList modList, Map<String, String> versions, String modId) {
        try {
            if (!modList.isLoaded(modId)) return;
            modList.getModContainerById(modId).ifPresentOrElse(
                    container -> versions.put(
                            modId, container.getModInfo().getVersion().toString()),
                    () -> versions.put(modId, UNREADABLE_VERSION));
        } catch (RuntimeException | LinkageError ignored) {
            // Not treated as mod absence: the adapter is built as for any installed mod (a class missing
            // at construction switches it off) and status shows the version as unreadable.
            versions.put(modId, UNREADABLE_VERSION);
        }
    }

    private static MenuTransactionAdapter instantiate(String className)
            throws ReflectiveOperationException {
        // No artifact or version admission in the loader: building the adapter is the check (a missing member
        // -> OFF). Since build B F1-4 no adapter's own constructor refuses an untested version or artifact; a
        // constructor that still throws is ADAPTER_UNAVAILABLE, and the construction is retried at the next use of
        // one of its own menus.
        Class<?> type = Class.forName(
                className, true, OptionalMenuAdapterLoader.class.getClassLoader());
        if (!MenuTransactionAdapter.class.isAssignableFrom(type)) {
            throw new ClassCastException(className + " is not a MenuTransactionAdapter");
        }
        Constructor<?> constructor = type.getDeclaredConstructor();
        return (MenuTransactionAdapter) constructor.newInstance();
    }

    @FunctionalInterface
    interface AdapterFactory {
        MenuTransactionAdapter create(String className) throws ReflectiveOperationException;
    }

    /**
     * One menu adapter. {@code testedVersion} and {@code testedWith} (dependency mod id to version) are information
     * only: the versions the adapter was tested with. Nothing compares them; whether the adapter is used depends only
     * on its mod being installed and its classes being present.
     *
     * <p>GF-ADAPTERSPECS: {@code key} is the specification's own menu key, named explicitly (a mod may have several
     * specifications, each with its own appended key; pinned per specification by {@code OptionalIntegrationKeyTest}).
     * {@code authoritativeGenericFallback} is this specification's own: a network or terminal sibling sets it false.
     * </p>
     */
    record AdapterSpec(
            AdapterKey key,
            String modId,
            String displayName,
            String testedVersion,
            Map<String, String> testedWith,
            String validatedContract,
            Set<String> specializedMenuBaseNames,
            boolean authoritativeGenericFallback,
            String adapterIdPrefix,
            String adapterClassName) {
        AdapterSpec {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(modId, "modId");
            if (!key.modId().equals(modId)) {
                throw new IllegalArgumentException("Adapter key " + key + " does not belong to mod " + modId);
            }
            Objects.requireNonNull(displayName, "displayName");
            specializedMenuBaseNames = Set.copyOf(specializedMenuBaseNames);
            if (adapterIdPrefix == null || adapterIdPrefix.isEmpty()) {
                throw new IllegalArgumentException("Adapter specification " + key + " needs an adapter id prefix");
            }
        }
    }

    /**
     * GF-ADAPTERSPECS: an ordered set of specifications, checked when it is made (for {@link #SPECS}: at class load).
     * Several specifications may share a mod id (siblings); each has its own key, its own exact adapter-id mapping,
     * its own recognizer and its own fallback flag, and siblings share one claim predicate:
     * <ul>
     *   <li>no two specifications share a key, and no specification's id prefix is a prefix of another's id prefix or
     *       OFF-blocker id, so every adapter id maps to at most one specification;</li>
     *   <li>siblings' {@code specializedMenuBaseNames} are disjoint and not empty, and a mod with a special-case
     *       recognition (Lootr, Create, the Sophisticated family and the rest of {@link #SPECIAL_RECOGNITION}) has one
     *       specification only;</li>
     *   <li>the claim predicate gives a menu that more than one sibling's names reach to the sibling whose named
     *       class or interface is met first, walking up from the menu's own class (the most specific claim); a tie at
     *       one level gives it to every tied sibling, so their answers block the open (fail closed);</li>
     *   <li>r2: a built adapter's NO_MATCH for a menu a sibling yielded to it ({@link #yieldingSibling}) is answered
     *       AMBIGUOUS (blocked), so the generic adapter never takes a menu its mod's recognition owns; a built
     *       adapter's id must map to its own specification ({@link #ownsAdapterId}), else that specification's own
     *       blocker is registered; a mod's first specification holds its group's lowest key ordinal.</li>
     * </ul>
     * With one specification per mod id (today) the claim predicate never yields, the OFF-blocker ids keep the frozen
     * form {@code <modId>:compatibility-block}, and every id {@code <modId>:...} still maps to that specification.
     */
    static final class SpecTable {
        private final List<AdapterSpec> specs;
        private final Map<String, List<AdapterSpec>> byModId;
        private final Set<AdapterKey> keys;
        /** r2 NIT-1: each specification's OFF-blocker id, made once (the first specification of an id kept). */
        private final Map<String, AdapterSpec> byBlockerId;
        /** r2 NIT-1: {@code <modId>:} of each mod with one specification, in table order, made once. */
        private final List<Map.Entry<String, AdapterSpec>> modIdPrefixes;

        SpecTable(List<AdapterSpec> specs) {
            this.specs = List.copyOf(specs);
            Map<String, List<AdapterSpec>> groups = new LinkedHashMap<>();
            Set<AdapterKey> seen = EnumSet.noneOf(AdapterKey.class);
            for (AdapterSpec spec : this.specs) {
                if (!seen.add(spec.key())) {
                    throw new IllegalStateException("Two adapter specifications share the key " + spec.key());
                }
                groups.computeIfAbsent(spec.modId(), ignored -> new ArrayList<>()).add(spec);
            }
            Map<String, List<AdapterSpec>> frozen = new LinkedHashMap<>();
            groups.forEach((modId, group) -> frozen.put(modId, List.copyOf(group)));
            this.byModId = Collections.unmodifiableMap(frozen);
            this.keys = Collections.unmodifiableSet(seen);
            // r2 NIT-1: the strings specFor compares, made once here, so specFor builds no string per call. The same
            // answers as the loops they replace: the first specification in table order wins each.
            Map<String, AdapterSpec> blockerIds = new LinkedHashMap<>();
            List<Map.Entry<String, AdapterSpec>> modIds = new ArrayList<>();
            for (AdapterSpec spec : this.specs) {
                blockerIds.putIfAbsent(blockerId(spec), spec);
                if (this.byModId.get(spec.modId()).size() == 1) modIds.add(Map.entry(spec.modId() + ":", spec));
            }
            this.byBlockerId = Collections.unmodifiableMap(blockerIds);
            this.modIdPrefixes = List.copyOf(modIds);
            for (AdapterSpec spec : this.specs) {
                for (AdapterSpec other : this.specs) {
                    if (spec == other) continue;
                    if (other.adapterIdPrefix().startsWith(spec.adapterIdPrefix())) {
                        throw new IllegalStateException("Adapter id prefix '" + spec.adapterIdPrefix() + "' of "
                                + spec.key() + " is a prefix of '" + other.adapterIdPrefix() + "' of " + other.key());
                    }
                    if (blockerId(other).startsWith(spec.adapterIdPrefix())) {
                        throw new IllegalStateException("Adapter id prefix '" + spec.adapterIdPrefix() + "' of "
                                + spec.key() + " is a prefix of the OFF-blocker id of " + other.key());
                    }
                }
            }
            for (List<AdapterSpec> group : this.byModId.values()) {
                // r2 NIT-2: a mod's first specification (the one whose OFF-blocker id keeps the frozen
                // <modId>:compatibility-block form) holds the lowest key ordinal of its group. Key ordinals are frozen,
                // so a new key takes a higher one: the frozen id cannot move to a sibling whose key was added later.
                AdapterSpec first = group.getFirst();
                for (AdapterSpec spec : group) {
                    if (spec.key().ordinal() < first.key().ordinal()) {
                        throw new IllegalStateException("The first adapter specification of " + first.modId() + " ("
                                + first.key() + ") must hold its lowest key ordinal, not " + spec.key());
                    }
                }
                if (group.size() < 2) continue;
                Set<String> names = new HashSet<>();
                for (AdapterSpec spec : group) {
                    if (SPECIAL_RECOGNITION.contains(spec.modId())) {
                        throw new IllegalStateException("Mod " + spec.modId()
                                + " has a special-case recognition and may have one adapter specification only");
                    }
                    if (spec.specializedMenuBaseNames().isEmpty()) {
                        throw new IllegalStateException("Sibling specification " + spec.key() + " names no menu");
                    }
                    for (String name : spec.specializedMenuBaseNames()) {
                        if (!names.add(name)) {
                            throw new IllegalStateException("Sibling specifications of " + spec.modId()
                                    + " both name " + name);
                        }
                    }
                }
            }
        }

        /** The production table: as any table, and every specification's key is a menu key. */
        static SpecTable production(List<AdapterSpec> specs) {
            for (AdapterSpec spec : specs) {
                if (spec.key().kind() != AdapterKey.Kind.MENU) {
                    throw new IllegalStateException("Adapter specification key " + spec.key() + " is not a menu key");
                }
            }
            return new SpecTable(specs);
        }

        List<AdapterSpec> specs() {
            return specs;
        }

        Set<AdapterKey> keys() {
            return keys;
        }

        /**
         * The id of the OFF blocker (and of the unbuilt handle) of {@code spec}: the frozen
         * {@code <modId>:compatibility-block} for the first specification of its mod id, and
         * {@code <adapterIdPrefix>compatibility-block} for a later sibling, so it maps to its own specification.
         */
        String blockerId(AdapterSpec spec) {
            List<AdapterSpec> group = byModId.get(spec.modId());
            return group == null || group.getFirst() == spec
                    ? spec.modId() + ":compatibility-block"
                    : spec.adapterIdPrefix() + "compatibility-block";
        }

        /**
         * The specification of an adapter id: the one whose own id prefix starts it, else the one whose OFF-blocker
         * id it is, else (only for a mod with one specification) the one whose {@code <modId>:} starts it.
         */
        AdapterSpec specFor(String adapterId) {
            if (adapterId == null) return null;
            for (AdapterSpec spec : specs) {
                if (adapterId.startsWith(spec.adapterIdPrefix())) return spec;
            }
            AdapterSpec blocker = byBlockerId.get(adapterId);
            if (blocker != null) return blocker;
            for (Map.Entry<String, AdapterSpec> modId : modIdPrefixes) {
                if (adapterId.startsWith(modId.getKey())) return modId.getValue();
            }
            return null;
        }

        /**
         * GF-ADAPTERSPECS r2 (review-01 MINOR-2): true when an adapter built for {@code spec} may carry
         * {@code adapterId}: the id maps to {@code spec} itself. An id that maps to no specification is accepted only
         * for a mod with one specification, as before GF-ADAPTERSPECS (its failures then name no key, and no other
         * specification's either); for a mod with siblings it is refused.
         */
        boolean ownsAdapterId(AdapterSpec spec, String adapterId) {
            AdapterSpec mapped = specFor(adapterId);
            if (mapped != null) return mapped == spec;
            List<AdapterSpec> group = byModId.get(spec.modId());
            return adapterId != null && group != null && group.size() == 1 && group.getFirst() == spec;
        }

        AdapterKey keyForAdapter(String adapterId) {
            AdapterSpec spec = specFor(adapterId);
            return spec == null ? null : spec.key();
        }

        /** As {@link OptionalMenuAdapterLoader#reportMatchFailure}, against the failing adapter's own key. */
        void reportMatchFailure(String adapterId, Throwable failure) {
            try {
                AdapterFailSafe.report(keyForAdapter(adapterId), MATCH_FAILURE_SITE, failure);
            } catch (Throwable ignored) {
                // The registry keeps this open fail-closed whatever happened here.
            }
        }

        /** OFF in {@code snapshot} for a runtime failure, a missing member or a concept not found (fail-safe aside). */
        boolean isRuntimeAdapterDisabled(Snapshot snapshot, String adapterId) {
            Objects.requireNonNull(snapshot, "snapshot");
            AdapterSpec spec = specFor(adapterId);
            if (spec == null) return false;
            IntegrationState current = snapshot.find(spec.key());
            return current != null
                    && (current.warningReason() == WarningReason.RUNTIME_ADAPTER_FAILURE
                            || current.warningReason() == WarningReason.MISSING_MEMBER
                            || current.warningReason() == WarningReason.CONCEPT_NOT_FOUND)
                    && current.disposition() == RuntimeDisposition.DISABLED;
        }

        /** As {@link #isRuntimeAdapterDisabled(Snapshot, String)}, or the adapter's own key is OFF in the fail-safe. */
        boolean isRuntimeAdapterDisabledNow(Snapshot snapshot, String adapterId) {
            AdapterSpec spec = specFor(adapterId);
            if (spec != null && AdapterFailSafe.switchedOff(spec.key())) return true;
            return isRuntimeAdapterDisabled(snapshot, adapterId);
        }

        Snapshot runtimeFailureSnapshot(Snapshot snapshot, String adapterId) {
            Objects.requireNonNull(snapshot, "snapshot");
            AdapterSpec spec = specFor(adapterId);
            if (spec == null) return null;
            IntegrationState current = snapshot.find(spec.key());
            if (current == null || current.contractState() == ContractState.ABSENT) return null;
            return snapshot.replace(current.runtimeFailure());
        }

        /** A disabled adapter's own menus, by its own specification and the shared claim predicate. */
        boolean runtimeDisabledAdapterOwnsContext(Snapshot snapshot, String adapterId, MenuAdapterContext context) {
            if (!isRuntimeAdapterDisabledNow(snapshot, adapterId) || context == null || context.menu() == null) {
                return false;
            }
            AdapterSpec spec = specFor(adapterId);
            return spec != null && owns(spec, context);
        }

        /** The menus {@code spec}'s adapter owns: its own recognition, less the menus a sibling claims. */
        boolean owns(AdapterSpec spec, MenuAdapterContext context) {
            return !siblingClaims(spec, context) && ownRecognition(spec, context);
        }

        /**
         * The shared claim predicate: true when a sibling of {@code spec} (same mod id, another specification) claims
         * this menu and {@code spec} does not (see the class comment). Always false for a mod with one specification.
         */
        boolean siblingClaims(AdapterSpec spec, MenuAdapterContext context) {
            List<AdapterSpec> group = byModId.get(spec.modId());
            if (group == null || group.size() < 2 || context == null || context.menu() == null) return false;
            List<AdapterSpec> claimants = claimants(group, context.menu().getClass());
            return !claimants.isEmpty() && !claimants.contains(spec);
        }

        /** {@code spec}'s claim predicate as the guarded adapter, its retry handle and its blocker take it. */
        Predicate<MenuAdapterContext> claimedBySibling(AdapterSpec spec) {
            List<AdapterSpec> group = byModId.get(spec.modId());
            if (group == null || group.size() < 2) return NO_SIBLING;
            return context -> siblingClaims(spec, context);
        }

        /**
         * GF-ADAPTERSPECS r2 (review-01 MINOR-1): a sibling of {@code spec} that yields this menu, or null: another
         * specification T of the same mod with {@code siblingClaims(T, context)} and its own recognition owning the
         * menu. T's guard then answers NO_MATCH by the claim predicate whatever T's adapter would say, so a NO_MATCH
         * from {@code spec}'s adapter must not let the generic adapter in. The guard asks only after its own claim
         * predicate gave the menu to it (not to a sibling), so the menu was yielded to {@code spec}. Null for a mod
         * with one specification.
         */
        AdapterSpec yieldingSibling(AdapterSpec spec, MenuAdapterContext context) {
            List<AdapterSpec> group = byModId.get(spec.modId());
            if (group == null || group.size() < 2 || context == null || context.menu() == null) return null;
            for (AdapterSpec other : group) {
                if (other != spec && siblingClaims(other, context) && ownRecognition(other, context)) return other;
            }
            return null;
        }

        /**
         * {@code spec}'s yield check as the guarded adapter and its retry handle take it: the yielding sibling's key,
         * or null. {@code NO_YIELD} for a mod with one specification, so nothing changes today.
         */
        Function<MenuAdapterContext, AdapterKey> yieldedToMe(AdapterSpec spec) {
            List<AdapterSpec> group = byModId.get(spec.modId());
            if (group == null || group.size() < 2) return NO_YIELD;
            return context -> {
                AdapterSpec yielding = yieldingSibling(spec, context);
                return yielding == null ? null : yielding.key();
            };
        }

        /** The OFF blocker of {@code spec}: its own id, names and special case, yielding its siblings' menus. */
        OffAdapterBlocker blocker(AdapterSpec spec, String reason) {
            return new OffAdapterBlocker(spec.modId(), spec.specializedMenuBaseNames(), reason, blockerId(spec),
                    claimedBySibling(spec));
        }

        /** The adapter's own name test (the blocker's recognition, which links no optional class): which menus it owns. */
        OffAdapterBlocker recognizer(AdapterSpec spec) {
            return blocker(spec, "recognizer");
        }

        LoadResult load(Map<String, String> installedVersions, AdapterFactory factory,
                AdapterKeyPresence.ModFiles modFiles) {
            return OptionalMenuAdapterLoader.load(this, installedVersions, factory, modFiles);
        }

        Snapshot absentSnapshot() {
            List<IntegrationState> states = new ArrayList<>(specs.size() + AdapterKey.values().length);
            specs.forEach(spec -> states.add(absent(spec)));
            states.addAll(nonMenuStates(Map.of(), null, keys));
            return new Snapshot(states);
        }

        /** The siblings whose named class or interface is met first walking up from {@code type}; empty if none. */
        private static List<AdapterSpec> claimants(List<AdapterSpec> group, Class<?> type) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                List<AdapterSpec> found = new ArrayList<>(2);
                for (AdapterSpec spec : group) {
                    if (namedAtLevel(current, spec.specializedMenuBaseNames())) found.add(spec);
                }
                if (!found.isEmpty()) return found;
            }
            return List.of();
        }

        /** One level of {@link OffAdapterBlocker#belongsToSpecializedHierarchy}: the class or its interfaces. */
        private static boolean namedAtLevel(Class<?> level, Set<String> names) {
            if (names.contains(level.getName())) return true;
            for (Class<?> contract : level.getInterfaces()) {
                if (OffAdapterBlocker.belongsToInterfaceHierarchy(contract, names)) return true;
            }
            return false;
        }
    }

    /**
     * A startup decision. {@code missingAtConstruction} holds, per key, the failure of an adapter whose
     * construction met a missing member; {@link OptionalMenuAdapterLoader#activate} switches those keys
     * OFF in the fail-safe.
     */
    public record LoadResult(
            List<MenuTransactionAdapter> adapters,
            Snapshot compatibility,
            Map<AdapterKey, Throwable> missingAtConstruction) {
        public LoadResult {
            adapters = List.copyOf(adapters);
            compatibility = Objects.requireNonNull(compatibility, "compatibility");
            Objects.requireNonNull(missingAtConstruction, "missingAtConstruction");
            missingAtConstruction = missingAtConstruction.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new EnumMap<>(missingAtConstruction));
        }

        /** A decision without construction failures (tests and fixtures). */
        public LoadResult(List<MenuTransactionAdapter> adapters, Snapshot compatibility) {
            this(adapters, compatibility, Map.of());
        }
    }

    /**
     * Registered in place of an adapter that is OFF (a member missing at construction, or the adapter could not
     * be built), never because of a version: it claims that adapter's own menus so they are blocked instead of
     * falling back to generic tracking. The id form {@code <modId>:compatibility-block} and the
     * {@link AdapterMatch#UNSUPPORTED_VERSION} answer are kept unchanged (frozen names other code compares).
     * GF-ADAPTERSPECS: a later sibling specification of a mod gets the id {@code <adapterIdPrefix>compatibility-block}
     * ({@link SpecTable#blockerId}), and a blocker never claims a menu the shared claim predicate gives to a sibling.
     */
    static final class OffAdapterBlocker implements MenuTransactionAdapter {
        private final String modId;
        private final Set<String> specializedMenuBaseNames;
        private final String reason;
        private final String adapterId;
        private final Predicate<MenuAdapterContext> claimedBySibling;

        OffAdapterBlocker(
                String modId, Set<String> specializedMenuBaseNames, String reason) {
            this(modId, specializedMenuBaseNames, reason, modId + ":compatibility-block", NO_SIBLING);
        }

        OffAdapterBlocker(
                String modId,
                Set<String> specializedMenuBaseNames,
                String reason,
                String adapterId,
                Predicate<MenuAdapterContext> claimedBySibling) {
            this.modId = Objects.requireNonNull(modId, "modId");
            this.specializedMenuBaseNames = Set.copyOf(specializedMenuBaseNames);
            this.reason = Objects.requireNonNull(reason, "reason");
            this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
            this.claimedBySibling = Objects.requireNonNull(claimedBySibling, "claimedBySibling");
        }

        @Override
        public String adapterId() {
            return adapterId;
        }

        @Override
        public boolean exact() {
            return true;
        }

        @Override
        public AdapterMatch match(MenuAdapterContext context) {
            if (context == null || context.menu() == null) return AdapterMatch.NO_MATCH;
            if (yieldsToSibling(context)) return AdapterMatch.NO_MATCH;
            if ("lootr".equals(modId)) return isLootrMenu(context)
                    ? AdapterMatch.UNSUPPORTED_VERSION : AdapterMatch.NO_MATCH;
            if ("enderstorage".equals(modId)) return isEnderStorageMenu(context.menu().getClass())
                    ? AdapterMatch.UNSUPPORTED_VERSION : AdapterMatch.NO_MATCH;
            if ("pocketstorage".equals(modId)) return isPocketStorageMenu(context.menu().getClass())
                    ? AdapterMatch.UNSUPPORTED_VERSION : AdapterMatch.NO_MATCH;
            if ("integratedterminals".equals(modId)) return isIntegratedStorageMenu(context.menu().getClass())
                    ? AdapterMatch.UNSUPPORTED_VERSION : AdapterMatch.NO_MATCH;
            if ("occultism".equals(modId)) return isOccultismStorageMenu(context.menu().getClass())
                    ? AdapterMatch.UNSUPPORTED_VERSION : AdapterMatch.NO_MATCH;
            if ("rftoolsstorage".equals(modId)) return isRftoolsScannerMenu(context.menu().getClass())
                    ? AdapterMatch.UNSUPPORTED_VERSION : AdapterMatch.NO_MATCH;
            if ("create".equals(modId)) return isCreateMountedMenu(context)
                    ? AdapterMatch.UNSUPPORTED_VERSION : AdapterMatch.NO_MATCH;
            if ("sophisticatedstoragecreateintegration".equals(modId)) return isSophisticatedCreateMountedMenu(context.menu().getClass())
                    ? AdapterMatch.UNSUPPORTED_VERSION : AdapterMatch.NO_MATCH;
            if ("sophisticatedstorageinmotion".equals(modId)) return isStorageInMotionMenu(context.menu().getClass())
                    ? AdapterMatch.UNSUPPORTED_VERSION : AdapterMatch.NO_MATCH;
            if ("sophisticatedstorage".equals(modId)
                    && isNonStationarySophisticatedMenu(context.menu().getClass())) return AdapterMatch.NO_MATCH;
            return belongsToSpecializedHierarchy(
                    context.menu().getClass(), specializedMenuBaseNames)
                    ? AdapterMatch.UNSUPPORTED_VERSION
                    : AdapterMatch.NO_MATCH;
        }

        @Override
        public ObservationStrategy observationStrategy() {
            return ObservationStrategy.ACTION_SCOPED_PHYSICAL;
        }

        String reason() {
            return reason;
        }

        /** The shared claim predicate; one that cannot answer yields nothing, so this blocker's own rule decides. */
        private boolean yieldsToSibling(MenuAdapterContext context) {
            try {
                return claimedBySibling.test(context);
            } catch (Throwable failure) {
                return false;
            }
        }

        static boolean belongsToSpecializedHierarchy(
                Class<?> type, Set<String> specializedBaseNames) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (specializedBaseNames.contains(current.getName())) return true;
                for (Class<?> contract : current.getInterfaces()) {
                    if (belongsToInterfaceHierarchy(contract, specializedBaseNames)) return true;
                }
            }
            return false;
        }

        private static boolean belongsToInterfaceHierarchy(
                Class<?> type, Set<String> specializedBaseNames) {
            if (specializedBaseNames.contains(type.getName())) return true;
            for (Class<?> parent : type.getInterfaces()) {
                if (belongsToInterfaceHierarchy(parent, specializedBaseNames)) return true;
            }
            return false;
        }
    }
}
