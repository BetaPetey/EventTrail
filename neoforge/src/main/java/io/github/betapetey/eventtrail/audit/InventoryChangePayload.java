package io.github.betapetey.eventtrail.audit;

import io.github.betapetey.eventtrail.audit.AuditPayloadCodec.IndexedPartyBinding;
import io.github.betapetey.eventtrail.audit.AuditPayloadCodec.OccurrenceConstraint;
import io.github.betapetey.eventtrail.events.block.ExplosionPartTag;
import io.github.betapetey.eventtrail.inventory.onplayer.OnPlayerSource;
import io.github.betapetey.eventtrail.util.AuditSnbtParser;
import io.github.betapetey.eventtrail.util.EventTrailTextBounds;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable semantic content of one on-player inventory change (GF-ONPLAYER design 3.2): an ITEM_GAINED or ITEM_LOST
 * row on the player whose inventory changed. Nobody is credited, so the payload has no actor field. What moved the
 * items is the required {@link ShownCause}, which may never be the marked UNIDENTIFIED cause (design 4.5 condition 3).
 *
 * <p>Fields, in the order {@link InventoryChangePayloadCodec} writes them: the holder binding, the row's place
 * ({@code at}), its window, the direction, the item (id, captured display name, quantity and three-state metadata, as
 * ContainerTransferPayload holds them), the holder side, the other side, the source, the cause, and the optional
 * device, device owner, fluid and part.</p>
 *
 * <p>The item display name is the row subject's presentation identity (design 3.1: "the captured stack name ...
 * stored as-is"). It is kept here because the payload is the only place a row stores it.</p>
 */
