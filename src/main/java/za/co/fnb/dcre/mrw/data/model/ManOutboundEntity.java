package za.co.fnb.dcre.mrw.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * MRW single-writer outbound registry (R-04, R-10). One row per INITIALIZED
 * spine entry, persisted WRITE-AHEAD of the pain StagedWrite: {@code entry_id} is
 * the full-identity idempotency key (UNIQUE) and {@code out_msg_id} is the
 * deterministic outbound MsgId (UNIQUE) MSR maps replies back through.
 * {@code orgnl_e2e} (A-69) is the deterministic OrgnlEndToEndId carried on the
 * outbound pain and echoed on the pain.012 reply, minted over the same identity.
 */
@Table("man_outbound")
public class ManOutboundEntity extends BaseEntity {

    private UUID entryId;
    private String outMsgId;
    private String orgnlE2e;
    private String mndtReqId;
    private String painType;
    private Instant writtenAt;

    public static ManOutboundEntity of(final UUID entryId, final String outMsgId, final String orgnlE2e,
                                        final String mndtReqId, final String painType) {
        final ManOutboundEntity e = new ManOutboundEntity();
        e.assignIdIfMissing();
        e.entryId = entryId;
        e.outMsgId = outMsgId;
        e.orgnlE2e = orgnlE2e;
        e.mndtReqId = mndtReqId;
        e.painType = painType;
        return e;
    }

    public UUID getEntryId() { return entryId; }
    public String getOutMsgId() { return outMsgId; }
    public String getOrgnlE2e() { return orgnlE2e; }
    public String getMndtReqId() { return mndtReqId; }
    public String getPainType() { return painType; }
    public Instant getWrittenAt() { return writtenAt; }
}
