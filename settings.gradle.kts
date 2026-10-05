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

val ownedLibraryPaths = file("gradle/owned-modules.txt").readLines().filter { it.isNotBlank() }
ownedLibraryPaths.forEach(::includeOwnedLibrary)
ownedLibraryPaths.filter { it.count { character -> character == ':' } > 1 }
    .map { it.substringBeforeLast(':') }.distinct().forEach { parent ->
        project(parent).projectDir = file("modules/${parent.drop(1).replace(':', '/')}")
    }
include(":ksp-annotations")
include(":ksp-processor")
include(":accompanist-lyrics-core")
include(":accompanist-lyrics-ui")
include(":hidden-api")
includeBuild("build-logic")

project(":accompanist-lyrics-core").projectDir = file("np-submodule/accompanist-lyrics-core")
project(":accompanist-lyrics-ui").projectDir = file("np-submodule/accompanist-lyrics-ui/src")
