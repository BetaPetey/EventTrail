package io.github.betapetey.eventtrail.commands.presentation;

import io.github.betapetey.eventtrail.audit.AuditAttribution;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.commands.investigation.PageWindow;
import io.github.betapetey.eventtrail.events.block.ExplosionPartTag;
import io.github.betapetey.eventtrail.inventory.access.ContainerAccessPayloadCodec;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayload;
import io.github.betapetey.eventtrail.inventory.core.ContainerTransferPayloadCodec;
import io.github.betapetey.eventtrail.inventory.core.TransactionDirection;
import io.github.betapetey.eventtrail.util.AuditSnbtParser;
import io.github.betapetey.eventtrail.util.DisplayNameResolver;
import io.github.betapetey.eventtrail.util.EntityEventPayloadCodec;
import io.github.betapetey.eventtrail.util.EventTrailNbtUtil;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Converts audit facts into product-facing views without creating chat components. */
public final class AuditEntryPresenter {
    private final RelativeAgeFormatter ages;
    private final AuditActionPresentationCatalog actions;
    private final Function<CompoundTag, EventTrailNbtUtil.StackDecodeResult> stackDecoder;

    public AuditEntryPresenter(
            RelativeAgeFormatter ages,
            AuditActionPresentationCatalog actions) {
        this(ages, actions, EventTrailNbtUtil::decodeStackFromNbt);
    }

    AuditEntryPresenter(
            RelativeAgeFormatter ages,
            AuditActionPresentationCatalog actions,
            Function<CompoundTag, EventTrailNbtUtil.StackDecodeResult> stackDecoder) {
        this.ages = Objects.requireNonNull(ages, "ages");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.stackDecoder = Objects.requireNonNull(stackDecoder, "stackDecoder");
    }

    public LookupResultsView lookup(
            LookupSummaryView summary,
            PageWindow<AuditRecord> page,
            boolean showDimensions,
            NavigationTargetFactory navigationTargets) {
        return lookup(summary, page, showDimensions, navigationTargets, ExplosionVerdicts.none());
    }

