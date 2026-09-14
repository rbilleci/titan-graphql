import io.titan.gradle.TitanExtension
import io.titan.gradle.TitanTranspileTask

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
}

tasks.register<Test>("integrationTest") {
    description = "Runs Docker-dependent (Testcontainers) tests tagged @Tag(\"docker\")."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("docker")
    }
    // The SQL-mode equivalence IT deploys the packaged migrations emitted by titanPackage
    // (R__titan_010_runtime.sql + R__titan_020_routines.sql).
    dependsOn("titanPackage")
    // The real-artifact pipeline IT (W3) additionally needs the install verification rewritten
    // to 'passed' by titanVerifyInstall (which itself depends on titanPackage and uses a
    // scratch container — Docker is already a given for this task).
    dependsOn("titanVerifyInstall")
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

tasks.named<TitanTranspileTask>("titanTranspile") {
    sourceFiles.setFrom(
        fileTree("src/main/java") {
            include("**/*.java")
            exclude("**/GraphqlHttpResource.java")
            // W5.1 serving layer (execution-mode plumbing + JDBC dispatch): Java/HTTP transport
            // around the kernel, never part of the transpiled bundle — without these excludes
            // the transpiler emits SQL model files for its records/enums (Execute, Mode, ...)
            // into the deployed routine set and shifts the package fingerprint.
            exclude("**/GraphqlExecutionEngine.java")
            // Phase C management-store selection/bootstrap: JDBC + Arc wiring around the store,
            // never part of the transpiled kernel (same reason as GraphqlExecutionEngine above).
            exclude("**/TitanGraphqlManagementStoreFactory.java")
            exclude("**/sqlmode/**")
            exclude("**/model/TitanGraphqlModelDocumentYaml*.java")
        },
        fileTree(layout.buildDirectory.dir("generated/sources/titan")) { include("**/*.java") }
    )
}
