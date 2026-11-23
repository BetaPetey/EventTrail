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

/** Maps one admitted discarded stack into an immutable committed item fact. */
public final class ItemDropEvidenceNormalizer {
    private ItemDropEvidenceNormalizer() {
    }

    public static AuditRecord toAuditRecord(
            CommittedItemDropEvidence evidence,
            boolean captureFullMetadata) {
        Objects.requireNonNull(evidence, "evidence");
        ItemStack stack = evidence.stack();
        ActorProvenance actor = evidence.actor();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getResourceKey(stack.getItem())
                .map(key -> key.location())
                .orElseThrow(() -> new IllegalArgumentException("Dropped item is not registered"));
        String stackNbt = EventTrailNbtUtil.stackToAuditString(stack, captureFullMetadata);
        String displayName = DisplayNameResolver.stackDisplayName(stack);
        if (displayName == null || displayName.isBlank()) {
            displayName = DisplayNameResolver.unresolvedItemDisplay(itemId.toString());
        }
        String presentation = stack.getCount() + "x " + displayName;
        ItemAuditPayload payload = ItemAuditPayload.captured(
                ItemAuditPayload.Outcome.DROP,
                itemId,
                stack.getCount(),
                presentation,
                actor.actorKind() == ActorProvenance.ActorKind.SYNTHETIC_PLAYER,
                stackNbt);
        return AuditRecord.captureAt(
                evidence.timestamp(),
                AuditAction.ITEM_DROP,
                ActorAttributionAdapter.indexedActor(actor),
                new AuditLocation(evidence.dimensionId(), evidence.position()),
                new AuditSubject(AuditSubject.Kind.ITEM, itemId.toString(), presentation),
                new AuditEvidence.Item(
                        ItemAuditPayloadCodec.CURRENT_VERSION,
                        AuditEvidence.ItemOutcome.DROP,
                        ItemAuditPayloadCodec.encode(payload)));
    }
}
