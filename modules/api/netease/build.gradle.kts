plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.api.netease"
}

dependencies {
    api(project(":data:model"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:logging"))
    implementation(libs.okhttp)
    implementation(libs.dec)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
