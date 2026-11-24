package io.github.betapetey.eventtrail.inventory.adapters.integrated;

import io.github.betapetey.eventtrail.adapters.hooks.HookVerdicts;
import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.spi.*;
import org.cyclops.integratedterminals.inventory.container.ContainerTerminalStorageBase;
import org.cyclops.integratedterminals.inventory.container.ContainerTerminalStoragePart;
import java.util.Optional;

/** Explicit optional ownership. Public access facts are withheld: native Network has no durable ID. */
public final class IntegratedTerminalsMenuAdapter implements MenuTransactionAdapter {
    /** Provider id of the legacy variant's rows. Row data, kept byte for byte (R1d, R1c). */
    public static final String ADAPTER_ID = "integratedterminals-1.8.0-841-item-operation";
    /** Provider id of the current variant's rows. Row data, kept byte for byte (R1d, R1c). */
    public static final String ATM10_ADAPTER_ID = "integratedterminals-1.10.0-item-operation";
    /** The id while no variant is chosen: no rows carry it, and it keeps the loader's {@code integratedterminals-} prefix. */
    static final String NO_VARIANT_ID = "integratedterminals-unadmitted-item-operation";
    /**
     * The loader builds this adapter once per server start, right after the fail-safe's fresh OFF table, so the
     * first-fit latch of the transfer lookup starts fresh here too (Advisor M-1, {@link IntegratedStorageAuthority#startRun()}).
     */
    public IntegratedTerminalsMenuAdapter() { IntegratedStorageAuthority.startRun(); }
    /** The id follows the variant the hook check chose, never a guess ({@link HookVerdicts#integratedVariant()}). */
    @Override public String adapterId() { return adapterId(HookVerdicts.integratedVariant()); }
    static String adapterId(HookVerdicts.IntegratedVariant variant) {
        return switch (variant) {
            case LEGACY -> ADAPTER_ID;
            case CURRENT -> ATM10_ADAPTER_ID;
            case NONE -> NO_VARIANT_ID;
        };
    }
    @Override public boolean exact() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.AUTHORITATIVE_OPERATION; }
    @Override public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof ContainerTerminalStorageBase<?>)) return AdapterMatch.NO_MATCH;
        return context.menu().getClass() == ContainerTerminalStoragePart.class && context.menu() instanceof IntegratedMenuCarrier
                ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
    }
    static IntegratedTerminalsBinding current(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof IntegratedMenuCarrier carrier)) return null;
        var binding = carrier.eventtrail$integratedBinding();
        return binding != null && binding.player == context.player() && binding.menu == context.menu() && binding.current() ? binding : null;
    }
    @Override public Optional<ContainerTarget> resolveAuthoritativeTarget(MenuAdapterContext context) {
        if (match(context) != AdapterMatch.SUPPORTED) return Optional.empty();
        var binding = current(context);
        return binding == null ? Optional.empty() : binding.target(IntegratedTerminalsOperations.channel(binding.menu));
    }
}
