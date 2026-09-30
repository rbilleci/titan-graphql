package io.titan.graphql.controlplane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TitanGraphqlArtifactJobCliTest {
    @Test
    void controlJobApiRejectsNonLoopbackBinding() {
        var dataSource = new DriverManagerDataSource("jdbc:postgresql://example.invalid/control", "", "",
                TitanGraphqlControlJobQueue.Dialect.POSTGRESQL);
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlControlJobHttpServer.start(
                new InetSocketAddress("0.0.0.0", 0), dataSource,
                TitanGraphqlControlJobQueue.Dialect.POSTGRESQL, "operator-token"));
    }

    @Test
    void rejectsInvalidCommandBeforeOpeningDatabase(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactJobCli.main(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactJobCli.main(new String[] {
                "request", "postgresql", "jdbc:postgresql://example.invalid/control",
                "key", "draft", "development", "sometimes"
        }));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactJobCli.main(new String[] {
                "run-one", "mysql", "jdbc:mysql://example.invalid/control", "management.log", "0"
        }));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactJobCli.main(new String[] {
                "run-one", "mysql", "jdbc:mysql://example.invalid/control",
                directory.resolve("missing-management-transaction-log").toString(), "30"
        }));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactJobCli.main(new String[] {
                "run-worker", "mysql", "jdbc:mysql://example.invalid/control",
                "management.log", "3601", "100"
        }));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactJobCli.main(new String[] {
                "run-worker", "mysql", "jdbc:mysql://example.invalid/control",
                "management.log", "30", "0"
        }));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactJobCli.main(new String[] {
                "run-worker", "mysql", "jdbc:mysql://example.invalid/control",
                "management.log", "30", "100", "0"
        }));
        IllegalArgumentException fileWorker = assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlArtifactJobCli.main(new String[] {
                        "run-worker", "mysql", "jdbc:mysql://example.invalid/control",
                        "management.log", "30", "100"
                }));
        assertEquals("continuous artifact worker requires JDBC management state", fileWorker.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactJobCli.main(new String[] {
                "check-worker-ready", "mysql", "jdbc:mysql://example.invalid/control",
                directory.resolve("missing-management-transaction-log").toString()
        }));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlArtifactJobCli.main(new String[] {
                "publish-preview", "postgresql", "jdbc:postgresql://example.invalid/control",
                directory.resolve("package").toString(), directory.resolve("registry.properties").toString(),
                "preview-001", "draft-001", "preview", "registry-001", "not-an-instant", "operator"
        }));
    }
}
