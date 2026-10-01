plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.playback.logic"
}

dependencies {
    api(project(":model"))
    api(libs.kotlinx.coroutines.android)
    implementation(project(":common"))
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
