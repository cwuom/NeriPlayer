@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration
import org.gradle.api.tasks.testing.Test
import java.util.UUID

plugins {
    id("build-logic.android.application")
    id("build-logic.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
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

        buildConfigField("String", "BUILD_UUID", "\"${buildUUID}\"")
        buildConfigField("String", "TAG", "\"[NeriPlayer]\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        renderscriptTargetApi = 31
        renderscriptSupportModeEnabled = true

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-fexceptions",
                    "-frtti"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }
    }

    buildTypes {
        val releaseSigningConfig = signingConfigs.getByName("release")

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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
}

tasks.withType<Test>().configureEach {
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


android.applicationVariants.all {
    outputs.all {
        if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl
            && !outputFileName.lowercase().contains("debug")
        ) {
            val versionName = project.android.defaultConfig.versionName ?: "dev"
            val abiName = filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI.name }
                ?.identifier
            val abiSuffix = abiName?.let { "-$it" } ?: ""
            outputFileName = "NeriPlayer-${versionName}${abiSuffix}.apk"
        }
    }
}

dependencies {
    implementation(project(":ksp-annotations"))
    ksp(project(":ksp-processor"))

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
    implementation(libs.androidx.animation)
    implementation(libs.accompanist.navigation.animation)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.dec)
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.lyricon.provider)
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

    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))

    // 模糊
    implementation(libs.haze.jetpack.compose)

    // Security - 加密存储
    implementation(libs.androidx.security.crypto)
    implementation(libs.taglib)

    // WorkManager - 后台同步
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.javascriptengine)



    implementation(libs.androidx.webkit)

    // 取主题色
    implementation(libs.androidx.palette.ktx)

    implementation(libs.superlyricapi)
}
