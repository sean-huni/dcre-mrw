package za.co.fnb.dcre.mrw;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import za.co.fnb.dcre.mrw.domain.OutMsgIdMinter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MRW job over the real chain + CockroachDB + filesystem (fleet Testcontainers
 * pattern). Exercises: one outbound pain message per INITIALIZED spine row for
 * each action (pain.009 CREATE / pain.010 AMEND / pain.011 CANCEL) with the
 * write-ahead registry, the INITIALIZED -> SUBMITTED transition, the restart file
 * no-op (byte-identical, exactly one artifact per row), the nothing-to-emit no-op,
 * and the fail-closed unmapped action.
 */
class MrwJobIT extends MrwTestcontainersBase {

    @Autowired
    Job mrwJob;
    @Autowired
    JobOperator jobOperator;

    @BeforeEach
    void seedSpine() {
        ManTestTables.createSpine(jdbc);
    }

    private JobExecution run(final UUID arrival, final String attempt) throws Exception {
        final JobParametersBuilder b = new JobParametersBuilder().addString("arrival.id", arrival.toString(), true);
        if (attempt != null) {
            b.addString("attempt", attempt, true);
        }
        final JobParameters params = b.toJobParameters();
        return jobOperator.start(mrwJob, params);
    }

    private List<String> spineStates(final UUID arrival) {
        return jdbc.queryForList(
                "SELECT spine_state FROM mandate_request_entry WHERE arrival_id=? ORDER BY sequence",
                String.class, arrival);
    }

    private String painType(final UUID arrival, final int seq) {
        return jdbc.queryForObject("""
                SELECT o.pain_type FROM man_outbound o JOIN mandate_request_entry e ON e.id = o.entry_id
                WHERE e.arrival_id=? AND e.sequence=?""", String.class, arrival, seq);
    }

