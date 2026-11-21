package io.github.betapetey.eventtrail.inventory.spi;

import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import io.github.betapetey.eventtrail.inventory.core.InventorySlotRole;
import net.minecraft.world.inventory.Slot;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** A deterministic owner/provider for one family of inventory menus. */
public interface MenuTransactionAdapter {
    String adapterId();

    boolean exact();

    AdapterMatch match(MenuAdapterContext context);

    ObservationStrategy observationStrategy();

    /**
     * True only for an exact adapter that proves its real device slots directly
     * from the menu's bounded handlers rather than a vanilla Container.
     */
    default boolean usesHandlerBackedView() {
        return false;
    }

    /** Complete owner-derived handler cells/roles, revalidated before and after capture. */
    default Optional<MenuPhysicalBacking.Description> describePhysicalBacking(MenuAdapterContext context) {
        return Optional.empty();
    }

    /** Exact concrete storage whose access does not require a preceding block click. */
    default boolean usesIndependentBackingTarget() { return false; }

    /** Explicit channel producer; the core separately admits only the exact Ender Storage class. */
    default boolean usesSharedChannelBacking() { return false; }

    /** Opts into a retained native backing guard for OPEN/CLOSE, including access-only recording. */
    default boolean usesCapturedAccessAuthority() { return false; }

    default Optional<BooleanSupplier> captureAccessGuard(MenuAdapterContext context) {
        return Optional.empty();
    }

    /**
     * The close guard of an access-point target ({@link MenuAccessTarget#accessPoint()}): true while the terminal block,
     * part or carried item that was opened is still that same object. It is captured once at open, by an exact
     * operation-authoritative adapter, and needs no {@link #usesCapturedAccessAuthority()}. The tracker composes it with
     * the run generation, the adapter's run-time switch, the exact session and (for a terminal) the stationary-frame
     * check; an access-point target with no guard, or a guard false at open, writes no row.
     */
    default Optional<BooleanSupplier> captureAccessPointGuard(MenuAdapterContext context) {
        return Optional.empty();
    }

    /** Action-local conflict witness; shared custody requires one before any inventory read. */
    default Optional<BooleanSupplier> captureActionGuard(MenuAdapterContext context) {
        return Optional.empty();
    }

    /** Exact entity/member producer; never a generic entity-container admission. */
    default boolean usesMovingBackingTarget() { return false; }

    /** Original live backing plus native motion epoch, retained only by an OPEN session. */
    default Optional<BooleanSupplier> captureMovingAccessGuard(MenuAdapterContext context) {
        return Optional.empty();
    }

    /** Item/placed context from the active menu's authoritative host; refreshed per action. */
    default Optional<ContainerTarget> resolveMenuBackingTarget(MenuAdapterContext context) {
        return Optional.empty();
    }

    /**
     * Resolves the physical block actually owned by an exact handler-backed
     * menu. EventTrail compares this with the consumed interaction target so a
     * stale nearby click can never supply audit attribution.
     */
    default Optional<MenuPhysicalBlockTarget> resolvePhysicalBlockTarget(
            MenuAdapterContext context) {
        return Optional.empty();
    }

    /**
     * SOPH-LABELS: the adapter's own name, under the one name rule, for the physical container it resolved with
     * {@link #resolvePhysicalBlockTarget}. Asked only once the open has admitted that block. Empty (the default)
     * keeps EventTrail's own label. Must never throw; a failure here never skips the open or switches the adapter off
     * (the guard does not count it as an adapter failure), and the row is recorded with the block's own name.
     */
    default Optional<String> physicalContainerLabel(MenuAdapterContext context) {
        return Optional.empty();
    }

    /**
     * Resolves the access point for one exact, operation-authoritative virtual
     * storage session. Mobile targets are resolved again at action start.
     */
    default Optional<ContainerTarget> resolveAuthoritativeTarget(
            MenuAdapterContext context) {
        return Optional.empty();
    }

    /**
     * Resolves durable, privacy-safe target evidence for an access session.
     *
     * <p>This contract is intentionally stricter than
     * {@link #resolveAuthoritativeTarget(MenuAdapterContext)}. Short-lived
     * player/menu correlation may prove a committed transaction, but must not
     * be promoted into public OPEN/CLOSE backing identity.</p>
     *
     * <p>Two kinds of evidence are allowed: durable backing identity, or an
     * access point, the exact block, part or carried item the player opened
     * ({@link MenuAccessTarget#accessPoint()}). An access-point row names that
     * access point and claims nothing about the storage behind it; it needs
     * {@link #captureAccessPointGuard(MenuAdapterContext)}.</p>
     */
    default Optional<MenuAccessTarget> resolveContainerAccessTarget(
            MenuAdapterContext context) {
        return Optional.empty();
    }

    /** Classifies a menu slot; unknown custom slots deliberately fail closed. */
    default InventorySlotRole classifySlot(
            MenuAdapterContext context, int menuSlotIndex, Slot slot) {
        return InventorySlotRole.UNKNOWN;
    }

    /**
     * Supplies an exact backing reader when a custom menu slot is neither a
     * normal Container slot nor NeoForge SlotItemHandler.
     */
    default Optional<MenuSlotAccess> resolveSlotAccess(
            MenuAdapterContext context, int menuSlotIndex, Slot slot) {
        return Optional.empty();
    }

    /**
     * Supplies separately synchronized slots that the exact adapter has proven
     * represent real custody. The default is empty because blindly discovering
     * mod-owned virtual slots can confuse filters or configuration cells with
     * items the player actually moved.
     */
    default List<MenuObservedSlot> additionalObservedSlots(MenuAdapterContext context) {
        return List.of();
    }
}
