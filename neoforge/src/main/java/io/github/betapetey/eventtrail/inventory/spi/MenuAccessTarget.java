package io.github.betapetey.eventtrail.inventory.spi;

import io.github.betapetey.eventtrail.inventory.core.ContainerTarget;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Exact target evidence for a public container-access fact.
 *
 * <p>This is deliberately separate from transaction correlation. A virtual
 * adapter may have enough short-lived context to attribute a committed item
 * operation while still lacking a durable, privacy-safe backing identity for
 * an OPEN/CLOSE audit session.</p>
 *
 * <p>Two kinds of access evidence are allowed (the access-row identity rule):</p>
 * <ul>
 *   <li>durable backing identity ({@code accessPoint == false}): the row names the storage the menu shows;</li>
 *   <li>an access point ({@code accessPoint == true}): the exact block, part or carried item the player opened. Such a
 *   row names that access point, at the terminal block's position or at the player, with the game's own name for it,
 *   and claims nothing about the storage behind it. Its backing reference names the access point (position, plus side
 *   for a part), not one physical object over time: a terminal replaced at the same position and side gets the same
 *   backing reference, and the exact-menu session id pairs OPEN with CLOSE. An access-point target is recorded only
 *   with a close guard ({@link MenuTransactionAdapter#captureAccessPointGuard(MenuAdapterContext)}).</li>
 * </ul>
 */
public record MenuAccessTarget(
        ContainerTarget target,
        String technicalTargetId,
        boolean accessPoint) {

    public MenuAccessTarget {
        target = Objects.requireNonNull(target, "target");
        ResourceLocation parsed = technicalTargetId == null
                ? null
                : ResourceLocation.tryParse(technicalTargetId);
        if (technicalTargetId == null
                || technicalTargetId.isBlank()
                || parsed == null
                || !parsed.toString().equals(technicalTargetId)) {
            throw new IllegalArgumentException(
                    "technicalTargetId must be an exact namespaced registry identifier");
        }
    }

    /** Durable backing identity, as every access target before access points. */
    public MenuAccessTarget(ContainerTarget target, String technicalTargetId) {
        this(target, technicalTargetId, false);
    }
}
