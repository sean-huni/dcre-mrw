package za.co.fnb.dcre.mrw.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.fnb.dcre.platform.files.ExchangeChannel;
import za.co.fnb.dcre.platform.files.ExchangeSub;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves a client's {@code fint-req-man/out} leaf under the exchange root, the
 * dedicated mandate outbound channel (ruling note 4). The on-disk convention is
 * uniform across the fleet: {@code <client-lowercased>/<channel-token>/<sub>}
 * (the collections {@code fint-req/out} analog), so the leaf is derived
 * mechanically from the {@link ExchangeChannel#FINT_REQ_MAN} + {@link ExchangeSub#OUT}
 * tokens rather than from the shared platform {@code ExchangeLayout}: the man
 * channels are not carried in the platform-batch layout until the AGT wiring (T9)
 * lands, and deriving the leaf here keeps MRW self-contained and correct for every
 * client the spine references. StagedWrite creates the leaf on first write.
 */
@Component
public class ManOutboundDir {

    private final Path exchangeRoot;

    public ManOutboundDir(@Value("${dcre.exchange-root}") final String exchangeRoot) {
        this.exchangeRoot = Path.of(exchangeRoot);
    }

    public Path outFor(final String client) {
        return exchangeRoot.resolve(client.toLowerCase(Locale.ROOT))
                .resolve(ExchangeChannel.FINT_REQ_MAN.token())
                .resolve(ExchangeSub.OUT.token());
    }
}
