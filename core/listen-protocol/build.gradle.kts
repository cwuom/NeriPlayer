plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "moe.ouom.neriplayer.core.listenprotocol"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
