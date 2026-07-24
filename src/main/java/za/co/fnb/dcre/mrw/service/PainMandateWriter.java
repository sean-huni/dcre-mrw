package za.co.fnb.dcre.mrw.service;

import org.springframework.stereotype.Component;
import za.co.fnb.dcre.mrw.data.model.ManSubmitEntryView;
import za.co.fnb.dcre.mrw.domain.PainType;

import java.util.ArrayList;
import java.util.List;

/**
 * SYNTHETIC-CONTRACT (R-35, A-60): pain.009/.010/.011-shaped XML skeletons on the
 * RMB profile versions {@code .001.03}. Real bindings become JAXB from the
 * Fintegrate XSD profile when recovered (R-18); until then the writer hand-builds
 * a deterministic, per-type distinct message (same pattern as CRW's Pain008Writer)
 * carrying the three correlation identities the reply legs need: the outbound MsgId
 * (deterministic, R-10), the MndtReqId (MRR-minted, R-07) and the MndtId
 * (mandate_ref). CREATE emits the mandate initiation; AMEND/CANCEL wrap the change
 * around the original mandate reference.
 */
@Component
public class PainMandateWriter {

    public List<String> build(final PainType type, final ManSubmitEntryView entry, final String outMsgId,
                              final String orgnlE2e) {
        final List<String> xml = new ArrayList<>();
        xml.add("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.add("<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:%s\">".formatted(type.profile()));
        xml.add("  <!-- SYNTHETIC-CONTRACT %s skeleton (A-60) -->".formatted(type.token()));
        xml.add("  <%s>".formatted(type.root()));
        xml.add("    <GrpHdr><MsgId>%s</MsgId><NbOfMndts>1</NbOfMndts></GrpHdr>".formatted(outMsgId));
        switch (type) {
            case PAIN009 -> mandate(xml, entry, orgnlE2e);
            case PAIN010 -> {
                xml.add("    <UndrlygAmdmntDtls><OrgnlMndt><MndtId>%s</MndtId></OrgnlMndt>"
                        .formatted(entry.getMandateRef()));
                mandate(xml, entry, orgnlE2e);
                xml.add("    </UndrlygAmdmntDtls>");
            }
            case PAIN011 -> xml.add(("    <UndrlygCxlDtls><MndtReqId>%s</MndtReqId>"
                    + "<OrgnlEndToEndId>%s</OrgnlEndToEndId>"
                    + "<OrgnlMndt><MndtId>%s</MndtId></OrgnlMndt></UndrlygCxlDtls>")
                    .formatted(entry.getMndtReqId(), orgnlE2e, entry.getMandateRef()));
        }
        xml.add("  </%s>".formatted(type.root()));
        xml.add("</Document>");
        return xml;
    }

    /** The mandate detail block (CREATE + the AMEND target): correlation ids + the debit instruction. */
    private void mandate(final List<String> xml, final ManSubmitEntryView entry, final String orgnlE2e) {
        xml.add("    <Mndt>");
        xml.add("      <MndtReqId>%s</MndtReqId>".formatted(entry.getMndtReqId()));
        xml.add("      <MndtId>%s</MndtId>".formatted(entry.getMandateRef()));
        xml.add("      <OrgnlEndToEndId>%s</OrgnlEndToEndId>".formatted(orgnlE2e));
        xml.add("      <CdtrAcct><Id>%s</Id></CdtrAcct>".formatted(nz(entry.getCreditorAccount())));
        xml.add("      <Dbtr><Nm>%s</Nm></Dbtr>".formatted(nz(entry.getDebtorName())));
        xml.add("      <DbtrAcct><Id>%s</Id><Brnch>%s</Brnch></DbtrAcct>"
                .formatted(nz(entry.getDebtorAccount()), nz(entry.getDebtorBranch())));
        xml.add("      <MaxAmt Ccy=\"%s\">%s</MaxAmt>".formatted(entry.getCurrency(), entry.getMaxCollectionAmount()));
        xml.add("    </Mndt>");
    }

    private static String nz(final String value) {
        return value == null ? "" : value.strip();
    }
}
