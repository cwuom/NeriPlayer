plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.data.sync.store"
}

dependencies {
    api(project(":data:model"))
    implementation(project(":data:sync"))
    implementation(project(":api:sync"))
    implementation(project(":core:logging"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
