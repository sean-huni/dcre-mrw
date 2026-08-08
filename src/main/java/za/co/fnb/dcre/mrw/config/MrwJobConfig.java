package za.co.fnb.dcre.mrw.config;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import za.co.fnb.dcre.mrw.service.SubmitTasklet;
import za.co.fnb.dcre.platform.batch.CrdbRetryExceptionHandler;
import za.co.fnb.dcre.platform.batch.HeartbeatWriter;
import za.co.fnb.dcre.platform.batch.OutcomeSeamListener;

/**
 * MRW job shape (single tasklet, un-partitioned: one instruction book is low
 * volume so the whole arrival is one emission pass, KISS): submitStep emits one
 * pain.009/.010/.011 per INITIALIZED spine row (write-ahead man_outbound, then the
 * StagedWrite, then the guarded spine_state INITIALIZED -> SUBMITTED). Identifying
 * JobParameter: arrival.id (R-16). Runs on the default SERIALIZABLE isolation (no
 * READ COMMITTED override; only CRG carries RC per SCRUM-90).
 */
@Configuration
public class MrwJobConfig {

    /**
     * CRDB 40001 retry for the tasklet that WRITES business rows (the outbound
     * registry + the guarded spine transition): the aborts hit the chunk-commit
     * boundary, which only a stepOperations-level handler sees.
     */
    private final CrdbRetryExceptionHandler crdbRetry = new CrdbRetryExceptionHandler("MRW");

    @Bean
    public Step submitStep(final JobRepository repo, final PlatformTransactionManager tx,
                           final SubmitTasklet tasklet) {
        return new StepBuilder("submitStep", repo).tasklet(tasklet, tx).exceptionHandler(crdbRetry).build();
    }

    @Bean
    public Job mrwJob(final JobRepository repo, final Step submitStep, final HeartbeatWriter heartbeatWriter,
                      @Value("${dcre.exchange-root}") final String exchangeRoot) {
        // SCRUM-58: shared platform-batch seam listener (COMPLETED-gated, constant
        // BUSINESS_ACCEPTED verdict; local fallback local-mrw-<executionId>).
        // SCRUM-85: heartbeatWriter ticks agt_ops.launch_intent while the job runs.
        return new JobBuilder("mrwJob", repo)
                .listener(new OutcomeSeamListener("mrw", exchangeRoot, execution -> "BUSINESS_ACCEPTED"))
                .listener(heartbeatWriter)
                .start(submitStep)
                .build();
    }
}
