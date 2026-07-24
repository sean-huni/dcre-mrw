package za.co.fnb.dcre.mrw.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.mrw.data.model.ManOutboundEntity;
import za.co.fnb.dcre.mrw.data.model.ManRequestHeaderView;
import za.co.fnb.dcre.mrw.data.model.ManSubmitEntryView;
import za.co.fnb.dcre.mrw.data.repo.ManOutboundRepo;
import za.co.fnb.dcre.mrw.data.repo.ManRequestHeaderRepo;
import za.co.fnb.dcre.mrw.data.repo.ManSpineSubmitRepo;
import za.co.fnb.dcre.mrw.data.repo.ManSubmitEntryRepo;
import za.co.fnb.dcre.mrw.domain.OutMsgIdMinter;
import za.co.fnb.dcre.mrw.domain.PainType;

import java.util.List;
import java.util.UUID;

/**
 * Business tier: the mandate request writer (T8). For every spine row an arrival
 * has advanced to INITIALIZED, MRW emits ONE outbound pain message to
 * {@code fint-req-man/out} and advances the row to SUBMITTED, in a strict
 * WRITE-AHEAD order (R-10): the {@code man_outbound} registry row commits in its
 * OWN transaction BEFORE the file exists, so MSR can always map a reply and a
 * crash leaves at most a registry row whose re-run re-emits the SAME file/ids.
 *
 * <p>Restart semantics come for free from the spine contract + full-identity
 * idempotency: the reader selects only INITIALIZED rows (a fully-submitted arrival
 * re-runs to a no-op), the outbound MsgId is deterministic from the row identity
 * (arrival_id, sequence, action_code), the registry INSERT is ON CONFLICT(entry_id)
 * DO NOTHING, StagedWrite is a file-existence no-op, and the SUBMITTED transition is
 * guarded ({@code WHERE spine_state='INITIALIZED'}). Every DB effect runs in its own
 * REQUIRES_NEW transaction under a 40001-aware retry (SERIALIZABLE; no READ COMMITTED
 * override, only PRG carries RC per SCRUM-90); the file write sits between the two.
 */
@Service
public class MandateSubmitService {

    private static final Logger log = LoggerFactory.getLogger(MandateSubmitService.class);
    private static final String INITIALIZED = "INITIALIZED";

    private final ManSubmitEntryRepo entries;
    private final ManRequestHeaderRepo headers;
    private final ManOutboundRepo outbound;
    private final ManSpineSubmitRepo spine;
    private final OutboundFileEmitter fileEmitter;
    private final TransactionTemplate requiresNewTx;

    public MandateSubmitService(final ManSubmitEntryRepo entries, final ManRequestHeaderRepo headers,
                                final ManOutboundRepo outbound, final ManSpineSubmitRepo spine,
                                final OutboundFileEmitter fileEmitter, final PlatformTransactionManager txManager) {
        this.entries = entries;
        this.headers = headers;
        this.outbound = outbound;
        this.spine = spine;
        this.fileEmitter = fileEmitter;
        // A fresh REQUIRES_NEW transaction per DB effect: a CRDB 40001 abort poisons
        // the surrounding transaction (25P02 on any further statement), so each retry
        // attempt needs its own transaction, and the write-ahead must commit strictly
        // before the file leaves.
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Emit the outbound pain messages for one arrival's INITIALIZED rows.
     *
     * @return the number of rows advanced to SUBMITTED in this run (0 on a clean replay).
     */
    public int submit(final UUID arrivalId) {
        final ManRequestHeaderView header = headers.findByArrivalId(arrivalId)
                .orElseThrow(() -> new IllegalStateException("no mandate_request_header for arrival " + arrivalId));
        final String client = header.client();
        int submitted = 0;
        for (final ManSubmitEntryView entry : entries.findByArrivalIdAndSpineStateOrderBySequence(arrivalId, INITIALIZED)) {
            submitted += emit(entry, client);
        }
        return submitted;
    }

    private int emit(final ManSubmitEntryView entry, final String client) {
        final PainType type = PainType.forAction(entry.getActionCode());
        final String outMsgId = OutMsgIdMinter.mint(entry.getArrivalId(), entry.getSequence(), entry.getActionCode());

        // WRITE-AHEAD (R-10): registry row commits BEFORE the file, keyed on the full
        // identity (entry_id) so a resume re-insert is a zero-duplicate no-op.
        CrdbRetry.run("write-ahead entry=%s".formatted(entry.getId()), () -> requiresNewTx.execute(status -> {
            outbound.writeAhead(ManOutboundEntity.of(entry.getId(), outMsgId, entry.getMndtReqId(), type.token()));
            return null;
        }));

        afterWriteAhead(entry); // crash-matrix seam (production no-op)

        fileEmitter.emit(type, entry, outMsgId, client); // StagedWrite file-existence restart no-op (R-05)

        // Guarded single-writer transition; 0 rows on a replay (already SUBMITTED).
        final int moved = CrdbRetry.run("submit entry=%s".formatted(entry.getId()),
                () -> requiresNewTx.execute(status -> spine.submit(entry.getId())));
        if (moved == 0) {
            log.warn("stage=MRW arrival={} seq={} entry={} already SUBMITTED on resume (transition no-op)",
                    entry.getArrivalId(), entry.getSequence(), entry.getId());
        }
        return moved;
    }

    /**
     * Crash-matrix seam: runs after the registry row has committed and before the
     * pain file lands. A kill here must leave a durable registry row and NO file, so
     * the resume re-emits the SAME deterministic id/file (write-ahead correctness).
     * Production no-op, package-private so the crash-resume IT can inject the kill.
     */
    void afterWriteAhead(final ManSubmitEntryView entry) {
        // production no-op: crash-matrix test seam
    }
}
