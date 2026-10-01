import org.apache.tools.ant.DirectoryScanner

pluginManagement {
    includeBuild("vendor/titan")
}

// The source distribution requires tracked Git metadata, but never the Git history directory.
DirectoryScanner.removeDefaultExclude("**/.gitignore")
DirectoryScanner.removeDefaultExclude("**/.gitmodules")

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
