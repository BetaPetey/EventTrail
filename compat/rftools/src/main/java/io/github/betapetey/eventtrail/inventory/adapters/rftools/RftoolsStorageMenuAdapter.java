package io.github.betapetey.eventtrail.inventory.adapters.rftools;

import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.spi.*;
import mcjty.rftoolsstorage.modules.scanner.blocks.StorageScannerContainer;
import java.util.Optional;

/**
 * Exact manual request extraction only; no buffer, crafting, remote-tablet or generic PROXY admission.
 *
 * <p>Built on any installed RFTools Storage build (R1c). Known limitation on a changed build: if its scanner menu no
 * longer passes the binding's shape checks (for example the exact player inventory wrapper class), no binding is made
 * and RFTools Storage records nothing for the whole run while {@code match} answers SUPPORTED (Advisor M-3).
 * GF-WARNINGS (B48): each such menu is counted, with one warning per site after each server start, and the key stays
 * ON.</p>
 */
public final class RftoolsStorageMenuAdapter implements MenuTransactionAdapter {
    public static final String ID = "rftoolsstorage-6.0.5-scanner-request";
    @Override public String adapterId() { return ID; }
    @Override public boolean exact() { return true; }
    @Override public ObservationStrategy observationStrategy() { return ObservationStrategy.AUTHORITATIVE_OPERATION; }
    @Override public AdapterMatch match(MenuAdapterContext context) {
        if (context == null || !(context.menu() instanceof StorageScannerContainer)) return AdapterMatch.NO_MATCH;
        return context.menu().getClass() == StorageScannerContainer.class ? AdapterMatch.SUPPORTED : AdapterMatch.AMBIGUOUS;
    }
    static Optional<RftoolsScannerBinding> current(MenuAdapterContext context) {
        if (context == null || context.player() == null || context.menu() == null || context.menu().getClass() != StorageScannerContainer.class
                || !(context.menu() instanceof RftoolsMenuCarrier carrier)) return Optional.empty();
        var binding = carrier.eventtrail$rftoolsBinding();
        return binding != null && binding.player == context.player() && binding.menu == context.menu() && binding.current()
                ? Optional.of(binding) : Optional.empty();
    }
    @Override public Optional<ContainerTarget> resolveAuthoritativeTarget(MenuAdapterContext context) { return current(context).flatMap(RftoolsScannerBinding::target); }
    // Deliberately no access target: native proxy has no durable unique identity across load/replacement.
}
