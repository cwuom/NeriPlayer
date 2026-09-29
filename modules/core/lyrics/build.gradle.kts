plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.core.lyrics"
}

dependencies {
    implementation(project(":data:model"))
    implementation(project(":accompanist-lyrics-core"))
    testImplementation(libs.junit)
}
