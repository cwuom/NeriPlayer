plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

android {
    namespace = "moe.ouom.neriplayer.data.bilibili"
}

dependencies {
    api(project(":api:bilibili"))
    api(project(":data:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:logging"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
