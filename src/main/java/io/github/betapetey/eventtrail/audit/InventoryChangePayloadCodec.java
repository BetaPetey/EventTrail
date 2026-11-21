package io.github.betapetey.eventtrail.audit;

import io.github.betapetey.eventtrail.audit.AuditPayloadCodec.IndexedPartyBinding;
import io.github.betapetey.eventtrail.inventory.onplayer.OnPlayerSource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic bounded versioned codec for on-player inventory-change evidence (GF-ONPLAYER design 3.2). It follows
 * ContainerTransferPayloadCodec: the int version first, the same size bounds, a refused unknown version, a
 * trailing-bytes check and a canonical re-encode equality check. Nothing that does not decode exactly is guessed.
 */
public final class InventoryChangePayloadCodec {
    public static final int CURRENT_VERSION = 1;
    public static final String PREFIX = "EVENTTRAIL_INVENTORY_CHANGE_V1|";
    public static final int MAX_BINARY_BYTES = 72 * 1024;
    public static final int MAX_ENCODED_CHARS = 100 * 1024;
    /** The bound on the stored source token, an {@link OnPlayerSource} name. */
    public static final int MAX_SOURCE_TOKEN_BYTES = 64;
    /**
     * The code a holder-network side would have. It is reserved and always refused: no on-player row has a
     * holder-network side (decision 31 = A, design M-5).
     */
    public static final String REFUSED_HOLDER_NETWORK_CODE = "holder_network";

    private InventoryChangePayloadCodec() {}

    /** The payload version {@link #encode} writes for {@code payload}: 1 for every payload this build can hold. */
    public static int versionOf(InventoryChangePayload payload) {
        Objects.requireNonNull(payload, "payload");
        return CURRENT_VERSION;
    }

