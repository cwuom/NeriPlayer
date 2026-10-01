import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "moe.ouom.neriplayer.data.ltw"
}

dependencies {
    api(project(":model"))
    implementation(project(":platform"))
    implementation(project(":common"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}

val ltwTestExecution = providers.provider {
    tasks.named<Test>("testDebugUnitTest").get().extensions.getByType<JacocoTaskExtension>().destinationFile
        ?: error("Listen Together JVM coverage destination is missing")
}
val verifyLtwCoverageExecution = tasks.register<VerifyCoverageExecutionData>("verifyLtwCoverageExecution") {
    dependsOn("testDebugUnitTest")
    executionData.from(ltwTestExecution)
}
val ltwCoverageSources = tasks.register<Sync>("ltwCoverageSources") {
    from(layout.projectDirectory.dir("src/main/java"))
    into(layout.buildDirectory.dir("reports/crap/sources"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
val ltwCoverageClasses = tasks.named<Jar>("coverageClassesJar")
val ltwCoverageReport = tasks.register<JacocoReport>("ltwCoverageReport") {
    dependsOn(verifyLtwCoverageExecution, ltwCoverageSources, ltwCoverageClasses)
    executionData.from(ltwTestExecution)
    classDirectories.from(ltwCoverageClasses.flatMap { it.archiveFile }.map { zipTree(it) })
    sourceDirectories.from(ltwCoverageSources.map { it.destinationDir })
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/crap/coverage.xml"))
    }
}
val ltwCrapScope = layout.buildDirectory.file("reports/crap/scope.json")
val generateLtwCrapScope = tasks.register("generateLtwCrapScope") {
    outputs.file(ltwCrapScope)
    doLast {
        ltwCrapScope.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""{"source_patterns":["moe/ouom/neriplayer/data/ltw/**/*.kt","moe/ouom/neriplayer/listentogether/**/*.kt","moe/ouom/neriplayer/api/ltw/**/*.kt"]}""")
        }
    }
}
val verifyCrap = tasks.register<Exec>("verifyCrap") {
    group = "verification"
    description = "Fail if any Listen Together runtime method has CRAP greater than 9."
    dependsOn(ltwCoverageReport, generateLtwCrapScope)
    inputs.file(rootProject.file("tools_pub/quality/crap_report.py"))
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", "-B", "tools_pub/quality/crap_report.py",
        "--xml", layout.buildDirectory.file("reports/crap/coverage.xml").get().asFile,
        "--source-root", layout.buildDirectory.dir("reports/crap/sources").get().asFile,
        "--scope", ltwCrapScope.get().asFile,
        "--output", layout.buildDirectory.dir("reports/crap").get().asFile
    )
}
val ltwDomainScope = layout.buildDirectory.file("reports/domain-dependencies/scope.json")
val generateLtwDomainScope = tasks.register("generateLtwDomainScope") {
    inputs.file(rootProject.file("config/quality/domain-dependencies.json"))
    outputs.file(ltwDomainScope)
    doLast {
        val domains = JsonSlurper().parse(rootProject.file("config/quality/domain-dependencies.json")) as List<*>
        val selected = domains.filter { domain ->
            val prefix = (domain as Map<*, *>)["package"] as String
            prefix.startsWith("moe.ouom.neriplayer.data.ltw.") ||
                prefix.startsWith("moe.ouom.neriplayer.listentogether.") ||
                prefix.startsWith("moe.ouom.neriplayer.api.ltw.")
        }
        check(selected.isNotEmpty()) { "Listen Together dependency scope has no domains" }
        ltwDomainScope.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.toJson(selected))
        }
    }
}
val verifyDomainDependencies = tasks.register<Exec>("verifyDomainDependencies") {
    group = "verification"
    description = "Check compiled Listen Together rules against explicit domain dependencies."
    dependsOn(ltwCoverageClasses, generateLtwDomainScope)
    inputs.file(rootProject.file("tools_pub/quality/domain_dependencies.py"))
    inputs.file(ltwDomainScope)
    inputs.files(ltwCoverageClasses)
    workingDir(rootProject.projectDir)
    doFirst {
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val jdkBin = File(System.getProperty("java.home"), "bin")
        commandLine(
            "python3", "-B", "tools_pub/quality/domain_dependencies.py",
            "--config", ltwDomainScope.get().asFile,
            "--jdeps", File(jdkBin, "jdeps$suffix"),
            "--javap", File(jdkBin, "javap$suffix"),
            "--input", ltwCoverageClasses.get().archiveFile.get().asFile,
            "--output", layout.buildDirectory.file("reports/domain-dependencies/report.json").get().asFile
        )
    }
}
tasks.named("check") {
    dependsOn(verifyCrap, verifyDomainDependencies)
}
