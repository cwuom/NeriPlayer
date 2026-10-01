plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.data.storage"
}

dependencies {
    api(project(":model"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