    /**
     * Keeps the exact item registry identity outside the binary envelope so parameterized SQL item filters stay literal,
     * as ContainerTransferPayloadCodec does.
     */
    public static String encode(InventoryChangePayload payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(versionOf(payload));
                writeUuid(output, payload.holder().playerId());
                writeString(output, payload.holder().canonicalName());
                writeLocation(output, payload.at());
                output.writeLong(payload.window().firstAtMillis());
                output.writeLong(payload.window().lastAtMillis());
                output.writeInt(payload.window().changeCount());
                writeString(output, directionCode(payload.direction()));
                writeString(output, payload.itemId().toString());
                writeString(output, payload.itemDisplay());
                output.writeInt(payload.quantity());
                writeString(output, metadataCode(payload.metadataState()));
                writeNullableString(output, payload.metadataSnbt());
                writeNullableString(output, payload.metadataUnavailableReason());
                writeHolderSide(output, payload.holderSide());
                writeOtherSide(output, payload.otherSide());
                writeString(output, payload.source().name());
                ShownCause.writeBlock(output, payload.cause());
                writeDevice(output, payload.device());
                writeDeviceOwner(output, payload.deviceOwner());
                writeFluid(output, payload.fluid());
                writePart(output, payload.part());
            }
            byte[] binary = bytes.toByteArray();
            if (binary.length > MAX_BINARY_BYTES) {
                throw new IllegalArgumentException("Inventory-change payload exceeds its retained-size limit");
            }
            String encoded = PREFIX + payload.itemId() + '|'
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(binary);
            if (encoded.length() > MAX_ENCODED_CHARS) {
                throw new IllegalArgumentException("Inventory-change payload exceeds its encoded-size limit");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory inventory-change encoding failed", impossible);
        }
    }

    /**
     * Decodes a payload and refuses it unless its direction is {@code required}: the row's action decides the
     * direction (ITEM_GAINED is GAINED, ITEM_LOST is LOST), and a payload that says otherwise is never shown.
     */
    public static DecodeResult decode(String encoded, InventoryChangePayload.Direction required) {
        Objects.requireNonNull(required, "required");
        DecodeResult decoded = decode(encoded);
        if (decoded.successful() && decoded.payload().direction() != required) {
            return DecodeResult.failure(Status.MALFORMED);
        }
        return decoded;
    }

    /**
     * Decodes a payload without checking its direction. Package-private so a row reader cannot skip the direction
     * check: readers use {@link #decode(String, InventoryChangePayload.Direction)}.
     */
    static DecodeResult decode(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            return DecodeResult.failure(Status.UNSUPPORTED);
        }
        if (encoded.length() > MAX_ENCODED_CHARS) return DecodeResult.failure(Status.TOO_LARGE);
        int itemEnd = encoded.indexOf('|', PREFIX.length());
        if (itemEnd < 0) return DecodeResult.failure(Status.MALFORMED);
        ResourceLocation outerItem = ResourceLocation.tryParse(encoded.substring(PREFIX.length(), itemEnd));
        if (outerItem == null) return DecodeResult.failure(Status.MALFORMED);

        byte[] binary;
        try {
            binary = Base64.getUrlDecoder().decode(encoded.substring(itemEnd + 1));
        } catch (IllegalArgumentException invalidBase64) {
            return DecodeResult.failure(Status.MALFORMED);
        }
        if (binary.length > MAX_BINARY_BYTES) return DecodeResult.failure(Status.TOO_LARGE);

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(binary))) {
            int version = input.readInt();
            if (version != CURRENT_VERSION) return DecodeResult.failure(Status.UNSUPPORTED);
            UUID holderId = readUuid(input);
            String holderName = readString(input);
            AuditLocation at = readLocation(input);
            long firstAt = input.readLong();
            long lastAt = input.readLong();
            int changeCount = input.readInt();
            InventoryChangePayload.Direction direction = directionFromCode(readString(input));
            ResourceLocation itemId = ResourceLocation.tryParse(readString(input));
            String itemDisplay = readString(input);
            int quantity = input.readInt();
            InventoryChangePayload.MetadataState metadataState = metadataFromCode(readString(input));
            String metadata = readNullableString(input);
            String metadataReason = readNullableString(input);
            InventoryChangePayload.HolderSide holderSide = readHolderSide(input);
            InventoryChangePayload.OtherSide otherSide = readOtherSide(input);
            OnPlayerSource source = sourceFromToken(readString(input));
            ShownCause cause = ShownCause.readBlock(input);
            InventoryChangePayload.Device device = readDevice(input);
            ShownCause.Owner deviceOwner = readDeviceOwner(input);
            InventoryChangePayload.Fluid fluid = readFluid(input);
            InventoryChangePayload.Part part = readPart(input);
            if (input.read() != -1 || direction == null || itemId == null
                    || !outerItem.equals(itemId) || metadataState == null || source == null) {
                return DecodeResult.failure(Status.MALFORMED);
            }
            try {
                InventoryChangePayload payload = new InventoryChangePayload(
                        new IndexedPartyBinding(holderId, holderName),
                        at,
                        new InventoryChangePayload.Window(firstAt, lastAt, changeCount),
                        direction, itemId, itemDisplay, quantity, metadataState, metadata, metadataReason,
                        holderSide, otherSide, source, cause, device, deviceOwner, fluid, part);
                if (!encoded.equals(encode(payload))) {
                    return DecodeResult.failure(Status.MALFORMED);
                }
                return DecodeResult.success(payload);
            } catch (IllegalArgumentException invalidPayload) {
                return DecodeResult.failure(Status.MALFORMED);
            }
        } catch (IOException | RuntimeException failure) {
            return DecodeResult.failure(Status.MALFORMED);
        }
    }

    // ---------------------------------------------------------------------
    // The sides, the device, the owner, the fluid and the part.
    // ---------------------------------------------------------------------

    private static void writeHolderSide(DataOutputStream output, InventoryChangePayload.HolderSide side)
            throws IOException {
        switch (side) {
            case InventoryChangePayload.PlayerInventory inventory -> {
                writeString(output, "player_inventory");
                writeString(output, sectionCode(inventory.section()));
            }
            case InventoryChangePayload.CarriedContainer container -> {
                writeString(output, "carried_container");
                writeString(output, container.containerItemId().toString());
                writeString(output, container.containerDisplay());
                if (container.containerUuid() != null) {
                    writeString(output, "uuid");
                    writeUuid(output, container.containerUuid());
                } else {
                    writeString(output, "slot");
                    output.writeInt(container.slot());
                }
            }
        }
    }

    private static InventoryChangePayload.HolderSide readHolderSide(DataInputStream input) throws IOException {
        String code = readString(input);
        return switch (code) {
            case "player_inventory" -> new InventoryChangePayload.PlayerInventory(sectionFromCode(readString(input)));
            case "carried_container" -> {
                ResourceLocation containerItemId = readId(input);
                String display = readString(input);
                String reference = readString(input);
                yield switch (reference) {
                    case "uuid" -> new InventoryChangePayload.CarriedContainer(
                            containerItemId, display, readUuid(input), null);
                    case "slot" -> new InventoryChangePayload.CarriedContainer(
                            containerItemId, display, null, input.readInt());
                    default -> throw new IOException("Unknown carried-container reference");
                };
            }
            case REFUSED_HOLDER_NETWORK_CODE -> throw new IOException("A holder-network side is never written");
            default -> throw new IOException("Unknown holder side");
        };
    }

    private static void writeOtherSide(DataOutputStream output, InventoryChangePayload.OtherSide side)
            throws IOException {
        switch (side) {
            case InventoryChangePayload.DeviceStorage storage -> {
                writeString(output, "device_storage");
                writeLocation(output, storage.at());
            }
            case InventoryChangePayload.DroppedAtDevice dropped -> {
                writeString(output, "dropped_at_device");
                writeLocation(output, dropped.at());
            }
            case InventoryChangePayload.Destroyed destroyed -> writeString(output, "destroyed");
            case InventoryChangePayload.Unresolved unresolved -> writeString(output, "unresolved");
            case InventoryChangePayload.Consumed consumed -> writeString(output, "consumed");
            case InventoryChangePayload.GroundItem ground -> {
                writeString(output, "ground_item");
                writeUuid(output, ground.itemEntityUuid());
            }
            case InventoryChangePayload.EntitySide entity -> {
                writeString(output, "entity");
                writeString(output, entity.entityTypeId().toString());
                writeUuid(output, entity.entityUuid());
            }
            case InventoryChangePayload.RemoteStorage remote -> {
                writeString(output, "remote_storage");
                writeString(output, networkCode(remote.kind()));
                writeString(output, remote.linkItemId().toString());
                writeString(output, remote.linkItemDisplay());
            }
            case InventoryChangePayload.Transformed transformed -> {
                writeString(output, "transformed");
                writeString(output, transformed.otherItemId().toString());
            }
            case InventoryChangePayload.OwnCarriedContainer container -> {
                writeString(output, "own_carried_container");
                writeString(output, container.containerItemId().toString());
                writeString(output, container.containerDisplay());
            }
        }
    }

    private static InventoryChangePayload.OtherSide readOtherSide(DataInputStream input) throws IOException {
        String code = readString(input);
        return switch (code) {
            case "device_storage" -> new InventoryChangePayload.DeviceStorage(readLocation(input));
            case "dropped_at_device" -> new InventoryChangePayload.DroppedAtDevice(readLocation(input));
            case "destroyed" -> new InventoryChangePayload.Destroyed();
            case "unresolved" -> new InventoryChangePayload.Unresolved();
            case "consumed" -> new InventoryChangePayload.Consumed();
            case "ground_item" -> new InventoryChangePayload.GroundItem(readUuid(input));
            case "entity" -> new InventoryChangePayload.EntitySide(readId(input), readUuid(input));
            case "remote_storage" -> new InventoryChangePayload.RemoteStorage(
                    networkFromCode(readString(input)), readId(input), readString(input));
            case "transformed" -> new InventoryChangePayload.Transformed(readId(input));
            case "own_carried_container" -> new InventoryChangePayload.OwnCarriedContainer(
                    readId(input), readString(input));
            default -> throw new IOException("Unknown other side");
        };
    }

    private static void writeDevice(DataOutputStream output, InventoryChangePayload.Device device)
            throws IOException {
        output.writeBoolean(device != null);
        if (device == null) return;
        switch (device) {
            case InventoryChangePayload.BlockDevice block -> {
                writeString(output, "block");
                writeLocation(output, block.at());
            }
            case InventoryChangePayload.EntityDevice entity -> {
                writeString(output, "entity");
                writeUuid(output, entity.entityUuid());
            }
        }
    }

    private static InventoryChangePayload.Device readDevice(DataInputStream input) throws IOException {
        if (!readPresence(input)) return null;
        return switch (readString(input)) {
            case "block" -> new InventoryChangePayload.BlockDevice(readLocation(input));
            case "entity" -> new InventoryChangePayload.EntityDevice(readUuid(input));
            default -> throw new IOException("Unknown device form");
        };
    }

    private static void writeDeviceOwner(DataOutputStream output, ShownCause.Owner owner) throws IOException {
        output.writeBoolean(owner != null);
        if (owner == null) return;
        writeString(output, owner.name());
        writeString(output, owner.uuid());
        writeString(output, ownerStateCode(owner.state()));
    }

    private static ShownCause.Owner readDeviceOwner(DataInputStream input) throws IOException {
        if (!readPresence(input)) return null;
        String name = readString(input);
        String uuid = readString(input);
        ShownCause.OwnerState state = ownerStateFromCode(readString(input));
        return new ShownCause.Owner(name, uuid, state);
    }

    private static void writeFluid(DataOutputStream output, InventoryChangePayload.Fluid fluid) throws IOException {
        output.writeBoolean(fluid != null);
        if (fluid == null) return;
        writeString(output, fluid.fluidId().toString());
        output.writeBoolean(fluid.amountMillibuckets() != null);
        if (fluid.amountMillibuckets() != null) output.writeLong(fluid.amountMillibuckets());
    }

    private static InventoryChangePayload.Fluid readFluid(DataInputStream input) throws IOException {
        if (!readPresence(input)) return null;
        ResourceLocation fluidId = readId(input);
        Long amount = readPresence(input) ? input.readLong() : null;
        return new InventoryChangePayload.Fluid(fluidId, amount);
    }

    private static void writePart(DataOutputStream output, InventoryChangePayload.Part part) throws IOException {
        output.writeBoolean(part != null);
        if (part == null) return;
        output.writeInt(part.index());
        output.writeInt(part.count());
    }

    private static InventoryChangePayload.Part readPart(DataInputStream input) throws IOException {
        if (!readPresence(input)) return null;
        int index = input.readInt();
        int count = input.readInt();
        return new InventoryChangePayload.Part(index, count);
    }

    // ---------------------------------------------------------------------
    // Primitive fields.
    // ---------------------------------------------------------------------

    private static void writeLocation(DataOutputStream output, AuditLocation location) throws IOException {
        writeString(output, location.dimensionId().toString());
        output.writeInt(location.position().getX());
        output.writeInt(location.position().getY());
        output.writeInt(location.position().getZ());
    }

    private static AuditLocation readLocation(DataInputStream input) throws IOException {
        ResourceLocation dimension = readId(input);
        int x = input.readInt();
        int y = input.readInt();
        int z = input.readInt();
        return new AuditLocation(dimension, new BlockPos(x, y, z));
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        long most = input.readLong();
        long least = input.readLong();
        return new UUID(most, least);
    }

    /** A registry id; the re-encode equality check refuses one that is not written canonically. */
    private static ResourceLocation readId(DataInputStream input) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(readString(input));
        if (id == null) throw new IOException("Invalid registry id");
        return id;
    }

    /** A presence flag: exactly 0 or 1, as writeBoolean writes it. */
    private static boolean readPresence(DataInputStream input) throws IOException {
        int value = input.readUnsignedByte();
        if (value == 0) return false;
        if (value == 1) return true;
        throw new IOException("Presence byte " + value + " is not canonical");
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) writeString(output, value);
    }

    private static String readNullableString(DataInputStream input) throws IOException {
        return readPresence(input) ? readString(input) : null;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BINARY_BYTES) throw new IllegalArgumentException("Payload field is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_BINARY_BYTES || length > input.available()) {
            throw new EOFException("Invalid inventory-change field length");
        }
        byte[] bytes = input.readNBytes(length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IOException("Inventory-change field is not valid UTF-8", malformedUtf8);
        }
    }

    // ---------------------------------------------------------------------
    // Stable wire codes.
    // ---------------------------------------------------------------------

    /** The source's name, or null for a token this build does not know (refused by the caller, never guessed). */
    private static OnPlayerSource sourceFromToken(String token) {
        if (token.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_TOKEN_BYTES) return null;
        for (OnPlayerSource value : OnPlayerSource.values()) {
            if (value.name().equals(token)) return value;
        }
        return null;
    }

    private static String directionCode(InventoryChangePayload.Direction value) {
        return switch (value) {
            case GAINED -> "gained";
            case LOST -> "lost";
        };
    }

    private static InventoryChangePayload.Direction directionFromCode(String value) {
        return switch (value) {
            case "gained" -> InventoryChangePayload.Direction.GAINED;
            case "lost" -> InventoryChangePayload.Direction.LOST;
            default -> null;
        };
    }

    private static String metadataCode(InventoryChangePayload.MetadataState value) {
        return switch (value) {
            case CAPTURED -> "captured";
            case UNAVAILABLE -> "unavailable";
        };
    }

    private static InventoryChangePayload.MetadataState metadataFromCode(String value) {
        return switch (value) {
            case "captured" -> InventoryChangePayload.MetadataState.CAPTURED;
            case "unavailable" -> InventoryChangePayload.MetadataState.UNAVAILABLE;
            default -> null;
        };
    }

    private static String sectionCode(InventoryChangePayload.Section value) {
        return switch (value) {
            case MAIN -> "main";
            case ARMOR -> "armor";
            case OFFHAND -> "offhand";
            case CURIO -> "curio";
            case UNKNOWN -> "unknown";
        };
    }

    private static InventoryChangePayload.Section sectionFromCode(String value) throws IOException {
        return switch (value) {
            case "main" -> InventoryChangePayload.Section.MAIN;
            case "armor" -> InventoryChangePayload.Section.ARMOR;
            case "offhand" -> InventoryChangePayload.Section.OFFHAND;
            case "curio" -> InventoryChangePayload.Section.CURIO;
            case "unknown" -> InventoryChangePayload.Section.UNKNOWN;
            default -> throw new IOException("Unknown inventory section");
        };
    }

    private static String networkCode(InventoryChangePayload.NetworkKind value) {
        return switch (value) {
            case AE2 -> "ae2";
            case RS2 -> "rs2";
            case OTHER -> "other";
        };
    }

    private static InventoryChangePayload.NetworkKind networkFromCode(String value) throws IOException {
        return switch (value) {
            case "ae2" -> InventoryChangePayload.NetworkKind.AE2;
            case "rs2" -> InventoryChangePayload.NetworkKind.RS2;
            case "other" -> InventoryChangePayload.NetworkKind.OTHER;
            default -> throw new IOException("Unknown network kind");
        };
    }

    /** The same codes as the shown-cause owner block. */
    private static String ownerStateCode(ShownCause.OwnerState value) {
        return switch (value) {
            case VALIDATED -> "validated";
            case NOT_VALIDATED -> "not_validated";
            case DEFAULT -> "default";
        };
    }

    private static ShownCause.OwnerState ownerStateFromCode(String value) throws IOException {
        return switch (value) {
            case "validated" -> ShownCause.OwnerState.VALIDATED;
            case "not_validated" -> ShownCause.OwnerState.NOT_VALIDATED;
            case "default" -> ShownCause.OwnerState.DEFAULT;
            default -> throw new IOException("Unknown device-owner state");
        };
    }

    public enum Status { OK, UNSUPPORTED, MALFORMED, TOO_LARGE }

    public record DecodeResult(Status status, InventoryChangePayload payload) {
        private static DecodeResult success(InventoryChangePayload payload) {
            return new DecodeResult(Status.OK, payload);
        }

        private static DecodeResult failure(Status status) {
            return new DecodeResult(status, null);
        }

        public boolean successful() {
            return status == Status.OK && payload != null;
        }
    }
}
