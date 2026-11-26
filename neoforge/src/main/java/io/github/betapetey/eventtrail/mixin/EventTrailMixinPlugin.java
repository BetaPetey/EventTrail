package io.github.betapetey.eventtrail.mixin;

import io.github.betapetey.eventtrail.adapters.hooks.HookAdmission;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The hook loader: hands an optional-mod hook to Mixin only when it passed the pre-apply check against the installed
 * mod's own classes (work map R4-wire). No version is read or compared.
 *
 * <ul>
 *   <li>The vanilla hooks are always handed over.</li>
 *   <li>The two engine-credit hooks need only their mod to be installed: they link no optional API.</li>
 *   <li>Every other hook goes through {@link HookAdmission}: the hook-to-key table, the check of each target, the
 *       exclusive groups, one warning per skipped hook and the family stops.</li>
 * </ul>
 *
 * <p>{@link #shouldApplyMixin} never throws: any failure while deciding an optional-mod hook leaves that hook out.
 * The class source of the check is dropped in {@link #getMixins()}, which Mixin calls once every hook of the list
 * has been decided.</p>
 *
 * <p>Both hook lists name this plugin (work map R4-nonfatal): the required {@code eventtrail.mixins.json} holds the
 * vanilla and engine-credit hooks, the non-required {@code eventtrail-adapters.mixins.json} every optional-mod hook.
 * Mixin makes one instance per list; only the adapters list's instance makes an admission.</p>
 *
 * <p>Links only to the JDK, ASM, Mixin, FML and EventTrail classes that do the same (the plugin-loads test pins
 * this): if this class failed to load, a non-required list would hand every hook to Mixin unchecked.</p>
 */
public final class EventTrailMixinPlugin implements IMixinConfigPlugin {
    static final String HOOK_PACKAGE = "io.github.betapetey.eventtrail.mixin.";

    /** The hooks on vanilla and NeoForge classes only: always handed to Mixin. */
    static final Set<String> CORE_HOOKS = Set.of(
            "AbstractContainerMenuItemOverrideMixin",
            "BeingHurtStackMixin",
            "CommandEngineCreditMixin",
            "CommandExecutionScopeMixin",
            "CommonHooksMixin",
            "CompoundContainerAccessor",
            "DamageCommandScopeMixin",
            "ExplosionMixin",
            "FakePlayerAttackScopeMixin",
            "FakePlayerEngineCreditMixin",
            "FramedSourceCarrierMixin",
            "FramedSourceHurtMixin",
            "ItemEntityMixin",
            "LastHitProofMixin",
            "LivingEntityDeathMixin",
            "PlayerActionScopeChunkTaskMixin",
            "PlayerActionScopeInteractMixin",
            "PlayerActionScopeLevelMixin",
            "PlayerActionScopeMoveVehicleMixin",
            "PlayerActionScopeNeighbourMixin",
            "PlayerActionScopeSteeredMixin",
            "PlayerAttackFrameMixin",
            "PlayerInputFrameMixin",
            "PrimedTntActionEvidenceMixin",
            "ProjectileActionEvidenceMixin",
            "RetaliationScopeMixin",
            "ServerGamePacketListenerImplMixin",
            "ServerPlayerBeingHurtMixin",
            "ServerPlayerDeathMixin",
            "ServerPlayerGameModeMixin",
            "TntBlockActionEvidenceMixin",
            "graves.ContainersDropItemStackSpillMixin",
            "graves.GiveItemToPlayerSpillMixin");

    /** Hooks on vanilla classes that deny a mod's stale last-hit credit: they need only that mod installed. */
    static final Map<String, String> ENGINE_CREDIT_HOOKS = Map.of(
            "ArsEngineCreditMixin", "ars_nouveau",
            "IronsEngineCreditMixin", "irons_spellbooks");

    private final Supplier<HookAdmission> admissions;
    private HookAdmission admission;
    private ILogger logger;

    public EventTrailMixinPlugin() {
        // Lambdas only: a named nested class would sit in the mixin package, which Mixin refuses to load once a
        // list naming that package has been prepared.
        this.admissions = () -> HookAdmission.forHookLoader(EventTrailMixinPlugin::targetLoaded, this::warn);
    }

    /** Test seam: the admission is made on first need. */
    EventTrailMixinPlugin(Supplier<HookAdmission> admissions) {
        this.admissions = admissions;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            if (mixinClassName == null || !mixinClassName.startsWith(HOOK_PACKAGE)) return false;
            String hook = mixinClassName.substring(HOOK_PACKAGE.length());
            if (CORE_HOOKS.contains(hook)) return true;
            String creditMod = ENGINE_CREDIT_HOOKS.get(hook);
            if (creditMod != null) return HookAdmission.installed(creditMod);
            return admission().shouldApply(hook, targetClassName);
        } catch (Throwable failure) {
            return false;
        }
    }

    private synchronized HookAdmission admission() {
        if (admission == null) admission = admissions.get();
        return admission;
    }

    /** Mixin's class tracker: a class it reports as loaded can no longer receive a hook. */
    private static boolean targetLoaded(String dottedName) {
        IClassTracker tracker = MixinService.getService().getClassTracker();
        return tracker != null && tracker.isClassLoaded(dottedName);
    }

    /** One skipped-hook warning, to Mixin's logger. Never throws. */
    private void warn(String message) {
        try {
            if (logger == null) logger = MixinService.getService().getLogger("eventtrail");
            logger.warn("{}", message);
        } catch (Throwable ignored) {
            // Logging must never stop the hook loader.
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    /**
     * Mixin calls this after every hook of the list has been decided: the check's class source, which holds parsed
     * classes of other mods, is dropped here (a later decision would read again).
     */
    @Override
    public List<String> getMixins() {
        try {
            HookAdmission held;
            synchronized (this) {
                held = admission;
            }
            if (held != null) held.release();
        } catch (Throwable ignored) {
            // Nothing to hand back either way.
        }
        return null;
    }

    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
    }
}
