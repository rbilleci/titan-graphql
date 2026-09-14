package io.titan.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.titan.graphql.demo.blog.DemoBlogTitanGraphqlFunctions;
import io.titan.graphql.demo.blog.DemoBlogGraphqlRuntime;
import io.titan.graphql.management.TitanGraphqlManagementRuntime;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import titan.dsl.StoredFunction;

class GraphqlRuntimeBoundaryTest {

    @Test
    void activeRuntimeIsDemoBlogAdapterForCurrentProof() {
        assertEquals(DemoBlogGraphqlRuntime.NAME, GraphqlRuntimeRegistry.activeRuntime().name());
    }

    @Test
    void managementRuntimeIsSiblingAdapterForAdminPath() {
        assertEquals(TitanGraphqlManagementRuntime.NAME, GraphqlRuntimeRegistry.managementRuntime().name());
    }

    @Test
    void httpResourceRoutesThroughRuntimeRegistryInsteadOfStoredFunctionClass() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/titan/graphql/GraphqlHttpResource.java"));

        assertFalse(source.contains("TitanGraphqlFunctions."),
                "HTTP transport should route through the active model runtime boundary");
    }

    @Test
    void adminHttpResourceRoutesThroughManagementRuntimeBoundary() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/titan/graphql/GraphqlAdminHttpResource.java"));

        assertTrue(source.contains("GraphqlRuntimeRegistry.managementRuntime()"),
                "admin transport should route through the management model runtime boundary");
        assertFalse(source.contains("GraphqlEngine.execute"),
                "admin transport should not execute the management model directly");
        assertFalse(source.contains("GraphqlManagementDataModel"),
                "admin transport should not construct management model internals directly");
    }

    @Test
    void coreTransportDoesNotDependOnDemoExecutorOrFixtures() throws IOException {
        assertCoreSourceDoesNotContainDemoMaterialization("src/main/java/io/titan/graphql/GraphqlHttpResource.java");
        assertCoreSourceDoesNotContainDemoMaterialization("src/main/java/io/titan/graphql/GraphqlEngine.java");
        assertCoreSourceDoesNotContainDemoMaterialization("src/main/java/io/titan/graphql/TitanGraphqlFunctions.java");
    }

    @Test
    void storedFunctionAnnotationsBelongToDemoKernelInsteadOfCompatibilityFacade() {
        assertTrue(hasPublicStoredFunction(DemoBlogTitanGraphqlFunctions.class),
                "demo-blog adapter should own the SQL-lowerable stored-function kernel");
        assertFalse(hasPublicStoredFunction(TitanGraphqlFunctions.class),
                "core compatibility facade should not be the SQL-lowerable kernel");
    }

    @Test
    void genericJsonWriterStaysFreeOfDemoResultMaterialization() throws IOException {
        String source = Files.readString(Path.of("src/main/java/io/titan/graphql/GraphqlJsonWriter.java"));

        assertFalse(source.contains("DemoBlog"), "generic error JSON writer should not know a demo adapter");
        assertFalse(source.contains("ArticleRow"), "generic error JSON writer should not materialize demo articles");
        assertFalse(source.contains("CommentRow"), "generic error JSON writer should not materialize demo comments");
        assertFalse(source.contains("UserRow"), "generic error JSON writer should not materialize demo users");
    }

    @Test
    void coreSourcesDoNotImportDemoBlogAdapterPackage() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java/io/titan/graphql"))) {
            List<Path> coreSources = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/demo/blog/") == false)
                    .toList();

            for (Path sourcePath : coreSources) {
                String source = Files.readString(sourcePath);
                assertFalse(
                        source.contains("import io.titan.graphql.demo.blog"),
                        sourcePath + " should not import demo-blog adapter code"
                );
            }
        }
    }

    @Test
    void compiledProductionPathContainsNoDemoOrSchemaNameDispatch() throws IOException {
        for (String sourcePath : List.of(
                "src/main/java/io/titan/graphql/GraphqlExecutionEngine.java",
                "src/main/java/io/titan/graphql/TitanCompiledGraphqlRuntime.java",
                "src/main/java/io/titan/graphql/TitanCompiledGraphqlDataModel.java",
                "src/main/java/io/titan/graphql/GraphqlVariableCoercer.java",
                "src/main/java/io/titan/graphql/codegen/TitanGraphqlRoutineSourceGenerator.java")) {
            String source = Files.readString(Path.of(sourcePath));
            for (String forbidden : List.of(
                    "DemoBlog", "demo.blog", "Article", "articles", "Customer", "customers")) {
                assertFalse(source.contains(forbidden),
                        sourcePath + " must not dispatch on schema-specific name '" + forbidden + "'");
            }
        }
    }

    private static void assertCoreSourceDoesNotContainDemoMaterialization(String sourcePath) throws IOException {
        String source = Files.readString(Path.of(sourcePath));

        assertFalse(source.contains("DemoBlogFixtureStore"), sourcePath + " should not depend on demo fixture storage");
        assertFalse(source.contains("DemoBlogGraphqlExecutor"), sourcePath + " should not depend on demo executor");
        assertFalse(source.contains("DemoBlogGraphqlJsonWriter"), sourcePath + " should not depend on demo result writer");
    }

    private static boolean hasPublicStoredFunction(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())
                    && method.isAnnotationPresent(StoredFunction.class)) {
                return true;
            }
        }
        return false;
    }
}
