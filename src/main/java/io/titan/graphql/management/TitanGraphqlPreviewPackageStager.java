package io.titan.graphql.management;

import io.titan.graphql.artifact.TitanGraphqlArtifactsDirectory;
import io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli;
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
import java.util.Objects;
import java.util.Properties;

public final class TitanGraphqlPreviewPackageStager {
    private TitanGraphqlPreviewPackageStager() {
    }

    public record Stage(String draftId, Path packageDirectory, Path registry) {
    }

    public static Stage stage(
            TitanGraphqlDurableManagementStore store,
            String draftId,
            Path packageDirectory,
            String dialect,
            Path registryPath
    ) {
        Objects.requireNonNull(packageDirectory, "preview package directory");
        Objects.requireNonNull(registryPath, "artifact package registry");
        TitanGraphqlModelDraft draft = TitanGraphqlPreviewDraftExporter.validatedDraft(store, draftId);
        Path selectedPackage = packageDirectory.toAbsolutePath().normalize();
        TitanGraphqlGap005ArtifactMetadata metadata = TitanGraphqlArtifactsDirectory.readGap005Metadata(
                selectedPackage, TitanGraphqlArtifactsDirectory.PORTABLE_DISPLAY_ROOT);
        TitanGraphqlModelDocument document = TitanGraphqlModelDocumentYaml.parse(draft.sourceText());
        TitanGraphqlPackageBinding.read(selectedPackage).verify(document, metadata);
        TitanGraphqlMutationPackageAttestor.verify(document, selectedPackage, metadata);
        TitanGraphqlDatabaseFrontendDescriptorCli.descriptorContents(selectedPackage, dialect);
        Path registry = registryPath.toAbsolutePath().normalize();
        Path parent = registry.getParent();
        try {
            Files.createDirectories(parent);
            try (FileChannel channel = FileChannel.open(parent.resolve(registry.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                Properties entries = readEntries(registry);
                String existing = entries.getProperty(draftId);
                if (existing != null) {
                    Path configured = Path.of(existing);
                    Path existingPackage = (configured.isAbsolute()
                            ? configured : parent.resolve(configured)).normalize();
                    if (!existingPackage.equals(selectedPackage)) {
                        throw new IllegalStateException("draft already selects a different artifact package: "
                                + draftId);
                    }
                    return new Stage(draftId, selectedPackage, registry);
                }
                entries.setProperty(draftId, selectedPackage.toString());
                Path temporary = Files.createTempFile(parent, ".artifact-packages-", ".tmp");
                try {
                    try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                        entries.store(writer, "Titan GraphQL draft package registry");
                    }
                    Files.move(temporary, registry, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("artifact package registry could not be staged: " + registry,
                    failure);
        }
        return new Stage(draftId, selectedPackage, registry);
    }

    private static Properties readEntries(Path registry) throws IOException {
        Properties entries = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) {
                    throw new IllegalArgumentException("duplicate draft ID in artifact package registry: " + key);
                }
                return super.put(key, value);
            }
        };
        if (Files.exists(registry)) {
            try (Reader reader = Files.newBufferedReader(registry, StandardCharsets.UTF_8)) {
                entries.load(reader);
            }
        }
        return entries;
    }
}
