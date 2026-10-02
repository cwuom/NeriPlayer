import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "moe.ouom.neriplayer.data.sync"
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    api(project(":model"))
    implementation(project(":platform"))
    implementation(project(":common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.zstd.jni) {
        artifact { type = "aar"; extension = "aar" }
    }

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.withType<Test>().configureEach {
    systemProperty("runSyncScale", providers.systemProperty("runSyncScale").getOrElse("false"))
}

val modelProject = project(":model")
val syncTestExecution = providers.provider {
    tasks.named<Test>("testDebugUnitTest").get().extensions.getByType<JacocoTaskExtension>().destinationFile
        ?: error("Sync JVM coverage destination is missing")
}
val modelTestExecution = providers.provider {
    modelProject.tasks.named<Test>("testDebugUnitTest").get().extensions.getByType<JacocoTaskExtension>().destinationFile
        ?: error("Sync model JVM coverage destination is missing")
}
val verifySyncCoverageExecution = tasks.register<VerifyCoverageExecutionData>("verifySyncCoverageExecution") {
    dependsOn("testDebugUnitTest", ":model:testDebugUnitTest")
    executionData.from(syncTestExecution, modelTestExecution)
}
val syncCoverageSources = tasks.register<Sync>("syncCoverageSources") {
    from(layout.projectDirectory.dir("src/main/java"))
    from(modelProject.layout.projectDirectory.dir("src/main/java")) {
        include("moe/ouom/neriplayer/data/model/sync/**")
    }
    into(layout.buildDirectory.dir("reports/crap/sources"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
val syncCoverageClasses = tasks.named<Jar>("coverageClassesJar")
val modelCoverageClasses = providers.provider {
    modelProject.tasks.named<Jar>("coverageClassesJar").get().archiveFile.get()
}
val syncCoverageReport = tasks.register<JacocoReport>("syncCoverageReport") {
    dependsOn(verifySyncCoverageExecution, syncCoverageSources, syncCoverageClasses, ":model:coverageClassesJar")
    executionData.from(syncTestExecution, modelTestExecution)
    classDirectories.from(syncCoverageClasses.flatMap { it.archiveFile }.map { zipTree(it) })
    classDirectories.from(modelCoverageClasses.map { zipTree(it) })
    sourceDirectories.from(syncCoverageSources.map { it.destinationDir })
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/crap/coverage.xml"))
    }
}
val syncCrapScope = layout.buildDirectory.file("reports/crap/scope.json")
val generateSyncCrapScope = tasks.register("generateSyncCrapScope") {
    dependsOn(syncCoverageSources)
    inputs.file(rootProject.file("config/quality/crap-scope.json"))
    inputs.files(syncCoverageSources)
    outputs.file(syncCrapScope)
    doLast {
        val definition = JsonSlurper().parse(rootProject.file("config/quality/crap-scope.json")) as Map<*, *>
        val sources = syncCoverageSources.get().destinationDir
        val patterns = (definition["source_patterns"] as List<*>).map { it as String }.filter { pattern ->
            fileTree(sources).matching { include(pattern) }.files.isNotEmpty()
        }
        val methods = (definition["method_scopes"] as List<*>).filter { rule ->
            val source = (rule as Map<*, *>)["source"] as String
            sources.resolve(source).isFile
        }
        check(patterns.isNotEmpty()) { "Sync CRAP scope has no matching sources" }
        syncCrapScope.get().asFile.writeText(
            JsonOutput.toJson(mapOf("source_patterns" to patterns, "method_scopes" to methods))
        )
    }
}
val verifyCrap = tasks.register<Exec>("verifyCrap") {
    group = "verification"
    description = "Fail if any owned sync method has CRAP greater than 9."
    dependsOn(syncCoverageReport, generateSyncCrapScope)
    inputs.file(rootProject.file("tools_pub/quality/crap_report.py"))
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", "-B", "tools_pub/quality/crap_report.py",
        "--xml", layout.buildDirectory.file("reports/crap/coverage.xml").get().asFile,
        "--source-root", syncCoverageSources.get().destinationDir,
        "--scope", syncCrapScope.get().asFile,
        "--output", layout.buildDirectory.dir("reports/crap").get().asFile
    )
}
val syncDomainScope = layout.buildDirectory.file("reports/domain-dependencies/scope.json")
val generateSyncDomainScope = tasks.register("generateSyncDomainScope") {
    inputs.file(rootProject.file("config/quality/domain-dependencies.json"))
    outputs.file(syncDomainScope)
    doLast {
        val domains = JsonSlurper().parse(rootProject.file("config/quality/domain-dependencies.json")) as List<*>
        val selected = domains.filter { domain ->
            val prefix = (domain as Map<*, *>)["package"] as String
            prefix.startsWith("moe.ouom.neriplayer.data.sync.") ||
                prefix.startsWith("moe.ouom.neriplayer.api.sync.")
        }
        check(selected.isNotEmpty()) { "Sync dependency scope has no domains" }
        syncDomainScope.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.toJson(selected))
        }
    }
}
val verifyDomainDependencies = tasks.register<Exec>("verifyDomainDependencies") {
    group = "verification"
    description = "Check compiled sync rules against explicit domain dependencies."
    dependsOn(syncCoverageClasses, ":model:coverageClassesJar", generateSyncDomainScope)
    inputs.file(rootProject.file("tools_pub/quality/domain_dependencies.py"))
    inputs.file(syncDomainScope)
    inputs.files(syncCoverageClasses, modelCoverageClasses)
    workingDir(rootProject.projectDir)
    doFirst {
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val jdkBin = File(System.getProperty("java.home"), "bin")
        commandLine(
            "python3", "-B", "tools_pub/quality/domain_dependencies.py",
            "--config", syncDomainScope.get().asFile,
            "--jdeps", File(jdkBin, "jdeps$suffix"),
            "--javap", File(jdkBin, "javap$suffix"),
            "--input", syncCoverageClasses.get().archiveFile.get().asFile,
            "--input", modelCoverageClasses.get().asFile,
            "--output", layout.buildDirectory.file("reports/domain-dependencies/report.json").get().asFile
        )
    }
}
tasks.named("check") {
    dependsOn(verifyCrap, verifyDomainDependencies)
}
