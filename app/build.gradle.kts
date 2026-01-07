import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "1.9.23"
    id("kotlin-parcelize")
}

android {
    namespace = "moe.ouom.neriplayer"
    compileSdk = 36

    val buildUUID = UUID.randomUUID()

    signingConfigs {
        create("release") {
            val storePath = project.findProperty("KEYSTORE_FILE") as String? ?: "neri.jks"
            val resolvedStoreFile = file(storePath)

            if (resolvedStoreFile.exists()) {
                storeFile = resolvedStoreFile
                storePassword = project.findProperty("KEYSTORE_PASSWORD") as String? ?: ""
                keyAlias = project.findProperty("KEY_ALIAS") as String? ?: "key0"
                keyPassword = project.findProperty("KEY_PASSWORD") as String? ?: ""
            } else {
                println("Release keystore not found at '${resolvedStoreFile.path}'. Using debug signing config instead.")
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
        minSdk = 27
        targetSdk = 36
        versionCode = getBuildVersionCode()
        versionName = getBuildVersionName()

        buildConfigField("String", "BUILD_UUID", "\"${buildUUID}\"")
        buildConfigField("String", "TAG", "\"[NeriPlayer]\"")
        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        renderscriptTargetApi = 31
        renderscriptSupportModeEnabled = true
    }

    buildTypes {
        val releaseSigningConfig = signingConfigs.getByName("release")
        val debugSigningConfig = signingConfigs.getByName("debug")

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseSigningConfig.storeFile?.exists() == true) {
                releaseSigningConfig
            } else {
                debugSigningConfig
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


}

fun getBuildVersionName(): String {
    return "${getShortGitRevision()}.${getCurrentDate()}"
}

private fun getCurrentDate(): String {
    val sdf = SimpleDateFormat("MMddHHmm", Locale.getDefault())
    return sdf.format(Date())
}


private fun getShortGitRevision(): String {
    val command = "git rev-parse --short HEAD"
    val processBuilder = ProcessBuilder(*command.split(" ").toTypedArray())
    val process = processBuilder.start()

    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    return if (exitCode == 0) {
        output.trim()
    } else {
        "no_commit"
    }
}

android.applicationVariants.all {
    outputs.all {
        if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl
            && !this.outputFileName.lowercase().contains("debug")
        ) {
            val versionName = project.android.defaultConfig.versionName
            this.outputFileName = "NeriPlayer-${versionName}.apk"
        }
    }
}

fun getBuildVersionCode(): Int {
    val appVerCode: Int by lazy {
        val versionCode = SimpleDateFormat("yyMMddHH", Locale.ENGLISH).format(Date())
        println("versionCode: $versionCode")
        versionCode.toInt()
    }
    return appVerCode
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.constraintlayout)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.foundation.layout)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.icons)
    implementation(libs.androidx.foundation)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.animation)
    implementation(libs.accompanist.navigation.animation)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.dec)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
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

    // WorkManager - 后台同步
    implementation(libs.androidx.work.runtime.ktx)

    // 取主题色
    implementation(libs.androidx.palette.ktx)
}