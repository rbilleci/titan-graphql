import io.titan.gradle.TitanExtension
import io.titan.gradle.TitanPackageTask
import io.titan.gradle.TitanTranspileTask
import io.titan.gradle.TitanVerifyInstallTask
import org.gradle.api.tasks.Sync
import org.gradle.language.jvm.tasks.ProcessResources
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    java
    id("io.titan.gradle")
}

group = "io.titan.graphql"
version = "0.1.0"
// Capture project identity during configuration. Verification actions must not reach through a
// Task to `project` at execution time, which Gradle 10 treats as an error.
val titanGraphqlProjectName = project.name
val titanGraphqlProjectVersion = project.version.toString()
repositories {
    mavenCentral()
}

tasks.named<ProcessResources>("processResources") {
    from("docs/query-contract-conformance.md") {
        into("docs")
    }
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation("io.titan:titan-dsl:0.1.0")
    implementation("io.titan:titan-management:0.1.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.7")
    implementation("io.titan:titan-runtime-jdbc:0.1.0") {
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.testcontainers")
        exclude(group = "com.mysql")
    }
    runtimeOnly("com.mysql:mysql-connector-j:8.4.0")
    runtimeOnly("com.google.protobuf:protobuf-java:3.25.5")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.titan:titan-runtime-jdbc:0.1.0")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:mysql:1.21.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Driver for core's scratch-container DDL introspection (ddlMode defaults to "container").
    titanJdbc("org.postgresql:postgresql:42.7.13")
    // Driver for MySQL scratch installation and installed-package tests.
    titanJdbc("com.mysql:mysql-connector-j:8.4.0")
    titanJdbc("com.google.protobuf:protobuf-java:3.25.5")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Plain tests exclude Docker-tagged cases; database-engine tasks own installed-package proofs.
tasks.test {
    useJUnitPlatform {
        excludeTags("docker")
        excludeTags("database-engine-commerce-http-restart")
        excludeTags("database-engine-commerce-http-corpus")
        excludeTags("database-engine-package-replacement")
        excludeTags("database-engine-snapshot")
    }
    // Docker-free tests read package metadata from the checked-in fixture.
    systemProperty(
        "titan.graphql.artifacts.dir",
        layout.projectDirectory.dir("src/test/resources/titan-artifacts").asFile.absolutePath
    )
}

tasks.register<Test>("databaseManagementStoreIntegrationTest") {
    description = "Runs durable JDBC management-store tests on PostgreSQL and MySQL."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("database-management-store") }
}

configure<TitanExtension> {
    database.jdbcUrl.set("")
    database.username.set("")
    database.password.set("")
    // Introspection (and the catalog built from it) is deliberately postgres-only: the
    // catalog is dialect-neutral Java, and the plugin's DDL file tree includes every *.sql
    // under ddlDir recursively — so the per-dialect demo DDL lives in ddl/<dialect>/ and
    // introspection points at the PostgreSQL subset. ddl/mysql/ serves the W5.2
    // deployment/equivalence legs only.
    database.ddlDir.set("ddl/postgres")
    database.dialect.set("postgresql")
    database.schemas.set(listOf("public"))

    catalog.targetPackage.set("io.titan.graphql.catalog")
    catalog.outputDir.set(layout.buildDirectory.dir("generated/sources/titan").get().asFile.absolutePath)

    transpiler.targets.set(listOf("postgresql", "mysql"))
    transpiler.outputDir.set(layout.buildDirectory.dir("generated/sql/titan").get().asFile.absolutePath)
    // strictMode removed: validation is unconditional in core (TG-BLK-002 tracks the dead knob).
    transpiler.observability.set(true)
    transpiler.debugMode.set(false)
    transpiler.sensitiveColumns.set(listOf("email"))

    deployment.mode.set("migration")
    deployment.migrationsDir.set(layout.buildDirectory.dir("generated/migrations/titan-core").get().asFile.absolutePath)
}

// Generated catalog sources are wired into sourceSets by the Titan plugin itself
// (core plan Phase 4.1), so no manual srcDir/dependsOn wiring is needed here.

val titanGraphqlModelFile = providers.gradleProperty("titanGraphqlModel")
    .orElse("src/test/resources/graphql/demo-blog.titan.graphql.yaml")

// The reusable database invocation client cannot import GraphQL execution code.
val databaseFrontend = sourceSets.create("databaseFrontend") {
    java.srcDir("src/database-frontend/java")
}

// The serving host may decode HTTP/JSON and invoke the one-call client, but cannot link the engine.
val databaseHttpFrontend = sourceSets.create("databaseHttpFrontend") {
    java.srcDir("src/database-http-frontend/java")
}
val databaseHttpFrontendTest = sourceSets.create("databaseHttpFrontendTest") {
    java.srcDir("src/database-http-frontend-test/java")
    resources.srcDir("src/database-http-frontend-test/resources")
}

// Build/control-plane code also uses the independently buildable frontend client for attestation.
sourceSets["main"].compileClasspath += databaseFrontend.output
sourceSets["main"].runtimeClasspath += databaseFrontend.output
sourceSets["test"].compileClasspath += databaseFrontend.output
sourceSets["test"].runtimeClasspath += databaseFrontend.output
tasks.named("compileJava") {
    dependsOn(databaseFrontend.classesTaskName)
}
tasks.named("compileTestJava") {
    dependsOn(databaseFrontend.classesTaskName)
}
tasks.named<Jar>("jar") {
    // The control-plane JAR uses the client for installed-package attestation.
    from(databaseFrontend.output)
}

val databaseFrontendJar = tasks.register<Jar>("databaseFrontendJar") {
    description = "Builds the framework-free whole-request database frontend artifact."
    group = "build"
    archiveClassifier.set("database-frontend")
    from(databaseFrontend.output)
}

val databaseHttpFrontendJar = tasks.register<Jar>("databaseHttpFrontendJar") {
    description = "Builds the standalone HTTP frontend with no JVM GraphQL runtime classes."
    group = "build"
    archiveClassifier.set("database-http-frontend")
    from(databaseFrontend.output)
    from(databaseHttpFrontend.output)
}

