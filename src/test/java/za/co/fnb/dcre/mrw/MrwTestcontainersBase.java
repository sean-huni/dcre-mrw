package za.co.fnb.dcre.mrw;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared Testcontainers base for the MRW ITs (fleet pattern): one CockroachDB
 * container + one Spring context (context caching) across subclasses, Liquibase on
 * (man_outbound + shared core), batch schema never re-initialized, job auto-run
 * off, outbound files under a per-run temp exchange root.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false"})
public abstract class MrwTestcontainersBase {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static final Path EXCHANGE = freshExchangeRoot();

    static {
        CRDB.start();
    }

    static Path freshExchangeRoot() {
        try {
            return Files.createTempDirectory("mrw-it");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.exchange-root", EXCHANGE::toString);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /** The client's fint-req-man/out leaf under the shared exchange root (lowercased base). */
    protected Path outDir(final String client) {
        return EXCHANGE.resolve(client.toLowerCase()).resolve("fint-req-man").resolve("out");
    }
}
