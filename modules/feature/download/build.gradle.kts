plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.feature.download"
}

dependencies {
    testImplementation(testFixtures(project(":core:common")))
    api(project(":core:download"))
    api(project(":data:model"))
    implementation(project(":core:common"))
    implementation(project(":core:logging"))
    implementation(project(":core:lyrics"))
    implementation(project(":core:network"))
    implementation(project(":api:bilibili"))
    implementation(project(":api:netease"))
    implementation(project(":api:youtube"))
    implementation(project(":data:bilibili"))
    implementation(project(":data:database"))
    implementation(project(":data:lyrics"))
    implementation(project(":data:repository"))
    implementation(project(":data:youtube"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.taglib)

    testImplementation(libs.junit)
    testImplementation(project(":data:sync"))
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
