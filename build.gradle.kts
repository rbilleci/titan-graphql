import io.titan.gradle.TitanExtension
import io.titan.gradle.TitanPackageTask
import io.titan.gradle.TitanTranspileTask
import io.titan.gradle.TitanVerifyInstallTask
import org.gradle.api.tasks.Sync
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    java
    id("io.quarkus") version "3.36.0"
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

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.36.0"))
    implementation("io.titan:titan-dsl:0.1.0")
    implementation("io.titan:titan-management:0.1.0")
    implementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    // Live SQL execution mode (completion plan W5.1): the /graphql endpoint can answer from
    // the deployed stored functions over the Quarkus default (Agroal) datasource.
    implementation("io.quarkus:quarkus-agroal")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-jdbc-mysql")
    // io.titan:titan-runtime-jdbc for the MAIN classpath (TitanExecutionListener +
    // JdbcTelemetrySink, the SQL-mode telemetry wiring) is declared in the afterEvaluate
    // block below — see the TG-BLK-009 workaround note there.

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    // Core's live-database test harness (TitanTestExtension, EquivalenceOracle) for the
    // SQL-mode equivalence leg (completion plan W2). Composite-substituted to vendor/titan; the
    // harness manages its Testcontainers PostgreSQL internally (and carries Testcontainers
    // plus the JDBC drivers on its runtime classpath), so no direct Testcontainers
    // dependency is needed here.
    testImplementation("io.titan:titan-runtime-jdbc:0.1.0")
    // The SQL-mode HTTP serving IT (W5.1) provisions its own PostgreSQL container and points
    // the Quarkus datasource at it before boot, so it needs compile-time Testcontainers
    // access (same version core's harness uses).
    testImplementation("org.testcontainers:postgresql:1.21.4")
    // The dogfood Phase C management-store IT additionally drives the jdbc mode against live
    // MySQL 8.4 (the second dialect core's JdbcManagementStoreDogfoodIT proves); the MySQL
    // testcontainer is on the harness runtime classpath transitively but needs an explicit
    // compile-time declaration (the connector itself, com.mysql:mysql-connector-j, is already
    // a titanJdbc + transitive harness dependency).
    testImplementation("org.testcontainers:mysql:1.21.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Driver for core's scratch-container DDL introspection (ddlMode defaults to "container").
    titanJdbc("org.postgresql:postgresql:42.7.4")
    // Driver for the MySQL scratch-container legs (W5.2: titanVerifyInstall + equivalence).
    titanJdbc("com.mysql:mysql-connector-j:8.4.0")
}