    @Test
    void emitsOnePainMessagePerInitializedRowPerActionAndSubmits() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 3);
        ManTestTables.insertInitialized(jdbc, arrival, 1, "CREATE", "MREF-A", "MRQ-A");
        ManTestTables.insertInitialized(jdbc, arrival, 2, "AMEND", "MREF-B", "MRQ-B");
        ManTestTables.insertInitialized(jdbc, arrival, 3, "CANCEL", "MREF-C", "MRQ-C");

        final JobExecution run = run(arrival, null);
        assertEquals(BatchStatus.COMPLETED, run.getStatus());

        assertEquals(List.of("SUBMITTED", "SUBMITTED", "SUBMITTED"), spineStates(arrival));
        assertEquals("PAIN009", painType(arrival, 1));
        assertEquals("PAIN010", painType(arrival, 2));
        assertEquals("PAIN011", painType(arrival, 3));

        assertPainFile(arrival, 1, "CREATE", "PAIN009", "pain.009.001.03", "<MndtInitnReq>");
        assertPainFile(arrival, 2, "AMEND", "PAIN010", "pain.010.001.03", "<MndtAmdmntReq>");
        assertPainFile(arrival, 3, "CANCEL", "PAIN011", "pain.011.001.03", "<MndtCxlReq>");

        // exactly one outbound artifact per row: three files, three registry rows, no .tmp leftovers.
        try (Stream<Path> files = Files.list(outDir("FNBCC01"))) {
            assertEquals(3, files.filter(Files::isRegularFile).count(), "one pain file per instruction row");
        }
        assertEquals(3, count("man_outbound o JOIN mandate_request_entry e ON e.id=o.entry_id WHERE e.arrival_id='"
                + arrival + "'"));
    }

    private void assertPainFile(final UUID arrival, final int seq, final String action, final String token,
                                final String profile, final String root) throws Exception {
        final String outMsgId = OutMsgIdMinter.mint(arrival, seq, action);
        final Path file = outDir("FNBCC01").resolve("FNBCC01_%s_%s.xml".formatted(outMsgId, token));
        assertTrue(Files.exists(file), "deterministic outbound file at " + file);
        final String xml = Files.readString(file);
        assertTrue(xml.contains(profile), "RMB profile version " + profile);
        assertTrue(xml.contains(root), "message root " + root);
        assertTrue(xml.contains("<MsgId>" + outMsgId + "</MsgId>"), "outbound MsgId in the message");
    }

    @Test
    void restartWithFilePresentIsANoOpWithExactlyOneArtifact() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBRF01", 1);
        ManTestTables.insertInitialized(jdbc, arrival, 1, "CREATE", "MREF-R", "MRQ-R");

        assertEquals(BatchStatus.COMPLETED, run(arrival, "1").getStatus());
        final String outMsgId = OutMsgIdMinter.mint(arrival, 1, "CREATE");
        final Path file = outDir("FNBRF01").resolve("FNBRF01_%s_PAIN009.xml".formatted(outMsgId));
        assertTrue(Files.exists(file));
        final byte[] before = Files.readAllBytes(file);
        final FileTime mtimeBefore = Files.getLastModifiedTime(file);

        // Fresh JobInstance re-processing the same arrival: the row is already SUBMITTED
        // and the file already present, so the re-run emits nothing new.
        assertEquals(BatchStatus.COMPLETED, run(arrival, "2").getStatus());

        assertEquals(List.of("SUBMITTED"), spineStates(arrival));
        assertEquals(before.length, Files.readAllBytes(file).length);
        assertEquals(mtimeBefore, Files.getLastModifiedTime(file), "restart never rewrites the landed file (R-05)");
        assertZeroDuplicateOutbound(arrival);
    }

    @Test
    void arrivalWithNoInitializedRowsEmitsNothing() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC02", 1);
        // spine row left at RECEIVED (not yet advanced to INITIALIZED by MIS)
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code, mandate_ref,
                    currency, max_collection_amount, mndt_req_id, spine_state)
                VALUES (?,1,'MD','CREATE','MREF-N','ZAR',100.00,'MRQ-N','RECEIVED')""", arrival);

        assertEquals(BatchStatus.COMPLETED, run(arrival, null).getStatus());
        assertEquals(List.of("RECEIVED"), spineStates(arrival), "MRW never touches a non-INITIALIZED row");
        assertEquals(0, count("man_outbound o JOIN mandate_request_entry e ON e.id=o.entry_id WHERE e.arrival_id='"
                + arrival + "'"));
    }

    @Test
    void unmappedActionFailsClosedWithoutRegistryRowOrFile() throws Exception {
        final UUID arrival = UUID.randomUUID();
        ManTestTables.insertHeader(jdbc, arrival, "FNBCC01", 1);
        ManTestTables.insertInitialized(jdbc, arrival, 1, "BOGUS", "MREF-X", "MRQ-X");

        final JobExecution run = run(arrival, null);
        assertEquals(BatchStatus.FAILED, run.getStatus(), "an unmapped action fails the job, never a silent skip");
        assertEquals(List.of("INITIALIZED"), spineStates(arrival), "the row is not advanced past its owned guard");
        assertEquals(0, count("man_outbound o JOIN mandate_request_entry e ON e.id=o.entry_id WHERE e.arrival_id='"
                + arrival + "'"), "fail-closed before the write-ahead: no registry row");
    }

    private void assertZeroDuplicateOutbound(final UUID arrival) {
        final Integer rows = count("man_outbound o JOIN mandate_request_entry e ON e.id=o.entry_id WHERE e.arrival_id='"
                + arrival + "'");
        final Integer distinctEntries = jdbc.queryForObject("""
                SELECT count(DISTINCT o.entry_id) FROM man_outbound o
                JOIN mandate_request_entry e ON e.id=o.entry_id WHERE e.arrival_id=?""", Integer.class, arrival);
        final Integer distinctMsgIds = jdbc.queryForObject("""
                SELECT count(DISTINCT o.out_msg_id) FROM man_outbound o
                JOIN mandate_request_entry e ON e.id=o.entry_id WHERE e.arrival_id=?""", Integer.class, arrival);
        assertEquals(rows, distinctEntries, "zero-duplicate audit: rows == distinct entry_id");
        assertEquals(rows, distinctMsgIds, "zero-duplicate audit: rows == distinct out_msg_id");
    }

    private Integer count(final String fromWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }
}