val databaseHttpFrontendDistribution = tasks.register<Zip>("databaseHttpFrontendDistribution") {
    description = "Packages the runnable standalone HTTP frontend and its small runtime closure."
    group = "distribution"
    dependsOn(databaseHttpFrontendJar)
    archiveClassifier.set("database-http-frontend")
    from(databaseHttpFrontendJar) {
        into("lib")
    }
    from(databaseHttpFrontend.runtimeClasspath.filter { it.isFile }) {
        into("lib")
    }
    from("src/database-http-frontend/bin") {
        into("bin")
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
    from("LICENSE", "THIRD_PARTY_NOTICES.md")
    from("licenses") {
        into("licenses")
    }
}

val controlJobWorkerDistribution = tasks.register<Zip>("controlJobWorkerDistribution") {
    description = "Packages the runnable control-plane artifact job worker."
    group = "distribution"
    dependsOn(tasks.named("jar"))
    archiveClassifier.set("control-job-worker")
    from(tasks.named<Jar>("jar")) {
        into("lib")
    }
    from(sourceSets["main"].runtimeClasspath.filter { it.isFile }) {
        into("lib")
    }
    from("src/control-job/bin") {
        into("bin")
        filePermissions {
            unix("rwxr-xr-x")
        }
    }
    from("LICENSE", "THIRD_PARTY_NOTICES.md")
    from("licenses") {
        into("licenses")
    }
}

val releaseTrackedSources = providers.exec {
    commandLine("git", "ls-files", "--recurse-submodules")
}.standardOutput.asText.map { output -> output.lineSequence().filter { it.isNotBlank() }.toList() }

val releaseSourceDistribution = tasks.register<Zip>("releaseSourceDistribution") {
    description = "Packages tracked project and pinned submodule sources for the runtime release."
    group = "distribution"
    archiveClassifier.set("sources")
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    from(layout.projectDirectory) {
        include(releaseTrackedSources.get())
    }
}

val titanGraphqlVerifyControlJobWorkerDistribution =
    tasks.register("titanGraphqlVerifyControlJobWorkerDistribution") {
        description = "Verifies that the control worker ZIP can connect to the MySQL management database."
        group = "verification"
        dependsOn(controlJobWorkerDistribution)
        val distribution = controlJobWorkerDistribution.flatMap { it.archiveFile }
        inputs.file(distribution)
        doLast {
            ZipFile(distribution.get().asFile).use { zip ->
                check(zip.getEntry("lib/mysql-connector-j-8.4.0.jar") != null) {
                    "control worker distribution omits the MySQL JDBC driver"
                }
                check(zip.getEntry("bin/titan-graphql-control-worker") != null) {
                    "control worker distribution omits its launcher"
                }
                for (notice in listOf("LICENSE", "THIRD_PARTY_NOTICES.md",
                        "licenses/Apache-2.0.txt", "licenses/protobuf-BSD-3-Clause.txt")) {
                    check(zip.getEntry(notice) != null) {
                        "control worker distribution omits $notice"
                    }
                }
            }
        }
    }

// Only the isolated ZIP is a serving artifact; the root JAR contains build/control-plane tools.
val titanGraphqlDatabaseHttpFrontendReleaseArtifact =
    tasks.register("titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact") {
        description = "Verifies the standalone HTTP ZIP is the only database-serving release artifact."
        group = "verification"
        dependsOn(databaseHttpFrontendDistribution, "titanGraphqlVerifyDatabaseHttpFrontendBoundary")
        doLast {
            val distribution = databaseHttpFrontendDistribution.get().archiveFile.get().asFile
            check(distribution.isFile) { "database HTTP frontend distribution is missing: $distribution" }
            val expectedFrontendJar = databaseHttpFrontendJar.get().archiveFileName.get()
            val expectedLaunchScript = "bin/titan-graphql-database-http"
            val expectedLibraries = setOf(
                expectedFrontendJar,
                "jackson-annotations-2.21.jar",
                "jackson-core-2.21.7.jar",
                "jackson-databind-2.21.7.jar",
                "mysql-connector-j-8.4.0.jar",
                "postgresql-42.7.13.jar",
                "protobuf-java-3.25.5.jar",
                "checker-qual-3.55.1.jar"
            )
            val distributionEntries = ZipFile(distribution).use { archive ->
                archive.entries().asSequence()
                    .filter { entry -> entry.isDirectory == false }
                    .associate { entry -> entry.name to archive.getInputStream(entry).readBytes() }
            }
            val expectedNotices = setOf("LICENSE", "THIRD_PARTY_NOTICES.md",
                "licenses/Apache-2.0.txt", "licenses/protobuf-BSD-3-Clause.txt")
            check(distributionEntries.keys == expectedLibraries.map { "lib/$it" }.toSet()
                    + expectedLaunchScript + expectedNotices) {
                "database HTTP frontend distribution has an unreviewed runtime closure: " +
                    distributionEntries.keys.sorted().joinToString()
            }
            val launcher = distributionEntries.getValue(expectedLaunchScript).toString(Charsets.UTF_8)
            check(launcher.contains("io.titan.graphql.frontend.DatabaseGraphqlHttpServerMain")) {
                "database HTTP frontend launcher does not invoke the isolated frontend entry point"
            }
            check(launcher.contains("quarkus", ignoreCase = true) == false) {
                "database HTTP frontend launcher references Quarkus"
            }

            val frontendClasses = ZipInputStream(
                distributionEntries.getValue("lib/$expectedFrontendJar").inputStream()
            ).use { jar ->
                buildList {
                    while (true) {
                        val entry = jar.nextEntry ?: break
                        if (entry.isDirectory == false && entry.name.endsWith(".class")) add(entry.name)
                    }
                }
            }
            val leakedTitanClasses = frontendClasses.filter { entry ->
                entry.startsWith("io/titan/graphql/") && entry.startsWith("io/titan/graphql/frontend/") == false
            }
            check(leakedTitanClasses.isEmpty()) {
                "database HTTP frontend release JAR contains non-frontend Titan GraphQL classes: " +
                    leakedTitanClasses.joinToString()
            }
            val forbiddenFrontendClasses = frontendClasses.filter { entry ->
                entry.endsWith("GraphqlParser.class")
                        || entry.endsWith("GraphqlEngine.class")
                        || entry.endsWith("GraphqlValidator.class")
                        || entry.endsWith("GraphqlReadPlanner.class")
                        || entry.endsWith("GraphqlLexer.class")
                        || entry.endsWith("GraphqlCursorCodec.class")
            }
            check(forbiddenFrontendClasses.isEmpty()) {
                "database HTTP frontend release JAR contains a local GraphQL implementation: " +
                    forbiddenFrontendClasses.joinToString()
            }
        }
    }

val titanGraphqlDatabaseEngineReleaseCheck = tasks.register("titanGraphqlDatabaseEngineReleaseCheck") {
    description = "Runs the local deployment gate for the database-resident GraphQL serving path."
    group = "verification"
    // This gate establishes the artifact a user may deploy.
    dependsOn(
        releaseSourceDistribution,
        titanGraphqlVerifyControlJobWorkerDistribution,
        "titanGraphqlVerifyDatabaseEngineBoundary",
        "titanGraphqlVerifyDatabaseFrontendBoundary",
        "titanGraphqlVerifyDatabaseEnginePackagePrivacy",
        "titanGraphqlDatabaseEngineArtifactReport",
        titanGraphqlDatabaseHttpFrontendReleaseArtifact,
        "databaseEngineIntegrationTest",
        "databaseEngineMySqlIntegrationTest",
        "databaseEngineCommerceIntegrationTest",
        "databaseEngineCommerceMySqlIntegrationTest",
        "databaseEngineCommerceHttpCorpusIntegrationTest",
        "databaseEnginePackageReplacementIntegrationTest",
        "databaseEngineSnapshotIntegrationTest",
        "databaseEngineCommerceHttpRestartIntegrationTest",
        "databaseManagementStoreIntegrationTest",
        "databaseHttpFrontendIntegrationTest"
    )
}

tasks.register("titanGraphqlVerifyDatabaseFrontendBoundary") {
    description = "Verifies the frontend artifact cannot contain JVM GraphQL execution classes."
    group = "verification"
    dependsOn(databaseFrontendJar)
    doLast {
        val forbiddenEntries = listOf(
            "io/titan/graphql/GraphqlParser.class",
            "io/titan/graphql/GraphqlEngine.class",
            "io/titan/graphql/GraphqlValidator.class",
            "io/titan/graphql/GraphqlReadPlanner.class",
            "io/titan/graphql/TitanCompiledGraphqlRuntime.class",
            "io/titan/graphql/GenericJdbcGraphqlRuntime.class",
            "io/titan/graphql/sqlmode/"
        )
        val entries = mutableListOf<String>()
        zipTree(databaseFrontendJar.get().archiveFile).visit {
            if (isDirectory == false) entries.add(path)
        }
        val offenders = entries.filter { entry -> forbiddenEntries.any { forbidden -> entry.startsWith(forbidden) } }
        check(offenders.isEmpty()) {
            "database frontend artifact contains JVM GraphQL execution classes: ${offenders.joinToString()}"
        }
        val forbiddenImports = listOf("io.titan.graphql.Graphql", "io.quarkus.", "jakarta.", "com.fasterxml.")
        val offendersBySource = fileTree("src/database-frontend/java") {
            include("**/*.java")
        }.filter { source ->
            val text = source.readText()
            forbiddenImports.any { forbidden -> text.contains(forbidden) }
        }.files
        check(offendersBySource.isEmpty()) {
            "database frontend source crossed the HTTP/JVM GraphQL boundary: ${offendersBySource.joinToString()}"
        }
    }
}

tasks.register("titanGraphqlVerifyDatabaseHttpFrontendBoundary") {
    description = "Fails when the standalone HTTP frontend links or packages JVM GraphQL execution."
    group = "verification"
    dependsOn(databaseHttpFrontendJar, databaseHttpFrontendDistribution)
    doLast {
        val frontendJar = databaseHttpFrontendJar.get().archiveFile.get().asFile
        val entries = mutableListOf<String>()
        zipTree(frontendJar).visit {
            if (isDirectory == false) entries.add(path)
        }
        val forbiddenClasses = listOf(
            "GraphqlParser.class",
            "GraphqlEngine.class",
            "GraphqlValidator.class",
            "GraphqlReadPlanner.class",
            "GraphqlLexer.class",
            "GraphqlCursorCodec.class",
            "TitanCompiledGraphqlRuntime.class",
            "GenericJdbcGraphqlRuntime.class",
            "GraphqlSqlModeRuntime.class"
        )
        val offenders = entries.filter { entry -> forbiddenClasses.any { entry.endsWith(it) } }
        check(offenders.isEmpty()) {
            "database HTTP frontend artifact contains JVM GraphQL execution classes: \${offenders.joinToString()}"
        }
        val nonFrontendTitanClasses = entries.filter { entry ->
            entry.startsWith("io/titan/graphql/") && entry.startsWith("io/titan/graphql/frontend/") == false
        }
        check(nonFrontendTitanClasses.isEmpty()) {
            "database HTTP frontend artifact contains non-frontend Titan GraphQL classes: " +
                    nonFrontendTitanClasses.joinToString()
        }
        val forbiddenImports = listOf(
            "io.titan.graphql.Graphql",
            "io.titan.graphql.sqlmode.",
            "io.titan.graphql.database.",
            "io.quarkus.",
            "jakarta."
        )
        val offendersBySource = fileTree("src/database-http-frontend/java") {
            include("**/*.java")
        }.filter { source ->
            val text = source.readText()
            forbiddenImports.any { forbidden -> text.contains(forbidden) }
        }.files
        check(offendersBySource.isEmpty()) {
            "database HTTP frontend source crossed the runtime boundary: \${offendersBySource.joinToString()}"
        }
        val mainOutput = sourceSets["main"].output.files
        val leakedMainOutputs = databaseHttpFrontend.runtimeClasspath.files.filter { candidate ->
            mainOutput.contains(candidate)
        }
        check(leakedMainOutputs.isEmpty()) {
            "database HTTP frontend runtime classpath includes the transitional application output: " +
                    leakedMainOutputs.joinToString()
        }
        val leakedMainTestOutputs = databaseHttpFrontendTest.runtimeClasspath.files.filter { candidate ->
            mainOutput.contains(candidate)
        }
        check(leakedMainTestOutputs.isEmpty()) {
            "database HTTP frontend integration-test classpath includes the transitional application output: " +
                    leakedMainTestOutputs.joinToString()
        }
        val distributionEntries = mutableListOf<String>()
        zipTree(databaseHttpFrontendDistribution.get().archiveFile).visit {
            if (isDirectory == false) distributionEntries.add(path)
        }
        check("bin/titan-graphql-database-http" in distributionEntries) {
            "database HTTP frontend distribution has no launch script"
        }
        check(frontendJar.name.let { "lib/$it" } in distributionEntries) {
            "database HTTP frontend distribution has no frontend application JAR"
        }
        val forbiddenDistributionEntries = distributionEntries.filter { entry ->
            entry.contains("quarkus", ignoreCase = true)
                    || entry.contains("graphql-engine", ignoreCase = true)
                    || entry == "lib/$titanGraphqlProjectName-$titanGraphqlProjectVersion.jar"
        }
        check(forbiddenDistributionEntries.isEmpty()) {
            "database HTTP frontend distribution contains transitional application/runtime artifacts: " +
                    forbiddenDistributionEntries.joinToString()
        }
    }
}

// The database engine has an intentionally separate source/dependency boundary.  It may use
// the JDK JDBC surface and Titan DSL annotations, but it must not see Quarkus, Jackson, the
// JVM GraphQL engine, or model/code-generation classes. Generated schema bindings are
// transpile-only source: asking javac to emit a JVM class for a full database program imposes
// the irrelevant 64 KiB JVM method ceiling before Titan can lower it into database routines.
// Titan still parses, attributes, validates, and transpiles the common sources plus exactly one
// generated dialect binding as a closed source-local routine graph.
val databaseEngine = sourceSets.create("databaseEngine") {
    java.srcDir("src/database-engine/java")
}

// Direct language-core tests exercise the same source Titan transpiles, without widening the
// production runtime classpath or giving the database engine access to the JVM GraphQL runtime.
sourceSets["test"].compileClasspath += databaseEngine.output
sourceSets["test"].runtimeClasspath += databaseEngine.output

dependencies {
    add(databaseEngine.implementationConfigurationName, "io.titan:titan-dsl:0.1.0")
    add(databaseHttpFrontend.implementationConfigurationName, files(databaseFrontend.output))
    add(databaseHttpFrontend.implementationConfigurationName, "com.fasterxml.jackson.core:jackson-databind:2.21.7")
    add(databaseHttpFrontend.runtimeOnlyConfigurationName, "org.postgresql:postgresql:42.7.13")
    add(databaseHttpFrontend.runtimeOnlyConfigurationName, "com.mysql:mysql-connector-j:8.4.0")
    add(databaseHttpFrontend.runtimeOnlyConfigurationName, "com.google.protobuf:protobuf-java:3.25.5")
    add(databaseHttpFrontendTest.implementationConfigurationName, files(databaseHttpFrontend.output))
    add(databaseHttpFrontendTest.implementationConfigurationName, files(databaseFrontend.output))
    add(databaseHttpFrontendTest.implementationConfigurationName, "com.fasterxml.jackson.core:jackson-databind:2.21.7")
    add(databaseHttpFrontendTest.implementationConfigurationName, platform("org.junit:junit-bom:6.0.3"))
    add(databaseHttpFrontendTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
    add(databaseHttpFrontendTest.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql:2.0.5")
    add(databaseHttpFrontendTest.implementationConfigurationName, "org.testcontainers:testcontainers-mysql:2.0.5")
    add(databaseHttpFrontendTest.implementationConfigurationName, "org.postgresql:postgresql:42.7.13")
    add(databaseHttpFrontendTest.implementationConfigurationName, "com.mysql:mysql-connector-j:8.4.0")
    add(databaseHttpFrontendTest.implementationConfigurationName, "com.google.protobuf:protobuf-java:3.25.5")
    add(databaseHttpFrontendTest.runtimeOnlyConfigurationName, "org.junit.platform:junit-platform-launcher")
}

val titanGraphqlDatabaseEngineGeneratedSource = layout.buildDirectory.file(
    "generated/sources/titan-graphql-database-engine/io/titan/graphql/database/generated/GeneratedDatabaseGraphqlSchema.java"
)
val titanGraphqlMySqlDatabaseEngineGeneratedSource = layout.buildDirectory.file(
    "generated/sources/titan-graphql-database-engine-mysql/io/titan/graphql/database/generated/GeneratedDatabaseGraphqlMySqlProcedure.java"
)
val titanGraphqlDatabaseEngineCommonSource = layout.projectDirectory.file(
    "src/database-engine/java/io/titan/graphql/database/DatabaseGraphqlEngine.java"
)
val titanGraphqlDatabaseLanguageSource = layout.projectDirectory.file(
    "src/database-engine/java/io/titan/graphql/database/DatabaseGraphqlLanguage.java"
)
val titanGraphqlDatabaseAstSource = layout.projectDirectory.file(
    "src/database-engine/java/io/titan/graphql/database/DatabaseGraphqlAst.java"
)
val titanGraphqlDatabaseTypeReferenceSource = layout.projectDirectory.file(
    "src/database-engine/java/io/titan/graphql/database/DatabaseGraphqlTypeReference.java"
)
val titanGraphqlDatabaseEngineSqlDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine/sql"
)
val titanGraphqlDatabaseEnginePackageDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine/package"
)
val titanGraphqlDatabaseEnginePackageSourceDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine/package-source"
)
val titanGraphqlMySqlDatabaseEngineSqlDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine-mysql/sql"
)
val titanGraphqlMySqlDatabaseEnginePackageDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine-mysql/package"
)
val titanGraphqlMySqlDatabaseEnginePackageSourceDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine-mysql/package-source"
)
val titanGraphqlDatabaseEngineFrontendDescriptor = titanGraphqlDatabaseEnginePackageDirectory.map {
    it.file("titan-graphql-database-frontend.postgresql.properties")
}
val titanGraphqlMySqlDatabaseEngineFrontendDescriptor = titanGraphqlMySqlDatabaseEnginePackageDirectory.map {
    it.file("titan-graphql-database-frontend.mysql.properties")
}
val titanGraphqlDatabaseEngineRuntimeIdentity = layout.buildDirectory.file(
    "generated/proofs/database-engine/titan-graphql-database-runtime-identity.postgresql.sha256"
)
val titanGraphqlMySqlDatabaseEngineRuntimeIdentity = layout.buildDirectory.file(
    "generated/proofs/database-engine-mysql/titan-graphql-database-runtime-identity.mysql.sha256"
)
val titanGraphqlDatabaseEnginePackageIdentity = layout.buildDirectory.file(
    "generated/proofs/database-engine/titan-graphql-database-package-identity.postgresql.sha256"
)
val titanGraphqlMySqlDatabaseEnginePackageIdentity = layout.buildDirectory.file(
    "generated/proofs/database-engine-mysql/titan-graphql-database-package-identity.mysql.sha256"
)

tasks.register<JavaExec>("titanGraphqlGenerateDatabaseEngineRuntimeIdentity") {
    description = "Calculates the PostgreSQL whole-request runtime identity before transpilation."
    group = "titan"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentityCli")
    args(
        titanGraphqlModelFile.get(), "postgresql",
        titanGraphqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        "common-engine", titanGraphqlDatabaseEngineCommonSource.asFile.absolutePath,
        "common-language", titanGraphqlDatabaseLanguageSource.asFile.absolutePath,
        "schema-generator", layout.projectDirectory.file(
            "src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java").asFile.absolutePath,
        "build-options", layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath,
        "dependency-locks", layout.projectDirectory.file("gradle.lockfile").asFile.absolutePath,
        "titan-version", layout.projectDirectory.file("vendor/titan/gradle.properties").asFile.absolutePath)
    inputs.files(
        layout.projectDirectory.file(titanGraphqlModelFile.get()), titanGraphqlDatabaseEngineCommonSource,
        titanGraphqlDatabaseLanguageSource,
        layout.projectDirectory.file("src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java"),
        layout.projectDirectory.file("build.gradle.kts"), layout.projectDirectory.file("gradle.lockfile"),
        layout.projectDirectory.file("vendor/titan/gradle.properties"))
    outputs.file(titanGraphqlDatabaseEngineRuntimeIdentity)
}

