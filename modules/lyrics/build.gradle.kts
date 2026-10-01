plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.core.lyrics"
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
}

dependencies {
    api(project(":model"))
    implementation(project(":common"))
    implementation(project(":accompanist-lyrics-core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lyricon.provider)
    implementation(libs.superlyricapi)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
