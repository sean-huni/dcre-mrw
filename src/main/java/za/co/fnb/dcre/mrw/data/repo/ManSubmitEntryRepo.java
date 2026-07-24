package za.co.fnb.dcre.mrw.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mrw.data.model.ManSubmitEntryView;

import java.util.List;
import java.util.UUID;

/**
 * Read side of the MRR-owned mandate_request_entry spine. MRW reads the rows an
 * arrival has advanced to INITIALIZED (MIS is the single writer of INITIALIZED);
 * all spine_state WRITES go through {@link ManSpineSubmitRepo}.
 */
public interface ManSubmitEntryRepo extends CrudRepository<ManSubmitEntryView, UUID> {

    List<ManSubmitEntryView> findByArrivalIdAndSpineStateOrderBySequence(UUID arrivalId, String spineState);
}
