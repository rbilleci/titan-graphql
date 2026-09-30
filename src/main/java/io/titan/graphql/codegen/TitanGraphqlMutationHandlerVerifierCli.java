package io.titan.graphql.codegen;

import io.titan.graphql.model.TitanGraphqlFieldDocument;
import io.titan.graphql.model.TitanGraphqlModelDocument;
import io.titan.graphql.model.TitanGraphqlModelDocumentYaml;
import io.titan.graphql.model.TitanGraphqlMutationDocument;
import io.titan.graphql.model.TitanGraphqlTypeDocument;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TitanGraphqlMutationHandlerVerifierCli {
    private static final String HANDLER_PACKAGE = "io.titan.graphql.database.handlers.";

    private TitanGraphqlMutationHandlerVerifierCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: TitanGraphqlMutationHandlerVerifierCli "
                    + "<model.yaml>... <database-engine-source-root>");
        }
        List<TitanGraphqlModelDocument> models = new ArrayList<>();
        for (int index = 0; index < args.length - 1; index++) {
            models.add(TitanGraphqlModelDocumentYaml.parse(Files.readString(Path.of(args[index]))));
        }
        verifyAll(models, Path.of(args[args.length - 1]), Thread.currentThread().getContextClassLoader());
    }

    public static void verify(TitanGraphqlModelDocument model, Path sourceRoot, ClassLoader loader) {
        verifyAll(List.of(model), sourceRoot, loader);
    }

    public static void verifyAll(
            List<TitanGraphqlModelDocument> models,
            Path sourceRoot,
            ClassLoader loader
    ) {
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException("at least one reviewed model is required");
        }
        Path root = sourceRoot.toAbsolutePath().normalize();
        Set<Method> registered = new HashSet<>();
        for (TitanGraphqlModelDocument model : models) {
            if (model == null) {
                throw new IllegalArgumentException("reviewed model must not be null");
            }
            verifyModel(model, root, loader, registered);
        }
        verifyNoUnregisteredHandlers(root, loader, registered);
    }

    private static void verifyModel(
            TitanGraphqlModelDocument model,
            Path root,
            ClassLoader loader,
            Set<Method> registered
    ) {
        for (TitanGraphqlMutationDocument mutation : model.mutations()) {
            if (mutation.operation() == TitanGraphqlMutationDocument.MutationDocumentOperation.UPDATE) {
                continue;
            }
            TitanGraphqlMutationDocument.MutationDocumentHandler handler = mutation.handler();
            if (handler == null || !handler.className().startsWith(HANDLER_PACKAGE)) {
                throw new IllegalArgumentException("procedure mutation '" + mutation.name()
                        + "' must name a handler in " + HANDLER_PACKAGE);
            }
            Path source = root.resolve(handler.className().replace('.', '/') + ".java").normalize();
            if (!source.startsWith(root) || !Files.isRegularFile(source)) {
                throw new IllegalArgumentException("procedure mutation '" + mutation.name()
                        + "' has no reviewed source file: " + source);
            }
            Class<?> handlerClass;
            try {
                handlerClass = Class.forName(handler.className(), false, loader);
            } catch (ClassNotFoundException | LinkageError failure) {
                throw new IllegalArgumentException("procedure mutation '" + mutation.name()
                        + "' handler class did not compile: " + handler.className(), failure);
            }
            TitanGraphqlTypeDocument type = model.types().stream()
                    .filter(candidate -> candidate.name().equals(mutation.type()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "procedure mutation '" + mutation.name() + "' has no target type"));
            List<Class<?>> parameters = new ArrayList<>();
            parameters.add(Connection.class);
            if (mutation.input() == null) {
                mutation.arguments().stream()
                        .sorted(Comparator.comparing(TitanGraphqlMutationDocument.MutationDocumentArgument::name))
                        .forEach(argument -> {
                            parameters.add(bindingType(model, type, argument.type(), argument.column()));
                            if (argument.nullable()) {
                                parameters.add(boolean.class);
                                parameters.add(boolean.class);
                            }
                        });
            } else {
                mutation.inputBindings().stream()
                        .sorted(Comparator.comparing(TitanGraphqlMutationDocument.MutationDocumentInputBinding::name))
                        .forEach(binding -> {
                            parameters.add(bindingType(model, type, binding.type(), binding.column()));
                            if (binding.nullable()) {
                                parameters.add(boolean.class);
                                parameters.add(boolean.class);
                            }
                        });
            }
            if (handler.includeTrustedContext()) {
                parameters.add(String.class);
            }
            Method method;
            try {
                method = handlerClass.getDeclaredMethod(handler.methodName(), parameters.toArray(Class<?>[]::new));
            } catch (NoSuchMethodException failure) {
                throw new IllegalArgumentException("procedure mutation '" + mutation.name()
                        + "' handler must declare " + signature(handler.methodName(), parameters), failure);
            }
            if (!Modifier.isPublic(handlerClass.getModifiers())
                    || !Modifier.isPublic(method.getModifiers())
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != void.class) {
                throw new IllegalArgumentException("procedure mutation '" + mutation.name()
                        + "' handler must be a public static void method");
            }
            for (java.lang.annotation.Annotation annotation : method.getDeclaredAnnotations()) {
                if (annotation.annotationType().getName().startsWith("titan.dsl.")) {
                    throw new IllegalArgumentException("procedure mutation '" + mutation.name()
                            + "' handler must be source-local, not a public Titan entry point");
                }
            }
            registered.add(method);
        }
    }

    private static void verifyNoUnregisteredHandlers(Path root, ClassLoader loader, Set<Method> registered) {
        Path handlerRoot = root.resolve(HANDLER_PACKAGE.replace('.', '/'));
        if (!Files.isDirectory(handlerRoot)) {
            return;
        }
        List<Path> sources;
        try (var paths = Files.walk(handlerRoot)) {
            sources = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new IllegalArgumentException("reviewed mutation handler sources could not be listed", failure);
        }
        for (Path source : sources) {
            String relative = root.relativize(source).toString().replace('/', '.').replace('\\', '.');
            String className = relative.substring(0, relative.length() - ".java".length());
            Class<?> handlerClass;
            try {
                handlerClass = Class.forName(className, false, loader);
            } catch (ClassNotFoundException | LinkageError failure) {
                throw new IllegalArgumentException(
                        "reviewed mutation handler source did not compile: " + className, failure);
            }
            for (Method method : handlerClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == void.class
                        && parameters.length > 0
                        && parameters[0] == Connection.class
                        && !registered.contains(method)) {
                    throw new IllegalArgumentException("reviewed mutation handler is not registered: "
                            + className + "." + signature(method.getName(), List.of(parameters)));
                }
            }
        }
    }

    private static Class<?> bindingType(
            TitanGraphqlModelDocument model,
            TitanGraphqlTypeDocument type,
            String graphqlType,
            String column
    ) {
        TitanGraphqlFieldDocument field = type.fields().stream()
                .filter(candidate -> column.equals(candidate.column()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "procedure binding has no stored field for column '" + column + "'"));
        if (graphqlType.equals("Int") || graphqlType.equals("Long")
                || graphqlType.equals("ID")
                && field.idStorage() == TitanGraphqlFieldDocument.FieldDocumentIdStorage.INTEGRAL) {
            return long.class;
        }
        if (graphqlType.equals("Boolean")) {
            return boolean.class;
        }
        if (graphqlType.equals("Float") || graphqlType.equals("Decimal")) {
            return double.class;
        }
        if (graphqlType.equals("ID") || graphqlType.equals("String") || graphqlType.equals("UUID")
                || model.enums().stream().anyMatch(candidate -> candidate.name().equals(graphqlType))) {
            return String.class;
        }
        throw new IllegalArgumentException("procedure binding has unsupported type '" + graphqlType + "'");
    }

    private static String signature(String methodName, List<Class<?>> parameters) {
        return methodName + "(" + parameters.stream().map(Class::getSimpleName)
                .reduce((left, right) -> left + ", " + right).orElse("") + ")";
    }
}
