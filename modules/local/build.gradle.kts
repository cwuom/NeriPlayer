plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
    id("build-logic.android.sync-integration-quality")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "moe.ouom.neriplayer.data.repository"
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testFixtures.enable = true
}

dependencies {
    api(project(":model"))
    api(project(":database"))
    api(project(":platform"))
    api(project(":sync"))
    implementation(project(":listentogether"))
    implementation(project(":common"))
    implementation(project(":download:logic"))
    implementation(project(":lyrics"))
    implementation(project(":network"))
    api(project(":ksp-annotations"))
    ksp(project(":ksp-processor"))
    api(libs.kotlinx.coroutines.android)
    api(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.taglib)

    testRuntimeOnly(libs.zstd.jni)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.androidx.documentfile)
}
