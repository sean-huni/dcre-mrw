package za.co.fnb.dcre.mrw.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A-69 full-identity idempotency (engineering.md, SCRUM-90): the mandate
 * {@code OrgnlEndToEndId} MUST be a deterministic function of the FULL business
 * identity (arrival_id, sequence, action_code) so a kill-resume re-mints the
 * identical reference and the reply echo correlates to exactly one instruction.
 * Never from now()/epoch/UUID, and a distinct value from the out_msg_id minted over
 * the same identity (domain separation).
 */
class OrgnlEndToEndIdMinterTest {

    private static final UUID ARRIVAL = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void sameIdentityMintsIdenticalReferenceAcrossCalls() {
        assertEquals(OrgnlEndToEndIdMinter.mint(ARRIVAL, 1, "CREATE"),
                OrgnlEndToEndIdMinter.mint(ARRIVAL, 1, "CREATE"),
                "a byte-verbatim replay must re-mint the identical OrgnlEndToEndId");
    }

    @Test
    void everyIdentityDimensionChangesTheReference() {
        final String base = OrgnlEndToEndIdMinter.mint(ARRIVAL, 1, "CREATE");
        assertNotEquals(base, OrgnlEndToEndIdMinter.mint(UUID.randomUUID(), 1, "CREATE"),
                "arrival_id is part of identity");
        assertNotEquals(base, OrgnlEndToEndIdMinter.mint(ARRIVAL, 2, "CREATE"), "sequence is part of identity");
        assertNotEquals(base, OrgnlEndToEndIdMinter.mint(ARRIVAL, 1, "AMEND"), "action_code is part of identity");
    }

    @Test
    void referenceHonoursTheIso20022EndToEndIdShape() {
        final String id = OrgnlEndToEndIdMinter.mint(ARRIVAL, 1, "CREATE");
        assertTrue(id.length() <= 35, "EndToEndId max length is 35");
        assertTrue(id.startsWith("E2E"), "carries the end-to-end reference prefix");
    }

    @Test
    void aBlankOrNullIdentityComponentFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> OrgnlEndToEndIdMinter.mint(ARRIVAL, 1, "  "),
                "no blank dimensions in a full-identity tuple");
        assertThrows(IllegalArgumentException.class, () -> OrgnlEndToEndIdMinter.mint(null, 1, "CREATE"),
                "arrival_id must be present");
    }

    @Test
    void isADistinctValueFromTheOutboundMsgIdOverTheSameIdentity() {
        assertNotEquals(OutMsgIdMinter.mint(ARRIVAL, 1, "CREATE"),
                OrgnlEndToEndIdMinter.mint(ARRIVAL, 1, "CREATE"),
                "domain separation: the e2e reference is not the out_msg_id");
    }
}
