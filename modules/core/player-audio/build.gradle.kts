plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.core.playeraudio"
}

dependencies {
    implementation(project(":data:model"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
