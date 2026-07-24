package za.co.fnb.dcre.mrw.service;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin entry adapter (3-tier): reads the identifying {@code arrival.id}
 * JobParameter (R-16) and delegates the whole outbound emission to the business
 * tier. The count of rows advanced to SUBMITTED this run is recorded in the step
 * ExecutionContext for observability.
 */
@Component
public class SubmitTasklet implements Tasklet {

    private final MandateSubmitService service;

    public SubmitTasklet(final MandateSubmitService service) {
        this.service = service;
    }

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final UUID arrivalId = UUID.fromString(
                (String) chunkContext.getStepContext().getJobParameters().get("arrival.id"));
        final int submitted = service.submit(arrivalId);
        chunkContext.getStepContext().getStepExecution().getExecutionContext().putInt("submitted", submitted);
        return RepeatStatus.FINISHED;
    }
}
