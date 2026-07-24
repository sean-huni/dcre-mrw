package za.co.fnb.dcre.mrw.data.repo;

import org.springframework.data.repository.CrudRepository;
import za.co.fnb.dcre.mrw.data.model.ManRequestHeaderView;

import java.util.Optional;
import java.util.UUID;

/**
 * Read side of the MRR-owned mandate_request_header (shared dcre_man): MRW reads
 * only the client that owns the outbound exchange dirs + pain filename prefix.
 */
public interface ManRequestHeaderRepo extends CrudRepository<ManRequestHeaderView, UUID> {

    Optional<ManRequestHeaderView> findByArrivalId(UUID arrivalId);
}
