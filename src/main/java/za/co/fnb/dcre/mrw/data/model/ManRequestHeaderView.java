package za.co.fnb.dcre.mrw.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.util.UUID;

/**
 * MRW read model over the MRR-owned {@code mandate_request_header}: the client
 * that owns the outbound exchange dirs + filename prefix. {@code client_token} is
 * the initiating client (nullable), {@code destination_id} the mandatory routing
 * client; MRW resolves the outbound client as the former, falling back to the
 * latter.
 */
@Table("mandate_request_header")
public class ManRequestHeaderView extends BaseEntity {

    private UUID arrivalId;
    private String clientToken;
    private String destinationId;

    public UUID getArrivalId() { return arrivalId; }
    public String getClientToken() { return clientToken; }
    public String getDestinationId() { return destinationId; }

    /** Fail-closed outbound client: client_token when present, else the mandatory destination_id. */
    public String client() {
        return (clientToken != null && !clientToken.isBlank()) ? clientToken : destinationId;
    }
}
