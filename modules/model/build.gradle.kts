plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

android {
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    namespace = "moe.ouom.neriplayer.data.model"
}

dependencies {
    compileOnly(libs.androidx.annotation)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
