plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    namespace = "moe.ouom.neriplayer.data.youtube"
}

dependencies {
    implementation(libs.androidx.media3.datasource)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:logging"))
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
