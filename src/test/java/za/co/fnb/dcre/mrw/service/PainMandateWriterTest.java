package za.co.fnb.dcre.mrw.service;

import org.junit.jupiter.api.Test;
import za.co.fnb.dcre.mrw.data.model.ManSubmitEntryView;
import za.co.fnb.dcre.mrw.domain.PainType;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SYNTHETIC-CONTRACT pain writer (A-60): each action code emits its own ISO 20022
 * message on the RMB {@code .001.03} profile, carrying the three correlation
 * identities the reply legs map through (out MsgId, MndtReqId, MndtId).
 */
class PainMandateWriterTest {

    private final PainMandateWriter writer = new PainMandateWriter();

    private static ManSubmitEntryView entry(final String action, final String ref, final String mndtReqId) {
        final ManSubmitEntryView e = mock(ManSubmitEntryView.class);
        when(e.getActionCode()).thenReturn(action);
        when(e.getMandateRef()).thenReturn(ref);
        when(e.getMndtReqId()).thenReturn(mndtReqId);
        when(e.getCreditorAccount()).thenReturn("6200000021");
        when(e.getDebtorAccount()).thenReturn("6299999999");
        when(e.getDebtorBranch()).thenReturn("250655");
        when(e.getDebtorName()).thenReturn("ACME PTY LTD");
        when(e.getCurrency()).thenReturn("ZAR");
        when(e.getMaxCollectionAmount()).thenReturn(new BigDecimal("100.00"));
        return e;
    }

    @Test
    void createBuildsPain009InitiationCarryingAllThreeCorrelationIds() {
        final String xml = String.join("\n",
                writer.build(PainType.PAIN009, entry("CREATE", "MREF-A", "MRQ-A"), "OMS-A"));
        assertTrue(xml.contains("pain.009.001.03"), "RMB profile version");
        assertTrue(xml.contains("<MndtInitnReq>"), "pain.009 initiation root");
        assertTrue(xml.contains("SYNTHETIC-CONTRACT PAIN009 skeleton (A-60)"), "synthetic-contract marker");
        assertTrue(xml.contains("<MsgId>OMS-A</MsgId>"), "outbound MsgId");
        assertTrue(xml.contains("<MndtReqId>MRQ-A</MndtReqId>"), "MRR-minted MndtReqId");
        assertTrue(xml.contains("<MndtId>MREF-A</MndtId>"), "mandate ref as MndtId");
        assertTrue(xml.contains("<MaxAmt Ccy=\"ZAR\">100.00</MaxAmt>"), "the debit instruction");
    }

    @Test
    void amendBuildsPain010AroundTheOriginalMandate() {
        final String xml = String.join("\n",
                writer.build(PainType.PAIN010, entry("AMEND", "MREF-B", "MRQ-B"), "OMS-B"));
        assertTrue(xml.contains("pain.010.001.03"), "RMB profile version");
        assertTrue(xml.contains("<MndtAmdmntReq>"), "pain.010 amendment root");
        assertTrue(xml.contains("<UndrlygAmdmntDtls>"), "wraps the underlying amendment");
        assertTrue(xml.contains("<OrgnlMndt><MndtId>MREF-B</MndtId></OrgnlMndt>"), "references the original mandate");
        assertTrue(xml.contains("<MsgId>OMS-B</MsgId>"), "outbound MsgId");
    }

    @Test
    void cancelBuildsPain011AroundTheOriginalMandate() {
        final String xml = String.join("\n",
                writer.build(PainType.PAIN011, entry("CANCEL", "MREF-C", "MRQ-C"), "OMS-C"));
        assertTrue(xml.contains("pain.011.001.03"), "RMB profile version");
        assertTrue(xml.contains("<MndtCxlReq>"), "pain.011 cancellation root");
        assertTrue(xml.contains("<UndrlygCxlDtls>"), "wraps the underlying cancellation");
        assertTrue(xml.contains("<OrgnlMndt><MndtId>MREF-C</MndtId></OrgnlMndt>"), "references the original mandate");
        assertTrue(xml.contains("<MndtReqId>MRQ-C</MndtReqId>"), "MndtReqId correlation");
    }
}
