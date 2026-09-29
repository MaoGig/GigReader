pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // MuPDF (AGPL-3.0) is published by Artifex on its own repository, not on Maven Central. The
        // exclusive filter means this host is asked for com.artifex.mupdf and nothing else, and that
        // group is never looked up anywhere else.
        exclusiveContent {
            forRepository {
                maven {
                    name = "ArtifexMuPdf"
                    url = uri("https://maven.ghostscript.com")
                }
            }
            filter {
                includeGroup("com.artifex.mupdf")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "GigReader"

include(":app")
include(":benchmark")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:data")
include(":core:pdf")
include(":core:ui")
include(":feature:library")
include(":feature:reader")
include(":feature:notes")
include(":feature:settings")
