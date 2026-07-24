package za.co.fnb.dcre.mrw.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrw.data.model.ManOutboundEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * MRW single writer of {@code man_outbound} (R-04). The registry row is committed
 * WRITE-AHEAD of the pain StagedWrite (R-10). Idempotent minting: the guarded
 * INSERT keys on the FULL business identity {@code entry_id} (never UPSERT on the
 * PK; CRDB resolves UPSERT on PK only, persistence.md), so a kill-resume re-insert
 * is a zero-duplicate no-op and the deterministic {@code out_msg_id} is unchanged.
 */
public interface ManOutboundRepo extends CrudRepository<ManOutboundEntity, UUID> {

    @Modifying
    @Query("""
            INSERT INTO man_outbound (id, entry_id, out_msg_id, mndt_req_id, pain_type)
            VALUES (:#{#e.id}, :#{#e.entryId}, :#{#e.outMsgId}, :#{#e.mndtReqId}, :#{#e.painType})
            ON CONFLICT (entry_id) DO NOTHING""")
    void writeAhead(@Param("e") ManOutboundEntity e);

    Optional<ManOutboundEntity> findByEntryId(UUID entryId);
}
