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
    api(project(":data:model"))
    api(project(":data:database"))
    api(project(":data:bilibili"))
    api(project(":data:netease"))
    api(project(":data:youtube"))
    implementation(project(":data:sync"))
    api(project(":data:sync-store"))
    api(project(":api:sync"))
    implementation(project(":data:storage"))
    implementation(project(":core:ltw-protocol"))
    implementation(project(":api:ltw"))
    implementation(project(":api:netease"))
    implementation(project(":api:youtube"))
    implementation(project(":core:common"))
    implementation(project(":core:download"))
    implementation(project(":core:logging"))
    implementation(project(":core:lyrics"))
    implementation(project(":core:network"))
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

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.androidx.documentfile)
}
