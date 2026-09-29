plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "moe.ouom.neriplayer.api.ltw"
}

dependencies {
    api(project(":core:ltw-protocol"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