tasks.register<JavaExec>("titanGraphqlGenerateMySqlDatabaseEngineRuntimeIdentity") {
    description = "Calculates the MySQL whole-request runtime identity before transpilation."
    group = "titan"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentityCli")
    args(
        titanGraphqlModelFile.get(), "mysql",
        titanGraphqlMySqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        "common-engine", titanGraphqlDatabaseEngineCommonSource.asFile.absolutePath,
        "common-language", titanGraphqlDatabaseLanguageSource.asFile.absolutePath,
        "schema-generator", layout.projectDirectory.file(
            "src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java").asFile.absolutePath,
        "build-options", layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath,
        "dependency-locks", layout.projectDirectory.file("gradle.lockfile").asFile.absolutePath,
        "titan-version", layout.projectDirectory.file("vendor/titan/gradle.properties").asFile.absolutePath)
    inputs.files(
        layout.projectDirectory.file(titanGraphqlModelFile.get()), titanGraphqlDatabaseEngineCommonSource,
        titanGraphqlDatabaseLanguageSource,
        layout.projectDirectory.file("src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java"),
        layout.projectDirectory.file("build.gradle.kts"), layout.projectDirectory.file("gradle.lockfile"),
        layout.projectDirectory.file("vendor/titan/gradle.properties"))
    outputs.file(titanGraphqlMySqlDatabaseEngineRuntimeIdentity)
}

tasks.register<JavaExec>("titanGraphqlGenerateDatabaseEngine") {
    description = "Generates the schema binding consumed by the database-resident GraphQL engine."
    group = "titan"
    dependsOn("classes", "titanGraphqlGenerateDatabaseEngineRuntimeIdentity")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorCli")
    args(titanGraphqlModelFile.get(), titanGraphqlDatabaseEngineGeneratedSource.get().asFile.absolutePath,
        titanGraphqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath)
    inputs.files(layout.projectDirectory.file(titanGraphqlModelFile.get()), titanGraphqlDatabaseEngineRuntimeIdentity)
    outputs.file(titanGraphqlDatabaseEngineGeneratedSource)
}

tasks.register<JavaExec>("titanGraphqlGenerateMySqlDatabaseEngine") {
    description = "Generates the MySQL procedure/result-set binding for the database-resident GraphQL engine."
    group = "titan"
    dependsOn("classes", "titanGraphqlGenerateMySqlDatabaseEngineRuntimeIdentity")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.codegen.TitanGraphqlMySqlDatabaseEngineSourceGeneratorCli")
    args(titanGraphqlModelFile.get(), titanGraphqlMySqlDatabaseEngineGeneratedSource.get().asFile.absolutePath,
        titanGraphqlMySqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath)
    inputs.files(layout.projectDirectory.file(titanGraphqlModelFile.get()), titanGraphqlMySqlDatabaseEngineRuntimeIdentity)
    outputs.file(titanGraphqlMySqlDatabaseEngineGeneratedSource)
}

tasks.register("titanGraphqlVerifyDatabaseEngineBoundary") {
    description = "Fails when database-engine sources or dependencies cross into the HTTP/JVM runtime."
    group = "verification"
    dependsOn(databaseEngine.compileJavaTaskName)
    doLast {
        val forbiddenImports = listOf(
            "io.quarkus.",
            "jakarta.",
            "com.fasterxml.",
            "io.titan.graphql.Graphql",
            "io.titan.graphql.codegen.",
            "io.titan.graphql.model."
        )
        val offenders = fileTree("src/database-engine/java") {
            include("**/*.java")
        }.filter { source ->
            val sourceText = source.readText()
            forbiddenImports.any { forbidden -> sourceText.contains(forbidden) }
        }.files
        check(offenders.isEmpty()) {
            "database-engine source crossed the runtime boundary: ${offenders.joinToString()}"
        }
        val forbiddenDependencies = databaseEngine.compileClasspath.files.filter { dependency ->
            val name = dependency.name.lowercase()
            name.contains("quarkus") || name.contains("jackson") || name.contains("resteasy")
        }
        check(forbiddenDependencies.isEmpty()) {
            "database-engine compile classpath crossed the runtime boundary: ${forbiddenDependencies.joinToString()}"
        }
    }
}

tasks.register<TitanTranspileTask>("titanGraphqlTranspileDatabaseEngine") {
    description = "Transpiles the PostgreSQL whole-request database GraphQL engine proof."
    group = "verification"
    dependsOn("titanGraphqlVerifyDatabaseEngineBoundary", "titanGraphqlGenerateDatabaseEngine")
    sourceFiles.setFrom(titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource,
        titanGraphqlDatabaseAstSource, titanGraphqlDatabaseTypeReferenceSource,
        titanGraphqlDatabaseEngineGeneratedSource)
    classpathFiles.from(databaseEngine.compileClasspath)
    // A JDBC read lowers to dynamic EXECUTE. PostgreSQL may execute that from a function; MySQL
    // requires the procedure/result-stream entry point tracked in the database-engine plan.
    targets.set(listOf("postgresql"))
    schemas.set(listOf("public"))
    strictWraparound.set(false)
    sqlSafety.set("strict")
    observability.set(true)
    debugMode.set(false)
    sensitiveColumns.set(listOf("email"))
    outputDir.set(titanGraphqlDatabaseEngineSqlDirectory)
}

tasks.register<TitanTranspileTask>("titanGraphqlTranspileMySqlDatabaseEngine") {
    description = "Transpiles the MySQL procedure/result-set whole-request database GraphQL engine proof."
    group = "verification"
    dependsOn("titanGraphqlVerifyDatabaseEngineBoundary", "titanGraphqlGenerateMySqlDatabaseEngine")
    sourceFiles.setFrom(titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource, titanGraphqlDatabaseAstSource,
        titanGraphqlDatabaseTypeReferenceSource,
        titanGraphqlMySqlDatabaseEngineGeneratedSource)
    classpathFiles.from(databaseEngine.compileClasspath)
    targets.set(listOf("mysql"))
    schemas.set(listOf("public"))
    strictWraparound.set(false)
    sqlSafety.set("strict")
    observability.set(true)
    debugMode.set(false)
    sensitiveColumns.set(listOf("email"))
    outputDir.set(titanGraphqlMySqlDatabaseEngineSqlDirectory)
    doLast {
        val mysqlRoutineDirectory = outputDir.get().asFile.resolve("mysql")
        val routineCount = mysqlRoutineDirectory.walkTopDown()
            .count { candidate -> candidate.isFile && candidate.extension == "sql" }
        logger.lifecycle("MySQL whole-request routine inventory: $routineCount")
    }
}

// Stage transpiled output before adding the self-excluded inventory-identity migration. Keeping
// this separate from the transpiler's output lets Gradle cache the expensive transpilation while
// still making the complete package inventory an explicit, reviewable package input.
tasks.register<Sync>("titanGraphqlStageDatabaseEnginePackage") {
    dependsOn("titanGraphqlTranspileDatabaseEngine")
    from(titanGraphqlDatabaseEngineSqlDirectory)
    from("src/database-engine-migrations/postgresql") { into("postgresql") }
    into(titanGraphqlDatabaseEnginePackageSourceDirectory)
}

tasks.register<Sync>("titanGraphqlStageMySqlDatabaseEnginePackage") {
    dependsOn("titanGraphqlTranspileMySqlDatabaseEngine")
    from(titanGraphqlMySqlDatabaseEngineSqlDirectory)
    from("src/database-engine-migrations/mysql") { into("mysql") }
    into(titanGraphqlMySqlDatabaseEnginePackageSourceDirectory)
}

tasks.register<JavaExec>("titanGraphqlGenerateDatabaseEnginePackageIdentity") {
    description = "Attests the PostgreSQL whole-request engine SQL inventory."
    group = "verification"
    dependsOn("classes", "titanGraphqlStageDatabaseEnginePackage")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentityCli")
    args(
        "postgresql",
        titanGraphqlDatabaseEnginePackageSourceDirectory.get().dir("postgresql").asFile.absolutePath,
        titanGraphqlDatabaseEnginePackageIdentity.get().asFile.absolutePath,
        titanGraphqlDatabaseEnginePackageSourceDirectory.get().file(
            "postgresql/R__titan_005_graphql_package_identity.sql").asFile.absolutePath
    )
    inputs.dir(titanGraphqlDatabaseEnginePackageSourceDirectory)
    outputs.file(titanGraphqlDatabaseEnginePackageIdentity)
    outputs.file(titanGraphqlDatabaseEnginePackageSourceDirectory.map {
        it.file("postgresql/R__titan_005_graphql_package_identity.sql")
    })
}

tasks.register<JavaExec>("titanGraphqlGenerateMySqlDatabaseEnginePackageIdentity") {
    description = "Attests the MySQL whole-request engine SQL inventory."
    group = "verification"
    dependsOn("classes", "titanGraphqlStageMySqlDatabaseEnginePackage")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentityCli")
    args(
        "mysql",
        titanGraphqlMySqlDatabaseEnginePackageSourceDirectory.get().dir("mysql").asFile.absolutePath,
        titanGraphqlMySqlDatabaseEnginePackageIdentity.get().asFile.absolutePath,
        titanGraphqlMySqlDatabaseEnginePackageSourceDirectory.get().file(
            "mysql/R__titan_005_graphql_package_identity.sql").asFile.absolutePath
    )
    inputs.dir(titanGraphqlMySqlDatabaseEnginePackageSourceDirectory)
    outputs.file(titanGraphqlMySqlDatabaseEnginePackageIdentity)
    outputs.file(titanGraphqlMySqlDatabaseEnginePackageSourceDirectory.map {
        it.file("mysql/R__titan_005_graphql_package_identity.sql")
    })
}

