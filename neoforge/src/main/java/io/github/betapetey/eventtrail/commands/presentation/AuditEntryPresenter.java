package io.github.betapetey.eventtrail.commands.presentation;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditAttribution;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditRecord;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import io.github.betapetey.eventtrail.commands.investigation.PageWindow;
import io.github.betapetey.eventtrail.events.block.BlockEntityEvidence;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts audit facts into product-facing views without creating chat components. */
public final class AuditEntryPresenter {
    /** The note a trash target's rows carry (GF-TRASHTARGET): the row names a trash, never a destroyed item. */
    public static final String TRASH_NOTE = " (trash)";
    private static final String RENAMED_MARKER = " (renamed \"";
    private static final Pattern COUNTED_ITEM = Pattern.compile("(\\d{1,10})[x×] (.+)", Pattern.DOTALL);
    private static final RowPart SPACE = new RowPart.Joiner(" ");

    private final RelativeAgeFormatter ages;
    private final AuditActionPresentationCatalog actions;
    private final Function<CompoundTag, EventTrailNbtUtil.StackDecodeResult> stackDecoder;
    private final GraveWording graves;

    public AuditEntryPresenter(
            RelativeAgeFormatter ages,
            AuditActionPresentationCatalog actions) {
        this(ages, actions, EventTrailNbtUtil::decodeStackFromNbt);
    }

    AuditEntryPresenter(
            RelativeAgeFormatter ages,
            AuditActionPresentationCatalog actions,
            Function<CompoundTag, EventTrailNbtUtil.StackDecodeResult> stackDecoder) {
        this(ages, actions, stackDecoder, GraveWording.production());
    }

    /** Test seam: as the three-argument form, with the grave wording's mod-name lookup given. */
    AuditEntryPresenter(
            RelativeAgeFormatter ages,
            AuditActionPresentationCatalog actions,
            Function<CompoundTag, EventTrailNbtUtil.StackDecodeResult> stackDecoder,
            GraveWording graves) {
        this.ages = Objects.requireNonNull(ages, "ages");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.stackDecoder = Objects.requireNonNull(stackDecoder, "stackDecoder");
        this.graves = Objects.requireNonNull(graves, "graves");
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
        return inspect(dimensionId, position, snapshot, requestedPage, perPage, verdicts, 0L);
    }

    /**
     * As above, with the inspect query's time in ms ("N results · Query: N ms"; a later page of the same snapshot shows
     * the first query's ms). The result count is the whole snapshot.
     */
    public InspectResultsView inspect(
            ResourceLocation dimensionId,
            BlockPos position,
            List<AuditRecord> snapshot,
            int requestedPage,
            int perPage,
            ExplosionVerdicts verdicts,
            long queryMillis) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(verdicts, "verdicts");
        PageWindow<AuditRecord> page = PageWindow.slice(snapshot, requestedPage, perPage);
        List<AuditRowView> rows = new ArrayList<>(page.items().size());
        for (int index = 0; index < page.items().size(); index++) {
            rows.add(row(page.ordinal(index), page.items().get(index), false, Optional.empty(), verdicts));
        }
        return new InspectResultsView(
                dimensionId, position, rows, navigation(page), snapshot.size(), Math.max(0L, queryMillis),
                Optional.empty());
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
     * The flat {@code detail} and Title Case label are kept for existing readers; chat shows the structured parts.
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
        // From the stored identity kind, never from the name text: only an environment actor is not a player.
        boolean partyIsPlayer = record.attribution().indexedParty().kind()
                != AuditAttribution.IdentityKind.ENVIRONMENT;
        String suffix = explosionSuffix(record, verdicts);
        return new AuditRowView(
                ordinal,
                ages.format(record.occurredAt()),
                indexedParty,
                actions.label(record),
                detail(record) + suffix,
                new AuditRowView.RowLocation(
                        record.location().dimensionId(),
                        record.location().position(),
                        showDimension,
                        navigationCommand),
                Optional.of(record.occurredAt()),
                actions.verb(record),
                parts(record, suffix),
                partyIsPlayer);
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

    // ------------------------------------------------------------------------------------------ structured parts

