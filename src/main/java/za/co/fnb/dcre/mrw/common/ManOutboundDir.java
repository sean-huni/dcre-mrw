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

    /** The property naming the exchange root every outbound leaf hangs off. */
    static final String EXCHANGE_ROOT = "dcre.exchange-root";

    private final Path exchangeRoot;

    public ManOutboundDir(@Value("${" + EXCHANGE_ROOT + "}") final String exchangeRoot) {
        this.exchangeRoot = Path.of(requiredExchangeRoot(exchangeRoot));
    }

    /**
     * Names the missing property, and this stage, before the value can reach {@link Path#of}.
     * Unguarded, an absent value dies inside the JDK's filesystem code with a
     * NullPointerException that names neither the property nor this stage. Blank is the worse
     * half and is why this is not left to Spring's placeholder resolution: a blank value
     * resolves, builds an empty relative path with no NullPointerException, and MRW then
     * writes every client's outbound leaf under the process working directory instead of the
     * exchange root, silently and successfully.
     */
    private static String requiredExchangeRoot(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MRW requires the property '" + EXCHANGE_ROOT
                    + "': it was " + (value == null ? "not supplied" : "blank ('" + value + "')")
                    + ". Set " + EXCHANGE_ROOT + "=<exchange root directory>, or DCRE_EXCHANGE_ROOT"
                    + " in the environment.");
        }
        return value;
    }

    public Path outFor(final String client) {
        return exchangeRoot.resolve(client.toLowerCase(Locale.ROOT))
                .resolve(ExchangeChannel.FINT_REQ_MAN.token())
                .resolve(ExchangeSub.OUT.token());
    }
}
