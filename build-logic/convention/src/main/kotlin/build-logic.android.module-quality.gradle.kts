import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("jacoco")
}

tasks.withType<Test>().configureEach {
    // Robolectric 沙箱加载的类没有代码源位置，需要显式纳入覆盖率
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
    maxHeapSize = "2g"
}

val moduleTestExecution = providers.provider {
    tasks.named<Test>("testDebugUnitTest").get()
        .extensions.getByType<JacocoTaskExtension>().destinationFile
        ?: error("JVM coverage destination is missing for $path")
}
val verifyModuleCoverageExecution = tasks.register<VerifyCoverageExecutionData>("verifyModuleCoverageExecution") {
    dependsOn("testDebugUnitTest")
    executionData.from(moduleTestExecution)
}
val moduleCoverageClasses = tasks.named<Jar>("coverageClassesJar")
val moduleCoverageReport = tasks.register<JacocoReport>("moduleCoverageReport") {
    dependsOn(verifyModuleCoverageExecution, moduleCoverageClasses)
    executionData.from(moduleTestExecution)
    classDirectories.from(moduleCoverageClasses.flatMap { it.archiveFile }.map { zipTree(it) })
    sourceDirectories.from(layout.projectDirectory.dir("src/main/java"))
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/crap/coverage.xml"))
    }
}
val moduleCrapScope = layout.buildDirectory.file("reports/crap/scope.json")
val generateModuleCrapScope = tasks.register<Exec>("generateModuleCrapScope") {
    inputs.file(rootProject.file("config/quality/crap-scope.json"))
    inputs.file(rootProject.file("tools_pub/quality/module_scope.py"))
    inputs.dir(layout.projectDirectory.dir("src/main/java"))
    outputs.file(moduleCrapScope)
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", "-B", "tools_pub/quality/module_scope.py",
        "--scope", rootProject.file("config/quality/crap-scope.json"),
        "--source-root", layout.projectDirectory.dir("src/main/java").asFile,
        "--output", moduleCrapScope.get().asFile,
    )
}
val verifyCrap = tasks.register<Exec>("verifyCrap") {
    group = "verification"
    description = "Fail when a scoped method in this module has CRAP greater than 9."
    dependsOn(moduleCoverageReport, generateModuleCrapScope)
    inputs.file(rootProject.file("tools_pub/quality/crap_report.py"))
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", "-B", "tools_pub/quality/crap_report.py",
        "--xml", layout.buildDirectory.file("reports/crap/coverage.xml").get().asFile,
        "--source-root", layout.projectDirectory.dir("src/main/java").asFile,
        "--scope", moduleCrapScope.get().asFile,
        "--output", layout.buildDirectory.dir("reports/crap").get().asFile,
    )
}
tasks.named("check") { dependsOn(verifyCrap) }
