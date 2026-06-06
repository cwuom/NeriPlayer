@file:Suppress("UnstableApiUsage")

import com.android.build.gradle.BaseExtension

plugins {
    id("com.android.base")
    kotlin("android")
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

kotlin {
    jvmToolchain(Version.java.toString().toInt())
}
