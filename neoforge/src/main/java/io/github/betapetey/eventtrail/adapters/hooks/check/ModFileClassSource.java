package io.github.betapetey.eventtrail.adapters.hooks.check;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.MixinService;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The run-time {@link ClassSource} of the pre-apply check: reads class bytes from the installed mod files without
 * loading any class (work map R4-check).
 *
 * <p>Order: every mod file FML lists ({@code LoadingModList}, the pattern the hook loader already uses at hook-loading
 * time); then, for a class that is in no mod file (a jar-in-jar library), the Mixin service's bytecode provider with
 * {@code getClassNode(name, true)}. Never {@code getClassNode(name, false)}: ModLauncher refuses untransformed bytes.
 * JDK classes are never read. Results, including "absent", are cached until {@link #clear()}; a read failure is not
 * cached and reaches the caller, which makes only the hook concerned unverifiable. The cache holds whole parsed
 * classes (targets, their superclass chains and the hierarchies of named owners), so its owner clears it, or drops
 * the source, once the last check that needs it has run.</p>
 *
 * <p>Links only to the JDK, ASM, FML and Mixin, like the hook loader. That this works on NeoForge 21.1 at hook-loading
 * time is UNVERIFIED by a run: the real-jar test and the deliberately broken audit launch (row D) are the proof.</p>
 */
public final class ModFileClassSource implements ClassSource {
    private final boolean useBytecodeProvider;
    private final Map<String, Optional<ClassNode>> cache = new ConcurrentHashMap<>();

    /**
     * @param useBytecodeProvider whether a class found in no mod file is asked of the Mixin bytecode provider
     */
    public ModFileClassSource(boolean useBytecodeProvider) {
        this.useBytecodeProvider = useBytecodeProvider;
    }

    @Override
    public ClassNode find(String internalName) throws Exception {
        if (HookReader.isJdk(internalName)) return null;
        Optional<ClassNode> cached = cache.get(internalName);
        if (cached != null) return cached.orElse(null);
        ClassNode node = read(internalName);
        cache.put(internalName, Optional.ofNullable(node));
        return node;
    }

    /**
     * Drops every cached class, so the parsed classes can be collected. The source stays usable: a later
     * {@link #find} reads the class again.
     */
    public void clear() {
        cache.clear();
    }

    private ClassNode read(String internalName) throws Exception {
        String resource = internalName + ".class";
        LoadingModList mods = LoadingModList.get();
        if (mods != null) {
            for (var info : mods.getModFiles()) {
                Path path = info.getFile().findResource(resource);
                if (path != null && Files.isRegularFile(path)) {
                    try (InputStream input = Files.newInputStream(path)) {
                        return parse(input.readAllBytes());
                    }
                }
            }
        }
        if (!useBytecodeProvider) return null;
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode(internalName.replace('/', '.'), true);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    /** Parses class bytes with code and debug information; stack-map frames are not needed. */
    static ClassNode parse(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }
}
