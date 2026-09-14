import io.titan.gradle.TitanExtension
import io.titan.gradle.TitanPackageTask
import io.titan.gradle.TitanTranspileTask
import io.titan.gradle.TitanVerifyInstallTask

plugins {
    java
    id("io.quarkus") version "3.36.0"
    id("io.titan.gradle")
}

group = "io.titan.graphql"
version = "0.1.0"

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
// so `build`/`test` stay green without Docker; `integrationTest` runs exactly those tests.
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
    description = "Runs Docker-dependent (Testcontainers) tests tagged @Tag(\"docker\")."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("docker")
        excludeTags("commerce-compiled")
        excludeTags("legacy-sql")
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
    titanVersion.set(providers.provider { project.version.toString() })
    outputDir.set(legacyPackageDirectory)
}

tasks.register<TitanVerifyInstallTask>("titanGraphqlVerifyLegacySqlInstall") {
    description = "Install-verifies the isolated historical SQL equivalence package."
    group = "verification"
    dependsOn("titanGraphqlPackageLegacySql")
    sqlInputDir.set(legacySqlDirectory)
    artifactDir.set(legacyPackageDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { project.version.toString() })
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
    titanVersion.set(providers.provider { project.version.toString() })
    outputDir.set(commercePackageDirectory)
}

tasks.register<TitanVerifyInstallTask>("titanGraphqlVerifyCommerceInstall") {
    description = "Installs and verifies the isolated commerce package on both dialects."
    group = "verification"
    dependsOn("titanGraphqlPackageCommerce")
    sqlInputDir.set(commerceSqlDirectory)
    artifactDir.set(commercePackageDirectory)
    mode.set("migration")
    titanVersion.set(providers.provider { project.version.toString() })
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
