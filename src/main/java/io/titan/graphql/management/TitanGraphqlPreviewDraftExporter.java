package io.titan.graphql.management;

import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class TitanGraphqlPreviewDraftExporter {
    private TitanGraphqlPreviewDraftExporter() {
    }

    public record Export(String draftId, String modelId, String semanticHash, Path source) {
    }

    public static Export export(TitanGraphqlDurableManagementStore store, String draftId, Path output) {
        Objects.requireNonNull(store, "management store");
        Objects.requireNonNull(output, "draft source output");
        TitanGraphqlModelDraft draft = validatedDraft(store, draftId);
        String semanticHash = draft.semanticHash();
        Path source = output.toAbsolutePath().normalize();
        Path parent = source.getParent();
        try {
            Files.createDirectories(parent);
            try (FileChannel channel = FileChannel.open(parent.resolve(source.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                if (Files.exists(source)) {
                    if (!Files.readString(source, StandardCharsets.UTF_8).equals(draft.sourceText())) {
                        throw new IllegalStateException("preview draft output already contains different source: "
                                + source);
                    }
                } else {
                    Path temporary = Files.createTempFile(parent, ".preview-draft-", ".tmp");
                    try {
                        Files.writeString(temporary, draft.sourceText(), StandardCharsets.UTF_8);
                        Files.move(temporary, source, StandardCopyOption.ATOMIC_MOVE);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("preview draft source could not be exported: " + source, failure);
        }
        return new Export(draft.id(), draft.modelId(), semanticHash, source);
    }

    static TitanGraphqlModelDraft validatedDraft(TitanGraphqlDurableManagementStore store, String draftId) {
        Objects.requireNonNull(store, "management store");
        TitanGraphqlModelDraft draft = store.draft(draftId);
        if (draft == null || (draft.status() != TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED
                && draft.status() != TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW)
                || draft.sourceFormat() != TitanGraphqlModelDraft.SourceFormat.YAML
                || draft.sourceText().isBlank()) {
            throw new IllegalArgumentException("preview package build requires a validated YAML draft");
        }
        TitanGraphqlValidationReportRef report = store.validationReport(draft.validationReportId());
        if (report == null || !draft.id().equals(report.draftId())
                || report.status() == TitanGraphqlValidationReportRef.ValidationReportStatus.FAIL
                || report.errors() != 0 || report.blocksDeployment()) {
            throw new IllegalArgumentException("preview package build requires a passing draft validation report");
        }
        String semanticHash = TitanGraphqlModelDocumentJson.semanticHash(
                TitanGraphqlModelDocumentYaml.parse(draft.sourceText()));
        if (!semanticHash.equals(draft.semanticHash())) {
            throw new IllegalArgumentException("preview draft source changed after validation");
        }
        return draft;
    }
}
