@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.BaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.base")
}

pluginManager.apply {
    if (!plugins.hasPlugin("org.jetbrains.kotlin.android")) {
        apply("org.jetbrains.kotlin.android")
    }
}

extensions.findByType(BaseExtension::class)?.run {
    compileSdkVersion(Version.compileSdkVersion)
    ndkVersion = Version.getNdkVersion()

    defaultConfig {
        minSdk = Version.minSdk
        targetSdk = Version.targetSdk
    }

    compileOptions {
        sourceCompatibility = Version.java
        targetCompatibility = Version.java
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    jvmToolchain(Version.java.toString().toInt())
}