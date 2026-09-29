plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.core.download"
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
