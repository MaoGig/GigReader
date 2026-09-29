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
