plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

android {
    namespace = "moe.ouom.neriplayer.core.model"
}

dependencies {
    implementation(libs.kotlinx.serialization.protobuf)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
