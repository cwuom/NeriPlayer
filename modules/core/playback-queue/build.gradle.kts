plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.core.playbackqueue"
}

dependencies {
    api(project(":data:model"))
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
