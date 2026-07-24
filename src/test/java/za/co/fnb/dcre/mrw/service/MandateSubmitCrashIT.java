package za.co.fnb.dcre.mrw.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mrw.ManTestTables;
import za.co.fnb.dcre.mrw.MrwTestcontainersBase;
import za.co.fnb.dcre.mrw.data.model.ManSubmitEntryView;
import za.co.fnb.dcre.mrw.data.repo.ManOutboundRepo;
import za.co.fnb.dcre.mrw.data.repo.ManRequestHeaderRepo;
import za.co.fnb.dcre.mrw.data.repo.ManSpineSubmitRepo;
import za.co.fnb.dcre.mrw.data.repo.ManSubmitEntryRepo;
import za.co.fnb.dcre.mrw.domain.OutMsgIdMinter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-matrix (chaos gate, engineering.md): a SIGKILL in the gap between the
 * write-ahead registry commit and the pain StagedWrite must leave a durable
 * registry row and NO file, and the resume must re-emit the SAME deterministic
 * id/file, yielding EXACTLY ONE outbound artifact + one registry row per
 * instruction row (zero-duplicate audit). Package {@code ...mrw.service} so the
 * test can override the package-private crash seam, mirroring CRW's EmissionSplitIT.
 */
class MandateSubmitCrashIT extends MrwTestcontainersBase {

    @Autowired
    ManSubmitEntryRepo entries;
    @Autowired
    ManRequestHeaderRepo headers;
    @Autowired
    ManOutboundRepo outbound;
    @Autowired
    ManSpineSubmitRepo spine;
    @Autowired
    OutboundFileEmitter fileEmitter;
    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void seedSpine() {
        ManTestTables.createSpine(jdbc);
    }

    private int outboundRows(final UUID arrival) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM man_outbound o JOIN mandate_request_entry e ON e.id=o.entry_id
                WHERE e.arrival_id=?""", Integer.class, arrival);
    }

    private long filesInOut(final String client) throws Exception {
        final Path dir = outDir(client);
        if (!Files.exists(dir)) {
            return 0; // StagedWrite never created the leaf: no file was ever written
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void killBetweenWriteAheadAndFileResumesToExactlyOneArtifactWithTheSameId() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC03", 1);
        ManTestTables.insertInitialized(jdbc, arrival, 1, "CREATE", "MREF-K", "MRQ-K");
        final String expectedMsgId = OutMsgIdMinter.mint(arrival, 1, "CREATE");

        final KillAfterWriteAheadService killable =
                new KillAfterWriteAheadService(entries, headers, outbound, spine, fileEmitter, txManager);

        // Kill in the gap: write-ahead committed, file never written.
        killable.arm();
        assertThrows(IllegalStateException.class, () -> killable.submit(arrival));

        assertEquals(1, outboundRows(arrival), "write-ahead: the registry row committed BEFORE the file");
        assertEquals(0, filesInOut("FNBCC03"), "no pain file left by the crash (registry precedes the file)");
        assertEquals(List.of("INITIALIZED"), jdbc.queryForList(
                "SELECT spine_state FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                String.class, arrival), "spine not advanced: the transition never ran");
        final String msgIdAtCrash = jdbc.queryForObject("""
                SELECT o.out_msg_id FROM man_outbound o JOIN mandate_request_entry e ON e.id=o.entry_id
                WHERE e.arrival_id=?""", String.class, arrival);
        assertEquals(expectedMsgId, msgIdAtCrash, "the write-ahead id is deterministic from the row identity");

        // Resume: re-emit the SAME id/file, transition to SUBMITTED.
        killable.heal();
        assertEquals(1, killable.submit(arrival), "resume advances exactly the one unfinished row");

        assertEquals(1, outboundRows(arrival), "zero-duplicate: still exactly one registry row after resume");
        assertEquals(1, filesInOut("FNBCC03"), "exactly one outbound artifact per instruction row");
        assertEquals(List.of("SUBMITTED"), jdbc.queryForList(
                "SELECT spine_state FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                String.class, arrival));
        final Path file = outDir("FNBCC03").resolve("FNBCC03_%s_PAIN009.xml".formatted(expectedMsgId));
        assertTrue(Files.exists(file), "the resume re-emits the SAME deterministic filename");
        assertEquals(expectedMsgId, jdbc.queryForObject("""
                SELECT o.out_msg_id FROM man_outbound o JOIN mandate_request_entry e ON e.id=o.entry_id
                WHERE e.arrival_id=?""", String.class, arrival), "out_msg_id unchanged across the resume");
    }

    /**
     * Kill switch: overrides the package-private production seam that runs after the
     * registry row commits and before the file lands, so the test crashes exactly in
     * that gap. Everything else is the real service against real beans.
     */
    static final class KillAfterWriteAheadService extends MandateSubmitService {

        private volatile boolean armed;

        KillAfterWriteAheadService(final ManSubmitEntryRepo entries, final ManRequestHeaderRepo headers,
                                   final ManOutboundRepo outbound, final ManSpineSubmitRepo spine,
                                   final OutboundFileEmitter fileEmitter, final PlatformTransactionManager txManager) {
            super(entries, headers, outbound, spine, fileEmitter, txManager);
        }

        void arm() {
            armed = true;
        }

        void heal() {
            armed = false;
        }

        @Override
        void afterWriteAhead(final ManSubmitEntryView entry) {
            if (armed) {
                throw new IllegalStateException(
                        "simulated kill: registry row committed for entry %s, file never written"
                                .formatted(entry.getId()));
            }
        }
    }
}