// This remains a deliberately isolated proof package until it supports the complete GraphQL
// contract.  Install verification is nevertheless mandatory: successful Java-to-SQL transpilation
// alone does not prove that PostgreSQL accepts the whole helper closure and public entry point.
tasks.register<TitanPackageTask>("titanGraphqlPackageDatabaseEngine") {
    description = "Packages the PostgreSQL whole-request database GraphQL engine proof."
    group = "verification"
    dependsOn("titanGraphqlGenerateDatabaseEnginePackageIdentity")
    sqlInputDir.set(titanGraphqlDatabaseEnginePackageSourceDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    outputDir.set(titanGraphqlDatabaseEnginePackageDirectory)
}

tasks.register<TitanVerifyInstallTask>("titanGraphqlVerifyDatabaseEngineInstall") {
    description = "Install-verifies the PostgreSQL whole-request database GraphQL engine proof."
    group = "verification"
    dependsOn("titanGraphqlPackageDatabaseEngine")
    sqlInputDir.set(titanGraphqlDatabaseEnginePackageSourceDirectory)
    artifactDir.set(titanGraphqlDatabaseEnginePackageDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    jdbcUrl.set("")
    username.set("")
    password.set("")
    dialect.set("postgresql")
    failOnVerificationError.set(true)
    jdbcDriverClasspath.from(configurations["titanJdbc"])
    outputs.upToDateWhen { false }
}

tasks.register<TitanPackageTask>("titanGraphqlPackageMySqlDatabaseEngine") {
    description = "Packages the MySQL procedure/result-set whole-request database GraphQL engine proof."
    group = "verification"
    dependsOn("titanGraphqlGenerateMySqlDatabaseEnginePackageIdentity")
    sqlInputDir.set(titanGraphqlMySqlDatabaseEnginePackageSourceDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    outputDir.set(titanGraphqlMySqlDatabaseEnginePackageDirectory)
}

tasks.register<TitanVerifyInstallTask>("titanGraphqlVerifyMySqlDatabaseEngineInstall") {
    description = "Install-verifies the MySQL procedure/result-set database GraphQL engine proof."
    group = "verification"
    dependsOn("titanGraphqlPackageMySqlDatabaseEngine")
    sqlInputDir.set(titanGraphqlMySqlDatabaseEnginePackageSourceDirectory)
    artifactDir.set(titanGraphqlMySqlDatabaseEnginePackageDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    jdbcUrl.set("")
    username.set("")
    password.set("")
    dialect.set("mysql")
    failOnVerificationError.set(true)
    jdbcDriverClasspath.from(configurations["titanJdbc"])
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("titanGraphqlBindDatabaseEnginePackage") {
    description = "Binds the verified PostgreSQL whole-request engine package to its reviewed model."
    group = "titan"
    dependsOn("classes", "titanGraphqlVerifyDatabaseEngineInstall", "titanGraphqlGenerateDatabaseEngineRuntimeIdentity")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
    args(titanGraphqlModelFile.get(), titanGraphqlDatabaseEnginePackageDirectory.get().asFile.absolutePath,
        titanGraphqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        titanGraphqlDatabaseEnginePackageIdentity.get().asFile.absolutePath)
    inputs.files(layout.projectDirectory.file(titanGraphqlModelFile.get()), titanGraphqlDatabaseEngineRuntimeIdentity,
        titanGraphqlDatabaseEnginePackageIdentity)
    inputs.files(
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-artifact.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-object-inventory.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-install-plan.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-install-verification.json") }
    )
    outputs.files(
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-package.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") })
}

tasks.register<JavaExec>("titanGraphqlBindMySqlDatabaseEnginePackage") {
    description = "Binds the verified MySQL whole-request engine package to its reviewed model."
    group = "titan"
    dependsOn("classes", "titanGraphqlVerifyMySqlDatabaseEngineInstall", "titanGraphqlGenerateMySqlDatabaseEngineRuntimeIdentity")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
    args(titanGraphqlModelFile.get(), titanGraphqlMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath,
        titanGraphqlMySqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        titanGraphqlMySqlDatabaseEnginePackageIdentity.get().asFile.absolutePath)
    inputs.files(layout.projectDirectory.file(titanGraphqlModelFile.get()), titanGraphqlMySqlDatabaseEngineRuntimeIdentity,
        titanGraphqlMySqlDatabaseEnginePackageIdentity)
    inputs.files(
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-artifact.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-object-inventory.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-install-plan.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-install-verification.json") }
    )
    outputs.files(
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-package.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") })
}

tasks.register<JavaExec>("titanGraphqlGenerateDatabaseEngineFrontendDescriptor") {
    description = "Generates the PostgreSQL standalone-frontend descriptor from the verified package."
    group = "titan"
    dependsOn("classes", "titanGraphqlBindDatabaseEnginePackage")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli")
    args(
        titanGraphqlDatabaseEnginePackageDirectory.get().asFile.absolutePath,
        "postgresql",
        titanGraphqlDatabaseEngineFrontendDescriptor.get().asFile.absolutePath)
    inputs.files(
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-artifact.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-object-inventory.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-install-plan.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-install-verification.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-package.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") })
    outputs.file(titanGraphqlDatabaseEngineFrontendDescriptor)
}

tasks.register<JavaExec>("titanGraphqlGenerateMySqlDatabaseEngineFrontendDescriptor") {
    description = "Generates the MySQL standalone-frontend descriptor from the verified package."
    group = "titan"
    dependsOn("classes", "titanGraphqlBindMySqlDatabaseEnginePackage")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli")
    args(
        titanGraphqlMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath,
        "mysql",
        titanGraphqlMySqlDatabaseEngineFrontendDescriptor.get().asFile.absolutePath)
    inputs.files(
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-artifact.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-object-inventory.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-install-plan.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-install-verification.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-package.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") })
    outputs.file(titanGraphqlMySqlDatabaseEngineFrontendDescriptor)
}

tasks.register<Test>("databaseEngineIntegrationTest") {
    description = "Runs the isolated database-resident GraphQL engine proof against PostgreSQL."
    group = "verification"
    dependsOn("titanGraphqlBindDatabaseEnginePackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // Track only the installed engine inputs. The standalone deployment descriptor is a sibling
    // deployment output, not an input to this direct-package test; using the package root here
    // made Gradle see an undeclared producer/consumer overlap when release tasks ran together.
    inputs.dir(titanGraphqlDatabaseEnginePackageDirectory.map { it.dir("postgresql") })
    inputs.files(
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-package.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") }
    )
    useJUnitPlatform { includeTags("database-engine") }
    systemProperty(
        "titan.graphql.database-engine.migrations.dir",
        titanGraphqlDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath
    )
    systemProperty("titan.graphql.artifacts.dir", titanGraphqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
}

tasks.register<Test>("databaseHttpFrontendIntegrationTest") {
    description = "Runs the standalone HTTP frontend on a classpath without the JVM GraphQL runtime."
    group = "verification"
    dependsOn(
        "titanGraphqlGenerateDatabaseEngineFrontendDescriptor",
        "titanGraphqlGenerateMySqlDatabaseEngineFrontendDescriptor",
        databaseHttpFrontendDistribution,
        databaseHttpFrontendTest.classesTaskName)
    testClassesDirs = databaseHttpFrontendTest.output.classesDirs
    classpath = databaseHttpFrontendTest.runtimeClasspath
    inputs.dir(titanGraphqlDatabaseEnginePackageDirectory)
    inputs.dir(titanGraphqlMySqlDatabaseEnginePackageDirectory)
    inputs.file(titanGraphqlDatabaseEngineFrontendDescriptor)
    inputs.file(titanGraphqlMySqlDatabaseEngineFrontendDescriptor)
    inputs.file(databaseHttpFrontendDistribution.flatMap { it.archiveFile })
    useJUnitPlatform { excludeTags("database-engine-management-http") }
    jvmArgs("-Djava.util.logging.manager=java.util.logging.LogManager")
    systemProperty(
        "titan.graphql.database-engine.migrations.dir",
        titanGraphqlDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath
    )
    systemProperty("titan.graphql.artifacts.dir", titanGraphqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-frontend.descriptor",
        titanGraphqlDatabaseEngineFrontendDescriptor.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-frontend.mysql.descriptor",
        titanGraphqlMySqlDatabaseEngineFrontendDescriptor.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-engine.mysql.migrations.dir",
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-frontend.distribution",
        databaseHttpFrontendDistribution.get().archiveFile.get().asFile.absolutePath)
}

tasks.register<Test>("databaseEngineMySqlIntegrationTest") {
    description = "Runs the isolated MySQL procedure/result-set database GraphQL engine proof."
    group = "verification"
    dependsOn("titanGraphqlBindMySqlDatabaseEnginePackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // Keep the direct test independent of the MySQL frontend descriptor, which is produced beside
    // this package for the standalone HTTP deployment only.
    inputs.dir(titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") })
    inputs.files(
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-package.json") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") }
    )
    useJUnitPlatform { includeTags("database-engine-mysql") }
    systemProperty(
        "titan.graphql.database-engine.mysql.migrations.dir",
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath
    )
    systemProperty("titan.graphql.artifacts.dir", titanGraphqlMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("databaseEngineIntegrationTest"))
}

// Isolated second-schema proof. Its generated source, SQL, package metadata, binding, and tests
// have separate output roots, so the commerce result cannot accidentally consume the demo package.
val commerceModelFile = layout.projectDirectory.file(
    "src/test/resources/graphql/commerce.titan.graphql.yaml"
)
val managementDatabaseModelFile = layout.projectDirectory.file(
    "src/main/resources/graphql/management-database.titan.graphql.yaml"
)
val commerceDatabaseEngineGeneratedSource = layout.buildDirectory.file(
    "generated/proofs/database-engine-commerce/sources/io/titan/graphql/database/generated/GeneratedDatabaseGraphqlSchema.java"
)
val commerceMySqlDatabaseEngineGeneratedSource = layout.buildDirectory.file(
    "generated/proofs/database-engine-commerce-mysql/sources/io/titan/graphql/database/generated/GeneratedDatabaseGraphqlMySqlProcedure.java"
)
val commerceDatabaseEngineSqlDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine-commerce/sql"
)
val commerceDatabaseEnginePackageDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine-commerce/package"
)
val commerceDatabaseEnginePackageSourceDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine-commerce/package-source"
)
val commerceMySqlDatabaseEngineSqlDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine-commerce-mysql/sql"
)
val commerceMySqlDatabaseEnginePackageDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine-commerce-mysql/package"
)
val commerceMySqlDatabaseEnginePackageSourceDirectory = layout.buildDirectory.dir(
    "generated/proofs/database-engine-commerce-mysql/package-source"
)
val commerceDatabaseEngineRuntimeIdentity = layout.buildDirectory.file(
    "generated/proofs/database-engine-commerce/titan-graphql-database-runtime-identity.postgresql.sha256"
)
val commerceMySqlDatabaseEngineRuntimeIdentity = layout.buildDirectory.file(
    "generated/proofs/database-engine-commerce-mysql/titan-graphql-database-runtime-identity.mysql.sha256"
)
val commerceDatabaseEnginePackageIdentity = layout.buildDirectory.file(
    "generated/proofs/database-engine-commerce/titan-graphql-database-package-identity.postgresql.sha256"
)
val commerceMySqlDatabaseEnginePackageIdentity = layout.buildDirectory.file(
    "generated/proofs/database-engine-commerce-mysql/titan-graphql-database-package-identity.mysql.sha256"
)
val databaseMutationHandlerSourceRoot = layout.projectDirectory.dir("src/database-engine/java")
val databaseMutationHandlerSources = fileTree(databaseMutationHandlerSourceRoot.asFile) {
    include("io/titan/graphql/database/handlers/**/*.java")
}
val databaseMutationHandlerIdentityArgs = databaseMutationHandlerSources.files
    .sortedBy { it.relativeTo(databaseMutationHandlerSourceRoot.asFile).invariantSeparatorsPath }
    .flatMap { source ->
        val relativePath = source.relativeTo(databaseMutationHandlerSourceRoot.asFile)
            .invariantSeparatorsPath.lowercase().replace(Regex("[^a-z0-9._-]"), "-")
        listOf("handler-$relativePath", source.absolutePath)
    }

tasks.register<JavaExec>("titanGraphqlVerifyDatabaseMutationHandlers") {
    description = "Checks reviewed procedure source and compiled signatures before packaging."
    group = "verification"
    dependsOn("classes", "databaseEngineClasses")
    classpath = sourceSets["main"].runtimeClasspath + databaseEngine.output
    mainClass.set("io.titan.graphql.codegen.TitanGraphqlMutationHandlerVerifierCli")
    args(commerceModelFile.asFile.absolutePath, managementDatabaseModelFile.asFile.absolutePath,
        databaseMutationHandlerSourceRoot.asFile.absolutePath)
    inputs.files(commerceModelFile, managementDatabaseModelFile, databaseMutationHandlerSources,
        databaseEngine.output)
}

tasks.register<JavaExec>("titanGraphqlGenerateCommerceDatabaseEngineRuntimeIdentity") {
    description = "Calculates the PostgreSQL commerce whole-request runtime identity before transpilation."
    group = "titan"
    dependsOn("titanGraphqlVerifyDatabaseMutationHandlers")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentityCli")
    args(
        commerceModelFile.asFile.absolutePath, "postgresql",
        commerceDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        "common-engine", titanGraphqlDatabaseEngineCommonSource.asFile.absolutePath,
        "common-language", titanGraphqlDatabaseLanguageSource.asFile.absolutePath)
    args(*databaseMutationHandlerIdentityArgs.toTypedArray())
    args("schema-generator", layout.projectDirectory.file(
            "src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java").asFile.absolutePath,
        "build-options", layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath,
        "dependency-locks", layout.projectDirectory.file("gradle.lockfile").asFile.absolutePath,
        "titan-version", layout.projectDirectory.file("vendor/titan/gradle.properties").asFile.absolutePath)
    inputs.files(
        commerceModelFile, titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource,
        databaseMutationHandlerSources,
        layout.projectDirectory.file("src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java"),
        layout.projectDirectory.file("build.gradle.kts"), layout.projectDirectory.file("gradle.lockfile"),
        layout.projectDirectory.file("vendor/titan/gradle.properties"))
    outputs.file(commerceDatabaseEngineRuntimeIdentity)
}

tasks.register<JavaExec>("titanGraphqlGenerateCommerceMySqlDatabaseEngineRuntimeIdentity") {
    description = "Calculates the MySQL commerce whole-request runtime identity before transpilation."
    group = "titan"
    dependsOn("titanGraphqlVerifyDatabaseMutationHandlers")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentityCli")
    args(
        commerceModelFile.asFile.absolutePath, "mysql",
        commerceMySqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        "common-engine", titanGraphqlDatabaseEngineCommonSource.asFile.absolutePath,
        "common-language", titanGraphqlDatabaseLanguageSource.asFile.absolutePath)
    args(*databaseMutationHandlerIdentityArgs.toTypedArray())
    args("schema-generator", layout.projectDirectory.file(
            "src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java").asFile.absolutePath,
        "build-options", layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath,
        "dependency-locks", layout.projectDirectory.file("gradle.lockfile").asFile.absolutePath,
        "titan-version", layout.projectDirectory.file("vendor/titan/gradle.properties").asFile.absolutePath)
    inputs.files(
        commerceModelFile, titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource,
        databaseMutationHandlerSources,
        layout.projectDirectory.file("src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java"),
        layout.projectDirectory.file("build.gradle.kts"), layout.projectDirectory.file("gradle.lockfile"),
        layout.projectDirectory.file("vendor/titan/gradle.properties"))
    outputs.file(commerceMySqlDatabaseEngineRuntimeIdentity)
}

// The commerce whole-request package has a separate source, SQL, and package output root.
tasks.register<JavaExec>("titanGraphqlGenerateCommerceDatabaseEngine") {
    description = "Generates the PostgreSQL whole-request database engine for the commerce proof model."
    group = "titan"
    dependsOn("classes", "titanGraphqlGenerateCommerceDatabaseEngineRuntimeIdentity")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorCli")
    args(commerceModelFile.asFile.absolutePath, commerceDatabaseEngineGeneratedSource.get().asFile.absolutePath,
        commerceDatabaseEngineRuntimeIdentity.get().asFile.absolutePath)
    inputs.files(commerceModelFile, commerceDatabaseEngineRuntimeIdentity)
    outputs.file(commerceDatabaseEngineGeneratedSource)
}

tasks.register<JavaExec>("titanGraphqlGenerateCommerceMySqlDatabaseEngine") {
    description = "Generates the MySQL whole-request database engine for the commerce proof model."
    group = "titan"
    dependsOn("classes", "titanGraphqlGenerateCommerceMySqlDatabaseEngineRuntimeIdentity")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.codegen.TitanGraphqlMySqlDatabaseEngineSourceGeneratorCli")
    args(commerceModelFile.asFile.absolutePath, commerceMySqlDatabaseEngineGeneratedSource.get().asFile.absolutePath,
        commerceMySqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath)
    inputs.files(commerceModelFile, commerceMySqlDatabaseEngineRuntimeIdentity)
    outputs.file(commerceMySqlDatabaseEngineGeneratedSource)
}

tasks.register<TitanTranspileTask>("titanGraphqlTranspileCommerceDatabaseEngine") {
    description = "Transpiles the PostgreSQL whole-request commerce engine proof."
    group = "verification"
    dependsOn("titanGraphqlVerifyDatabaseEngineBoundary", "titanGraphqlGenerateCommerceDatabaseEngine")
    sourceFiles.setFrom(titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource, titanGraphqlDatabaseAstSource,
        titanGraphqlDatabaseTypeReferenceSource, databaseMutationHandlerSources,
        commerceDatabaseEngineGeneratedSource)
    classpathFiles.from(databaseEngine.compileClasspath)
    targets.set(listOf("postgresql"))
    schemas.set(listOf("public"))
    strictWraparound.set(false)
    sqlSafety.set("strict")
    observability.set(true)
    debugMode.set(false)
    sensitiveColumns.set(listOf("email"))
    outputDir.set(commerceDatabaseEngineSqlDirectory)
}

tasks.register<TitanTranspileTask>("titanGraphqlTranspileCommerceMySqlDatabaseEngine") {
    description = "Transpiles the MySQL whole-request commerce engine proof."
    group = "verification"
    dependsOn("titanGraphqlVerifyDatabaseEngineBoundary", "titanGraphqlGenerateCommerceMySqlDatabaseEngine")
    sourceFiles.setFrom(titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource, titanGraphqlDatabaseAstSource,
        titanGraphqlDatabaseTypeReferenceSource, databaseMutationHandlerSources,
        commerceMySqlDatabaseEngineGeneratedSource)
    classpathFiles.from(databaseEngine.compileClasspath)
    targets.set(listOf("mysql"))
    schemas.set(listOf("public"))
    strictWraparound.set(false)
    sqlSafety.set("strict")
    // The whole-request procedure emits its GraphQL result as its final protocol message. MySQL
    // observability appends a status UPDATE after that result, which is both rolled back with
    // GraphQL failures and leaves reusable procedure sessions unstable. Request telemetry belongs
    // at the thin transport/deployment boundary; keep this database artifact result-final.
    observability.set(false)
    debugMode.set(false)
    sensitiveColumns.set(listOf("email"))
    outputDir.set(commerceMySqlDatabaseEngineSqlDirectory)
    doLast {
        val mysqlRoutineDirectory = outputDir.get().asFile.resolve("mysql")
        val routineCount = mysqlRoutineDirectory.walkTopDown()
            .count { candidate -> candidate.isFile && candidate.extension == "sql" }
        logger.lifecycle("MySQL whole-request routine inventory: $routineCount")
    }
}

tasks.register<Sync>("titanGraphqlStageCommerceDatabaseEnginePackage") {
    dependsOn("titanGraphqlTranspileCommerceDatabaseEngine")
    from(commerceDatabaseEngineSqlDirectory)
    from("src/database-engine-migrations/postgresql") { into("postgresql") }
    into(commerceDatabaseEnginePackageSourceDirectory)
}

tasks.register<Sync>("titanGraphqlStageCommerceMySqlDatabaseEnginePackage") {
    dependsOn("titanGraphqlTranspileCommerceMySqlDatabaseEngine")
    from(commerceMySqlDatabaseEngineSqlDirectory)
    from("src/database-engine-migrations/mysql") { into("mysql") }
    into(commerceMySqlDatabaseEnginePackageSourceDirectory)
}

tasks.register<JavaExec>("titanGraphqlGenerateCommerceDatabaseEnginePackageIdentity") {
    description = "Attests the PostgreSQL commerce whole-request engine SQL inventory."
    group = "verification"
    dependsOn("classes", "titanGraphqlStageCommerceDatabaseEnginePackage")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentityCli")
    args(
        "postgresql",
        commerceDatabaseEnginePackageSourceDirectory.get().dir("postgresql").asFile.absolutePath,
        commerceDatabaseEnginePackageIdentity.get().asFile.absolutePath,
        commerceDatabaseEnginePackageSourceDirectory.get().file(
            "postgresql/R__titan_005_graphql_package_identity.sql").asFile.absolutePath
    )
    inputs.dir(commerceDatabaseEnginePackageSourceDirectory)
    outputs.file(commerceDatabaseEnginePackageIdentity)
    outputs.file(commerceDatabaseEnginePackageSourceDirectory.map {
        it.file("postgresql/R__titan_005_graphql_package_identity.sql")
    })
}

tasks.register<JavaExec>("titanGraphqlGenerateCommerceMySqlDatabaseEnginePackageIdentity") {
    description = "Attests the MySQL commerce whole-request engine SQL inventory."
    group = "verification"
    dependsOn("classes", "titanGraphqlStageCommerceMySqlDatabaseEnginePackage")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentityCli")
    args(
        "mysql",
        commerceMySqlDatabaseEnginePackageSourceDirectory.get().dir("mysql").asFile.absolutePath,
        commerceMySqlDatabaseEnginePackageIdentity.get().asFile.absolutePath,
        commerceMySqlDatabaseEnginePackageSourceDirectory.get().file(
            "mysql/R__titan_005_graphql_package_identity.sql").asFile.absolutePath
    )
    inputs.dir(commerceMySqlDatabaseEnginePackageSourceDirectory)
    outputs.file(commerceMySqlDatabaseEnginePackageIdentity)
    outputs.file(commerceMySqlDatabaseEnginePackageSourceDirectory.map {
        it.file("mysql/R__titan_005_graphql_package_identity.sql")
    })
}

tasks.register<TitanPackageTask>("titanGraphqlPackageCommerceDatabaseEngine") {
    description = "Packages the PostgreSQL whole-request commerce engine proof."
    group = "verification"
    dependsOn("titanGraphqlGenerateCommerceDatabaseEnginePackageIdentity")
    sqlInputDir.set(commerceDatabaseEnginePackageSourceDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    outputDir.set(commerceDatabaseEnginePackageDirectory)
}

tasks.register<TitanPackageTask>("titanGraphqlPackageCommerceMySqlDatabaseEngine") {
    description = "Packages the MySQL whole-request commerce engine proof."
    group = "verification"
    dependsOn("titanGraphqlGenerateCommerceMySqlDatabaseEnginePackageIdentity")
    sqlInputDir.set(commerceMySqlDatabaseEnginePackageSourceDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    outputDir.set(commerceMySqlDatabaseEnginePackageDirectory)
}

// Packages are public, deployable artifacts.  The compiler reports source locations for useful
// diagnostics, but those locations must never disclose a developer's workstation path in either
// SQL comments or runtime error text.  Keep this guard independent of the emitter unit tests: it
// checks the actual packaged output for both supported dialects and both proof schemas.
tasks.register("titanGraphqlVerifyDatabaseEnginePackagePrivacy") {
    description = "Rejects workstation paths from all whole-request database engine packages."
    group = "verification"
    dependsOn(
        "titanGraphqlBindDatabaseEnginePackage",
        "titanGraphqlBindMySqlDatabaseEnginePackage",
        "titanGraphqlBindCommerceDatabaseEnginePackage",
        "titanGraphqlBindCommerceMySqlDatabaseEnginePackage",
        "titanGraphqlGenerateDatabaseEngineFrontendDescriptor",
        "titanGraphqlGenerateMySqlDatabaseEngineFrontendDescriptor"
    )
    val packageDirectories = listOf(
        titanGraphqlDatabaseEnginePackageDirectory,
        titanGraphqlMySqlDatabaseEnginePackageDirectory,
        commerceDatabaseEnginePackageDirectory,
        commerceMySqlDatabaseEnginePackageDirectory
    )
    val report = layout.buildDirectory.file("reports/database-engine/privacy-scan.txt")
    packageDirectories.forEach { directory -> inputs.dir(directory) }
    outputs.file(report)
    doLast {
        val privateMachinePath = Regex(
            """(?i)(?:/""" +
                """home/[^/\s]+/|/users/[^/\s]+/|/private/|[a-z]:\\\\users\\\\)"""
        )
        val absoluteSourceLocation = Regex(
            """(?m)(?:-- titan:source:|NullPointerException at )(?:/|[a-z]:[\\\\/])"""
        )
        val credential = Regex(
            """(?i)-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----|""" +
                """AKIA[0-9A-Z]{16}|github_pat_[A-Za-z0-9_]{30,}|gh[pousr]_[A-Za-z0-9_]{30,}"""
        )
        fun finding(contents: String): String? = when {
            privateMachinePath.containsMatchIn(contents) -> "private machine path"
            absoluteSourceLocation.containsMatchIn(contents) -> "absolute source location"
            credential.containsMatchIn(contents) -> "high-confidence credential"
            else -> null
        }
        check(finding("/" + "home/example-user/project/source.sql") == "private machine path")
        check(finding("-----BEGIN " + "PRIVATE KEY-----") == "high-confidence credential")
        check(finding("github_pat_" + "A".repeat(30)) == "high-confidence credential")
        check(finding("SELECT 1;") == null)
        val packageFiles = packageDirectories.flatMap { directory ->
            directory.get().asFile.walkTopDown()
                .filter { file -> file.isFile && file.extension in setOf("sql", "json", "sha256", "properties", "txt") }
                .toList()
        }
        check(packageFiles.isNotEmpty()) { "database engine packages did not produce SQL or metadata" }
        val leak = packageFiles.asSequence().mapNotNull { file ->
            val category = finding(file.readText())
            category?.let { "${file.name}: $it" }
        }.firstOrNull()
        check(leak == null) { "database engine package privacy scan failed: $leak" }
        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText("status=passed\nfiles-scanned=${packageFiles.size}\n"
            + "patterns=private-machine-path,absolute-source-location,high-confidence-credential\n")
    }
}

tasks.register("titanGraphqlDatabaseEngineArtifactReport") {
    description = "Records reproducible hashes and byte sizes for the reviewed database-serving artifacts."
    group = "verification"
    dependsOn("titanGraphqlVerifyDatabaseEnginePackagePrivacy", databaseHttpFrontendDistribution)
    val packages = listOf(
        "demo-postgresql" to titanGraphqlDatabaseEnginePackageDirectory,
        "demo-mysql" to titanGraphqlMySqlDatabaseEnginePackageDirectory,
        "commerce-postgresql" to commerceDatabaseEnginePackageDirectory,
        "commerce-mysql" to commerceMySqlDatabaseEnginePackageDirectory
    )
    val report = layout.buildDirectory.file("reports/database-engine/artifact-inventory.tsv")
    packages.forEach { (_, directory) -> inputs.dir(directory) }
    inputs.file(databaseHttpFrontendDistribution.flatMap { it.archiveFile })
    outputs.file(report)
    doLast {
        fun hash(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }
        val rows = mutableListOf("artifact\tpath\tbytes\tsha256")
        for ((name, directory) in packages) {
            val root = directory.get().asFile
            for (file in root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }) {
                rows += listOf(name, file.relativeTo(root).path.replace('\\', '/'),
                    file.length().toString(), hash(file)).joinToString("\t")
            }
        }
        val zip = databaseHttpFrontendDistribution.get().archiveFile.get().asFile
        rows += listOf("frontend-zip", zip.name, zip.length().toString(), hash(zip)).joinToString("\t")
        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(rows.joinToString("\n", postfix = "\n"))
    }
}

tasks.register<TitanVerifyInstallTask>("titanGraphqlVerifyCommerceDatabaseEngineInstall") {
    description = "Install-verifies the PostgreSQL whole-request commerce engine proof."
    group = "verification"
    dependsOn("titanGraphqlPackageCommerceDatabaseEngine")
    sqlInputDir.set(commerceDatabaseEnginePackageSourceDirectory)
    artifactDir.set(commerceDatabaseEnginePackageDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    jdbcUrl.set("")
    username.set("")
    password.set("")
    dialect.set("postgresql")
    failOnVerificationError.set(true)
    jdbcDriverClasspath.from(configurations["titanJdbc"])
    outputs.upToDateWhen { false }
}

tasks.register<TitanVerifyInstallTask>("titanGraphqlVerifyCommerceMySqlDatabaseEngineInstall") {
    description = "Install-verifies the MySQL whole-request commerce engine proof."
    group = "verification"
    dependsOn("titanGraphqlPackageCommerceMySqlDatabaseEngine")
    sqlInputDir.set(commerceMySqlDatabaseEnginePackageSourceDirectory)
    artifactDir.set(commerceMySqlDatabaseEnginePackageDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    jdbcUrl.set("")
    username.set("")
    password.set("")
    dialect.set("mysql")
    failOnVerificationError.set(true)
    jdbcDriverClasspath.from(configurations["titanJdbc"])
    outputs.upToDateWhen { false }
}

// The whole-request packages are production-shaped artifacts, so bind each installed dialect
// package to the reviewed model just as the carrier packages are bound. The HTTP integration
// tests consume these sidecars; a carrier-only or stale database-engine package must fail before
// a request can reach its datasource.
tasks.register<JavaExec>("titanGraphqlBindCommerceDatabaseEnginePackage") {
    description = "Binds the verified PostgreSQL whole-request commerce engine to its reviewed model."
    group = "titan"
    dependsOn("classes", "titanGraphqlVerifyCommerceDatabaseEngineInstall", "titanGraphqlGenerateCommerceDatabaseEngineRuntimeIdentity")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
    args(commerceModelFile.asFile.absolutePath, commerceDatabaseEnginePackageDirectory.get().asFile.absolutePath,
        commerceDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        commerceDatabaseEnginePackageIdentity.get().asFile.absolutePath)
    inputs.files(commerceModelFile, commerceDatabaseEngineRuntimeIdentity, commerceDatabaseEnginePackageIdentity)
    inputs.files(
        commerceDatabaseEnginePackageDirectory.map { it.file("titan-artifact.json") },
        commerceDatabaseEnginePackageDirectory.map { it.file("titan-object-inventory.json") },
        commerceDatabaseEnginePackageDirectory.map { it.file("titan-install-plan.json") },
        commerceDatabaseEnginePackageDirectory.map { it.file("titan-install-verification.json") }
    )
    outputs.files(
        commerceDatabaseEnginePackageDirectory.map { it.file("titan-graphql-package.json") },
        commerceDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
        commerceDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") })
}

tasks.register<JavaExec>("titanGraphqlBindCommerceMySqlDatabaseEnginePackage") {
    description = "Binds the verified MySQL whole-request commerce engine to its reviewed model."
    group = "titan"
    dependsOn("classes", "titanGraphqlVerifyCommerceMySqlDatabaseEngineInstall", "titanGraphqlGenerateCommerceMySqlDatabaseEngineRuntimeIdentity")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
    args(commerceModelFile.asFile.absolutePath, commerceMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath,
        commerceMySqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        commerceMySqlDatabaseEnginePackageIdentity.get().asFile.absolutePath)
    inputs.files(commerceModelFile, commerceMySqlDatabaseEngineRuntimeIdentity, commerceMySqlDatabaseEnginePackageIdentity)
    inputs.files(
        commerceMySqlDatabaseEnginePackageDirectory.map { it.file("titan-artifact.json") },
        commerceMySqlDatabaseEnginePackageDirectory.map { it.file("titan-object-inventory.json") },
        commerceMySqlDatabaseEnginePackageDirectory.map { it.file("titan-install-plan.json") },
        commerceMySqlDatabaseEnginePackageDirectory.map { it.file("titan-install-verification.json") }
    )
    outputs.files(
        commerceMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-package.json") },
        commerceMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
        commerceMySqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") })
}

listOf("postgresql" to "PostgreSql", "mysql" to "MySql").forEach { (dialectId, taskSuffix) ->
    val root = "generated/proofs/database-engine-commerce-replacement-$dialectId"
    val sourceDirectory = layout.buildDirectory.dir("$root/package-source")
    val packageDirectory = layout.buildDirectory.dir("$root/package")
    val packageIdentity = layout.buildDirectory.file("$root/package-identity.sha256")
    val baseSource = if (dialectId == "mysql")
        commerceMySqlDatabaseEnginePackageSourceDirectory else commerceDatabaseEnginePackageSourceDirectory
    val runtimeIdentity = if (dialectId == "mysql")
        commerceMySqlDatabaseEngineRuntimeIdentity else commerceDatabaseEngineRuntimeIdentity
    val baseIdentityTask = if (dialectId == "mysql")
        "titanGraphqlGenerateCommerceMySqlDatabaseEnginePackageIdentity"
        else "titanGraphqlGenerateCommerceDatabaseEnginePackageIdentity"
    val helperClass = if (dialectId == "mysql")
        "GeneratedDatabaseGraphqlMySqlProcedure" else "GeneratedDatabaseGraphqlSchema"
    val stageTask = "titanGraphqlStageCommerce${taskSuffix}ReplacementPackage"
    val identityTask = "titanGraphqlGenerateCommerce${taskSuffix}ReplacementPackageIdentity"
    val packageTask = "titanGraphqlPackageCommerce${taskSuffix}Replacement"
    val verifyTask = "titanGraphqlVerifyCommerce${taskSuffix}ReplacementInstall"
    val bindTask = "titanGraphqlBindCommerce${taskSuffix}ReplacementPackage"

    tasks.register<Sync>(stageTask) {
        dependsOn(baseIdentityTask)
        from(baseSource)
        into(sourceDirectory)
        doLast {
            val helper = sourceDirectory.get().file(
                "$dialectId/io_titan_graphql_database_generated_${helperClass}__mutationRegistryIdentity.sql")
                .asFile
            val original = helper.readText()
            check(original.contains("BEGIN\n")) { "replacement proof helper has no routine body" }
            helper.writeText(original.replaceFirst("BEGIN\n", "BEGIN\n    -- m5 package replacement proof\n"))
        }
    }
    tasks.register<JavaExec>(identityTask) {
        dependsOn("classes", stageTask)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentityCli")
        args(dialectId, sourceDirectory.get().dir(dialectId).asFile.absolutePath,
            packageIdentity.get().asFile.absolutePath,
            sourceDirectory.get().file("$dialectId/R__titan_005_graphql_package_identity.sql")
                .asFile.absolutePath)
        inputs.dir(sourceDirectory)
        outputs.file(packageIdentity)
        outputs.file(sourceDirectory.map { it.file("$dialectId/R__titan_005_graphql_package_identity.sql") })
    }
    tasks.register<TitanPackageTask>(packageTask) {
        dependsOn(identityTask)
        sqlInputDir.set(sourceDirectory)
        mode.set("migration")
        titanVersion.set(providers.provider { titanGraphqlProjectVersion })
        outputDir.set(packageDirectory)
    }
    tasks.register<TitanVerifyInstallTask>(verifyTask) {
        dependsOn(packageTask)
        sqlInputDir.set(sourceDirectory)
        artifactDir.set(packageDirectory)
        mode.set("migration")
        titanVersion.set(providers.provider { titanGraphqlProjectVersion })
        jdbcUrl.set("")
        username.set("")
        password.set("")
        dialect.set(dialectId)
        failOnVerificationError.set(true)
        jdbcDriverClasspath.from(configurations["titanJdbc"])
        outputs.upToDateWhen { false }
    }
    tasks.register<JavaExec>(bindTask) {
        dependsOn("classes", verifyTask)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
        args(commerceModelFile.asFile.absolutePath, packageDirectory.get().asFile.absolutePath,
            runtimeIdentity.get().asFile.absolutePath, packageIdentity.get().asFile.absolutePath)
        inputs.files(commerceModelFile, runtimeIdentity, packageIdentity)
        inputs.files(
            packageDirectory.map { it.file("titan-artifact.json") },
            packageDirectory.map { it.file("titan-object-inventory.json") },
            packageDirectory.map { it.file("titan-install-plan.json") },
            packageDirectory.map { it.file("titan-install-verification.json") })
        outputs.files(
            packageDirectory.map { it.file("titan-graphql-package.json") },
            packageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
            packageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") })
    }
}

val managementDatabaseEngineSchema = "management_graphql"
listOf("postgresql" to "PostgreSql", "mysql" to "MySql").forEach { (dialectId, taskSuffix) ->
    val root = "generated/proofs/database-engine-management-$dialectId"
    val generatedClass = if (dialectId == "mysql")
        "GeneratedDatabaseGraphqlMySqlProcedure.java" else "GeneratedDatabaseGraphqlSchema.java"
    val generatedSource = layout.buildDirectory.file(
        "$root/sources/io/titan/graphql/database/generated/$generatedClass"
    )
    val sqlDirectory = layout.buildDirectory.dir("$root/sql")
    val packageSourceDirectory = layout.buildDirectory.dir("$root/package-source")
    val packageDirectory = layout.buildDirectory.dir("$root/package")
    val runtimeIdentity = layout.buildDirectory.file("$root/runtime-identity.$dialectId.sha256")
    val packageIdentity = layout.buildDirectory.file("$root/package-identity.$dialectId.sha256")
    val identityTask = "titanGraphqlGenerateManagement${taskSuffix}DatabaseEngineRuntimeIdentity"
    val sourceTask = "titanGraphqlGenerateManagement${taskSuffix}DatabaseEngine"
    val transpileTask = "titanGraphqlTranspileManagement${taskSuffix}DatabaseEngine"
    val stageTask = "titanGraphqlStageManagement${taskSuffix}DatabaseEnginePackage"
    val packageIdentityTask = "titanGraphqlGenerateManagement${taskSuffix}DatabaseEnginePackageIdentity"
    val packageTask = "titanGraphqlPackageManagement${taskSuffix}DatabaseEngine"
    val verifyTask = "titanGraphqlVerifyManagement${taskSuffix}DatabaseEngineInstall"
    val bindTask = "titanGraphqlBindManagement${taskSuffix}DatabaseEnginePackage"

    tasks.register<JavaExec>(identityTask) {
        group = "titan"
        dependsOn("titanGraphqlVerifyDatabaseMutationHandlers")
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentityCli")
        args(managementDatabaseModelFile.asFile.absolutePath, dialectId,
            runtimeIdentity.get().asFile.absolutePath,
            "common-engine", titanGraphqlDatabaseEngineCommonSource.asFile.absolutePath,
            "common-language", titanGraphqlDatabaseLanguageSource.asFile.absolutePath,
            "schema-generator", layout.projectDirectory.file(
                "src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java").asFile.absolutePath,
            "build-options", layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath,
            "dependency-locks", layout.projectDirectory.file("gradle.lockfile").asFile.absolutePath,
            "titan-version", layout.projectDirectory.file("vendor/titan/gradle.properties").asFile.absolutePath)
        args(*databaseMutationHandlerIdentityArgs.toTypedArray())
        inputs.files(managementDatabaseModelFile, titanGraphqlDatabaseEngineCommonSource,
            titanGraphqlDatabaseLanguageSource, databaseMutationHandlerSources,
            layout.projectDirectory.file("src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java"),
            layout.projectDirectory.file("build.gradle.kts"), layout.projectDirectory.file("gradle.lockfile"),
            layout.projectDirectory.file("vendor/titan/gradle.properties"))
        outputs.file(runtimeIdentity)
    }

    tasks.register<JavaExec>(sourceTask) {
        group = "titan"
        dependsOn(identityTask)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(if (dialectId == "mysql")
            "io.titan.graphql.codegen.TitanGraphqlMySqlDatabaseEngineSourceGeneratorCli"
            else "io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorCli")
        args(managementDatabaseModelFile.asFile.absolutePath, generatedSource.get().asFile.absolutePath,
            runtimeIdentity.get().asFile.absolutePath, managementDatabaseEngineSchema)
        inputs.files(managementDatabaseModelFile, runtimeIdentity)
        outputs.file(generatedSource)
    }

    tasks.register<TitanTranspileTask>(transpileTask) {
        group = "verification"
        dependsOn("titanGraphqlVerifyDatabaseEngineBoundary", sourceTask)
        sourceFiles.setFrom(titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource,
            titanGraphqlDatabaseAstSource, titanGraphqlDatabaseTypeReferenceSource,
            databaseMutationHandlerSources, generatedSource)
        classpathFiles.from(databaseEngine.compileClasspath)
        targets.set(listOf(dialectId))
        schemas.set(listOf(managementDatabaseEngineSchema))
        strictWraparound.set(false)
        sqlSafety.set("strict")
        observability.set(dialectId == "postgresql")
        debugMode.set(false)
        sensitiveColumns.set(listOf("document"))
        outputDir.set(sqlDirectory)
    }

    tasks.register<Sync>(stageTask) {
        dependsOn(transpileTask)
        from(sqlDirectory)
        from("src/database-engine-migrations/management/$dialectId") { into(dialectId) }
        into(packageSourceDirectory)
    }

    tasks.register<JavaExec>(packageIdentityTask) {
        group = "verification"
        dependsOn(stageTask)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentityCli")
        args(dialectId, packageSourceDirectory.get().dir(dialectId).asFile.absolutePath,
            packageIdentity.get().asFile.absolutePath,
            packageSourceDirectory.get().file("$dialectId/R__titan_005_graphql_package_identity.sql")
                .asFile.absolutePath,
            "$managementDatabaseEngineSchema.execute_graphql_request")
        inputs.dir(packageSourceDirectory)
        outputs.file(packageIdentity)
        outputs.file(packageSourceDirectory.map {
            it.file("$dialectId/R__titan_005_graphql_package_identity.sql")
        })
    }

    tasks.register<TitanPackageTask>(packageTask) {
        group = "verification"
        dependsOn(packageIdentityTask)
        sqlInputDir.set(packageSourceDirectory)
        mode.set("migration")
        titanVersion.set(providers.provider { titanGraphqlProjectVersion })
        outputDir.set(packageDirectory)
    }

    tasks.register<TitanVerifyInstallTask>(verifyTask) {
        group = "verification"
        dependsOn(packageTask)
        sqlInputDir.set(packageSourceDirectory)
        artifactDir.set(packageDirectory)
        mode.set("migration")
        titanVersion.set(providers.provider { titanGraphqlProjectVersion })
        jdbcUrl.set("")
        username.set("")
        password.set("")
        dialect.set(dialectId)
        failOnVerificationError.set(true)
        jdbcDriverClasspath.from(configurations["titanJdbc"])
        outputs.upToDateWhen { false }
    }

    tasks.register<JavaExec>(bindTask) {
        group = "titan"
        dependsOn(verifyTask, identityTask)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
        args(managementDatabaseModelFile.asFile.absolutePath, packageDirectory.get().asFile.absolutePath,
            runtimeIdentity.get().asFile.absolutePath, packageIdentity.get().asFile.absolutePath)
        inputs.files(managementDatabaseModelFile, runtimeIdentity, packageIdentity)
        inputs.files(
            packageDirectory.map { it.file("titan-artifact.json") },
            packageDirectory.map { it.file("titan-object-inventory.json") },
            packageDirectory.map { it.file("titan-install-plan.json") },
            packageDirectory.map { it.file("titan-install-verification.json") })
        outputs.files(
            packageDirectory.map { it.file("titan-graphql-package.json") },
            packageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
            packageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") })
    }

    tasks.register<JavaExec>("titanGraphqlGenerateManagement${taskSuffix}DatabaseEngineFrontendDescriptor") {
        group = "titan"
        dependsOn(bindTask)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli")
        val descriptor = layout.buildDirectory.file("$root/frontend-deployment.properties")
        args(packageDirectory.get().asFile.absolutePath, dialectId, descriptor.get().asFile.absolutePath)
        inputs.files(
            packageDirectory.map { it.file("titan-artifact.json") },
            packageDirectory.map { it.file("titan-object-inventory.json") },
            packageDirectory.map { it.file("titan-install-verification.json") },
            packageDirectory.map { it.file("titan-graphql-package.json") },
            packageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
            packageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") })
        outputs.file(descriptor)
    }
}

val previewModelOption = providers.gradleProperty("titanGraphqlPreviewModel").orNull
val previewIdOption = providers.gradleProperty("titanGraphqlPreviewId").orNull
if (previewModelOption != null || previewIdOption != null) {
    check(previewModelOption != null && previewIdOption != null) {
        "titanGraphqlPreviewModel and titanGraphqlPreviewId must be supplied together"
    }
    check(previewIdOption.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
        "titanGraphqlPreviewId is invalid"
    }
    val previewModelSource = file(previewModelOption)
    check(previewModelSource.isFile) { "titanGraphqlPreviewModel does not exist: $previewModelSource" }
    val candidateDigest = MessageDigest.getInstance("SHA-256").apply {
        update(previewIdOption.toByteArray(StandardCharsets.UTF_8))
        update(0)
        update(previewModelSource.readBytes())
    }.digest()
    val candidateKey = HexFormat.of().formatHex(candidateDigest).take(24)
    val previewSchema = "preview_$candidateKey"
    listOf("postgresql" to "PostgreSql", "mysql" to "MySql").forEach { (dialectId, taskSuffix) ->
        val root = "generated/preview-candidates/$previewIdOption-$candidateKey/$dialectId"
        val generatedClass = if (dialectId == "mysql")
            "GeneratedDatabaseGraphqlMySqlProcedure.java" else "GeneratedDatabaseGraphqlSchema.java"
        val generatedSource = layout.buildDirectory.file(
            "$root/sources/io/titan/graphql/database/generated/$generatedClass"
        )
        val sqlDirectory = layout.buildDirectory.dir("$root/sql")
        val packageSourceDirectory = layout.buildDirectory.dir("$root/package-source")
        val packageDirectory = layout.buildDirectory.dir("$root/package")
        val runtimeIdentity = layout.buildDirectory.file("$root/runtime-identity.$dialectId.sha256")
        val packageIdentity = layout.buildDirectory.file("$root/package-identity.$dialectId.sha256")
        val identityTask = "titanGraphqlGeneratePreview${taskSuffix}RuntimeIdentity"
        val sourceTask = "titanGraphqlGeneratePreview${taskSuffix}Source"
        val transpileTask = "titanGraphqlTranspilePreview$taskSuffix"
        val stageTask = "titanGraphqlStagePreview${taskSuffix}Package"
        val packageIdentityTask = "titanGraphqlGeneratePreview${taskSuffix}PackageIdentity"
        val packageTask = "titanGraphqlPackagePreview$taskSuffix"
        val verifyTask = "titanGraphqlVerifyPreview${taskSuffix}Install"
        val bindTask = "titanGraphqlBindPreview${taskSuffix}Package"

        tasks.register<JavaExec>(identityTask) {
            group = "titan"
            dependsOn("titanGraphqlVerifyDatabaseMutationHandlers")
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentityCli")
            args(previewModelSource.absolutePath, dialectId, runtimeIdentity.get().asFile.absolutePath,
                "common-engine", titanGraphqlDatabaseEngineCommonSource.asFile.absolutePath,
                "common-language", titanGraphqlDatabaseLanguageSource.asFile.absolutePath,
                "schema-generator", layout.projectDirectory.file(
                    "src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java")
                    .asFile.absolutePath,
                "build-options", layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath,
                "dependency-locks", layout.projectDirectory.file("gradle.lockfile").asFile.absolutePath,
                "titan-version", layout.projectDirectory.file("vendor/titan/gradle.properties")
                    .asFile.absolutePath)
            args(*databaseMutationHandlerIdentityArgs.toTypedArray())
            inputs.files(previewModelSource, titanGraphqlDatabaseEngineCommonSource,
                titanGraphqlDatabaseLanguageSource, databaseMutationHandlerSources,
                layout.projectDirectory.file(
                    "src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java"),
                layout.projectDirectory.file("build.gradle.kts"),
                layout.projectDirectory.file("gradle.lockfile"),
                layout.projectDirectory.file("vendor/titan/gradle.properties"))
            outputs.file(runtimeIdentity)
        }

        tasks.register<JavaExec>(sourceTask) {
            group = "titan"
            dependsOn(identityTask)
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set(if (dialectId == "mysql")
                "io.titan.graphql.codegen.TitanGraphqlMySqlDatabaseEngineSourceGeneratorCli"
                else "io.titan.graphql.codegen.TitanGraphqlDatabaseEngineSourceGeneratorCli")
            args(previewModelSource.absolutePath, generatedSource.get().asFile.absolutePath,
                runtimeIdentity.get().asFile.absolutePath, previewSchema)
            inputs.files(previewModelSource, runtimeIdentity)
            outputs.file(generatedSource)
        }

        tasks.register<TitanTranspileTask>(transpileTask) {
            group = "verification"
            dependsOn("titanGraphqlVerifyDatabaseEngineBoundary", sourceTask)
            sourceFiles.setFrom(titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource,
                titanGraphqlDatabaseAstSource, titanGraphqlDatabaseTypeReferenceSource,
                databaseMutationHandlerSources, generatedSource)
            classpathFiles.from(databaseEngine.compileClasspath)
            targets.set(listOf(dialectId))
            schemas.set(listOf(previewSchema))
            strictWraparound.set(false)
            sqlSafety.set("strict")
            observability.set(dialectId == "postgresql")
            debugMode.set(false)
            sensitiveColumns.set(listOf("email", "document"))
            outputDir.set(sqlDirectory)
        }

        tasks.register<Sync>(stageTask) {
            dependsOn(transpileTask)
            from(sqlDirectory)
            from("src/database-engine-migrations/$dialectId") { into(dialectId) }
            into(packageSourceDirectory)
            doLast {
                val schemaMigration = packageSourceDirectory.get()
                    .file("$dialectId/R__titan_001_preview_schema.sql").asFile
                schemaMigration.writeText(if (dialectId == "postgresql")
                    "CREATE SCHEMA IF NOT EXISTS \"$previewSchema\";\n"
                    else "CREATE DATABASE IF NOT EXISTS `$previewSchema`;\n")
            }
        }

        tasks.register<JavaExec>(packageIdentityTask) {
            group = "verification"
            dependsOn(stageTask)
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabasePackageIdentityCli")
            args(dialectId, packageSourceDirectory.get().dir(dialectId).asFile.absolutePath,
                packageIdentity.get().asFile.absolutePath,
                packageSourceDirectory.get().file("$dialectId/R__titan_005_graphql_package_identity.sql")
                    .asFile.absolutePath,
                "$previewSchema.execute_graphql_request")
            inputs.dir(packageSourceDirectory)
            outputs.file(packageIdentity)
            outputs.file(packageSourceDirectory.map {
                it.file("$dialectId/R__titan_005_graphql_package_identity.sql")
            })
        }

        tasks.register<TitanPackageTask>(packageTask) {
            group = "verification"
            dependsOn(packageIdentityTask)
            sqlInputDir.set(packageSourceDirectory)
            mode.set("migration")
            titanVersion.set(providers.provider { titanGraphqlProjectVersion })
            if (dialectId == "mysql") additionalRuntimeSchemas.set(listOf(previewSchema))
            outputDir.set(packageDirectory)
        }

        tasks.register<TitanVerifyInstallTask>(verifyTask) {
            group = "verification"
            dependsOn(packageTask)
            sqlInputDir.set(packageSourceDirectory)
            artifactDir.set(packageDirectory)
            mode.set("migration")
            titanVersion.set(providers.provider { titanGraphqlProjectVersion })
            if (dialectId == "mysql") additionalRuntimeSchemas.set(listOf(previewSchema))
            jdbcUrl.set("")
            username.set("")
            password.set("")
            dialect.set(dialectId)
            failOnVerificationError.set(true)
            jdbcDriverClasspath.from(configurations["titanJdbc"])
            outputs.upToDateWhen { false }
        }

        tasks.register<JavaExec>(bindTask) {
            group = "titan"
            dependsOn(verifyTask, identityTask)
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
            args(previewModelSource.absolutePath, packageDirectory.get().asFile.absolutePath,
                runtimeIdentity.get().asFile.absolutePath, packageIdentity.get().asFile.absolutePath)
            inputs.files(previewModelSource, runtimeIdentity, packageIdentity,
                packageDirectory.map { it.file("titan-artifact.json") },
                packageDirectory.map { it.file("titan-object-inventory.json") },
                packageDirectory.map { it.file("titan-install-plan.json") },
                packageDirectory.map { it.file("titan-install-verification.json") })
            outputs.file(packageDirectory.map { it.file("titan-graphql-package.json") })
        }

        tasks.register<JavaExec>("titanGraphqlBuildPreview$taskSuffix") {
            description = "Builds and scratch-verifies an isolated $dialectId preview package."
            group = "titan"
            dependsOn(bindTask)
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseFrontendDescriptorCli")
            args(packageDirectory.get().asFile.absolutePath, dialectId,
                packageDirectory.get().file("frontend-deployment.properties").asFile.absolutePath)
            inputs.files(packageDirectory.map { it.file("titan-graphql-package.json") },
                packageDirectory.map { it.file("titan-install-verification.json") })
            outputs.file(packageDirectory.map { it.file("frontend-deployment.properties") })
            doLast {
                logger.lifecycle("Preview candidate package: {}", packageDirectory.get().asFile.absolutePath)
                logger.lifecycle("Preview candidate schema: {}", previewSchema)
            }
        }

        tasks.register<TitanVerifyInstallTask>("titanGraphqlInstallPreview$taskSuffix") {
            description = "Installs the isolated $dialectId preview package into a configured database."
            group = "titan"
            dependsOn("titanGraphqlBuildPreview$taskSuffix")
            sqlInputDir.set(packageSourceDirectory)
            artifactDir.set(packageDirectory)
            mode.set("migration")
            titanVersion.set(providers.provider { titanGraphqlProjectVersion })
            if (dialectId == "mysql") additionalRuntimeSchemas.set(listOf(previewSchema))
            jdbcUrl.set(providers.gradleProperty("titanGraphqlPreviewJdbcUrl").orElse(""))
            username.set(providers.environmentVariable("TITAN_GRAPHQL_CONTROL_DB_USER").orElse(""))
            password.set(providers.environmentVariable("TITAN_GRAPHQL_CONTROL_DB_PASSWORD").orElse(""))
            dialect.set(dialectId)
            failOnVerificationError.set(true)
            jdbcDriverClasspath.from(configurations["titanJdbc"])
            outputs.upToDateWhen { false }
            doFirst {
                check(jdbcUrl.get().isNotBlank()) {
                    "titanGraphqlPreviewJdbcUrl is required for target preview installation"
                }
            }
        }
    }

    tasks.register<Test>("databaseEnginePreviewCandidateIntegrationTest") {
        description = "Proves draft-to-candidate preview publication on PostgreSQL and MySQL."
        group = "verification"
        dependsOn("titanGraphqlBuildPreviewPostgreSql", "titanGraphqlBuildPreviewMySql",
            "titanGraphqlGenerateManagementPostgreSqlDatabaseEngineFrontendDescriptor",
            "titanGraphqlGenerateManagementMySqlDatabaseEngineFrontendDescriptor")
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("database-engine-preview-candidate") }
        systemProperty("titan.graphql.preview.candidate.model", previewModelSource.absolutePath)
        systemProperty("titan.graphql.preview.candidate.package.postgresql",
            layout.buildDirectory.dir("generated/preview-candidates/$previewIdOption-$candidateKey/postgresql/package")
                .get().asFile.absolutePath)
        systemProperty("titan.graphql.preview.candidate.package.mysql",
            layout.buildDirectory.dir("generated/preview-candidates/$previewIdOption-$candidateKey/mysql/package")
                .get().asFile.absolutePath)
        inputs.file(previewModelSource)
        inputs.dir(layout.buildDirectory.dir(
            "generated/preview-candidates/$previewIdOption-$candidateKey/postgresql/package"))
        inputs.dir(layout.buildDirectory.dir(
            "generated/preview-candidates/$previewIdOption-$candidateKey/mysql/package"))
        shouldRunAfter(tasks.named("test"))
    }
}

tasks.register<Test>("databaseEngineManagementIntegrationTest") {
    group = "verification"
    dependsOn("titanGraphqlGenerateManagementPostgreSqlDatabaseEngineFrontendDescriptor",
        "titanGraphqlGenerateManagementMySqlDatabaseEngineFrontendDescriptor",
        "titanGraphqlBindCommerceDatabaseEnginePackage",
        "titanGraphqlBindCommerceMySqlDatabaseEnginePackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    inputs.files(
        commerceDatabaseEnginePackageIdentity,
        commerceMySqlDatabaseEnginePackageIdentity,
        layout.buildDirectory.file("generated/proofs/database-engine-management-postgresql/package-identity.postgresql.sha256"),
        layout.buildDirectory.file("generated/proofs/database-engine-management-mysql/package-identity.mysql.sha256"),
        layout.buildDirectory.file("generated/proofs/database-engine-management-postgresql/frontend-deployment.properties"),
        layout.buildDirectory.file("generated/proofs/database-engine-management-mysql/frontend-deployment.properties"))
    useJUnitPlatform { includeTags("database-engine-management") }
}

tasks.register<Test>("databaseEngineManagementHttpIntegrationTest") {
    group = "verification"
    dependsOn(
        "titanGraphqlGenerateDatabaseEngineFrontendDescriptor",
        "titanGraphqlGenerateMySqlDatabaseEngineFrontendDescriptor",
        "titanGraphqlGenerateManagementPostgreSqlDatabaseEngineFrontendDescriptor",
        "titanGraphqlGenerateManagementMySqlDatabaseEngineFrontendDescriptor",
        databaseHttpFrontendDistribution,
        controlJobWorkerDistribution,
        databaseHttpFrontendTest.classesTaskName)
    testClassesDirs = databaseHttpFrontendTest.output.classesDirs
    classpath = databaseHttpFrontendTest.runtimeClasspath
    inputs.dir(titanGraphqlDatabaseEnginePackageDirectory)
    inputs.dir(titanGraphqlMySqlDatabaseEnginePackageDirectory)
    inputs.dir(layout.buildDirectory.dir("generated/proofs/database-engine-management-postgresql/package"))
    inputs.dir(layout.buildDirectory.dir("generated/proofs/database-engine-management-mysql/package"))
    inputs.file(databaseHttpFrontendDistribution.flatMap { it.archiveFile })
    inputs.file(controlJobWorkerDistribution.flatMap { it.archiveFile })
    useJUnitPlatform { includeTags("database-engine-management-http") }
    jvmArgs("-Djava.util.logging.manager=java.util.logging.LogManager")
    systemProperty("titan.graphql.database-engine.migrations.dir",
        titanGraphqlDatabaseEnginePackageDirectory.get().dir("postgresql").asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.mysql.migrations.dir",
        titanGraphqlMySqlDatabaseEnginePackageDirectory.get().dir("mysql").asFile.absolutePath)
    systemProperty("titan.graphql.database-frontend.descriptor",
        titanGraphqlDatabaseEngineFrontendDescriptor.get().asFile.absolutePath)
    systemProperty("titan.graphql.database-frontend.mysql.descriptor",
        titanGraphqlMySqlDatabaseEngineFrontendDescriptor.get().asFile.absolutePath)
    systemProperty("titan.graphql.management-frontend.descriptor",
        layout.buildDirectory.file("generated/proofs/database-engine-management-postgresql/frontend-deployment.properties")
            .get().asFile.absolutePath)
    systemProperty("titan.graphql.management-frontend.mysql.descriptor",
        layout.buildDirectory.file("generated/proofs/database-engine-management-mysql/frontend-deployment.properties")
            .get().asFile.absolutePath)
    systemProperty("titan.graphql.management.migrations.dir",
        layout.buildDirectory.dir("generated/proofs/database-engine-management-postgresql/package/postgresql")
            .get().asFile.absolutePath)
    systemProperty("titan.graphql.management.mysql.migrations.dir",
        layout.buildDirectory.dir("generated/proofs/database-engine-management-mysql/package/mysql")
            .get().asFile.absolutePath)
    systemProperty("titan.graphql.database-frontend.distribution",
        databaseHttpFrontendDistribution.get().archiveFile.get().asFile.absolutePath)
    systemProperty("titan.graphql.control-job.worker.distribution",
        controlJobWorkerDistribution.get().archiveFile.get().asFile.absolutePath)
}

tasks.register<Test>("databaseEngineContainerDeploymentIntegrationTest") {
    group = "verification"
    dependsOn(
        "titanGraphqlGenerateDatabaseEngineFrontendDescriptor",
        "titanGraphqlGenerateMySqlDatabaseEngineFrontendDescriptor",
        "titanGraphqlGenerateManagementPostgreSqlDatabaseEngineFrontendDescriptor",
        "titanGraphqlGenerateManagementMySqlDatabaseEngineFrontendDescriptor",
        "titanGraphqlVerifyDatabaseHttpFrontendReleaseArtifact",
        controlJobWorkerDistribution,
        "testClasses")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("database-engine-container-deployment") }
    inputs.dir(titanGraphqlDatabaseEnginePackageDirectory)
    inputs.dir(titanGraphqlMySqlDatabaseEnginePackageDirectory)
    inputs.dir(layout.buildDirectory.dir("generated/proofs/database-engine-management-postgresql/package"))
    inputs.dir(layout.buildDirectory.dir("generated/proofs/database-engine-management-mysql/package"))
    inputs.file(layout.buildDirectory.file(
        "generated/proofs/database-engine-management-postgresql/frontend-deployment.properties"))
    inputs.file(layout.buildDirectory.file(
        "generated/proofs/database-engine-management-mysql/frontend-deployment.properties"))
    inputs.file(databaseHttpFrontendDistribution.flatMap { it.archiveFile })
    inputs.file(controlJobWorkerDistribution.flatMap { it.archiveFile })
    inputs.files("deployment/compose.yaml", "deployment/Dockerfile.frontend", "deployment/Dockerfile.worker")
    systemProperty("titan.graphql.database-frontend.descriptor",
        titanGraphqlDatabaseEngineFrontendDescriptor.get().asFile.absolutePath)
    systemProperty("titan.graphql.database-frontend.mysql.descriptor",
        titanGraphqlMySqlDatabaseEngineFrontendDescriptor.get().asFile.absolutePath)
    systemProperty("titan.graphql.management-frontend.descriptor",
        layout.buildDirectory.file("generated/proofs/database-engine-management-postgresql/frontend-deployment.properties")
            .get().asFile.absolutePath)
    systemProperty("titan.graphql.management-frontend.mysql.descriptor",
        layout.buildDirectory.file("generated/proofs/database-engine-management-mysql/frontend-deployment.properties")
            .get().asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.migrations.dir",
        titanGraphqlDatabaseEnginePackageDirectory.get().dir("postgresql").asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.mysql.migrations.dir",
        titanGraphqlMySqlDatabaseEnginePackageDirectory.get().dir("mysql").asFile.absolutePath)
    systemProperty("titan.graphql.management.migrations.dir",
        layout.buildDirectory.dir("generated/proofs/database-engine-management-postgresql/package/postgresql")
            .get().asFile.absolutePath)
    systemProperty("titan.graphql.management.mysql.migrations.dir",
        layout.buildDirectory.dir("generated/proofs/database-engine-management-mysql/package/mysql")
            .get().asFile.absolutePath)
}

tasks.register<Test>("databaseEngineCommerceIntegrationTest") {
    description = "Runs the unrelated-schema database engine proof against PostgreSQL."
    group = "verification"
    dependsOn("titanGraphqlBindCommerceDatabaseEnginePackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    inputs.dir(commerceDatabaseEnginePackageDirectory)
    useJUnitPlatform { includeTags("database-engine-commerce") }
    systemProperty(
        "titan.graphql.database-engine.commerce.migrations.dir",
        commerceDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath
    )
    systemProperty(
        "titan.graphql.artifacts.dir",
        commerceDatabaseEnginePackageDirectory.get().asFile.absolutePath
    )
    shouldRunAfter(tasks.named("databaseEngineIntegrationTest"))
}

tasks.register<Test>("databaseEngineCommerceMySqlIntegrationTest") {
    description = "Runs the unrelated-schema database engine proof against MySQL."
    group = "verification"
    dependsOn("titanGraphqlBindCommerceMySqlDatabaseEnginePackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    inputs.dir(commerceMySqlDatabaseEnginePackageDirectory)
    useJUnitPlatform { includeTags("database-engine-commerce-mysql") }
    systemProperty(
        "titan.graphql.database-engine.commerce.migrations.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath
    )
    systemProperty(
        "titan.graphql.artifacts.dir",
        commerceMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath
    )
    shouldRunAfter(tasks.named("databaseEngineCommerceIntegrationTest"))
}

tasks.register<Test>("databaseEngineCommerceHttpRestartIntegrationTest") {
    description = "Proves keyed Commerce mutation recovery across standalone frontend processes."
    group = "verification"
    dependsOn(
        "titanGraphqlBindCommerceDatabaseEnginePackage",
        "titanGraphqlBindCommerceMySqlDatabaseEnginePackage",
        databaseHttpFrontendDistribution)
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("database-engine-commerce-http-restart") }
    systemProperty(
        "titan.graphql.database-engine.commerce.migrations.dir",
        commerceDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-engine.commerce.migrations.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-engine.commerce.package.dir",
        commerceDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-engine.commerce.package.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-frontend.distribution",
        databaseHttpFrontendDistribution.get().archiveFile.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("databaseEngineCommerceMySqlIntegrationTest"))
}

tasks.register<Test>("databaseEngineCommerceHttpCorpusIntegrationTest") {
    description = "Runs the shared Commerce expected-result corpus through the standalone HTTP distribution."
    group = "verification"
    dependsOn(
        "titanGraphqlBindCommerceDatabaseEnginePackage",
        "titanGraphqlBindCommerceMySqlDatabaseEnginePackage",
        databaseHttpFrontendDistribution)
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("database-engine-commerce-http-corpus") }
    systemProperty(
        "titan.graphql.database-engine.commerce.migrations.dir",
        commerceDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-engine.commerce.migrations.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-engine.commerce.package.dir",
        commerceDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-engine.commerce.package.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-frontend.distribution",
        databaseHttpFrontendDistribution.get().archiveFile.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("databaseEngineCommerceMySqlIntegrationTest"))
}

tasks.register<Test>("databaseEnginePackageReplacementIntegrationTest") {
    description = "Proves that a same-schema package replacement rejects a stale binding on a reused connection."
    group = "verification"
    dependsOn(
        "titanGraphqlBindCommerceDatabaseEnginePackage",
        "titanGraphqlBindCommerceMySqlDatabaseEnginePackage",
        "titanGraphqlBindCommercePostgreSqlReplacementPackage",
        "titanGraphqlBindCommerceMySqlReplacementPackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("database-engine-package-replacement") }
    systemProperty("titan.graphql.database-engine.commerce.migrations.dir",
        commerceDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.commerce.migrations.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.commerce.package.dir.postgresql",
        commerceDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.commerce.package.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.commerce.replacement.package.dir.postgresql",
        layout.buildDirectory.dir("generated/proofs/database-engine-commerce-replacement-postgresql/package")
            .get().asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.commerce.replacement.package.dir.mysql",
        layout.buildDirectory.dir("generated/proofs/database-engine-commerce-replacement-mysql/package")
            .get().asFile.absolutePath)
    shouldRunAfter(tasks.named("databaseEngineCommerceMySqlIntegrationTest"))
}

tasks.register<Test>("databaseEngineSnapshotIntegrationTest") {
    description = "Proves one repeatable-read snapshot across separate GraphQL roots on both dialects."
    group = "verification"
    dependsOn(
        "titanGraphqlBindCommerceDatabaseEnginePackage",
        "titanGraphqlBindCommerceMySqlDatabaseEnginePackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("database-engine-snapshot") }
    systemProperty("titan.graphql.database-engine.commerce.migrations.dir",
        commerceDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.commerce.migrations.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.commerce.package.dir.postgresql",
        commerceDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    systemProperty("titan.graphql.database-engine.commerce.package.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("databaseEngineCommerceMySqlIntegrationTest"))
}

tasks.register<Test>("databaseEngineControlJobRestartIntegrationTest") {
    description = "Proves control-plane artifact jobs survive separate command processes."
    group = "verification"
    dependsOn(
        "titanGraphqlBindCommerceDatabaseEnginePackage",
        "titanGraphqlBindCommerceMySqlDatabaseEnginePackage",
        controlJobWorkerDistribution)
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("database-engine-control-job-restart") }
    systemProperty(
        "titan.graphql.database-engine.commerce.migrations.dir",
        commerceDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath)
    systemProperty(
        "titan.graphql.database-engine.commerce.migrations.dir.mysql",
        commerceMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath)
    doFirst {
        systemProperty("titan.graphql.control-job.classpath", sourceSets["main"].runtimeClasspath.asPath)
    }
    systemProperty(
        "titan.graphql.control-job.worker.distribution",
        controlJobWorkerDistribution.get().archiveFile.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("databaseEngineCommerceMySqlIntegrationTest"))
}
