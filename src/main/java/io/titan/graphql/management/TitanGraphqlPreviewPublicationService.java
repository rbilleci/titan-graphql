package io.titan.graphql.management;

import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;

public final class TitanGraphqlPreviewPublicationService {
    private final TitanGraphqlDurableManagementStore store;
    private final DataSource servingDatabase;

    public TitanGraphqlPreviewPublicationService(
            TitanGraphqlDurableManagementStore store,
            DataSource servingDatabase
    ) {
        this.store = Objects.requireNonNull(store, "management store");
        this.servingDatabase = Objects.requireNonNull(servingDatabase, "serving database");
    }

    public TitanGraphqlPreviewDeploymentPublisher.Publication publish(
            String previewBuildId,
            String draftId,
            String environment,
            String operationRegistryId,
            Instant expiresAt,
            String createdBy,
            Path packageDirectory,
            String dialect,
            Path registryPath
    ) {
        Objects.requireNonNull(expiresAt, "preview expiration");
        TitanGraphqlModelDraft draft = store.draft(draftId);
        if (draft == null || draft.status() != TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW
                || draft.artifactSetId().isBlank()) {
            throw new IllegalArgumentException("preview publication requires a generated READY_FOR_REVIEW draft");
        }
        if (draft.sourceFormat() != TitanGraphqlModelDraft.SourceFormat.YAML || draft.sourceText().isBlank()) {
            throw new IllegalArgumentException("preview publication requires the reviewed YAML draft source");
        }
        TitanGraphqlArtifactSetRef artifactSet = store.artifactSet(draft.artifactSetId());
        String sourceHash = TitanGraphqlModelDocumentJson.semanticHash(
                TitanGraphqlModelDocumentYaml.parse(draft.sourceText()));
        if (artifactSet == null || !artifactSet.draftId().equals(draft.id())
                || !sourceHash.equals(draft.semanticHash())
                || !sourceHash.equals(artifactSet.semanticHash())) {
            throw new IllegalArgumentException("preview draft source and generated artifact set do not match");
        }
        TitanGraphqlPreviewBuild candidate = new TitanGraphqlPreviewBuild(
                previewBuildId, draft.modelId(), draft.id(), artifactSet.id(), environment,
                TitanGraphqlPreviewBuild.PreviewBuildStatus.READY,
                "/preview/" + previewBuildId + "/graphql", "", artifactSet.sdlHash(), "", "",
                operationRegistryId, expiresAt.toString(), createdBy, Instant.now().toString());
        return TitanGraphqlPreviewDeploymentPublisher.publish(
                store, candidate, packageDirectory, dialect, registryPath, servingDatabase);
    }
}
