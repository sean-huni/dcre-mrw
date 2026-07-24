package za.co.fnb.dcre.mrw.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.mrw.data.model.ManSubmitEntryView;

import java.util.UUID;

/**
 * Single writer of the {@code spine_state} column MRW owns (ruling note 2): the
 * INITIALIZED -> SUBMITTED transition, applied AFTER the outbound registry row and
 * pain file are durable. The GUARDED atomic UPDATE ({@code WHERE spine_state =
 * 'INITIALIZED'}, persistence.md) makes MRW the single writer of SUBMITTED and the
 * transition idempotent + resumable: a re-run touches zero already-submitted rows,
 * and MRW can never clobber a state it does not own. Native @Query per the
 * guarded-mutation canon (QueryDSL cannot express these).
 */
public interface ManSpineSubmitRepo extends Repository<ManSubmitEntryView, UUID> {

    @Modifying
    @Query("""
            UPDATE mandate_request_entry SET spine_state = 'SUBMITTED', updated_at = now()
            WHERE id = :entryId AND spine_state = 'INITIALIZED'""")
    int submit(@Param("entryId") UUID entryId);
}
