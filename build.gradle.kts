// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

val verifyModuleBoundaries = tasks.register<Exec>("verifyModuleBoundaries") {
    group = "verification"
    description = "Check library dependency direction and owned source budgets."
    workingDir(rootDir)
    commandLine("python3", "-B", "tools_pub/quality/module_boundaries.py")
}

tasks.register("verifyModularization") {
    group = "verification"
    description = "Run app and library tests, combined CRAP coverage, lint and module boundary checks."
    dependsOn(verifyModuleBoundaries, ":app:verifyCrap", ":app:lintDebug")
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
    subprojects.filter { it.path.startsWith(":core:") || it.path.startsWith(":data:") }
        .forEach { dependsOn("${it.path}:lintDebug") }
}
