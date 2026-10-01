plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.data.netease"
}

dependencies {
    api(project(":data:model"))
    api(project(":data:database"))
    api(project(":api:netease"))
    implementation(project(":core:common"))
    implementation(project(":core:logging"))
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
