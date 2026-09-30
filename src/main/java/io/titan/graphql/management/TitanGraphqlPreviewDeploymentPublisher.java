package io.titan.graphql.management;

import io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli;
import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlGap005ArtifactMetadata;
import io.titan.graphql.artifact.TitanGraphqlMutationPackageAttestor;
import io.titan.graphql.artifact.TitanGraphqlPackageBinding;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;

public final class TitanGraphqlPreviewDeploymentPublisher {

    public record Publication(
            TitanGraphqlPreviewBuildManifest manifest,
            Path descriptor,
            Path registry
    ) {
    }

    private TitanGraphqlPreviewDeploymentPublisher() {
    }

    public static Publication publish(
            TitanGraphqlDurableManagementStore store,
            TitanGraphqlPreviewBuild candidate,
            Path packageDirectory,
            String dialect,
            Path registryPath,
            DataSource servingDatabase
    ) {
        Objects.requireNonNull(store, "management store");
        Objects.requireNonNull(candidate, "preview build");
        Objects.requireNonNull(packageDirectory, "package directory");
        Objects.requireNonNull(registryPath, "preview registry path");
        Objects.requireNonNull(servingDatabase, "serving database");
        if (!candidate.id().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("preview build ID is invalid for the deployment registry");
        }
        if (!candidate.operationRegistryId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("preview operation registry ID is invalid for the descriptor");
        }

        String packageDescriptor = TitanGraphqlDatabaseFrontendDescriptorCli.descriptorContents(
                packageDirectory, dialect);
        TitanGraphqlPackageBinding binding = TitanGraphqlPackageBinding.read(packageDirectory);
        TitanGraphqlModelDraft draft = store.draft(candidate.draftId());
        if (draft == null || draft.sourceFormat() != TitanGraphqlModelDraft.SourceFormat.YAML
                || draft.sourceText().isBlank()) {
            throw new IllegalArgumentException("preview publication requires the reviewed YAML draft source");
        }
        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlArtifactsDirectory.readGap005Metadata(
                packageDirectory, TitanGraphqlArtifactsDirectory.PORTABLE_DISPLAY_ROOT);
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(draft.sourceText());
        binding.verify(document, metadata);
        TitanGraphqlMutationPackageAttestor.verify(document, packageDirectory, metadata);
        TitanGraphqlArtifactSetRef artifactSet = store.artifactSet(candidate.artifactSetId());
        TitanGraphqlArtifactEvidenceRef evidence = store.artifactEvidence(candidate.artifactSetId());
        if (artifactSet == null || evidence == null
                || !candidate.draftId().equals(artifactSet.draftId())
                || !stripHashPrefix(artifactSet.semanticHash()).equals(binding.modelSemanticSha256())
                || !evidence.artifactId().equals(binding.artifactId())
                || !evidence.verificationStatus().equals("passed")) {
            throw new IllegalArgumentException("preview package does not match verified artifact set and model");
        }
        if (candidate.artifactManifestHash().isBlank() == false
                && !stripHashPrefix(candidate.artifactManifestHash()).equals(binding.manifestContentSha256())) {
            throw new IllegalArgumentException("preview package manifest does not match preview build");
        }

        Path registry = registryPath.toAbsolutePath().normalize();
        Path parent = registry.getParent();
        try {
            Files.createDirectories(parent);
            try (FileChannel channel = FileChannel.open(parent.resolve(registry.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                Properties entries = readEntries(registry);
                String deploymentSnapshot = TitanGraphqlPreviewDeploymentAttestor.attest(
                        servingDatabase, packageDescriptor, candidate,
                        store.operationRegistry(candidate.operationRegistryId()));
                TitanGraphqlPreviewBuildManifest manifest = store.saveVerifiedPreviewBuild(candidate);
                if (!manifest.artifactManifestHash().equals(binding.manifestContentSha256())) {
                    throw new IllegalArgumentException("preview package manifest does not match verified build");
                }
                String descriptorText = packageDescriptor
                        + "preview-build-id=" + candidate.id() + "\n"
                        + "preview-expires-at=" + Instant.parse(manifest.expiration().expiresAt()) + "\n"
                        + "operation-registry-id=" + candidate.operationRegistryId() + "\n"
                        + "preview-deployment-sha256=" + deploymentSnapshot + "\n";
                String descriptorName = "preview-" + candidate.id() + "-" + sha256(descriptorText)
                        + ".properties";
                Path descriptor = parent.resolve(descriptorName);
                writeImmutableDescriptor(descriptor, descriptorText);
                entries.setProperty(candidate.id(), descriptor.toString());
                writeRegistry(registry, entries);
                return new Publication(manifest, descriptor, registry);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("preview deployment registry could not be published: " + registry,
                    failure);
        }
    }

    private static Properties readEntries(Path registry) throws IOException {
        Properties entries = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("duplicate preview build ID in registry: " + key);
                }
                return super.put(key, value);
            }
        };
        if (Files.exists(registry)) {
            try (Reader reader = Files.newBufferedReader(registry, StandardCharsets.UTF_8)) {
                entries.load(reader);
            }
        }
        Properties result = new Properties();
        result.putAll(entries);
        return result;
    }

    private static void writeImmutableDescriptor(Path descriptor, String contents) throws IOException {
        if (Files.exists(descriptor)) {
            if (!Files.readString(descriptor, StandardCharsets.UTF_8).equals(contents)) {
                throw new IllegalStateException("published preview descriptor bytes changed: " + descriptor);
            }
            return;
        }
        Path temporary = Files.createTempFile(descriptor.getParent(), ".preview-descriptor-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            move(temporary, descriptor);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeRegistry(Path registry, Properties entries) throws IOException {
        Path temporary = Files.createTempFile(registry.getParent(), ".preview-registry-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                entries.store(writer, "Titan GraphQL preview deployment registry");
            }
            move(temporary, registry);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String stripHashPrefix(String hash) {
        return hash != null && hash.startsWith("sha256:") ? hash.substring(7) : hash;
    }

    private static String sha256(String contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(contents.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 digest is not available", unavailable);
        }
    }
}