    private List<RowPart> parts(AuditRecord record, String explosionSuffix) {
        List<RowPart> parts = new ArrayList<>();
        if (record.evidence() instanceof AuditEvidence.ContainerTransfer transfer) {
            transferParts(record, transfer, parts);
        } else if (record.evidence() instanceof AuditEvidence.ContainerAccess access) {
            accessParts(record, access, parts);
        } else if (record.evidence() instanceof AuditEvidence.Entity entity) {
            entityParts(record, entity, parts);
        } else if (record.evidence() instanceof AuditEvidence.Item) {
            itemParts(record, parts);
        } else {
            subjectParts(record, parts);
        }
        if (!explosionSuffix.isEmpty()) parts.add(new RowPart.Note(explosionSuffix));
        return parts;
    }

    /** Blocks, operations and rows whose evidence is unavailable: the subject as stored. */
    private static void subjectParts(AuditRecord record, List<RowPart> parts) {
        AuditSubject subject = record.subject();
        parts.add(SPACE);
        switch (subject.kind()) {
            case BLOCK -> parts.add(blockThing(record));
            case OPERATION -> parts.add(new RowPart.Note(subject.presentationIdentity()));
            case CONTAINER -> parts.add(thing(subject.presentationIdentity(), subject.technicalIdentity(),
                    RowPart.Role.CONTAINER));
            default -> parts.add(thing(subject.presentationIdentity(), subject.technicalIdentity(),
                    RowPart.Role.OBJECT));
        }
    }

