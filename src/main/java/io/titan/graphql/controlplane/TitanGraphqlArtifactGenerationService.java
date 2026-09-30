package io.titan.graphql.controlplane;

import io.titan.graphql.GraphqlException;
import io.titan.graphql.TitanGraphqlGeneratedArtifactWorkflow;
import io.titan.graphql.artifact.TitanGraphqlArtifactSet;
import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlGeneratedArtifactSet;
import io.titan.graphql.artifact.TitanGraphqlIntrospectionArtifactPolicy;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
import io.titan.graphql.management.TitanGraphqlArtifactSetRef;
import io.titan.graphql.management.TitanGraphqlDurableManagementStore;
import io.titan.graphql.management.TitanGraphqlManagementStore;
import io.titan.graphql.management.TitanGraphqlModelDraft;
import io.titan.graphql.management.TitanGraphqlValidationReportRef;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentJson;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class TitanGraphqlArtifactGenerationService {
    private final TitanGraphqlManagementStore store;
    private final Function<String, Path> packageDirectory;

    public TitanGraphqlArtifactGenerationService(TitanGraphqlManagementStore store) {
        this(store, ignored -> TitanGraphqlArtifactsDirectory.configuredDirectory());
    }

    public TitanGraphqlArtifactGenerationService(
            TitanGraphqlManagementStore store,
            Function<String, Path> packageDirectory
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.packageDirectory = Objects.requireNonNull(packageDirectory, "package directory selector");
    }

    public record Result(
            String draftId,
            String artifactSetId,
            String semanticHash,
            String sdlHash,
            String introspectionHash,
            String conformanceHash,
            String generatedSqlHash,
            int generatedArtifacts
    ) {
        public Map<String, Object> payload() {
            return Map.of(
                    "accepted", true,
                    "draftId", draftId,
                    "artifactSetId", artifactSetId,
                    "semanticHash", semanticHash,
                    "sdlHash", sdlHash,
                    "introspectionHash", introspectionHash,
                    "conformanceHash", conformanceHash,
                    "generatedSqlHash", generatedSqlHash,
                    "generatedArtifacts", generatedArtifacts);
        }
    }

    record Prepared(
            TitanGraphqlArtifactSetRef artifactSet,
            TitanGraphqlModelDraft draft,
            TitanGraphqlGap005ArtifactMetadata metadata,
            Result result
    ) {
    }

    public Result generate(String draftId, String generationProfile, boolean enableIntrospection) {
        Prepared prepared = prepare(draftId, generationProfile, enableIntrospection);
        publish(prepared);
        return prepared.result();
    }

    Prepared prepare(String draftId, String generationProfile, boolean enableIntrospection) {
        if (draftId == null || draftId.isBlank()) {
            throw new GraphqlException("model draft id is required for artifact generation");
        }
        String profile = generationProfile == null || generationProfile.isBlank()
                ? "development" : generationProfile;
        TitanGraphqlModelDraft draft = store.draft(draftId);
        if (draft == null) {
            throw new GraphqlException("unknown model draft '" + draftId + "'");
        }
        if ((draft.status() != TitanGraphqlModelDraft.ModelDraftStatus.VALIDATED
                && draft.status() != TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW)
                || draft.sourceFormat() != TitanGraphqlModelDraft.SourceFormat.YAML
                || draft.sourceText().isBlank()) {
            throw new GraphqlException("draft '" + draftId + "' requires validated YAML source");
        }
        TitanGraphqlValidationReportRef validation = store.validationReport(draft.validationReportId());
        if (validation == null || !draft.id().equals(validation.draftId())
                || validation.status() == TitanGraphqlValidationReportRef.ValidationReportStatus.FAIL
                || validation.errors() != 0 || validation.blocksDeployment()) {
            throw new GraphqlException("draft '" + draftId + "' must pass validation before artifacts can be generated");
        }
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(draft.sourceText());
        if (!TitanGraphqlModelDocumentJson.semanticHash(document).equals(draft.semanticHash())) {
            throw new GraphqlException("draft '" + draftId + "' source changed after validation");
        }
        String artifactSetId = "artifact-" + draftId;
        Path selectedPackage = document.artifacts().generateSql()
                ? Objects.requireNonNull(packageDirectory.apply(draftId), "selected package directory")
                : null;
        TitanGraphqlGap005ArtifactMetadata gap005Metadata = document.artifacts().generateSql()
                ? TitanGraphqlArtifactsDirectory.readGap005Metadata(
                        selectedPackage, TitanGraphqlArtifactsDirectory.PORTABLE_DISPLAY_ROOT)
                : null;
        TitanGraphqlPackageBinding packageBinding = document.artifacts().generateSql()
                ? TitanGraphqlPackageBinding.read(selectedPackage)
                : null;
        TitanGraphqlGeneratedArtifactSet generated = TitanGraphqlGeneratedArtifactWorkflow.generateFromModelDocument(
                artifactSetId,
                draftId,
                document,
                enableIntrospection
                        ? TitanGraphqlIntrospectionArtifactPolicy.ENABLED
                        : TitanGraphqlIntrospectionArtifactPolicy.DISABLED,
                profile,
                "",
                gap005Metadata,
                packageBinding
        );
        TitanGraphqlArtifactSet manifest = generated.manifest();
        TitanGraphqlArtifactSetRef ref = new TitanGraphqlArtifactSetRef(
                manifest.id(),
                manifest.draftId(),
                manifest.semanticHash(),
                manifest.validationHash(),
                manifest.driftHash(),
                manifest.sdlHash(),
                manifest.introspectionHash(),
                manifest.conformanceHash(),
                manifest.generatedSqlHash(),
                manifest.generationProfile(),
                manifest.createdAt()
        );
        TitanGraphqlModelDraft generatedDraft = new TitanGraphqlModelDraft(
                draft.id(),
                draft.modelId(),
                TitanGraphqlModelDraft.ModelDraftStatus.READY_FOR_REVIEW,
                draft.sourceFormat(),
                draft.sourceText(),
                draft.canonicalJson(),
                draft.semanticHash(),
                draft.validationReportId(),
                ref.id(),
                draft.driftReportId(),
                draft.createdBy(),
                draft.createdAt(),
                ""
        );
        Result result = new Result(
                draftId,
                ref.id(),
                ref.semanticHash(),
                ref.sdlHash(),
                ref.introspectionHash(),
                ref.conformanceHash(),
                ref.generatedSqlHash(),
                generated.artifacts().size());
        return new Prepared(ref, generatedDraft, gap005Metadata, result);
    }

    void publish(Prepared prepared) {
        if (store instanceof TitanGraphqlDurableManagementStore durableStore) {
            durableStore.saveGeneratedArtifacts(prepared.artifactSet(), prepared.draft(), prepared.metadata());
        } else {
            store.saveArtifactSet(prepared.artifactSet());
            store.saveDraft(prepared.draft());
        }
    }

    boolean supportsAtomicJobCompletion() {
        return store instanceof TitanGraphqlDurableManagementStore durableStore
                && durableStore.supportsAtomicArtifactJobCompletion();
    }

    boolean publishAndComplete(
            Prepared prepared,
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlControlJobQueue.ClaimedJob job,
            String resultJson
    ) {
        if (!(store instanceof TitanGraphqlDurableManagementStore durableStore)) {
            throw new IllegalStateException("atomic artifact job completion requires durable management state");
        }
        return durableStore.saveGeneratedArtifactsAndCompleteJob(
                prepared.artifactSet(), prepared.draft(), prepared.metadata(), queue, job, resultJson);
    }
}
