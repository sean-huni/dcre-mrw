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
 */
@Table("man_outbound")
public class ManOutboundEntity extends BaseEntity {

    private UUID entryId;
    private String outMsgId;
    private String mndtReqId;
    private String painType;
    private Instant writtenAt;

    public static ManOutboundEntity of(final UUID entryId, final String outMsgId, final String mndtReqId,
                                        final String painType) {
        final ManOutboundEntity e = new ManOutboundEntity();
        e.assignIdIfMissing();
        e.entryId = entryId;
        e.outMsgId = outMsgId;
        e.mndtReqId = mndtReqId;
        e.painType = painType;
        return e;
    }

    public UUID getEntryId() { return entryId; }
    public String getOutMsgId() { return outMsgId; }
    public String getMndtReqId() { return mndtReqId; }
    public String getPainType() { return painType; }
    public Instant getWrittenAt() { return writtenAt; }
}
