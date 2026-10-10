plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.feature.player"
    defaultConfig.consumerProguardFiles("consumer-rules.pro")
}

dependencies {
    testImplementation(testFixtures(project(":common")))
    implementation(project(":common"))
    implementation(project(":native"))
    implementation(project(":lyrics"))
    implementation(project(":network"))
    implementation(project(":playback:logic"))
    implementation(project(":platform"))
    implementation(project(":database"))
    implementation(project(":listentogether"))
    implementation(project(":model"))
    implementation(project(":local"))
    implementation(project(":sync"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.coil.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.icons)
    implementation(libs.androidx.appcompat)

    // HyperOS Focus notifications and privileged UID network control.
    implementation(libs.focus.api)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hidden.api.bypass)
    compileOnly(project(":hidden-api"))

    // 解码器的 native 库由应用打包, 库模块只使用编译接口
    compileOnly(files(rootProject.file("app/libs/lib-decoder-ffmpeg-media3-1.8.0-ffmpeg-6.0-api28-common-release.aar")))
    testImplementation(files(rootProject.file("app/libs/lib-decoder-ffmpeg-media3-1.8.0-ffmpeg-6.0-api28-common-release.aar")))
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(files(rootProject.file("app/libs/lib-decoder-ffmpeg-media3-1.8.0-ffmpeg-6.0-api28-common-release.aar")))
}
