pluginManagement {
    includeBuild("vendor/titan")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Titan and Titan DSL are public submodules. Clone with --recurse-submodules (or run
// `git submodule update --init --recursive`) before invoking the local Gradle wrapper.
includeBuild("vendor/titan")
includeBuild("vendor/titan-dsl")

rootProject.name = "titan-graphql"
