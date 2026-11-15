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

rootProject.name = "clipdrop"

include(":protocol") // frames, messages, codec: pure Kotlin/JVM
include(":engine") // sessions, pairing, transfers: pure Kotlin/JVM
include(":app") // Android adapters and UI

// The JVM side of the CI interop test lives in /interop but builds here, so it runs the same
// :engine code the app ships.
include(":interop-harness")
project(":interop-harness").projectDir = file("../interop/kotlin")
