plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}
android { namespace = "moe.ouom.neriplayer.lanltw"; defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
dependencies {
    api(project(":model"))
    implementation(project(":common"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.java.websocket)
    api(libs.nanohttpd)
    implementation(libs.androidx.webkit)
    implementation(libs.zipline)
    compileOnly(libs.androidx.annotation)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
}

// Host JVM tests load the desktop native runtime instead of Android .so files.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("app.cash.zipline:zipline")).using(module("app.cash.zipline:zipline-jvm:1.28.0"))
        substitute(module("app.cash.zipline:zipline-android")).using(module("app.cash.zipline:zipline-jvm:1.28.0"))
    }
}