public record InventoryChangePayload(
        IndexedPartyBinding holder,
        AuditLocation at,
        Window window,
        Direction direction,
        ResourceLocation itemId,
        String itemDisplay,
        int quantity,
        MetadataState metadataState,
        String metadataSnbt,
        String metadataUnavailableReason,
        HolderSide holderSide,
        OtherSide otherSide,
        OnPlayerSource source,
        ShownCause cause,
        Device device,
        ShownCause.Owner deviceOwner,
        Fluid fluid,
        Part part) {

    /** The bound on an unavailable-metadata reason, as ContainerTransferPayload bounds it. */
    public static final int MAX_REASON_CHARS = 96;

    public InventoryChangePayload {
        holder = Objects.requireNonNull(holder, "holder");
        at = Objects.requireNonNull(at, "at");
        window = Objects.requireNonNull(window, "window");
        direction = Objects.requireNonNull(direction, "direction");
        itemId = Objects.requireNonNull(itemId, "itemId");
        itemDisplay = boundedDisplay(itemDisplay, "itemDisplay");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        metadataState = Objects.requireNonNull(metadataState, "metadataState");
        holderSide = Objects.requireNonNull(holderSide, "holderSide");
        otherSide = Objects.requireNonNull(otherSide, "otherSide");
        source = Objects.requireNonNull(source, "source");
        cause = Objects.requireNonNull(cause, "cause");
        if (cause.form() != ShownCause.Form.NAMED) {
            throw new IllegalArgumentException("An on-player row never carries the marked cause");
        }
        // The device owner is the owner of the device the cause names. It needs a device, and it must be the same
        // owner the shown cause carries, so the stored owner and the shown cause can never disagree.
        if (deviceOwner != null) {
            if (device == null) {
                throw new IllegalArgumentException("deviceOwner needs a device");
            }
            if (!deviceOwner.equals(cause.owner())) {
                throw new IllegalArgumentException("deviceOwner must equal the shown cause's owner");
            }
        }
        // Design 05 (:586): "`part` (new): optional {part index, part count}. Used only by a split scanner seizure
        // (3.14)." A part on any other source is refused.
        if (part != null && source != OnPlayerSource.INVENTORY_SCANNER) {
            throw new IllegalArgumentException("part is used only by a split scanner seizure");
        }

        if (metadataState == MetadataState.CAPTURED) {
            metadataSnbt = requireMetadata(metadataSnbt, itemId, quantity);
            metadataUnavailableReason = null;
        } else {
            metadataSnbt = null;
            metadataUnavailableReason = requireText(metadataUnavailableReason, "metadataUnavailableReason");
            if (metadataUnavailableReason.length() > MAX_REASON_CHARS) {
                throw new IllegalArgumentException("metadataUnavailableReason is too long");
            }
        }
    }

    /** The row's exact location: {@code at} (design 3.3), so AuditRecord's location check holds. */
    public AuditLocation exactLocation() {
        return at;
    }

    /** The row's occurrence: exactly the window's first change (design 3.13: occurredAt = firstAt). */
    public OccurrenceConstraint occurrenceConstraint() {
        return OccurrenceConstraint.exact(window.firstAtMillis());
    }

    /** GAINED for ITEM_GAINED, LOST for ITEM_LOST. */
    public enum Direction { GAINED, LOST }

    /** The same three metadata states as ContainerTransferPayload: captured SNBT, or an explicit unavailable reason. */
    public enum MetadataState { CAPTURED, UNAVAILABLE }

    /**
     * When the row's changes happened (design 3.13). One change has firstAt = lastAt and changeCount = 1; a coalesced
     * window has changeCount above 1 and lastAt not before firstAt.
     */
    public record Window(long firstAtMillis, long lastAtMillis, int changeCount) {
        public Window {
            if (firstAtMillis < 0L) throw new IllegalArgumentException("firstAtMillis must not be negative");
            if (lastAtMillis < firstAtMillis) throw new IllegalArgumentException("lastAtMillis is before firstAtMillis");
            if (changeCount < 1) throw new IllegalArgumentException("changeCount must be at least 1");
            if (changeCount == 1 && lastAtMillis != firstAtMillis) {
                throw new IllegalArgumentException("One change has one time");
            }
        }

        public static Window single(long atMillis) {
            return new Window(atMillis, atMillis, 1);
        }
    }

    /** Which part of the player's inventory changed. */
    public enum Section { MAIN, ARMOR, OFFHAND, CURIO, UNKNOWN }

    /** AE2, RS2 or another network, for a remote-storage other side. */
    public enum NetworkKind { AE2, RS2, OTHER }

    /**
     * The holder's side of the change. There is no network form: no on-player row has a holder-network side (decision
     * 31 = A, design M-5), and the codec refuses its reserved code.
     */
    public sealed interface HolderSide permits PlayerInventory, CarriedContainer {}

    /** The player's own inventory. */
    public record PlayerInventory(Section section) implements HolderSide {
        public PlayerInventory {
            section = Objects.requireNonNull(section, "section");
        }
    }

    /**
     * A container item the player carries (a backpack), named by its item id and captured display name, and by either
     * its contents uuid or the slot that holds it: exactly one of {@code containerUuid} and {@code slot}.
     */
    public record CarriedContainer(
            ResourceLocation containerItemId,
            String containerDisplay,
            UUID containerUuid,
            Integer slot) implements HolderSide {
        public CarriedContainer {
            containerItemId = Objects.requireNonNull(containerItemId, "containerItemId");
            containerDisplay = boundedDisplay(containerDisplay, "containerDisplay");
            if ((containerUuid == null) == (slot == null)) {
                throw new IllegalArgumentException("A carried container is named by its uuid or its slot, not both");
            }
            if (slot != null && slot < 0) throw new IllegalArgumentException("slot must not be negative");
        }
    }

    /** Where the items came from or went (design 3.2 field 4): ten forms. */
    public sealed interface OtherSide permits DeviceStorage, DroppedAtDevice, Destroyed, Unresolved, Consumed,
            GroundItem, EntitySide, RemoteStorage, Transformed, OwnCarriedContainer {}

    /** The device's own storage at a place. */
    public record DeviceStorage(AuditLocation at) implements OtherSide {
        public DeviceStorage {
            at = Objects.requireNonNull(at, "at");
        }
    }

    /** Dropped into the world at the device's place. */
    public record DroppedAtDevice(AuditLocation at) implements OtherSide {
        public DroppedAtDevice {
            at = Objects.requireNonNull(at, "at");
        }
    }

    /** Destroyed. */
    public record Destroyed() implements OtherSide {}

    /** The other side is not known. */
    public record Unresolved() implements OtherSide {}

    /** Consumed (eaten or used up). */
    public record Consumed() implements OtherSide {}

    /** An item entity on the ground. */
    public record GroundItem(UUID itemEntityUuid) implements OtherSide {
        public GroundItem {
            itemEntityUuid = Objects.requireNonNull(itemEntityUuid, "itemEntityUuid");
        }
    }

    /** An entity, by type id and uuid. */
    public record EntitySide(ResourceLocation entityTypeId, UUID entityUuid) implements OtherSide {
        public EntitySide {
            entityTypeId = Objects.requireNonNull(entityTypeId, "entityTypeId");
            entityUuid = Objects.requireNonNull(entityUuid, "entityUuid");
        }
    }

    /** A network's storage, reached through a link item the player carries. */
    public record RemoteStorage(NetworkKind kind, ResourceLocation linkItemId, String linkItemDisplay)
            implements OtherSide {
        public RemoteStorage {
            kind = Objects.requireNonNull(kind, "kind");
            linkItemId = Objects.requireNonNull(linkItemId, "linkItemId");
            linkItemDisplay = boundedDisplay(linkItemDisplay, "linkItemDisplay");
        }
    }

    /** The item became, or came from, the other row's item (a transform chain, or the eaten food of a leftover). */
    public record Transformed(ResourceLocation otherItemId) implements OtherSide {
        public Transformed {
            otherItemId = Objects.requireNonNull(otherItemId, "otherItemId");
        }
    }

    /** A container item the player carries, on the other side of the change. */
    public record OwnCarriedContainer(ResourceLocation containerItemId, String containerDisplay)
            implements OtherSide {
        public OwnCarriedContainer {
            containerItemId = Objects.requireNonNull(containerItemId, "containerItemId");
            containerDisplay = boundedDisplay(containerDisplay, "containerDisplay");
        }
    }

    /** The device that made the change (design 3.2 field 7): a block at a place, or an entity by uuid. */
    public sealed interface Device permits BlockDevice, EntityDevice {}

    /** A device block at a place. */
    public record BlockDevice(AuditLocation at) implements Device {
        public BlockDevice {
            at = Objects.requireNonNull(at, "at");
        }
    }

    /** A device entity. */
    public record EntityDevice(UUID entityUuid) implements Device {
        public EntityDevice {
            entityUuid = Objects.requireNonNull(entityUuid, "entityUuid");
        }
    }

    /**
     * A fluid moved with the item (the pump, decision 23 = A): its id and its amount in mB, or null when the amount is
     * not known. A coalesced chain's amount is the sum over the window when every step was exact.
     */
    public record Fluid(ResourceLocation fluidId, Long amountMillibuckets) {
        public Fluid {
            fluidId = Objects.requireNonNull(fluidId, "fluidId");
            if (amountMillibuckets != null && amountMillibuckets < 1L) {
                throw new IllegalArgumentException("A known fluid amount must be positive");
            }
        }

        public static Fluid notKnown(ResourceLocation fluidId) {
            return new Fluid(fluidId, null);
        }
    }

    /**
     * One numbered part of a split scanner seizure (design 3.14), shown as "(seizure part i of n)". The bounds are the
     * part publisher's: 1 to {@link ExplosionPartTag#MAX_PARTS} parts.
     */
    public record Part(int index, int count) {
        public static final int MAX_PARTS = ExplosionPartTag.MAX_PARTS;

        public Part {
            if (count < 1 || count > MAX_PARTS) throw new IllegalArgumentException("count must be 1.." + MAX_PARTS);
            if (index < 1 || index > count) throw new IllegalArgumentException("index must be 1..count");
        }
    }

    private static String boundedDisplay(String value, String field) {
        return EventTrailTextBounds.boundAuditDescription(requireText(value, field));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    // ---------------------------------------------------------------------
    // Metadata, checked exactly as ContainerTransferPayload checks it.
    // ---------------------------------------------------------------------

    private static String requireMetadata(String value, ResourceLocation expectedItemId, int expectedQuantity) {
        if (!validMetadata(value, expectedItemId, expectedQuantity)) {
            throw new IllegalArgumentException(
                    "metadataSnbt is invalid, too large, or conflicts with the declared item");
        }
        return value;
    }

    private static boolean validMetadata(String value, ResourceLocation expectedItemId, int expectedQuantity) {
        if (value == null || value.isBlank()
                || value.length() > EventTrailTextBounds.MAX_ITEM_METADATA_CHARS
                || value.getBytes(StandardCharsets.UTF_8).length
                    > EventTrailTextBounds.MAX_ITEM_METADATA_NBT_BYTES) {
            return false;
        }
        AuditSnbtParser.ParseResult parsed = AuditSnbtParser.parsePayload(value, 0);
        return parsed.successful() && matchesDeclaredItem(parsed.tag(), expectedItemId, expectedQuantity);
    }

    private static boolean matchesDeclaredItem(CompoundTag tag, ResourceLocation expectedItemId, int expectedQuantity) {
        if (!tag.contains("id", Tag.TAG_STRING)) return false;
        if (!expectedItemId.toString().equals(tag.getString("id"))) return false;
        if (!tag.contains("count")) {
            // ItemStack.CODEC omits its default count of one.
            return expectedQuantity == 1;
        }
        return tag.contains("count", Tag.TAG_INT) && tag.getInt("count") == expectedQuantity;
    }
}
