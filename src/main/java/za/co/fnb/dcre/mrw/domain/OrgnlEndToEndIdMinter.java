package za.co.fnb.dcre.mrw.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * A-69 / full-identity idempotency (engineering.md, SCRUM-90): the mandate
 * {@code OrgnlEndToEndId} is a DETERMINISTIC digest over the FULL business identity
 * of a spine row (arrival_id, sequence, action_code), never {@code now()}/epoch/UUID,
 * exactly like {@link OutMsgIdMinter}. It travels on the OUTGOING pain.009/.010/.011
 * and echoes back on the INCOMING pain.012 reply, so MAR correlates the debtor
 * end-to-end reference to the originating instruction. A kill-resume re-mints the
 * IDENTICAL reference, so the deterministic outbound file no-ops and the persisted
 * {@code man_outbound.orgnl_e2e} is stable across replays.
 *
 * <p>Every component is mandatory (house rule: no nullable dimensions in an identity
 * tuple) and LENGTH-PREFIXED before hashing so tuple boundaries are part of the digest
 * input and a raw delimiter join cannot make distinct tuples collide. A fixed DOMAIN
 * label is framed into the input so this reference is a DISTINCT value from the
 * {@code out_msg_id} minted over the same identity (domain separation), not a mere
 * prefix swap. Shape: "E2E" + 32 hex chars of SHA-256 = 35 chars, the ISO 20022
 * EndToEndId maximum.
 */
public final class OrgnlEndToEndIdMinter {

    static final String PREFIX = "E2E";
    static final int LENGTH = 35;
    private static final String DOMAIN = "OrgnlEndToEndId";

    private OrgnlEndToEndIdMinter() {
    }

    public static String mint(final UUID arrivalId, final int sequence, final String actionCode) {
        final String tuple = frame(DOMAIN)
                + frame(required("arrivalId", arrivalId == null ? null : arrivalId.toString()))
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
                    "orgnl_e2e identity component '" + name + "' is blank: full-identity tuple required (A-69)");
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