    /**
     * A block's real name and any custom name (plan G1, display-time fix for old rows too): the stored name when it
     * already holds both ({@code Chest (renamed "X")}); otherwise, when the stored block-entity evidence names the
     * block differently from its registry name, that name is the custom name and the registry name the real one.
     */
    static RowPart.Thing blockThing(AuditRecord record) {
        String technical = record.subject().technicalIdentity();
        String shown = record.subject().presentationIdentity();
        String id = blockId(technical);
        Split split = splitRenamed(shown);
        if (split.custom() != null) return new RowPart.Thing(split.real(), split.custom(), id, RowPart.Role.OBJECT);
        if (record.evidence() instanceof AuditEvidence.Block block) {
            try {
                String withEvidence = DisplayNameResolver.blockStateDisplayName(technical, block.encodedPayload());
                String registryName = DisplayNameResolver.capturedBlockStateDisplayName(
                        technical, BlockEntityEvidence.absent());
                if (withEvidence != null && registryName != null && !withEvidence.equals(registryName)) {
                    Split stored = splitRenamed(withEvidence);
                    return stored.custom() != null
                            ? new RowPart.Thing(stored.real(), stored.custom(), id, RowPart.Role.OBJECT)
                            : new RowPart.Thing(registryName, withEvidence, id, RowPart.Role.OBJECT);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // The stored name below is still shown whole; a lost custom name is never invented.
            }
        }
        return new RowPart.Thing(shown, null, id, RowPart.Role.OBJECT);
    }

    /** "ns:path" from a serialized block state "ns:path[props]"; null when it is not a registry ID. */
    static String blockId(String serializedState) {
        if (serializedState == null) return null;
        int properties = serializedState.indexOf('[');
        String id = properties < 0 ? serializedState : serializedState.substring(0, properties);
        return RowPart.Thing.validId(id) ? id : null;
    }

    /**
     * Drops and pick-ups: "3x Diamond (renamed "X")" as stored becomes 3×, the item and its ID. The count always shows,
     * a count of one included ("dropped 1× Torch", "dropped 7× Torch"), as it does on every other row with an item
     * count (a standing Owner rule). A stored count below one shows no count part (there is no such row to show).
     */
    private static void itemParts(AuditRecord record, List<RowPart> parts) {
        String shown = record.subject().presentationIdentity();
        String id = record.subject().technicalIdentity();
        parts.add(SPACE);
        Matcher counted = COUNTED_ITEM.matcher(shown);
        if (counted.matches()) {
            long count = Long.parseLong(counted.group(1));
            if (count >= 1L) parts.add(new RowPart.Count(count));
            parts.add(thing(counted.group(2), id, RowPart.Role.OBJECT));
        } else {
            parts.add(thing(shown, id, RowPart.Role.OBJECT));
        }
    }

    private void accessParts(AuditRecord record, AuditEvidence.ContainerAccess access, List<RowPart> parts) {
        String containerId = record.subject().technicalIdentity();
        parts.add(SPACE);
        ContainerAccessPayloadCodec.AuditProjectionResult decoded =
                ContainerAccessPayloadCodec.decodeAuditProjection(access.encodedPayload());
        if (decoded.successful()) {
            parts.add(thing(decoded.projection().subjectPresentationIdentity(), containerId, RowPart.Role.CONTAINER));
        } else {
            parts.add(thing(record.subject().presentationIdentity(), containerId, RowPart.Role.CONTAINER));
            parts.add(new RowPart.Note(" (stored access metadata unavailable)"));
        }
        if (actions.trashRow(record)) parts.add(new RowPart.Note(TRASH_NOTE));
        else if (actions.itemsNotTracked(record)) {
            parts.add(new RowPart.Note(AuditActionPresentationCatalog.ITEMS_NOT_TRACKED_SUFFIX));
        }
    }

    private void transferParts(AuditRecord record, AuditEvidence.ContainerTransfer transfer, List<RowPart> parts) {
        String itemId = transfer.itemId().toString();
        boolean trash = actions.trashRow(record);
        parts.add(SPACE);
        parts.add(new RowPart.Count(transfer.quantity()));
        ContainerTransferPayloadCodec.DecodeResult decoded =
                ContainerTransferPayloadCodec.decode(transfer.encodedPayload());
        Optional<String> graveText = Optional.empty();
        if (decoded.successful() && transferMatches(decoded.payload(), transfer)) {
            String capturedName = capturedItemName(decoded.payload());
            if (capturedName != null) {
                parts.add(thing(capturedName, itemId, RowPart.Role.OBJECT));
            } else {
                parts.add(new RowPart.Thing(DisplayNameResolver.itemDisplayName(itemId), null, itemId,
                        RowPart.Role.OBJECT));
                parts.add(new RowPart.Note(metadataFidelitySuffix(decoded.payload())));
            }
            // Graves design r6, 1.9: a grave row says whose grave it was instead of the container (as the flat detail).
            graveText = graves.transfer(decoded.payload().providerId(), decoded.payload().shownCause(),
                    record.attribution().indexedParty().playerId());
        } else {
            parts.add(new RowPart.Thing(EventTrailTextBounds.literalDisplayText(itemId), null, itemId,
                    RowPart.Role.OBJECT));
            parts.add(new RowPart.Note(" (stored transfer metadata unavailable)"));
        }
        if (graveText.isPresent()) {
            // "took 3× Diamond from Bob's grave (GraveStone)"; the wordings that do not start with "from" ("Bob's
            // items, from a grave") follow a comma, as the flat detail's " • " reads elsewhere in Design B.
            String text = graveText.get();
            parts.add(new RowPart.Joiner(text.startsWith("from ") ? " " : ", "));
            parts.add(new RowPart.Note(text));
            return;
        }
        boolean deposit = transfer.direction() == AuditEvidence.TransferDirection.DEPOSIT;
        parts.add(new RowPart.Joiner(deposit ? (trash ? " into " : " to ") : " from "));
        // Plan G8: the transfer's backing reference gets a hover only when it is a registry ID (never a wrong ID).
        parts.add(thing(EventTrailTextBounds.containerTargetDisplayName(record.subject().presentationIdentity()),
                record.subject().technicalIdentity(), RowPart.Role.CONTAINER));
        if (trash) parts.add(new RowPart.Note(TRASH_NOTE));
    }

    private static void entityParts(AuditRecord record, AuditEvidence.Entity evidence, List<RowPart> parts) {
        EntityEventPayloadCodec.PresentationProjectionResult decoded =
                EntityEventPayloadCodec.decodePresentationProjection(
                        record.subject().technicalIdentity(), evidence.encodedPayload());
        boolean kill = record.action() == AuditAction.ENTITY_KILL;
        if (!decoded.successful()) {
            if (kill) {
                parts.add(SPACE);
                parts.add(thing(record.subject().presentationIdentity(), record.subject().technicalIdentity(),
                        RowPart.Role.ENTITY));
            }
            parts.add(new RowPart.Note(" (stored entity metadata unavailable)"));
            return;
        }
        EntityEventPayloadCodec.PresentationProjection projection = decoded.projection();
        if (projection.rowKind() == EntityEventPayloadCodec.RowKind.KILL) {
            parts.add(SPACE);
            switch (projection.subjectKind()) {
                case REDACTED_SYNTHETIC_PLAYER -> parts.add(new RowPart.Name("automated player"));
                case SYNTHETIC_PLAYER -> {
                    parts.add(new RowPart.Name(projection.subjectPresentationIdentity()));
                    parts.add(new RowPart.Note(" (automated)"));
                }
                case PLAYER -> parts.add(new RowPart.Name(projection.subjectPresentationIdentity()));
                default -> parts.add(thing(projection.subjectPresentationIdentity(),
                        record.subject().technicalIdentity(), RowPart.Role.ENTITY));
            }
            mechanismParts(projection, parts);
        } else if (projection.provenance()
                == EntityEventPayloadCodec.PresentationProvenance.EXCLUDED_SYNTHETIC_PLAYER) {
            parts.add(new RowPart.Joiner(", killed by "));
            parts.add(new RowPart.Name("automated player"));
        } else if (projection.provenance()
                == EntityEventPayloadCodec.PresentationProvenance.RETALIATION) {
            // Kill credit A (design-05c 4.2): the hit-back is the killer; no wearer and no weapon are shown.
            parts.add(new RowPart.Joiner(", killed by "));
            parts.add(new RowPart.Note(retaliationLabel(projection)));
            return;
        } else if (projection.accountableActorPresentation() == null
                && projection.killingPresentation() != null
                && projection.viaPresentation() != null
                && (projection.provenance() == EntityEventPayloadCodec.PresentationProvenance.PET_OR_MINION
                || projection.ownerPresentation() != null)) {
            // 4.2's via form: the killing entity with its recorded owner, then the other entity.
            parts.add(new RowPart.Joiner(", killed by "));
            parts.add(thing(projection.killingPresentation(), null, RowPart.Role.ENTITY));
            ownerParts(projection, parts);
            parts.add(new RowPart.Joiner(" via "));
            parts.add(thing(projection.viaPresentation(), null, RowPart.Role.ENTITY));
            mechanismParts(projection, parts);
        } else if (projection.accountableActorPresentation() != null) {
            parts.add(new RowPart.Joiner(projection.provenance()
                    == EntityEventPayloadCodec.PresentationProvenance.ENGINE_KILL_CREDIT
                    ? ", death credited to " : ", killed by "));
            parts.add(new RowPart.Name(projection.accountableActorPresentation()));
            if (projection.accountableActorSynthetic()) parts.add(new RowPart.Note(" (automated)"));
            mechanismParts(projection, parts);
        } else if (projection.immediateCausePresentation() != null) {
            parts.add(new RowPart.Joiner(", killed by "));
            parts.add(thing(projection.immediateCausePresentation(), null, RowPart.Role.ENTITY));
            ownerParts(projection, parts);
            mechanismParts(projection, parts);
        } else {
            parts.add(new RowPart.Joiner(", damage: "));
            parts.add(new RowPart.Note(projection.damageType()));
            if (projection.sourceIdentityUnavailable()) {
                parts.add(new RowPart.Note(" (source identity unavailable)"));
            }
            // Owner Q265 (a): a plain note on the no-credit death row only, never a kill; no hover (Q266 B).
            if (projection.lastHitByPresentation() != null) {
                parts.add(new RowPart.Joiner(", last hit by "));
                parts.add(new RowPart.Name(projection.lastHitByPresentation()));
            }
        }
        weaponParts(projection.weapon(), parts);
    }

    private static void ownerParts(EntityEventPayloadCodec.PresentationProjection projection, List<RowPart> parts) {
        if (projection.ownerPresentation() == null) return;
        parts.add(new RowPart.Note(" (owned by " + projection.ownerPresentation()
                + (projection.ownerSynthetic() ? " (automated)" : "") + ")"));
    }

    private static void mechanismParts(EntityEventPayloadCodec.PresentationProjection projection, List<RowPart> parts) {
        switch (projection.provenance()) {
            case PLAYER_PROJECTILE -> {
                parts.add(new RowPart.Joiner(" via "));
                parts.add(new RowPart.Note(projection.mechanism() == null ? "projectile" : projection.mechanism()));
            }
            case TNT_IGNITER -> {
                parts.add(new RowPart.Joiner(" via "));
                parts.add(new RowPart.Note("TNT"));
            }
            default -> { }
        }
    }

    /**
     * " with Diamond Sword" (the mock's form), or " holding Diamond Sword" for a held-item weapon (Owner K2, as the
     * flat detail); the weapon's plain name with no count (the Owner's name rule, 11-22), its ID on hover.
     */
    private static void weaponParts(EntityEventPayloadCodec.WeaponPresentation weapon, List<RowPart> parts) {
        if (weapon == null) return;
        parts.add(new RowPart.Joiner(weapon.held() ? " holding " : " with "));
        parts.add(thing(weapon.presentationIdentity(), weapon.technicalIdentity(), RowPart.Role.WEAPON));
        switch (weapon.metadataStatus()) {
            case CAPTURED -> { }
            case UNAVAILABLE -> parts.add(new RowPart.Note(" (metadata unavailable)"));
            case TOO_LARGE -> parts.add(new RowPart.Note(" (metadata omitted: too large)"));
        }
    }

    /** A thing from its stored name: {@code real (renamed "custom")} splits into the real and the custom name. */
    static RowPart.Thing thing(String shown, String id, RowPart.Role role) {
        Split split = splitRenamed(shown);
        return new RowPart.Thing(split.real(), split.custom(), id, role);
    }

    /** The two names of a stored {@code real (renamed "custom")} name (DisplayNameResolver's form), or none. */
    record Split(String real, String custom) {}

    static Split splitRenamed(String shown) {
        if (shown == null || !shown.endsWith("\")")) return new Split(shown, null);
        int from = 1;
        while (true) {
            int at = shown.indexOf(RENAMED_MARKER, from);
            if (at < 0) return new Split(shown, null);
            int start = at + RENAMED_MARKER.length();
            int end = shown.length() - 2;
            if (start <= end) {
                String custom = unescape(shown.substring(start, end));
                if (custom != null && !custom.isEmpty()) return new Split(shown.substring(0, at), custom);
            }
            from = at + 1;
        }
    }

    /** Undoes DisplayNameResolver.quoted(): \\ and \" only; null when the text is not one quoted value. */
    private static String unescape(String escaped) {
        StringBuilder out = new StringBuilder(escaped.length());
        for (int index = 0; index < escaped.length(); index++) {
            char ch = escaped.charAt(index);
            if (ch == '"') return null;
            if (ch == '\\') {
                if (index + 1 >= escaped.length()) return null;
                char next = escaped.charAt(++index);
                if (next != '\\' && next != '"') return null;
                out.append(next);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------ flat detail (kept)

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
        Optional<String> graveText = Optional.empty();
        ContainerTransferPayloadCodec.DecodeResult decoded =
                ContainerTransferPayloadCodec.decode(transfer.encodedPayload());
        if (decoded.successful() && transferMatches(decoded.payload(), transfer)) {
            String capturedName = capturedItemName(decoded.payload());
            // The registry identity survives intentional metadata omission, but
            // a lost custom name does not. Never imply full captured fidelity.
            itemPresentation = capturedName != null ? capturedName
                    : DisplayNameResolver.itemDisplayName(transfer.itemId().toString())
                            + metadataFidelitySuffix(decoded.payload());
            // Graves design r6, 1.9: a grave row's cause says whose grave it was instead of the container label.
            graveText = graves.transfer(decoded.payload().providerId(), decoded.payload().shownCause(),
                    record.attribution().indexedParty().playerId());
        } else {
            itemPresentation = EventTrailTextBounds.literalDisplayText(transfer.itemId().toString())
                    + " (stored transfer metadata unavailable)";
        }
        return transfer.quantity() + "× " + itemPresentation
                + " • " + graveText.orElseGet(() -> EventTrailTextBounds.containerTargetDisplayName(
                        record.subject().presentationIdentity()));
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
            // Owner Q265 (a): a plain note, never a kill; the codec gives it only on a no-credit death row.
            if (projection.lastHitByPresentation() != null) {
                detail += " • last hit by " + projection.lastHitByPresentation();
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
        // Owner K2: a held-item weapon reads "holding X" from the start. The Owner's name rule (11-22): the weapon's
        // plain name, with no count ("holding Diamond Sword", not "holding 1× Diamond Sword").
        String value = detail + (weapon.held() ? " • holding " : " • ") + weapon.presentationIdentity();
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
