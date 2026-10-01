import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.jvm.tasks.Jar

plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.api.sync"
}

dependencies {
    api(project(":data:model"))
    implementation(project(":core:logging"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

val domainScope = layout.buildDirectory.file("reports/domain-dependencies/scope.json")
val generateDomainScope = tasks.register("generateSyncDomainScope") {
    inputs.file(rootProject.file("config/quality/domain-dependencies.json"))
    outputs.file(domainScope)
    doLast {
        val domains = JsonSlurper().parse(rootProject.file("config/quality/domain-dependencies.json")) as List<*>
        val selected = domains.filter { ((it as Map<*, *>)["package"] as String).startsWith("moe.ouom.neriplayer.api.sync.") }
        check(selected.isNotEmpty()) { "Sync transport has no dependency domains" }
        domainScope.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.toJson(selected))
        }
    }
}
val coverageClasses = tasks.named<Jar>("coverageClassesJar")
val verifyDomainDependencies = tasks.register<Exec>("verifyDomainDependencies") {
    group = "verification"
    description = "Check sync transports against explicit dependency domains."
    dependsOn(coverageClasses, generateDomainScope)
    inputs.file(rootProject.file("tools_pub/quality/domain_dependencies.py"))
    inputs.file(domainScope)
    inputs.files(coverageClasses)
    workingDir(rootProject.projectDir)
    doFirst {
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val bin = File(System.getProperty("java.home"), "bin")
        commandLine(
            "python3", "-B", "tools_pub/quality/domain_dependencies.py",
            "--config", domainScope.get().asFile,
            "--jdeps", File(bin, "jdeps$suffix"), "--javap", File(bin, "javap$suffix"),
            "--input", coverageClasses.get().archiveFile.get().asFile,
            "--output", layout.buildDirectory.file("reports/domain-dependencies/report.json").get().asFile
        )
    }
}
tasks.named("check") { dependsOn(verifyDomainDependencies) }
