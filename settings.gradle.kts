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

rootProject.name = "Virga"

include(":app")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:rclone")
include(":core:storage")
include(":core:data")
include(":sync-worker")
include(":feature:sync")
include(":feature:remotes")
include(":feature:settings")
include(":feature:explorer")
