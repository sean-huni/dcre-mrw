package za.co.fnb.dcre.mrw.service;

import org.springframework.stereotype.Service;
import za.co.fnb.dcre.mrw.common.ManOutboundDir;
import za.co.fnb.dcre.mrw.data.model.ManSubmitEntryView;
import za.co.fnb.dcre.mrw.domain.PainType;
import za.co.fnb.dcre.platform.files.StagedWrite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Boundary file write (R-24): builds the synthetic pain message and lands it in
 * the client's {@code fint-req-man/out} leaf via {@link StagedWrite} (tmp +
 * fsync + ATOMIC_MOVE). The filename is deterministic from the outbound identity
 * ({@code <CLIENT>_<outMsgId>_<PAIN00x>.xml}, the CRW {@code fint-req/out}
 * convention + the T10 fint-sim glob), so a restart with the file already present
 * is a no-op (R-05), never a rewrite, and the artifact-per-row count stays exactly
 * one. One outbound message per instruction row (no batch splitting in v1).
 */
@Service
public class OutboundFileEmitter {

    private final PainMandateWriter painWriter;
    private final ManOutboundDir outboundDir;

    public OutboundFileEmitter(final PainMandateWriter painWriter, final ManOutboundDir outboundDir) {
        this.painWriter = painWriter;
        this.outboundDir = outboundDir;
    }

    /** @return true when the file was written; false when it already existed (restart no-op). */
    public boolean emit(final PainType type, final ManSubmitEntryView entry, final String outMsgId,
                        final String client) {
        final List<String> xml = painWriter.build(type, entry, outMsgId);
        final String fileName = "%s_%s_%s.xml".formatted(client, outMsgId, type.token());
        final Path target = outboundDir.outFor(client).resolve(fileName);
        try {
            return StagedWrite.write(target, xml);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
