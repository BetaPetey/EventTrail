package io.github.betapetey.eventtrail.compat;

import net.neoforged.fml.loading.LoadingModList;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a key whose mod is installed is itself installed (Sophisticated part 4 core r2, review-01 MINOR-1).
 *
 * <p>Every key is installed while its mod is installed, except a key whose feature lives in one package of its mod
 * that older builds of the mod do not have. Today that is only {@link AdapterKey#SOPHISTICATED_LINKED_GROUPS}: Core
 * 1.4.x (the retained packs' Core 1.4.90.2299) has no {@code linkedstorage} package, so on such a Core the key is not
 * installed: its hook is left out as not installed (no warning, nothing stopped) and its status line reads "absent",
 * never "in use".</p>
 *
 * <p>Only a definite "the mod's own file does not hold the package's marker class file" makes the key not installed.
 * When that cannot be read (no FML mod list, no mod file, a lookup that throws), the key counts as installed, so the
 * hook's own exact check still runs: a Core that has the package but fails that check still warns once and stops only
 * this key. This is a marker presence test on the mod's file; no version and no artifact bytes are read.</p>
 */
public final class AdapterKeyPresence {
    /** The class file whose absence from Core's own file means Core has no linked storage (Core 1.4.x). */
    public static final String SOPHISTICATED_LINKED_PACKAGE_MARKER =
            "net/p3pp3rf1y/sophisticatedcore/linkedstorage/LinkedStorageGroupManager.class";

    /** Whether the file of a mod holds a resource: TRUE, FALSE, or null when that cannot be read. */
    @FunctionalInterface
    public interface ModFiles {
        Boolean holds(String modId, String resource) throws Exception;
    }

    /** The running game's mod files, through FML's loading mod list (also usable while Mixin prepares its hooks). */
    public static final ModFiles FML = AdapterKeyPresence::fmlHolds;

    private AdapterKeyPresence() {
    }

    /** The resource whose absence from the key's mod file means the key is not installed, or null for none. */
    public static String packageMarker(AdapterKey key) {
        return key == AdapterKey.SOPHISTICATED_LINKED_GROUPS ? SOPHISTICATED_LINKED_PACKAGE_MARKER : null;
    }

    /**
     * True only when the key has a package marker and its mod's file definitely does not hold it. False for every
     * other key and whenever the answer cannot be read. Never throws.
     */
    public static boolean lacksPackage(AdapterKey key, ModFiles files) {
        String marker = key == null ? null : packageMarker(key);
        if (marker == null || files == null) return false;
        try {
            return Boolean.FALSE.equals(files.holds(key.modId(), marker));
        } catch (Throwable unreadable) {
            return false;
        }
    }

    private static Boolean fmlHolds(String modId, String resource) throws Exception {
        LoadingModList mods = LoadingModList.get();
        if (mods == null || modId == null || resource == null) return null;
        var file = mods.getModFileById(modId);
        if (file == null || file.getFile() == null) return null;
        Path path = file.getFile().findResource(resource);
        return path == null ? null : Files.isRegularFile(path);
    }
}
