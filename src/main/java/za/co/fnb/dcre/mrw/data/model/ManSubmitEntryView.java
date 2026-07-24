package za.co.fnb.dcre.mrw.data.model;

import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * MRW read model over the MRR-owned {@code mandate_request_entry} (shared
 * dcre_man): the spine columns the pain writer serialises plus the deterministic
 * {@code mndt_req_id} MRR minted. MRW writes only the {@code spine_state} column
 * it owns (ruling note 2, INITIALIZED -> SUBMITTED) via the guarded transition on
 * {@code ManSpineSubmitRepo}, never through this entity.
 */
@Table("mandate_request_entry")
public class ManSubmitEntryView extends BaseEntity {

    private UUID arrivalId;
    private Integer sequence;
    private String actionCode;
    private String mandateRef;
    private String contractRef;
    private String creditorAccount;
    private String debtorAccount;
    private String debtorBranch;
    private String debtorName;
    private String currency;
    private BigDecimal maxCollectionAmount;
    private String mndtReqId;
    private String spineState;

    public UUID getArrivalId() { return arrivalId; }
    public Integer getSequence() { return sequence; }
    public String getActionCode() { return actionCode; }
    public String getMandateRef() { return mandateRef; }
    public String getContractRef() { return contractRef; }
    public String getCreditorAccount() { return creditorAccount; }
    public String getDebtorAccount() { return debtorAccount; }
    public String getDebtorBranch() { return debtorBranch; }
    public String getDebtorName() { return debtorName; }
    public String getCurrency() { return currency; }
    public BigDecimal getMaxCollectionAmount() { return maxCollectionAmount; }
    public String getMndtReqId() { return mndtReqId; }
    public String getSpineState() { return spineState; }
}
