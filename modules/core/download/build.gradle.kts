plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.core.download"
}

dependencies {
    api(project(":data:model"))
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
