@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.base")
}

fun CommonExtension.configureAndroidBase() {
    compileSdk = Version.compileSdkVersion
    ndkVersion = Version.getNdkVersion()

    defaultConfig.minSdk = Version.minSdk

    compileOptions.sourceCompatibility = Version.java
    compileOptions.targetCompatibility = Version.java
}

extensions.findByType(ApplicationExtension::class)?.run {
    configureAndroidBase()
    defaultConfig.targetSdk = Version.targetSdk
}

extensions.findByType(LibraryExtension::class)?.configureAndroidBase()
