import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    id("build-logic.android.library")
}

android {
    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
    }
    testOptions.unitTests.isReturnDefaultValues = true
}

tasks.withType<Test>().configureEach {
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
    listOf(
        "runNeteaseSmoke", "runYouTubePlaybackSmoke", "youtubeSmokeVideoId",
        "youtubeSmokeForceRefresh", "youtubeSmokeCookieFile"
    ).forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
}

val coverageClasses = tasks.register<CoverageClassesJar>("coverageClassesJar") {
    archiveClassifier.set("coverage")
    from(projectDirectories)
    from(projectJars.map { jars -> jars.map { zipTree(it.asFile) } })
}

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
        .use(coverageClasses)
        .toGet(ScopedArtifact.CLASSES, CoverageClassesJar::projectJars, CoverageClassesJar::projectDirectories)
}

configurations.create("coverageClassesElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(coverageClasses)
}

configurations.create("coverageExecutionElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(providers.provider {
        tasks.named<Test>("testDebugUnitTest").get()
            .extensions.getByType<JacocoTaskExtension>().destinationFile
            ?: error("Debug JVM coverage destination is missing for $path")
    }) {
        builtBy("testDebugUnitTest")
    }
}
