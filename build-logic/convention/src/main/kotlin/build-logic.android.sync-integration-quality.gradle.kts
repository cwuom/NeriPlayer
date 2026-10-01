import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("jacoco")
}

val repositoryTests = providers.provider { tasks.named<Test>("testDebugUnitTest").get() }
val syncIntegrationTest = tasks.register<Test>("testSyncIntegration") {
    group = "verification"
    description = "Run Android sync adapter JVM tests independently of other repositories."
    dependsOn(repositoryTests.map { it.taskDependencies.getDependencies(it) })
    testClassesDirs = files(repositoryTests.map { it.testClassesDirs })
    classpath = files(repositoryTests.map { it.classpath })
    javaLauncher.set(repositoryTests.flatMap { it.javaLauncher })
    filter.includeTestsMatching("moe.ouom.neriplayer.data.sync.*")
}
val syncIntegrationExecution = syncIntegrationTest.map {
    it.extensions.getByType<JacocoTaskExtension>().destinationFile
        ?: error("Sync integration coverage destination is missing")
}
val verifySyncIntegrationExecution = tasks.register<VerifyCoverageExecutionData>("verifySyncIntegrationExecution") {
    dependsOn(syncIntegrationTest)
    executionData.from(syncIntegrationExecution)
}
val syncIntegrationClasses = tasks.named<Jar>("coverageClassesJar")
val syncIntegrationCoverage = tasks.register<JacocoReport>("syncIntegrationCoverageReport") {
    dependsOn(verifySyncIntegrationExecution, syncIntegrationClasses)
    executionData.from(syncIntegrationExecution)
    classDirectories.from(syncIntegrationClasses.flatMap { it.archiveFile }.map { zipTree(it) })
    sourceDirectories.from(layout.projectDirectory.dir("src/main/java"))
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/sync-crap/coverage.xml"))
    }
}
val syncIntegrationScope = layout.buildDirectory.file("reports/sync-crap/scope.json")
val generateSyncIntegrationScope = tasks.register("generateSyncIntegrationScope") {
    inputs.file(rootProject.file("config/quality/crap-scope.json"))
    outputs.file(syncIntegrationScope)
    doLast {
        val definition = JsonSlurper().parse(rootProject.file("config/quality/crap-scope.json")) as Map<*, *>
        val prefix = "moe/ouom/neriplayer/data/sync/"
        val patterns = (definition["source_patterns"] as List<*>).map { it as String }.filter { it.startsWith(prefix) }
        check("$prefix**/*.kt" in patterns) { "Sync integration must retain whole-directory complexity coverage" }
        val methods = (definition["method_scopes"] as List<*>).filter {
            ((it as Map<*, *>)["source"] as String).startsWith(prefix)
        }
        syncIntegrationScope.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.toJson(mapOf("source_patterns" to patterns, "method_scopes" to methods)))
        }
    }
}
val verifySyncIntegrationCrap = tasks.register<Exec>("verifySyncIntegrationCrap") {
    group = "verification"
    description = "Fail when any Android sync adapter method has CRAP greater than 9."
    dependsOn(syncIntegrationCoverage, generateSyncIntegrationScope)
    inputs.file(rootProject.file("tools_pub/quality/crap_report.py"))
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", "-B", "tools_pub/quality/crap_report.py",
        "--xml", layout.buildDirectory.file("reports/sync-crap/coverage.xml").get().asFile,
        "--source-root", layout.projectDirectory.dir("src/main/java").asFile,
        "--scope", syncIntegrationScope.get().asFile,
        "--output", layout.buildDirectory.dir("reports/sync-crap").get().asFile
    )
}
tasks.named("check") { dependsOn(verifySyncIntegrationCrap) }
