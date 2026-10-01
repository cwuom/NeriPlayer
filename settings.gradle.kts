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
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "NeriPlayer"
include(":app")
fun includeOwnedLibrary(path: String) {
    include(path)
    project(path).projectDir = file("modules/${path.drop(1).replace(':', '/')}")
}

includeOwnedLibrary(":core:common")
includeOwnedLibrary(":core:download")
includeOwnedLibrary(":feature:download")
includeOwnedLibrary(":core:logging")
includeOwnedLibrary(":core:ltw-protocol")
includeOwnedLibrary(":core:lyrics")
includeOwnedLibrary(":core:network")
includeOwnedLibrary(":core:playback-queue")
includeOwnedLibrary(":core:player-policy")
includeOwnedLibrary(":core:player-runtime")
includeOwnedLibrary(":core:player-audio")
includeOwnedLibrary(":api:bilibili")
includeOwnedLibrary(":api:ltw")
includeOwnedLibrary(":api:lyrics")
includeOwnedLibrary(":api:netease")
includeOwnedLibrary(":api:search")
includeOwnedLibrary(":api:youtube")
includeOwnedLibrary(":data:bilibili")
includeOwnedLibrary(":data:comments")
includeOwnedLibrary(":data:database")
includeOwnedLibrary(":data:lyrics")
includeOwnedLibrary(":data:ltw")
includeOwnedLibrary(":data:model")
includeOwnedLibrary(":data:netease")
includeOwnedLibrary(":data:repository")
includeOwnedLibrary(":data:storage")
includeOwnedLibrary(":data:sync")
includeOwnedLibrary(":data:youtube")
includeOwnedLibrary(":feature:player")
project(":core").projectDir = file("modules/core")
project(":feature").projectDir = file("modules/feature")
project(":api").projectDir = file("modules/api")
project(":data").projectDir = file("modules/data")
include(":ksp-annotations")
include(":ksp-processor")
include(":accompanist-lyrics-core")
include(":accompanist-lyrics-ui")
includeBuild("build-logic")

project(":accompanist-lyrics-core").projectDir = file("np-submodule/accompanist-lyrics-core")
project(":accompanist-lyrics-ui").projectDir = file("np-submodule/accompanist-lyrics-ui/src")
