plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.core.playerruntime"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:logging"))
    implementation(project(":core:player-policy"))
    implementation(project(":data:model"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
