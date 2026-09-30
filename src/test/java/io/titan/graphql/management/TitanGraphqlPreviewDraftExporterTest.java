package io.titan.graphql.management;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TitanGraphqlPreviewDraftExporterTest {
    @Test
    void exportsValidatedDraftAndRefusesChangedSource(@TempDir Path directory) throws Exception {
        String source = Files.readString(Path.of("src/test/resources/graphql/demo-blog.titan.graphql.yaml"));
        String semanticHash = TitanGraphqlModelDocumentJson.semanticHash(
                TitanGraphqlModelDocumentYaml.parse(source));
        TitanGraphqlDurableManagementStore store = new TitanGraphqlDurableManagementStore(
                directory.resolve("management.log"));
        store.saveModel(new TitanGraphqlManagedModel(
                "model-001", "workspace-001", "demo-blog", "", "", "draft-001", "",
                "validation-001", "", "", ""));
        store.saveDraft(new TitanGraphqlModelDraft(
                "draft-001", "model-001", TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                TitanGraphqlModelDraft.SourceFormat.YAML, source, "", semanticHash,
                "validation-001", "", "", "operator", "", ""));
        store.saveValidationReport(new TitanGraphqlValidationReportRef(
                "validation-001", "draft-001",
                TitanGraphqlValidationReportRef.ValidationReportStatus.PASS,
                "passed", 0, 0, 0, false, ""));
        Path output = directory.resolve("candidate/model.yaml");

        TitanGraphqlPreviewDraftExporter.Export exported = TitanGraphqlPreviewDraftExporter.export(
                store, "draft-001", output);
        assertEquals(semanticHash, exported.semanticHash());
        assertEquals(source, Files.readString(output));
        assertEquals(exported, TitanGraphqlPreviewDraftExporter.export(store, "draft-001", output));

        Files.writeString(output, "different source");
        assertThrows(IllegalStateException.class,
                () -> TitanGraphqlPreviewDraftExporter.export(store, "draft-001", output));
        assertEquals("different source", Files.readString(output));

        store.saveValidationReport(new TitanGraphqlValidationReportRef(
                "validation-001", "draft-001",
                TitanGraphqlValidationReportRef.ValidationReportStatus.FAIL,
                "failed", 0, 0, 0, false, ""));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlPreviewDraftExporter.export(
                store, "draft-001", directory.resolve("failed-report/model.yaml")));
        store.saveValidationReport(new TitanGraphqlValidationReportRef(
                "validation-001", "draft-001",
                TitanGraphqlValidationReportRef.ValidationReportStatus.PASS,
                "passed", 0, 0, 0, false, ""));

        store.saveDraft(new TitanGraphqlModelDraft(
                "draft-001", "model-001", TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED,
                TitanGraphqlModelDraft.SourceFormat.YAML, source.replace("demo-blog", "changed-blog"), "",
                semanticHash, "validation-001", "", "", "operator", "", ""));
        assertThrows(IllegalArgumentException.class, () -> TitanGraphqlPreviewDraftExporter.export(
                store, "draft-001", directory.resolve("stale/model.yaml")));
    }
}
