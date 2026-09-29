plugins {
    id("build-logic.android.feature-library")
}

android {
    namespace = "moe.ouom.neriplayer.data.youtube"
}

dependencies {
    implementation(project(":api:youtube"))
    implementation(project(":core:common"))
    implementation(project(":data:model"))
    implementation(project(":core:network"))
    implementation(project(":core:logging"))
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