// TG-BLK-009 workaround: core's published JDBC runtime surface (TitanExecutionListener +
// JdbcTelemetrySink) is needed on the MAIN classpath for the W5.1 live SQL execution mode,
// but the Quarkus plugin's configuration-time dependency walk recurses into the
// composite-substituted titan-runtime-jdbc project. Declaring the dependency in afterEvaluate
// (which runs after the
// plugin's walk) keeps it out of that walk while remaining a fully ordinary dependency for
// compilation, the Quarkus application model, dev mode, and tests. The excludes keep
// titan-runtime-jdbc's implementation-scope test-harness dependencies (JUnit, Testcontainers,
// MySQL driver — also TG-BLK-009) off the application classpath.
afterEvaluate {
    dependencies {
        "implementation"("io.titan:titan-runtime-jdbc:0.1.0") {
            exclude(group = "org.junit.jupiter")
            exclude(group = "org.testcontainers")
            exclude(group = "com.mysql")
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Docker split (same convention as core's root build): plain `test` excludes @Tag("docker")
// so `build`/`test` stay green without Docker. The generic `integrationTest` owns only the
// generic compiled package; whole-request engine legs have isolated package directories and
// explicitly configured Test tasks below.
// `check` intentionally does NOT depend on integrationTest.
tasks.test {
    useJUnitPlatform {
        excludeTags("docker")
    }
    // Docker-free runs cannot assume titanPackage/titanVerifyInstall output exists, so plain
    // tests read GAP-005 package metadata from a checked-in, schema-faithful fixture package.
    // The real-artifact integration leg lives in integrationTest (see below).
    systemProperty(
        "titan.graphql.artifacts.dir",
        layout.projectDirectory.dir("src/test/resources/titan-artifacts").asFile.absolutePath
    )
    // Most Docker-free transport tests intentionally exercise the in-JVM reference fixture.
    // Production application.properties remains fail-closed compiled mode.
    systemProperty("titan.graphql.execution.mode", "java")
}

tasks.register<Test>("integrationTest") {
    description = "Runs generic compiled-package Docker tests; isolated whole-request legs use dedicated tasks."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // The generic compiled runtime installs this generated package. Model/generator changes must
    // invalidate the live test even when the JUnit classes themselves have not changed.
    inputs.dir(layout.buildDirectory.dir("generated/migrations/titan"))
    useJUnitPlatform {
        includeTags("docker")
        excludeTags("commerce-compiled")
        excludeTags("legacy-sql")
        excludeTags("database-engine")
        excludeTags("database-engine-mysql")
        excludeTags("database-engine-commerce")
        excludeTags("database-engine-commerce-mysql")
        excludeTags("database-engine-http")
    }
    // Generic compiled-mode tests consume only the install-verified, model-bound generated package.
    // Historical whole-request SQL equivalence tests run separately in legacySqlIntegrationTest.
    dependsOn("titanGraphqlBindPackage")
    systemProperty(
        "titan.graphql.migrations.dir",
        layout.buildDirectory.dir("generated/migrations/titan/postgresql").get().asFile.absolutePath
    )
    // The MySQL equivalence leg (W5.2) deploys the packaged MySQL migrations.
    systemProperty(
        "titan.graphql.migrations.dir.mysql",
        layout.buildDirectory.dir("generated/migrations/titan/mysql").get().asFile.absolutePath
    )
    // Point the management plane's artifacts directory at the REAL titanPackage output.
    systemProperty(
        "titan.graphql.artifacts.dir",
        layout.buildDirectory.dir("generated/migrations/titan").get().asFile.absolutePath
    )
    shouldRunAfter(tasks.test)
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
    deployment.migrationsDir.set(layout.buildDirectory.dir("generated/migrations/titan").get().asFile.absolutePath)
}

// Generated catalog sources are wired into sourceSets by the Titan plugin itself
// (core plan Phase 4.1), so no manual srcDir/dependsOn wiring is needed here.

val titanGraphqlModelFile = providers.gradleProperty("titanGraphqlModel")
    .orElse("src/test/resources/graphql/demo-blog.titan.graphql.yaml")
val titanGraphqlGeneratedRoutineSource = layout.buildDirectory.file(
    "generated/sources/titan-graphql/io/titan/graphql/generated/GeneratedTitanGraphqlReads.java"
)
val titanGraphqlPackageDirectory = layout.buildDirectory.dir("generated/migrations/titan")

// The final serving distribution must be able to contain only HTTP/JDBC transport code. Keep the
// reusable database invocation client in an independent source set now, so it cannot import the
// JVM GraphQL parser/planner/executor while the legacy application artifact is still being removed.
val databaseFrontend = sourceSets.create("databaseFrontend") {
    java.srcDir("src/database-frontend/java")
}

// This target serving host is separate from the transpilable engine and transitional Quarkus
// application. It may decode HTTP/JSON and invoke the one-call client, but cannot link the JVM
// GraphQL runtime.
val databaseHttpFrontend = sourceSets.create("databaseHttpFrontend") {
    java.srcDir("src/database-http-frontend/java")
}
val databaseHttpFrontendTest = sourceSets.create("databaseHttpFrontendTest") {
    java.srcDir("src/database-http-frontend-test/java")
    resources.srcDir("src/database-http-frontend-test/resources")
}

// The transitional application still adapts its existing runtime request/context records, but
// delegates its JDBC boundary to the independently buildable frontend client.
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
    // Quarkus packages the main application JAR. Include the extracted client while the
    // transitional resource still delegates to it; the final frontend distribution will use
    // databaseFrontendJar directly and omit the legacy application classes altogether.
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
}

// This is deliberately a release *artifact* task rather than an alias for the root `assemble`
// or `build` lifecycle. Those lifecycle tasks still build the transitional Quarkus application
// while its source is being migrated. A deployer must receive this ZIP, never that application
// archive: it has the small HTTP/JDBC closure and no local GraphQL implementation.
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
                "jackson-core-2.21.3.jar",
                "jackson-databind-2.21.3.jar",
                "mysql-connector-j-8.4.0.jar",
                "postgresql-42.7.11.jar",
                "protobuf-java-3.25.1.jar"
            )
            val distributionEntries = ZipFile(distribution).use { archive ->
                archive.entries().asSequence()
                    .filter { entry -> entry.isDirectory == false }
                    .associate { entry -> entry.name to archive.getInputStream(entry).readBytes() }
            }
            check(distributionEntries.keys == expectedLibraries.map { "lib/$it" }.toSet() + expectedLaunchScript) {
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
    // Do not add Quarkus, compiled, or historical SQL tests here. They are retained migration
    // oracles, but this gate establishes the artifact a user may actually deploy.
    dependsOn(
        "titanGraphqlVerifyDatabaseEngineBoundary",
        "titanGraphqlVerifyDatabaseFrontendBoundary",
        "titanGraphqlVerifyDatabaseEnginePackagePrivacy",
        titanGraphqlDatabaseHttpFrontendReleaseArtifact,
        "databaseEngineIntegrationTest",
        "databaseEngineMySqlIntegrationTest",
        "databaseEngineCommerceIntegrationTest",
        "databaseEngineCommerceMySqlIntegrationTest",
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

tasks.register("titanGraphqlVerifyApplicationFrontendLink") {
    description = "Verifies the transitional Quarkus application packages its extracted JDBC frontend."
    group = "verification"
    dependsOn("quarkusBuild")
    doLast {
        val applicationJar = layout.buildDirectory.file(
            "quarkus-app/app/$titanGraphqlProjectName-$titanGraphqlProjectVersion.jar")
                .get().asFile
        check(applicationJar.isFile) { "Quarkus application JAR is missing: $applicationJar" }
        val entries = mutableListOf<String>()
        zipTree(applicationJar).visit {
            if (isDirectory == false) entries.add(path)
        }
        check("io/titan/graphql/frontend/DatabaseWholeRequestClient.class" in entries) {
            "Quarkus application JAR does not package the extracted database frontend client"
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
    add(databaseHttpFrontend.implementationConfigurationName, "com.fasterxml.jackson.core:jackson-databind:2.21.3")
    add(databaseHttpFrontend.runtimeOnlyConfigurationName, "org.postgresql:postgresql:42.7.11")
    add(databaseHttpFrontend.runtimeOnlyConfigurationName, "com.mysql:mysql-connector-j:8.4.0")
    add(databaseHttpFrontendTest.implementationConfigurationName, files(databaseHttpFrontend.output))
    add(databaseHttpFrontendTest.implementationConfigurationName, files(databaseFrontend.output))
    add(databaseHttpFrontendTest.implementationConfigurationName, "com.fasterxml.jackson.core:jackson-databind:2.21.3")
    add(databaseHttpFrontendTest.implementationConfigurationName, platform("org.junit:junit-bom:6.0.3"))
    add(databaseHttpFrontendTest.implementationConfigurationName, "org.junit.jupiter:junit-jupiter")
    add(databaseHttpFrontendTest.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql:2.0.5")
    add(databaseHttpFrontendTest.implementationConfigurationName, "org.testcontainers:testcontainers-mysql:2.0.5")
    add(databaseHttpFrontendTest.implementationConfigurationName, "org.postgresql:postgresql:42.7.11")
    add(databaseHttpFrontendTest.implementationConfigurationName, "com.mysql:mysql-connector-j:8.4.0")
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
    into(titanGraphqlDatabaseEnginePackageSourceDirectory)
}

tasks.register<Sync>("titanGraphqlStageMySqlDatabaseEnginePackage") {
    dependsOn("titanGraphqlTranspileMySqlDatabaseEngine")
    from(titanGraphqlMySqlDatabaseEngineSqlDirectory)
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
        "titan.graphql.migrations.dir",
        titanGraphqlDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath
    )
    systemProperty("titan.graphql.artifacts.dir", titanGraphqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("integrationTest"))
}

tasks.register<Test>("databaseEngineHttpIntegrationTest") {
    description = "Boots Quarkus against the manifest-bound PostgreSQL whole-request engine."
    group = "verification"
    dependsOn("titanGraphqlBindDatabaseEnginePackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // See databaseEngineIntegrationTest: do not claim the descriptor-producing package root as
    // an input to the transitional Quarkus proof.
    inputs.dir(titanGraphqlDatabaseEnginePackageDirectory.map { it.dir("postgresql") })
    inputs.files(
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-package.json") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-runtime-identity.sha256") },
        titanGraphqlDatabaseEnginePackageDirectory.map { it.file("titan-graphql-database-package-identity.sha256") }
    )
    useJUnitPlatform { includeTags("database-engine-http") }
    systemProperty(
        "titan.graphql.database-engine.migrations.dir",
        titanGraphqlDatabaseEnginePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath
    )
    systemProperty("titan.graphql.artifacts.dir", titanGraphqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("databaseEngineIntegrationTest"))
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
    useJUnitPlatform()
    // The root project is Quarkus-based and exports this property to its normal test workers.
    // The standalone host intentionally has no JBoss LogManager dependency.
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
    shouldRunAfter(tasks.named("databaseEngineHttpIntegrationTest"))
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
        "titan.graphql.migrations.dir.mysql",
        titanGraphqlMySqlDatabaseEnginePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath
    )
    systemProperty("titan.graphql.artifacts.dir", titanGraphqlMySqlDatabaseEnginePackageDirectory.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("databaseEngineIntegrationTest"))
}

tasks.register<JavaExec>("titanGraphqlGenerateRoutines") {
    description = "Generates Titan-transpilable read routines from the reviewed GraphQL model."
    group = "titan"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.codegen.TitanGraphqlRoutineSourceGeneratorCli")
    args(titanGraphqlModelFile.get(), titanGraphqlGeneratedRoutineSource.get().asFile.absolutePath)
    inputs.file(layout.projectDirectory.file(titanGraphqlModelFile.get()))
    outputs.file(titanGraphqlGeneratedRoutineSource)
}

tasks.named<TitanTranspileTask>("titanTranspile") {
    dependsOn("titanGraphqlGenerateRoutines")
    // Production packages contain only schema-generated carriers. The historical whole-request
    // demo kernel is compiled into a separate, explicitly legacy proof package below.
    sourceFiles.setFrom(titanGraphqlGeneratedRoutineSource)
}

tasks.register<JavaExec>("titanGraphqlBindPackage") {
    description = "Binds an install-verified Titan package to the exact reviewed GraphQL model."
    group = "titan"
    dependsOn("classes", "titanVerifyInstall")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
    args(titanGraphqlModelFile.get(), titanGraphqlPackageDirectory.get().asFile.absolutePath)
    inputs.file(layout.projectDirectory.file(titanGraphqlModelFile.get()))
    inputs.files(
        titanGraphqlPackageDirectory.map { it.file("titan-artifact.json") },
        titanGraphqlPackageDirectory.map { it.file("titan-object-inventory.json") },
        titanGraphqlPackageDirectory.map { it.file("titan-install-plan.json") },
        titanGraphqlPackageDirectory.map { it.file("titan-install-verification.json") }
    )
    outputs.file(titanGraphqlPackageDirectory.map { it.file("titan-graphql-package.json") })
}

// Transitional equivalence-only package. It is deliberately isolated from the production
// generated-carrier package and from compiledSchemaIntegrationTest.
val legacySqlDirectory = layout.buildDirectory.dir("generated/proofs/legacy-sql/sql")
val legacyPackageDirectory = layout.buildDirectory.dir("generated/proofs/legacy-sql/package")

tasks.register<TitanTranspileTask>("titanGraphqlTranspileLegacySql") {
    description = "Transpiles the historical demo whole-request kernel for equivalence tests only."
    group = "verification"
    dependsOn("classes", "titanGraphqlGenerateRoutines")
    sourceFiles.setFrom(
        layout.projectDirectory.file(
            "src/main/java/io/titan/graphql/demo/blog/DemoBlogTitanGraphqlFunctions.java"
        ),
        titanGraphqlGeneratedRoutineSource
    )
    classpathFiles.from(sourceSets["main"].runtimeClasspath)
    targets.set(listOf("postgresql", "mysql"))
    schemas.set(listOf("public"))
    strictWraparound.set(false)
    sqlSafety.set("strict")
    observability.set(true)
    debugMode.set(false)
    sensitiveColumns.set(listOf("email"))
    outputDir.set(legacySqlDirectory)
}

tasks.register<TitanPackageTask>("titanGraphqlPackageLegacySql") {
    description = "Packages the isolated historical SQL equivalence kernel."
    group = "verification"
    dependsOn("titanGraphqlTranspileLegacySql")
    sqlInputDir.set(legacySqlDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    outputDir.set(legacyPackageDirectory)
}

tasks.register<TitanVerifyInstallTask>("titanGraphqlVerifyLegacySqlInstall") {
    description = "Install-verifies the isolated historical SQL equivalence package."
    group = "verification"
    dependsOn("titanGraphqlPackageLegacySql")
    sqlInputDir.set(legacySqlDirectory)
    artifactDir.set(legacyPackageDirectory)
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

tasks.register<JavaExec>("titanGraphqlBindLegacySqlPackage") {
    description = "Binds the isolated historical SQL equivalence package to the demo model."
    group = "verification"
    dependsOn("classes", "titanGraphqlVerifyLegacySqlInstall")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
    args(titanGraphqlModelFile.get(), legacyPackageDirectory.get().asFile.absolutePath)
}

tasks.register<Test>("legacySqlIntegrationTest") {
    description = "Runs the isolated historical SQL-mode equivalence proofs."
    group = "verification"
    dependsOn("titanGraphqlBindLegacySqlPackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    inputs.dir(legacyPackageDirectory)
    useJUnitPlatform { includeTags("legacy-sql") }
    systemProperty("titan.graphql.migrations.dir",
        legacyPackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath)
    systemProperty("titan.graphql.migrations.dir.mysql",
        legacyPackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath)
    systemProperty("titan.graphql.artifacts.dir", legacyPackageDirectory.get().asFile.absolutePath)
    shouldRunAfter(tasks.named("integrationTest"))
}

// Isolated second-schema proof. Its generated source, SQL, package metadata, binding, and tests
// have separate output roots, so the commerce result cannot accidentally consume the demo package.
val commerceModelFile = layout.projectDirectory.file(
    "src/test/resources/graphql/commerce.titan.graphql.yaml"
)
val commerceGeneratedRoutineSource = layout.buildDirectory.file(
    "generated/proofs/commerce/sources/io/titan/graphql/generated/GeneratedTitanGraphqlReads.java"
)
val commerceSqlDirectory = layout.buildDirectory.dir("generated/proofs/commerce/sql")
val commercePackageDirectory = layout.buildDirectory.dir("generated/proofs/commerce/package")
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

tasks.register<JavaExec>("titanGraphqlGenerateCommerceDatabaseEngineRuntimeIdentity") {
    description = "Calculates the PostgreSQL commerce whole-request runtime identity before transpilation."
    group = "titan"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentityCli")
    args(
        commerceModelFile.asFile.absolutePath, "postgresql",
        commerceDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        "common-engine", titanGraphqlDatabaseEngineCommonSource.asFile.absolutePath,
        "common-language", titanGraphqlDatabaseLanguageSource.asFile.absolutePath,
        "schema-generator", layout.projectDirectory.file(
            "src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java").asFile.absolutePath,
        "build-options", layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath,
        "dependency-locks", layout.projectDirectory.file("gradle.lockfile").asFile.absolutePath,
        "titan-version", layout.projectDirectory.file("vendor/titan/gradle.properties").asFile.absolutePath)
    inputs.files(
        commerceModelFile, titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource,
        layout.projectDirectory.file("src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java"),
        layout.projectDirectory.file("build.gradle.kts"), layout.projectDirectory.file("gradle.lockfile"),
        layout.projectDirectory.file("vendor/titan/gradle.properties"))
    outputs.file(commerceDatabaseEngineRuntimeIdentity)
}

tasks.register<JavaExec>("titanGraphqlGenerateCommerceMySqlDatabaseEngineRuntimeIdentity") {
    description = "Calculates the MySQL commerce whole-request runtime identity before transpilation."
    group = "titan"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlDatabaseRuntimeIdentityCli")
    args(
        commerceModelFile.asFile.absolutePath, "mysql",
        commerceMySqlDatabaseEngineRuntimeIdentity.get().asFile.absolutePath,
        "common-engine", titanGraphqlDatabaseEngineCommonSource.asFile.absolutePath,
        "common-language", titanGraphqlDatabaseLanguageSource.asFile.absolutePath,
        "schema-generator", layout.projectDirectory.file(
            "src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java").asFile.absolutePath,
        "build-options", layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath,
        "dependency-locks", layout.projectDirectory.file("gradle.lockfile").asFile.absolutePath,
        "titan-version", layout.projectDirectory.file("vendor/titan/gradle.properties").asFile.absolutePath)
    inputs.files(
        commerceModelFile, titanGraphqlDatabaseEngineCommonSource, titanGraphqlDatabaseLanguageSource,
        layout.projectDirectory.file("src/main/java/io/titan/graphql/codegen/TitanGraphqlDatabaseEngineSourceGenerator.java"),
        layout.projectDirectory.file("build.gradle.kts"), layout.projectDirectory.file("gradle.lockfile"),
        layout.projectDirectory.file("vendor/titan/gradle.properties"))
    outputs.file(commerceMySqlDatabaseEngineRuntimeIdentity)
}

// The whole-request engine has its own pair of commerce outputs.  Do not reuse the older
// carrier-only commerce package above: that would make this proof silently exercise the legacy
// JVM-planned architecture rather than the generated database entry point.
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
        titanGraphqlDatabaseTypeReferenceSource,
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
        titanGraphqlDatabaseTypeReferenceSource,
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
    into(commerceDatabaseEnginePackageSourceDirectory)
}

tasks.register<Sync>("titanGraphqlStageCommerceMySqlDatabaseEnginePackage") {
    dependsOn("titanGraphqlTranspileCommerceMySqlDatabaseEngine")
    from(commerceMySqlDatabaseEngineSqlDirectory)
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
        "titanGraphqlPackageDatabaseEngine",
        "titanGraphqlPackageMySqlDatabaseEngine",
        "titanGraphqlPackageCommerceDatabaseEngine",
        "titanGraphqlPackageCommerceMySqlDatabaseEngine"
    )
    val packageDirectories = listOf(
        titanGraphqlDatabaseEnginePackageDirectory,
        titanGraphqlMySqlDatabaseEnginePackageDirectory,
        commerceDatabaseEnginePackageDirectory,
        commerceMySqlDatabaseEnginePackageDirectory
    )
    packageDirectories.forEach { directory -> inputs.dir(directory) }
    doLast {
        val privateMachinePath = Regex(
            """(?i)(?:/""" +
                """home/[^/\s]+/|/users/[^/\s]+/|/private/|[a-z]:\\\\users\\\\)"""
        )
        val absoluteSourceLocation = Regex(
            """(?m)(?:-- titan:source:|NullPointerException at )(?:/|[a-z]:[\\\\/])"""
        )
        val packageFiles = packageDirectories.flatMap { directory ->
            directory.get().asFile.walkTopDown()
                .filter { file -> file.isFile && file.extension in setOf("sql", "json") }
                .toList()
        }
        check(packageFiles.isNotEmpty()) { "database engine packages did not produce SQL or metadata" }
        val leak = packageFiles.asSequence().mapNotNull { file ->
            val contents = file.readText()
            val match = privateMachinePath.find(contents) ?: absoluteSourceLocation.find(contents)
            match?.let { "${file}: ${it.value}" }
        }.firstOrNull()
        check(leak == null) { "database engine package contains a private or absolute source path: $leak" }
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

tasks.register<JavaExec>("titanGraphqlGenerateCommerceRoutines") {
    description = "Generates Titan read carriers for the unrelated commerce proof model."
    group = "titan"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.codegen.TitanGraphqlRoutineSourceGeneratorCli")
    args(commerceModelFile.asFile.absolutePath, commerceGeneratedRoutineSource.get().asFile.absolutePath)
    inputs.file(commerceModelFile)
    outputs.file(commerceGeneratedRoutineSource)
}

tasks.register<TitanTranspileTask>("titanGraphqlTranspileCommerce") {
    description = "Transpiles only the generated commerce carriers for PostgreSQL and MySQL."
    group = "titan"
    dependsOn("classes", "titanGraphqlGenerateCommerceRoutines")
    sourceFiles.setFrom(commerceGeneratedRoutineSource)
    classpathFiles.from(sourceSets["main"].runtimeClasspath)
    targets.set(listOf("postgresql", "mysql"))
    schemas.set(listOf("public"))
    strictWraparound.set(false)
    sqlSafety.set("strict")
    observability.set(true)
    debugMode.set(false)
    sensitiveColumns.set(listOf("email"))
    outputDir.set(commerceSqlDirectory)
}

tasks.register<TitanPackageTask>("titanGraphqlPackageCommerce") {
    description = "Packages the isolated commerce carrier SQL."
    group = "titan"
    dependsOn("titanGraphqlTranspileCommerce")
    sqlInputDir.set(commerceSqlDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { titanGraphqlProjectVersion })
    outputDir.set(commercePackageDirectory)
}

tasks.register<TitanVerifyInstallTask>("titanGraphqlVerifyCommerceInstall") {
    description = "Installs and verifies the isolated commerce package on both dialects."
    group = "verification"
    dependsOn("titanGraphqlPackageCommerce")
    sqlInputDir.set(commerceSqlDirectory)
    artifactDir.set(commercePackageDirectory)
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

tasks.register<JavaExec>("titanGraphqlBindCommercePackage") {
    description = "Binds the verified commerce package to its exact reviewed model."
    group = "titan"
    dependsOn("classes", "titanGraphqlVerifyCommerceInstall")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.titan.graphql.artifact.TitanGraphqlPackageBindingCli")
    args(commerceModelFile.asFile.absolutePath, commercePackageDirectory.get().asFile.absolutePath)
    inputs.file(commerceModelFile)
    inputs.files(
        commercePackageDirectory.map { it.file("titan-artifact.json") },
        commercePackageDirectory.map { it.file("titan-object-inventory.json") },
        commercePackageDirectory.map { it.file("titan-install-plan.json") },
        commercePackageDirectory.map { it.file("titan-install-verification.json") }
    )
    outputs.file(commercePackageDirectory.map { it.file("titan-graphql-package.json") })
}

tasks.register<Test>("commerceIntegrationTest") {
    description = "Runs the isolated compiled commerce proof on PostgreSQL and MySQL."
    group = "verification"
    dependsOn("titanGraphqlBindCommercePackage")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    inputs.dir(commercePackageDirectory)
    useJUnitPlatform { includeTags("commerce-compiled") }
    systemProperty(
        "titan.graphql.migrations.dir.commerce",
        commercePackageDirectory.map { it.dir("postgresql") }.get().asFile.absolutePath
    )
    systemProperty(
        "titan.graphql.migrations.dir.commerce.mysql",
        commercePackageDirectory.map { it.dir("mysql") }.get().asFile.absolutePath
    )
    systemProperty(
        "titan.graphql.artifacts.dir",
        commercePackageDirectory.get().asFile.absolutePath
    )
    shouldRunAfter(tasks.named("integrationTest"))
}

tasks.register("compiledSchemaIntegrationTest") {
    description = "Runs both independently packaged compiled-schema proofs."
    group = "verification"
    dependsOn("integrationTest", "commerceIntegrationTest")
}
