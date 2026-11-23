package io.github.betapetey.eventtrail.storage;

import io.github.betapetey.eventtrail.audit.AuditAction;
import io.github.betapetey.eventtrail.audit.AuditEvidence;
import io.github.betapetey.eventtrail.audit.AuditPayloadCodec;
import io.github.betapetey.eventtrail.audit.AuditSubject;
import net.minecraft.nbt.StringTag;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The exact-name lookup filter ({@code name:"Oak Iron Chest"}): a block or container row whose logged name, the
 * subject presentation identity the lookup line shows, equals the typed name character for character
 * (case-sensitive, no folding).
 *
 * <p>The name lives only inside {@code extra_data}: as an int32 byte length plus UTF-8 bytes in the base64url
 * binary of block-effect and container-transfer payloads, and as the SNBT {@code display} string of
 * container-access payloads. SQL therefore gets a NECESSARY condition only ({@link #binaryFieldFragments()} and
 * {@link #accessPayloadNeedle()}, matched with {@code LIKE}), and {@link #matches} decides every candidate row in
 * Java with the read path's payload codec, so every backend returns the same rows.</p>
 *
 * <p>Stateless apart from the immutable name; safe on any thread.</p>
 */
final class LoggedNameFilter {
    /** Actions whose payload holds the name in a base64url binary field. */
    static final List<AuditAction> BINARY_PAYLOAD_ACTIONS = List.of(
            AuditAction.BLOCK_BREAK, AuditAction.BLOCK_PLACE, AuditAction.EXPLOSION,
            AuditAction.CONTAINER_DEPOSIT, AuditAction.CONTAINER_WITHDRAWAL);
    /** The action whose payload holds the name as SNBT text. */
    static final AuditAction ACCESS_PAYLOAD_ACTION = AuditAction.CONTAINER_ACCESS;
    /** The stored columns {@link #matches} reads, in this order. */
    static final List<String> COLUMNS = List.of("action_type", "block_state", "extra_data");

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final String name;
    private final List<String> binaryFieldFragments;
    private final String accessPayloadNeedle;

    LoggedNameFilter(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("An exact-name filter needs a name");
        this.name = name;
        this.binaryFieldFragments = fragmentsOf(name);
        this.accessPayloadNeedle = StringTag.quoteAndEscape(name);
    }

    String name() {
        return name;
    }

    /**
     * The base64url texts one of which every block-effect or container-transfer payload whose name field holds
     * exactly this name contains: one per byte alignment (0, 1, 2) of the field {@code int32 length + UTF-8}.
     */
    List<String> binaryFieldFragments() {
        return binaryFieldFragments;
    }

    /** The SNBT text ({@code StringTag.quoteAndEscape}, quotes included) a matching access payload contains. */
    String accessPayloadNeedle() {
        return accessPayloadNeedle;
    }

    /**
     * True when the stored row is a block or container row whose decoded logged name equals this name. A row the
     * payload codec reports unavailable (an older or unreadable payload format) has no logged name: no match.
     *
     * <p>Lane xn r2 (review-01 MINOR-2): a row the read path would refuse to read fails the lookup loudly, with
     * the same {@link EventTrailQueryException#dataReadFailure data-read failure} that {@code mapRow} raises for
     * it (an unknown action code, a missing subject identity, a codec exception such as a transfer whose action
     * conflicts with its payload direction, or no projection). Counting it as "no match" would make a corrupt row
     * or a codec regression look like "nobody touched it". Only candidates reach this check (rows of the six
     * block and container actions whose payload holds the name's bytes, or every such row after a failed
     * self-test), so such a row fails only the lookups that could have shown it.</p>
     */
    boolean matches(AuditPayloadCodec codec, String actionCode, String subjectIdentity, String encodedPayload) {
        AuditAction action = actionCode == null ? null : AuditAction.fromStorageCode(actionCode).orElse(null);
        if (action == null) {
            throw EventTrailQueryException.dataReadFailure(
                    new IllegalArgumentException("Unknown schema-v1 action code"));
        }
        if (action != ACCESS_PAYLOAD_ACTION && !BINARY_PAYLOAD_ACTIONS.contains(action)) return false;
        if (subjectIdentity == null || subjectIdentity.isBlank()) {
            throw EventTrailQueryException.dataReadFailure(
                    new IllegalArgumentException("Stored subject identity is missing"));
        }
        AuditPayloadCodec.DecodedProjection projection;
        try {
            projection = Objects.requireNonNull(codec.decode(action, subjectIdentity, encodedPayload),
                    "payload codec returned no projection");
        } catch (RuntimeException undecodable) {
            throw EventTrailQueryException.dataReadFailure(undecodable);
        }
        if (projection.evidence() instanceof AuditEvidence.Unavailable) return false;
        AuditSubject.Kind kind = projection.subjectKind();
        if (kind != AuditSubject.Kind.BLOCK && kind != AuditSubject.Kind.CONTAINER) return false;
        return name.equals(projection.subjectPresentationIdentity());
    }

    /**
     * For a field that starts at byte offset p of the binary, the base64url characters that depend only on the
     * field's bytes: with p mod 3 = shift, encode shift filler bytes plus the field, drop the leading characters
     * that mix in a filler byte (none, 2 or 3) and the last character when it mixes in a byte after the field.
     */
    static List<String> fragmentsOf(String name) {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        byte[] field = ByteBuffer.allocate(Integer.BYTES + utf8.length).putInt(utf8.length).put(utf8).array();
        LinkedHashSet<String> fragments = new LinkedHashSet<>();
        for (int shift = 0; shift < 3; shift++) {
            byte[] aligned = new byte[shift + field.length];
            System.arraycopy(field, 0, aligned, shift, field.length);
            String encoded = BASE64URL.encodeToString(aligned);
            int dropFront = shift == 0 ? 0 : shift + 1;
            int dropBack = aligned.length % 3 == 0 ? 0 : 1;
            fragments.add(encoded.substring(dropFront, encoded.length() - dropBack));
        }
        return List.copyOf(fragments);
    }
}
