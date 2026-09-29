plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.data.lyrics"
}

dependencies {
    implementation(project(":core:common"))
    api(project(":data:model"))
    api(project(":api:lyrics"))
    api(project(":api:search"))
    api(project(":api:youtube"))
    api(project(":core:lyrics"))
    implementation(project(":core:logging"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
