pluginManagement {
    repositories {
        mavenLocal()
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

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Home API SDK is not on a public Maven repo. Each developer downloads it from Google Home
        // Developers and puts it in ~/.m2/repository (see README). It must never be committed.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "IC Card Home"
include(":app")
