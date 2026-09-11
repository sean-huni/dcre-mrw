package za.co.fnb.dcre.mrw.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Red-proofs the {@code dcre.exchange-root} guard.
 *
 * <p>Unguarded, an absent value dies inside the JDK's filesystem code with a NullPointerException
 * from {@code Objects.requireNonNull} that names neither the property nor this stage: the same
 * defect measured on the reader stages on a live cluster on 2026-09-11, arriving through a
 * configuration property rather than a job parameter.
 *
 * <p>The blank case is the one that matters more here, and it is why this is not left to Spring's
 * placeholder resolution. A blank value RESOLVES, builds an empty relative path with no
 * NullPointerException, and MRW then writes every client's outbound leaf under the process working
 * directory instead of the exchange root, silently and successfully. The third case asserts the
 * relative-path outcome that the guard removes is no longer reachable, and the fourth is the
 * control proving a real value is still resolved exactly as before.
 */
class ManOutboundDirGuardTest {

    @Test
    void anAbsentExchangeRootNamesThePropertyAndTheStage() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new ManOutboundDir(null));

        assertEquals("MRW requires the property 'dcre.exchange-root': it was not supplied."
                        + " Set dcre.exchange-root=<exchange root directory>, or DCRE_EXCHANGE_ROOT"
                        + " in the environment.",
                thrown.getMessage(),
                "the message must name the property and the stage; the NullPointerException it"
                        + " replaces named neither");
    }

    @Test
    void anEmptyExchangeRootIsMissingToo() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new ManOutboundDir(""));

        assertEquals("MRW requires the property 'dcre.exchange-root': it was blank ('')."
                        + " Set dcre.exchange-root=<exchange root directory>, or DCRE_EXCHANGE_ROOT"
                        + " in the environment.",
                thrown.getMessage(),
                "an empty value builds an empty path with no NullPointerException at all, so every"
                        + " outbound file would land under the working directory");
    }

    @Test
    void aWhitespaceOnlyExchangeRootIsMissingToo() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> new ManOutboundDir("  "));

        assertEquals("MRW requires the property 'dcre.exchange-root': it was blank ('  ')."
                        + " Set dcre.exchange-root=<exchange root directory>, or DCRE_EXCHANGE_ROOT"
                        + " in the environment.",
                thrown.getMessage(),
                "whitespace-only is a legal directory name on a POSIX filesystem, which is exactly"
                        + " why it must be refused rather than created");
    }

    @Test
    void aSuppliedExchangeRootStillResolvesTheSameLeaf() {
        assertEquals(Path.of("/dcre/exchange/abc/fint-req-man/out"),
                new ManOutboundDir("/dcre/exchange").outFor("ABC"),
                "the healthy path must be byte-identical: client lowercased, then the mandate"
                        + " outbound channel and its out sub-directory");
    }
}
