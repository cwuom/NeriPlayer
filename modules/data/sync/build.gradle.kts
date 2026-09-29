plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.data.sync"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(project(":data:model"))
    implementation(project(":api:youtube"))
    implementation(project(":data:bilibili"))

    testImplementation(libs.junit)
    testImplementation(libs.gson)
}
