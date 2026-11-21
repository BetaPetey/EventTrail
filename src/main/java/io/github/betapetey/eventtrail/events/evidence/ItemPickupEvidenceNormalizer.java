package io.github.betapetey.eventtrail.events.evidence;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditLocation;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.events.item.ItemAuditPayload;
import io.github.betapetey.eventtrail.events.item.ItemAuditPayloadCodec;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.EventTrailNbtUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.Function;

/** Maps committed accountable-possession evidence into EventTrail's existing row format. */
public final class ItemPickupEvidenceNormalizer {
    private ItemPickupEvidenceNormalizer() {
    }

    public static AuditRecord toAuditRecord(
            CommittedItemPickupEvidence evidence,
            boolean captureFullMetadata) {
        return toAuditRecord(evidence, captureFullMetadata, DisplayNameResolver::stackDisplayName);
    }

    static AuditRecord toAuditRecord(
            CommittedItemPickupEvidence evidence,
            boolean captureFullMetadata,
            Function<ItemStack, String> displayNameResolver) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(displayNameResolver, "displayNameResolver");
        ItemStack stack = evidence.stack();
        ActorProvenance actor = evidence.actor();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                .map(key -> key.location())
                .orElseThrow(() -> new IllegalArgumentException("Picked-up item is not registered"));
        String stackNbt = EventTrailNbtUtil.stackToAuditString(stack, captureFullMetadata);
        String displayName = null;
        try {
            displayName = displayNameResolver.apply(stack);
        } catch (RuntimeException | LinkageError ignored) {
            // Technical stack identity remains in extra_data. Presentation
            // failure must not discard an otherwise proven pickup row.
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = DisplayNameResolver.unresolvedItemDisplay(itemId.toString());
        }
        ItemAuditPayload payload = ItemAuditPayload.captured(
                ItemAuditPayload.Outcome.PICKUP,
                itemId,
                stack.getCount(),
                stack.getCount() + "x " + displayName,
                actor.actorKind() == ActorProvenance.ActorKind.SYNTHETIC_PLAYER,
                stackNbt);
        String presentation = payload.presentationIdentity();
        return AuditRecord.captureAt(
                evidence.timestamp(),
                AuditAction.ITEM_PICKUP,
                ActorAttributionAdapter.indexedActor(actor),
                new AuditLocation(evidence.dimensionId(), evidence.position()),
                new AuditSubject(AuditSubject.Kind.ITEM, itemId.toString(), presentation),
                new AuditEvidence.Item(
                        ItemAuditPayloadCodec.CURRENT_VERSION,
                        AuditEvidence.ItemOutcome.PICKUP,
                        ItemAuditPayloadCodec.encode(payload)));
    }
}
