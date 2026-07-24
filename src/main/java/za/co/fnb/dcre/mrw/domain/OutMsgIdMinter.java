package za.co.fnb.dcre.mrw.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * R-10 / full-identity idempotency (engineering.md, SCRUM-90): the outbound MsgId
 * is a DETERMINISTIC digest over the FULL business identity of a spine row
 * (arrival_id, sequence, action_code), never {@code now()}/epoch/UUID. A
 * kill-resume re-mints the IDENTICAL id, so the deterministic filename StagedWrite
 * no-ops and the {@code man_outbound.out_msg_id} UNIQUE arbitrates duplicates
 * instead of minting drift: exactly one outbound artifact per instruction row.
 * Every component is mandatory (house rule: no nullable dimensions in an identity
 * tuple). Components are LENGTH-PREFIXED before hashing so their boundaries are
 * part of the digest input and a raw delimiter join cannot make distinct tuples
 * collide. Shape: "OMS" + 32 hex chars of SHA-256 = 35 chars, the ISO 20022 MsgId
 * maximum.
 */
public final class OutMsgIdMinter {

    static final String PREFIX = "OMS";
    static final int LENGTH = 35;

    private OutMsgIdMinter() {
    }

    public static String mint(final UUID arrivalId, final int sequence, final String actionCode) {
        final String tuple = frame(required("arrivalId", arrivalId == null ? null : arrivalId.toString()))
                + frame(required("sequence", Integer.toString(sequence)))
                + frame(required("actionCode", actionCode));
        final String hex = HexFormat.of().formatHex(
                sha256().digest(tuple.getBytes(StandardCharsets.UTF_8)));
        return PREFIX + hex.substring(0, LENGTH - PREFIX.length());
    }

    /** Length-prefixed component framing ("&lt;len&gt;:&lt;value&gt;"): injective, so no delimiter ambiguity. */
    private static String frame(final String component) {
        return "%d:%s".formatted(component.length(), component);
    }

    private static String required(final String name, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "out_msg_id identity component '" + name + "' is blank: full-identity tuple required (R-10)");
        }
        return value.strip();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
