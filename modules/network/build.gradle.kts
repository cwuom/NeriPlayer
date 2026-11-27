plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.network"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":common"))
    implementation(libs.androidx.media3.datasource)
    implementation(libs.okhttp)
    implementation(libs.doh)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
