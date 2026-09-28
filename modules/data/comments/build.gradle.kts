plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.data.comments"
}

dependencies {
    api(project(":core:model"))
    api(project(":data:bilibili"))
    api(project(":data:netease"))
    implementation(project(":core:common"))
    implementation(project(":core:logging"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
