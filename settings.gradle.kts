pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "Virga"

include(":app")
include(":rclone-build")
include(":core:common")
include(":core:ui")
include(":core:database")
include(":core:datastore")
include(":core:rclone")
include(":core:data")
include(":sync-worker")
include(":feature:sync")
include(":feature:remotes")
include(":feature:settings")
include(":feature:explorer")
include(":benchmark")
