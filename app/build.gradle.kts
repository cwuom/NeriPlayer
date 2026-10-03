@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import java.util.UUID

plugins {
    id("build-logic.android.application")
    id("build-logic.android.compose")
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

val ownedLibraryPaths = rootProject.file("gradle/owned-modules.txt")
    .readLines().filter { it.isNotBlank() }
val libraryCoverageClasses = configurations.create("libraryCoverageClasses") {
    isCanBeConsumed = false
    isTransitive = false
}
val libraryCoverageExecution = configurations.create("libraryCoverageExecution") {
    isCanBeConsumed = false
    isTransitive = false
}
val coverageSources = tasks.register<Sync>("collectCoverageSources") {
    (listOf(project.path) + ownedLibraryPaths).forEach { module ->
        listOf("java", "kotlin").forEach { language ->
            from(project(module).layout.projectDirectory.dir("src/main/$language"))
        }
    }
    into(layout.buildDirectory.dir("reports/crap/sources"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

val isGithubPullRequest = providers.environmentVariable("GITHUB_EVENT_NAME").orNull == "pull_request"
val isIdeBuild = listOf("android.injected.invoked.from.ide", "idea.active").any { propertyName ->
    (project.findProperty(propertyName) as String?)?.toBoolean() == true ||
        providers.systemProperty(propertyName).orNull?.toBoolean() == true
}
val allowUnsignedRelease =
    (project.findProperty("allowUnsignedRelease") as String?)?.toBoolean() == true ||
        isGithubPullRequest ||
        isIdeBuild
val releaseKeystorePath = project.findProperty("KEYSTORE_FILE") as String? ?: "neri.jks"
val releaseKeystoreFile = project.file(releaseKeystorePath)
val releaseStorePassword = project.findProperty("KEYSTORE_PASSWORD") as String?
val releaseKeyAlias = project.findProperty("KEY_ALIAS") as String? ?: "key0"
val releaseKeyPassword = project.findProperty("KEY_PASSWORD") as String?
val releaseSigningReady = releaseKeystoreFile.exists() &&
    !releaseStorePassword.isNullOrBlank() &&
    releaseKeyAlias.isNotBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "moe.ouom.neriplayer"
    val buildUUID = UUID.randomUUID()
    val buildAllReleaseAbis = (project.findProperty("buildAllReleaseAbis") as String?)?.toBoolean() == true
    val defaultReleaseAbiFilters = listOf("arm64-v8a")
    val allReleaseAbiFilters = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword.orEmpty()
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword.orEmpty()
            }
        }
    }

    println(" __  __                     ____    ___                                     \n" +
            "/\\ \\/\\ \\                 __/\\  _`\\ /\\_ \\                                    \n" +
            "\\ \\ `\\\\ \\     __   _ __ /\\_\\ \\ \\L\\ \\//\\ \\      __     __  __     __   _ __  \n" +
            " \\ \\ , ` \\  /'__`\\/\\`'__\\/\\ \\ \\ ,__/ \\ \\ \\   /'__`\\  /\\ \\/\\ \\  /'__`\\/\\`'__\\\n" +
            "  \\ \\ \\`\\ \\/\\  __/\\ \\ \\/ \\ \\ \\ \\ \\/   \\_\\ \\_/\\ \\L\\.\\_\\ \\ \\_\\ \\/\\  __/\\ \\ \\/ \n" +
            "   \\ \\_\\ \\_\\ \\____\\\\ \\_\\  \\ \\_\\ \\_\\   /\\____\\ \\__/.\\_\\\\/`____ \\ \\____\\\\ \\_\\ \n" +
            "    \\/_/\\/_/\\/____/ \\/_/   \\/_/\\/_/   \\/____/\\/__/\\/_/ `/___/> \\/____/ \\/_/ \n" +
            "                                                          /\\___/            \n" +
            "                                                          \\/__/             ")
    println("buildUUID: $buildUUID")

    defaultConfig {
        applicationId = "moe.ouom.neriplayer"
        val inviteScheme = "neriplayer"
        manifestPlaceholders["listenTogetherInviteScheme"] = inviteScheme
        buildConfigField("String", "LISTEN_TOGETHER_INVITE_SCHEME", "\"$inviteScheme\"")

        buildConfigField("String", "BUILD_UUID", "\"${buildUUID}\"")
        buildConfigField("String", "TAG", "\"[NeriPlayer]\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")


        testInstrumentationRunner = "moe.ouom.neriplayer.testing.NeriPlayerInstrumentationTestRunner"

        renderscriptTargetApi = 31
        renderscriptSupportModeEnabled = true

    }

    buildTypes {
        val releaseSigningConfig = signingConfigs.getByName("release")

        debug {
            applicationIdSuffix = ".debug"
            val inviteScheme = "neriplayer-debug"
            manifestPlaceholders["listenTogetherInviteScheme"] = inviteScheme
            buildConfigField("String", "LISTEN_TOGETHER_INVITE_SCHEME", "\"$inviteScheme\"")
            enableUnitTestCoverage = true
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (!buildAllReleaseAbis) {
                ndk {
                    // Regular release stays lean; manual release can opt into all ABI splits.
                    abiFilters += defaultReleaseAbiFilters
                }
            }
            if (releaseSigningReady) {
                signingConfig = releaseSigningConfig
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("androidTest") {
            assets.directories.add(project(":database").layout.projectDirectory.dir("schemas").asFile.path)
        }
    }

    packaging {
        dex {
            // minSdk 28 后 AGP 默认会把 dex 直接存储，恢复 legacy packaging 可显著降低 APK 体积
            useLegacyPackaging = true
        }
        jniLibs {
            // 压缩 APK 内的 native so，优先降低 release 下载体积
            useLegacyPackaging = true
        }
        resources {
            // Compose instrumentation 依赖 kotlinx.coroutines 的 ServiceLoader，
            // androidTest APK 需要合并同名 service 文件，避免只保留单个实现
            merges += "META-INF/services/*"
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*",
                "google/protobuf/*.proto",
                "org/mozilla/javascript/resources/Messages_*.properties",
                "DebugProbesKt.bin"
            )
        }
    }

    splits {
        abi {
            isEnable = buildAllReleaseAbis
            reset()
            include(*allReleaseAbiFilters.toTypedArray())
            isUniversalApk = false
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

}

gradle.taskGraph.whenReady {
    val releasePackagingRequested = allTasks.any { task ->
        task.project == project && (
            task.name == "assemble" ||
                task.name == "bundle" ||
                task.name.contains("Release", ignoreCase = false) &&
                listOf("assemble", "bundle", "package", "sign").any(task.name::startsWith)
            )
    }
    if (releasePackagingRequested && !allowUnsignedRelease && !releaseSigningReady) {
        val missingSigningParts = buildList {
            if (!releaseKeystoreFile.exists()) add("KEYSTORE_FILE=$releaseKeystorePath")
            if (releaseStorePassword.isNullOrBlank()) add("KEYSTORE_PASSWORD")
            if (releaseKeyAlias.isBlank()) add("KEY_ALIAS")
            if (releaseKeyPassword.isNullOrBlank()) add("KEY_PASSWORD")
        }.joinToString()

        throw GradleException(
            "Release signing material is required. Missing: $missingSigningParts. " +
                "Pass -PKEYSTORE_FILE, -PKEYSTORE_PASSWORD, -PKEY_ALIAS and -PKEY_PASSWORD " +
                "or use -PallowUnsignedRelease=true for PR validation builds."
        )
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/com/mocharealm/accompanist/lyrics/ui/utils/String.kt")
    if (name.endsWith("AndroidTestKotlin") || name.endsWith("UnitTestKotlin")) {
        // 应用集成测试继续验证下载内部恢复状态，业务编译仍遵守模块可见性
        val compileClasspathName = name.removePrefix("compile").removeSuffix("Kotlin")
            .replaceFirstChar(Char::lowercaseChar) + "CompileClasspath"
        val downloadIntegrationClasses = providers.provider {
            configurations.getByName(compileClasspathName).incoming.artifactView {
                attributes.attribute(
                    org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                    "android-classes-jar"
                )
                componentFilter { component ->
                    component is org.gradle.api.artifacts.component.ProjectComponentIdentifier &&
                        component.projectPath == ":download:runtime"
                }
            }.files
        }
        friendPaths.from(downloadIntegrationClasses)
        dependsOn(downloadIntegrationClasses)
    }
}

tasks.withType<Test>().configureEach {
    // Android 单元测试中的 Context 可能没有真实文件目录，临时文件统一放到任务临时目录
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
    systemProperty(
        "runNeteaseSmoke",
        System.getProperty("runNeteaseSmoke") ?: "false"
    )
    systemProperty(
        "runYouTubePlaybackSmoke",
        System.getProperty("runYouTubePlaybackSmoke") ?: "false"
    )
    systemProperty(
        "youtubeSmokeVideoId",
        System.getProperty("youtubeSmokeVideoId") ?: ""
    )
    systemProperty(
        "youtubeSmokeForceRefresh",
        System.getProperty("youtubeSmokeForceRefresh") ?: "false"
    )
    systemProperty(
        "youtubeSmokeCookieFile",
        System.getProperty("youtubeSmokeCookieFile") ?: ""
    )
}

abstract class ProjectCoverageReport : JacocoReport() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectDirectories: ListProperty<Directory>
}

abstract class DomainDependencyCheck : Exec() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectDirectories: ListProperty<Directory>
}

val verifyDomainDependencies = tasks.register<DomainDependencyCheck>("verifyDomainDependencies") {
    group = "verification"
    description = "Check compiled app and library rules against explicit domain dependencies."
    dependsOn(libraryCoverageClasses)
    inputs.files(libraryCoverageClasses)
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.file("tools_pub/quality/domain_dependencies.py"),
        rootProject.file("config/quality/domain-dependencies.json"))
    doFirst {
        val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val jdkBin = File(System.getProperty("java.home"), "bin")
        val artifacts = projectJars.get().map { it.asFile } + projectDirectories.get().map { it.asFile } +
            libraryCoverageClasses.files
        commandLine(listOf(
            "python3", "-B", "tools_pub/quality/domain_dependencies.py",
            "--config", rootProject.file("config/quality/domain-dependencies.json").absolutePath,
            "--jdeps", File(jdkBin, "jdeps$executableSuffix").absolutePath,
            "--javap", File(jdkBin, "javap$executableSuffix").absolutePath,
            "--output", layout.buildDirectory.file("reports/domain-dependencies/report.json").get().asFile.absolutePath
        ) + artifacts.flatMap { listOf("--input", it.absolutePath) })
    }
}

val crapExecutionData = providers.provider {
    tasks.named<Test>("testDebugUnitTest").get()
        .extensions.getByType<JacocoTaskExtension>().destinationFile
        ?: throw GradleException("Debug JVM coverage destination is missing")
}

val verifyCrapExecutionData = tasks.register<VerifyCoverageExecutionData>("verifyCrapExecutionData") {
    dependsOn("testDebugUnitTest", libraryCoverageExecution)
    executionData.from(crapExecutionData, libraryCoverageExecution)
}

val crapCoverageReport = tasks.register<ProjectCoverageReport>("crapCoverageReport") {
    group = "verification"
    description = "Collect coverage for app and owned library Kotlin and Java classes."
    dependsOn(verifyCrapExecutionData, coverageSources, libraryCoverageExecution)
    executionData.setFrom(crapExecutionData)
    executionData.from(libraryCoverageExecution)
    classDirectories.from(projectJars, projectDirectories)
    classDirectories.from(libraryCoverageClasses)
    sourceDirectories.from(coverageSources)
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/crap/coverage.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/crap/coverage"))
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
        .use(verifyDomainDependencies)
        .toGet(
            ScopedArtifact.CLASSES,
            DomainDependencyCheck::projectJars,
            DomainDependencyCheck::projectDirectories
        )
    variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
        .use(crapCoverageReport)
        .toGet(
            ScopedArtifact.CLASSES,
            ProjectCoverageReport::projectJars,
            ProjectCoverageReport::projectDirectories
        )
}

val crapToolTests = tasks.register<Exec>("crapToolTests") {
    group = "verification"
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", "-m", "unittest", "discover", "-s", "tools_pub/quality", "-p", "test_*.py")
}

verifyDomainDependencies.configure { dependsOn(crapToolTests) }

val crapReport = tasks.register<Exec>("crapReport") {
    group = "verification"
    description = "List all owned app/library method scores and every CRAP score greater than 8."
    dependsOn(crapCoverageReport, crapToolTests)
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", "-B", "tools_pub/quality/crap_report.py",
        "--xml", layout.buildDirectory.file("reports/crap/coverage.xml").get().asFile,
        "--source-root", coverageSources.get().destinationDir,
        "--scope", rootProject.file("config/quality/crap-scope.json"),
        "--output", layout.buildDirectory.dir("reports/crap").get().asFile,
        "--report-only"
    )
}

