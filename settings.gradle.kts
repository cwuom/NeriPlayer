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

includeOwnedLibrary(":core:lyrics")
includeOwnedLibrary(":core:common")
includeOwnedLibrary(":core:listen-protocol")
includeOwnedLibrary(":core:logging")
includeOwnedLibrary(":core:model")
includeOwnedLibrary(":core:network")
includeOwnedLibrary(":data:bilibili")
includeOwnedLibrary(":data:lyrics")
includeOwnedLibrary(":data:listen-together")
includeOwnedLibrary(":data:netease")
includeOwnedLibrary(":data:youtube")
project(":core").projectDir = file("modules/core")
project(":data").projectDir = file("modules/data")
include(":ksp-annotations")
include(":ksp-processor")
include(":accompanist-lyrics-core")
include(":accompanist-lyrics-ui")
includeBuild("build-logic")

project(":accompanist-lyrics-core").projectDir = file("np-submodule/accompanist-lyrics-core")
project(":accompanist-lyrics-ui").projectDir = file("np-submodule/accompanist-lyrics-ui/src")
