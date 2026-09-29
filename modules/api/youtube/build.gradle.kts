plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
    namespace = "moe.ouom.neriplayer.api.youtube"
}

dependencies {
    api(project(":data:model"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:logging"))
    implementation(libs.androidx.media3.datasource)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.javascriptengine)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