val verifyCrap = tasks.register<Exec>("verifyCrap") {
    group = "verification"
    description = "Fail if any method in the refactored source scope has CRAP greater than 9."
    dependsOn(crapReport)
    workingDir(rootProject.projectDir)
    commandLine(crapReport.get().commandLine.dropLast(1))
}

tasks.named("check") {
    dependsOn(verifyCrap, verifyDomainDependencies)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.buildType == "debug") return@onVariants

        variant.outputs.forEach { output ->
            val abiSuffix = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?.let { "-$it" }
                ?: ""
            output.outputFileName.set(
                output.versionName.orElse("dev").map { versionName ->
                    "NeriPlayer-${versionName}${abiSuffix}.apk"
                }
            )
        }
    }
}

dependencies {
    testImplementation(testFixtures(project(":common")))
    ownedLibraryPaths.forEach { module ->
        implementation(project(module))
        // 纯 native 库由 CTest 验证，不提供 JVM 覆盖率产物
        if (module != ":native") {
            add(libraryCoverageClasses.name, project(mapOf("path" to module, "configuration" to "coverageClassesElements")))
            add(libraryCoverageExecution.name, project(mapOf("path" to module, "configuration" to "coverageExecutionElements")))
        }
    }
    implementation(project(":ksp-annotations"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.foundation.layout)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.icons)
    implementation(libs.androidx.foundation)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(testFixtures(project(":local")))
    implementation(libs.androidx.animation)
    implementation(libs.accompanist.navigation.animation)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    implementation(libs.okhttp)
    implementation(libs.zxing.core)

    implementation(project(":accompanist-lyrics-core"))
    implementation(project(":accompanist-lyrics-ui"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)

    // 拖拽排序
    implementation(libs.reorderable)
    implementation(libs.gson)

    implementation(libs.androidx.media)

    implementation(libs.androidx.ui.graphics)

    implementation(libs.material.kolor)

    implementation(files("libs/lib-decoder-ffmpeg-media3-1.8.0-ffmpeg-6.0-api28-common-release.aar"))

    // Security - 加密存储
    implementation(libs.androidx.security.crypto)
    implementation(libs.taglib)

    // WorkManager - 后台同步
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.webkit)

    // 取主题色
    implementation(libs.androidx.palette.ktx)

}
