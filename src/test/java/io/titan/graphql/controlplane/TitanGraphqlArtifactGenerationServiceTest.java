package io.titan.graphql.controlplane;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.titan.graphql.GraphqlException;
import io.titan.graphql.management.TitanGraphqlInMemoryManagementStore;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlValidationReportRef;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TitanGraphqlArtifactGenerationServiceTest {
    @Test
    void refusesSourceChangedAfterValidation() throws Exception {
        String source = source();
        String changed = source.replace("Titan GraphQL management API model", "Changed management API model");
        TitanGraphqlInMemoryManagementStore store = store(changed,
                TitanGraphqlModelDocumentJson.semanticHash(TitanGraphqlModelDocumentYaml.parse(source)),
                TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                "draft-001", TitanGraphqlValidationReportRef.ValidationReportStatus.PASS, 0);

        assertThrows(GraphqlException.class,
                () -> new TitanGraphqlArtifactGenerationService(store).prepare("draft-001", "preview", true));
    }

    @Test
    void refusesAReportForAnotherDraft() throws Exception {
        String source = source();
        TitanGraphqlInMemoryManagementStore store = store(source,
                TitanGraphqlModelDocumentJson.semanticHash(TitanGraphqlModelDocumentYaml.parse(source)),
                TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                "draft-other", TitanGraphqlValidationReportRef.ValidationReportStatus.PASS, 0);

        assertThrows(GraphqlException.class,
                () -> new TitanGraphqlArtifactGenerationService(store).prepare("draft-001", "preview", true));
    }

    @Test
    void refusesFailedReportsAndUnvalidatedDrafts() throws Exception {
        String source = source();
        String hash = TitanGraphqlModelDocumentJson.semanticHash(TitanGraphqlModelDocumentYaml.parse(source));
        TitanGraphqlInMemoryManagementStore failedReport = store(source, hash,
                TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                "draft-001", TitanGraphqlValidationReportRef.ValidationReportStatus.FAIL, 0);
        TitanGraphqlInMemoryManagementStore unvalidated = store(source, hash,
                TitanGraphqlModelDraft.ModelDraftStatus.IMPORTED,
                "draft-001", TitanGraphqlValidationReportRef.ValidationReportStatus.PASS, 0);
        TitanGraphqlInMemoryManagementStore reportedErrors = store(source, hash,
                TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                "draft-001", TitanGraphqlValidationReportRef.ValidationReportStatus.PASS, 1);

        assertThrows(GraphqlException.class,
                () -> new TitanGraphqlArtifactGenerationService(failedReport).prepare("draft-001", "preview", true));
        assertThrows(GraphqlException.class,
                () -> new TitanGraphqlArtifactGenerationService(unvalidated).prepare("draft-001", "preview", true));
        assertThrows(GraphqlException.class,
                () -> new TitanGraphqlArtifactGenerationService(reportedErrors).prepare("draft-001", "preview", true));
    }

    private static TitanGraphqlInMemoryManagementStore store(
            String source,
            String hash,
            TitanGraphqlModelDraft.ModelDraftStatus status,
            String reportDraftId,
            TitanGraphqlValidationReportRef.ValidationReportStatus reportStatus,
            int errors
    ) {
        TitanGraphqlInMemoryManagementStore store = new TitanGraphqlInMemoryManagementStore();
        store.saveDraft(new TitanGraphqlModelDraft(
                "draft-001", "model-001", status, TitanGraphqlModelDraft.SourceFormat.YAML,
                source, "", hash, "validation-001", "", "", "operator", "", ""));
        store.saveValidationReport(new TitanGraphqlValidationReportRef(
                "validation-001", reportDraftId, reportStatus,
                "validation", errors, 0, 0, false, ""));
        return store;
    }

    private static String source() throws Exception {
        return Files.readString(Path.of("src/test/resources/graphql/management.titan.graphql.yaml"));
    }
}