    /** As {@link #lookup(LookupSummaryView, PageWindow, boolean, NavigationTargetFactory)}, with the page's verdicts. */
    public LookupResultsView lookup(
            LookupSummaryView summary,
            PageWindow<AuditRecord> page,
            boolean showDimensions,
            NavigationTargetFactory navigationTargets,
            ExplosionVerdicts verdicts) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(navigationTargets, "navigationTargets");
        Objects.requireNonNull(verdicts, "verdicts");
        List<AuditRowView> rows = new ArrayList<>(page.items().size());
        for (int index = 0; index < page.items().size(); index++) {
            AuditRecord record = page.items().get(index);
            Optional<String> target = navigationTargets.commandFor(record);
            rows.add(row(page.ordinal(index), record, showDimensions, target, verdicts));
        }
        return new LookupResultsView(summary, rows, navigation(page));
    }

    public InspectResultsView inspect(
            ResourceLocation dimensionId,
            BlockPos position,
            List<AuditRecord> snapshot,
            int requestedPage,
            int perPage) {
        return inspect(dimensionId, position, snapshot, requestedPage, perPage, ExplosionVerdicts.none());
    }

    /** As {@link #inspect(ResourceLocation, BlockPos, List, int, int)}, with the snapshot's verdicts. */
    public InspectResultsView inspect(
            ResourceLocation dimensionId,
            BlockPos position,
            List<AuditRecord> snapshot,
            int requestedPage,
            int perPage,
            ExplosionVerdicts verdicts) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(verdicts, "verdicts");
        PageWindow<AuditRecord> page = PageWindow.slice(snapshot, requestedPage, perPage);
        List<AuditRowView> rows = new ArrayList<>(page.items().size());
        for (int index = 0; index < page.items().size(); index++) {
            rows.add(row(page.ordinal(index), page.items().get(index), false, Optional.empty(), verdicts));
        }
        return new InspectResultsView(
                dimensionId, position, rows, navigation(page));
    }

    public AuditRowView row(
            long ordinal,
            AuditRecord record,
            boolean showDimension,
            Optional<String> navigationCommand) {
        return row(ordinal, record, showDimension, navigationCommand, ExplosionVerdicts.none());
    }

    /**
     * One row. Explosion split (design-02 3.1-3.2): a block row of an explosion stored in parts gets
     * " [explosion 3f9a1c part 2/5]" and its explosion's verdict; a part row gets the verdict after its stored text.
     */
    public AuditRowView row(
            long ordinal,
            AuditRecord record,
            boolean showDimension,
            Optional<String> navigationCommand,
            ExplosionVerdicts verdicts) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(verdicts, "verdicts");
        String indexedParty = record.attribution().indexedParty().canonicalName();
        if (record.attribution().indexedParty().kind()
                == AuditAttribution.IdentityKind.SYNTHETIC_PLAYER) {
            indexedParty += " (automated)";
        }
        return new AuditRowView(
                ordinal,
                ages.format(record.occurredAt()),
                indexedParty,
                actions.label(record),
                detail(record) + explosionSuffix(record, verdicts),
                new AuditRowView.RowLocation(
                        record.location().dimensionId(),
                        record.location().position(),
                        showDimension,
                        navigationCommand));
    }

    /** Explosion split: the tag label and verdict of a row of an explosion stored in parts; "" for any other row. */
    static String explosionSuffix(AuditRecord record, ExplosionVerdicts verdicts) {
        Optional<ExplosionPartTag> tag = ExplosionVerdicts.tagOf(record);
        if (tag.isEmpty()) return "";
        String verdict = verdicts.verdictFor(tag.get()).suffix();
        if (tag.get().form() == ExplosionPartTag.Form.PART) return verdict;
        return " [explosion " + tag.get().displayId() + " part " + tag.get().index() + "/" + tag.get().parts() + "]"
                + verdict;
    }

    private String detail(AuditRecord record) {
        if (record.evidence() instanceof AuditEvidence.ContainerTransfer transfer) {
            return transferDetail(record, transfer);
        }
        if (record.evidence() instanceof AuditEvidence.ContainerAccess access) {
            ContainerAccessPayloadCodec.AuditProjectionResult decoded =
                    ContainerAccessPayloadCodec.decodeAuditProjection(access.encodedPayload());
            return decoded.successful()
                    ? decoded.projection().subjectPresentationIdentity()
                    : record.subject().presentationIdentity()
                            + " (stored access metadata unavailable)";
        }
        if (record.evidence() instanceof AuditEvidence.Entity entity) {
            return entityDetail(record, entity);
        }
        return record.subject().presentationIdentity();
    }

    private String transferDetail(
            AuditRecord record,
            AuditEvidence.ContainerTransfer transfer) {
        String itemPresentation;
        ContainerTransferPayloadCodec.DecodeResult decoded =
                ContainerTransferPayloadCodec.decode(transfer.encodedPayload());
        if (decoded.successful() && transferMatches(decoded.payload(), transfer)) {
            String capturedName = capturedItemName(decoded.payload());
            // The registry identity survives intentional metadata omission, but
            // a lost custom name does not. Never imply full captured fidelity.
            itemPresentation = capturedName != null ? capturedName
                    : DisplayNameResolver.itemDisplayName(transfer.itemId().toString())
                            + metadataFidelitySuffix(decoded.payload());
        } else {
            itemPresentation = EventTrailTextBounds.literalDisplayText(transfer.itemId().toString())
                    + " (stored transfer metadata unavailable)";
        }
        return transfer.quantity() + "× " + itemPresentation
                + " • " + EventTrailTextBounds.containerTargetDisplayName(
                        record.subject().presentationIdentity());
    }

    private static String metadataFidelitySuffix(ContainerTransferPayload payload) {
        String reason = payload.metadataUnavailableReason();
        if (payload.metadataState() == ContainerTransferPayload.MetadataState.UNAVAILABLE) {
            if (EventTrailTextBounds.METADATA_REASON_TOO_LARGE.equals(reason)) {
                return " (metadata omitted: too large)";
            }
            if (EventTrailTextBounds.METADATA_REASON_BUFFER_PRESSURE.equals(reason)) {
                return " (metadata unavailable: writer pressure)";
            }
            if (EventTrailTextBounds.METADATA_REASON_CODEC_FAILURE.equals(reason)) {
                return " (metadata unavailable: codec failure)";
            }
        }
        return " (metadata unavailable)";
    }

    private static boolean transferMatches(
            ContainerTransferPayload payload,
            AuditEvidence.ContainerTransfer transfer) {
        TransactionDirection direction = transfer.direction()
                == AuditEvidence.TransferDirection.DEPOSIT
                ? TransactionDirection.DEPOSIT
                : TransactionDirection.WITHDRAWAL;
        return payload.direction() == direction
                && payload.itemId().equals(transfer.itemId())
                && payload.quantity() == transfer.quantity();
    }

    private String capturedItemName(ContainerTransferPayload payload) {
        if (payload.metadataState() != ContainerTransferPayload.MetadataState.CAPTURED) {
            return null;
        }
        try {
            AuditSnbtParser.ParseResult parsed =
                    AuditSnbtParser.parsePayload(payload.metadataSnbt(), 0);
            if (!parsed.successful()) return null;
            EventTrailNbtUtil.StackDecodeResult decoded =
                    stackDecoder.apply(parsed.tag());
            if (decoded.metadataUnavailable() || decoded.stack().isEmpty()) return null;
            return DisplayNameResolver.stackDisplayName(decoded.stack());
        } catch (RuntimeException | LinkageError | StackOverflowError ignored) {
            return null;
        }
    }

    private static String entityDetail(AuditRecord record, AuditEvidence.Entity evidence) {
        EntityEventPayloadCodec.PresentationProjectionResult decoded =
                EntityEventPayloadCodec.decodePresentationProjection(
                        record.subject().technicalIdentity(), evidence.encodedPayload());
        if (!decoded.successful()) {
            return record.subject().presentationIdentity()
                    + " (stored entity metadata unavailable)";
        }
        EntityEventPayloadCodec.PresentationProjection projection = decoded.projection();
        String detail;
        if (projection.rowKind() == EntityEventPayloadCodec.RowKind.KILL) {
            detail = switch (projection.subjectKind()) {
                case REDACTED_SYNTHETIC_PLAYER -> "Automated player";
                case SYNTHETIC_PLAYER -> projection.subjectPresentationIdentity()
                        + " (automated)";
                default -> projection.subjectPresentationIdentity();
            };
            detail += mechanismSuffix(projection);
        } else if (projection.provenance()
                == EntityEventPayloadCodec.PresentationProvenance.EXCLUDED_SYNTHETIC_PLAYER) {
            detail = "Killed by automated player";
        } else if (projection.provenance()
                == EntityEventPayloadCodec.PresentationProvenance.RETALIATION) {
            // Kill credit A (design-05c 4.2): the hit-back is the killer; no wearer and no weapon are shown.
            return "Killed by " + retaliationLabel(projection);
        } else if (projection.accountableActorPresentation() == null
                && projection.killingPresentation() != null
                && projection.viaPresentation() != null
                && (projection.provenance() == EntityEventPayloadCodec.PresentationProvenance.PET_OR_MINION
                || projection.ownerPresentation() != null)) {
            // 4.2's via form: the killing entity (the shooter of a projectile, otherwise the direct entity: the one
            // definition the owner attach uses, lead ruling 2), with its recorded owner, then the other entity.
            // PET_OR_MINION rows and rows carrying an owner only (review m7).
            detail = "Killed by " + projection.killingPresentation() + ownerSuffix(projection)
                    + " • via " + projection.viaPresentation()
                    + mechanismSuffix(projection);
        } else if (projection.accountableActorPresentation() != null) {
            detail = projection.provenance()
                    == EntityEventPayloadCodec.PresentationProvenance.ENGINE_KILL_CREDIT
                    ? "Death credited to " + projection.accountableActorPresentation()
                    : "Killed by " + projection.accountableActorPresentation();
            if (projection.accountableActorSynthetic()) detail += " (automated)";
            detail += mechanismSuffix(projection);
        } else if (projection.immediateCausePresentation() != null) {
            detail = "Killed by " + projection.immediateCausePresentation() + ownerSuffix(projection)
                    + mechanismSuffix(projection);
        } else {
            detail = "Damage: " + projection.damageType();
            if (projection.sourceIdentityUnavailable()) {
                detail += " (source identity unavailable)";
            }
        }
        return appendWeapon(detail, projection.weapon());
    }

    /**
     * 4.2: " (owned by <owner>)" when the payload records the killer's owner, otherwise nothing. A synthetic owner keeps
     * its own name and is marked " (automated)", as the actor labels above mark a synthetic actor (lead ruling 3 of
     * fold 02).
     */
    private static String ownerSuffix(EntityEventPayloadCodec.PresentationProjection projection) {
        if (projection.ownerPresentation() == null) return "";
        return " (owned by " + projection.ownerPresentation()
                + (projection.ownerSynthetic() ? " (automated)" : "") + ")";
    }

    /**
     * 4.2's RETALIATION label: the enchantment text captured at death (signal 1), else its id, else "Thorns" for
     * Thorns-type damage (signal 2), else "hit-back" (signal 3).
     */
    private static String retaliationLabel(EntityEventPayloadCodec.PresentationProjection projection) {
        if (projection.retaliationName() != null) return projection.retaliationName();
        if (projection.retaliationEffect() != null) return projection.retaliationEffect();
        if ("minecraft:thorns".equals(projection.damageType())) return "Thorns";
        return "hit-back";
    }

    private static String mechanismSuffix(
            EntityEventPayloadCodec.PresentationProjection projection) {
        return switch (projection.provenance()) {
            case PLAYER_PROJECTILE -> " • via "
                    + (projection.mechanism() == null ? "projectile" : projection.mechanism());
            case TNT_IGNITER -> " • via TNT";
            default -> "";
        };
    }

    private static String appendWeapon(
            String detail,
            EntityEventPayloadCodec.WeaponPresentation weapon) {
        if (weapon == null) return detail;
        String value = detail + " • " + weapon.count() + "× "
                + weapon.presentationIdentity();
        return switch (weapon.metadataStatus()) {
            case CAPTURED -> value;
            case UNAVAILABLE -> value + " (metadata unavailable)";
            case TOO_LARGE -> value + " (metadata omitted: too large)";
        };
    }

    @FunctionalInterface
    public interface NavigationTargetFactory {
        Optional<String> commandFor(AuditRecord record);
    }

    private static NavigationView navigation(PageWindow<?> page) {
        String pagePrefix = "/eventtrail page ";
        return new NavigationView(
                page.page(), page.totalPages(),
                page.hasPrevious() ? Optional.of(pagePrefix + (page.page() - 1)) : Optional.empty(),
                page.hasNext() ? Optional.of(pagePrefix + (page.page() + 1)) : Optional.empty());
    }
}
