package za.co.fnb.dcre.mrw.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-identity idempotency (engineering.md, SCRUM-90): the outbound MsgId MUST be
 * a deterministic function of the FULL business identity (arrival_id, sequence,
 * action_code), so a kill-resume re-mints the identical id and the outbound
 * artifact is exactly-once. Never from now()/epoch/UUID.
 */
class OutMsgIdMinterTest {

    private static final UUID ARRIVAL = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void sameIdentityMintsIdenticalIdAcrossCalls() {
        assertEquals(OutMsgIdMinter.mint(ARRIVAL, 1, "CREATE"), OutMsgIdMinter.mint(ARRIVAL, 1, "CREATE"),
                "a byte-verbatim replay must re-mint the identical outbound MsgId");
    }

    @Test
    void everyIdentityDimensionChangesTheId() {
        final String base = OutMsgIdMinter.mint(ARRIVAL, 1, "CREATE");
        assertNotEquals(base, OutMsgIdMinter.mint(UUID.randomUUID(), 1, "CREATE"), "arrival_id is part of identity");
        assertNotEquals(base, OutMsgIdMinter.mint(ARRIVAL, 2, "CREATE"), "sequence is part of identity");
        assertNotEquals(base, OutMsgIdMinter.mint(ARRIVAL, 1, "AMEND"), "action_code is part of identity");
    }

    @Test
    void idHonoursTheIso20022MsgIdShape() {
        final String id = OutMsgIdMinter.mint(ARRIVAL, 1, "CREATE");
        assertEquals(35, id.length(), "MsgId max length is 35");
        assertTrue(id.startsWith("OMS"), "carries the outbound prefix");
    }

    @Test
    void aBlankIdentityComponentFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> OutMsgIdMinter.mint(ARRIVAL, 1, "  "),
                "no nullable/blank dimensions in a full-identity tuple");
    }
}
