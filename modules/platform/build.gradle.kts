import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.jvm.tasks.Jar

plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

android {
    namespace = "moe.ouom.neriplayer.platform"
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(project(":model"))
    api(project(":database"))
    api(project(":lyrics"))
    implementation(project(":network"))
    implementation(project(":common"))

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.dec)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.newpipe.extractor)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.gson)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
}

val platformCoverageClasses = tasks.named<Jar>("coverageClassesJar")
val platformDomainScope = layout.buildDirectory.file("reports/domain-dependencies/scope.json")
val generatePlatformDomainScope = tasks.register("generatePlatformDomainScope") {
    group = "verification"
    description = "Generate the platform dependency rules from the shared domain configuration."
    inputs.file(rootProject.file("config/quality/domain-dependencies.json"))
    outputs.file(platformDomainScope)
    doLast {
        val domains = JsonSlurper().parse(rootProject.file("config/quality/domain-dependencies.json")) as List<*>
        val selected = domains.filter { domain ->
            ((domain as Map<*, *>)["name"] as String).startsWith("platform-")
        }
        check(selected.isNotEmpty()) { "Platform dependency scope has no domains" }
        platformDomainScope.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.toJson(selected))
        }
    }
}
val verifyDomainDependencies = tasks.register<Exec>("verifyDomainDependencies") {
    group = "verification"
    description = "Check platform protocol and repository dependency boundaries."
    dependsOn(platformCoverageClasses, generatePlatformDomainScope)
    inputs.file(rootProject.file("tools_pub/quality/domain_dependencies.py"))
    inputs.file(platformDomainScope)
    inputs.files(platformCoverageClasses)
    workingDir(rootProject.projectDir)
    doFirst {
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val jdkBin = File(System.getProperty("java.home"), "bin")
        commandLine(
            "python3", "-B", "tools_pub/quality/domain_dependencies.py",
            "--config", platformDomainScope.get().asFile,
            "--jdeps", File(jdkBin, "jdeps$suffix"),
            "--javap", File(jdkBin, "javap$suffix"),
            "--input", platformCoverageClasses.get().archiveFile.get().asFile,
            "--output", layout.buildDirectory.file("reports/domain-dependencies/report.json").get().asFile
        )
    }
}
tasks.named("check") { dependsOn(verifyDomainDependencies) }
