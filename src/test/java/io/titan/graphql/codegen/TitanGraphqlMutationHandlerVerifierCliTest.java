package io.titan.graphql.codegen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TitanGraphqlMutationHandlerVerifierCliTest {
    private static final Path SOURCE_ROOT = Path.of("src/database-engine/java");
    private static final Path MODEL = Path.of("src/test/resources/graphql/commerce.titan.graphql.yaml");
    private static final Path MANAGEMENT_MODEL = Path.of(
            "src/main/resources/graphql/management-database.titan.graphql.yaml");

    @Test
    void acceptsCompiledReviewedHandlerSignature() throws Exception {
        TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse(Files.readString(MODEL));
        TitanGraphqlModelDocument management = TitanGraphqlModelDocumentYaml.parse(
                Files.readString(MANAGEMENT_MODEL));

        assertDoesNotThrow(() -> TitanGraphqlMutationHandlerVerifierCli.verifyAll(
                List.of(model, management), SOURCE_ROOT, getClass().getClassLoader()));
    }

    @Test
    void rejectsUnregisteredSourceAndMismatchedSignature() throws Exception {
        String source = Files.readString(MODEL);
        TitanGraphqlModelDocument outsideTree = TitanGraphqlModelDocumentYaml.parse(source.replace(
                "className: io.titan.graphql.database.handlers.CommerceMutationProcedures",
                "className: io.titan.graphql.database.DatabaseGraphqlEngine"));
        TitanGraphqlModelDocument wrongMethod = TitanGraphqlModelDocumentYaml.parse(source.replace(
                "methodName: renameCustomer", "methodName: missingMethod"));
        TitanGraphqlModelDocument missingNullableFlags = TitanGraphqlModelDocumentYaml.parse(source.replace(
                "methodName: setNickname", "methodName: renameCustomer"));

        IllegalArgumentException sourceFailure = assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlMutationHandlerVerifierCli.verify(
                        outsideTree, SOURCE_ROOT, getClass().getClassLoader()));
        IllegalArgumentException signatureFailure = assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlMutationHandlerVerifierCli.verify(
                        wrongMethod, SOURCE_ROOT, getClass().getClassLoader()));
        IllegalArgumentException nullableSignatureFailure = assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlMutationHandlerVerifierCli.verify(
                        missingNullableFlags, SOURCE_ROOT, getClass().getClassLoader()));

        assertTrue(sourceFailure.getMessage().contains("must name a handler"));
        assertTrue(signatureFailure.getMessage().contains("missingMethod(Connection, long, String)"));
        assertTrue(nullableSignatureFailure.getMessage().contains(
                "renameCustomer(Connection, long, String, boolean, boolean)"));
    }

    @Test
    void rejectsCompiledHandlerWithoutModelRegistration() throws Exception {
        String source = Files.readString(MODEL);
        TitanGraphqlModelDocument model = TitanGraphqlModelDocumentYaml.parse(source.replaceFirst(
                "(?s)  renameCustomerWithProcedure:.*?(?=  renameCustomerWithInput:)", ""));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlMutationHandlerVerifierCli.verify(
                        model, SOURCE_ROOT, getClass().getClassLoader()));

        assertTrue(failure.getMessage().contains(
                "CommerceMutationProcedures.renameCustomer(Connection, long, String)"));
    }

    @Test
    void trustedContextHandlerRequiresMatchingSignature() throws Exception {
        String source = Files.readString(MANAGEMENT_MODEL);
        TitanGraphqlModelDocument withoutContext = TitanGraphqlModelDocumentYaml.parse(
                source.replaceFirst("(?s)(  requestModelImport:.*?includeTrustedContext: )true", "$1false"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlMutationHandlerVerifierCli.verify(
                        withoutContext, SOURCE_ROOT, getClass().getClassLoader()));

        assertTrue(failure.getMessage().contains(
                "requestModelImport(Connection, String, String, String)"));
    }

    @Test
    void verifiesHandlersRegisteredBySeparateModels(@TempDir Path sourceRoot) throws Exception {
        String source = Files.readString(MODEL);
        TitanGraphqlModelDocument commerce = TitanGraphqlModelDocumentYaml.parse(source);
        TitanGraphqlModelDocument secondary = TitanGraphqlModelDocumentYaml.parse(source.replace(
                "className: io.titan.graphql.database.handlers.CommerceMutationProcedures",
                "className: io.titan.graphql.database.handlers.SecondaryMutationProcedures"));
        Path handlerRoot = sourceRoot.resolve("io/titan/graphql/database/handlers");
        Files.createDirectories(handlerRoot);
        Files.copy(SOURCE_ROOT.resolve("io/titan/graphql/database/handlers/CommerceMutationProcedures.java"),
                handlerRoot.resolve("CommerceMutationProcedures.java"));
        Files.copy(Path.of("src/test/java/io/titan/graphql/database/handlers/SecondaryMutationProcedures.java"),
                handlerRoot.resolve("SecondaryMutationProcedures.java"));

        assertDoesNotThrow(() -> TitanGraphqlMutationHandlerVerifierCli.verifyAll(
                List.of(commerce, secondary), sourceRoot, getClass().getClassLoader()));
        Path commerceFile = sourceRoot.resolve("commerce.yaml");
        Path secondaryFile = sourceRoot.resolve("secondary.yaml");
        Files.writeString(commerceFile, source);
        Files.writeString(secondaryFile, source.replace(
                "className: io.titan.graphql.database.handlers.CommerceMutationProcedures",
                "className: io.titan.graphql.database.handlers.SecondaryMutationProcedures"));
        assertDoesNotThrow(() -> TitanGraphqlMutationHandlerVerifierCli.main(new String[] {
                commerceFile.toString(), secondaryFile.toString(), sourceRoot.toString()
        }));
        IllegalArgumentException orphan = assertThrows(IllegalArgumentException.class,
                () -> TitanGraphqlMutationHandlerVerifierCli.verify(
                        commerce, sourceRoot, getClass().getClassLoader()));
        assertTrue(orphan.getMessage().contains("SecondaryMutationProcedures."));
    }
}
