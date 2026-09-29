plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "moe.ouom.neriplayer.api.search"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":api:netease"))
    implementation(project(":core:network"))
    implementation(project(":core:lyrics"))
    implementation(project(":core:logging"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
