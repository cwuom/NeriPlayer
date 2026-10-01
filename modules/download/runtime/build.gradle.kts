plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}

android {
    namespace = "moe.ouom.neriplayer.feature.download"
}

dependencies {
    testImplementation(testFixtures(project(":common")))
    api(project(":download:logic"))
    api(project(":model"))
    implementation(project(":common"))
    implementation(project(":lyrics"))
    implementation(project(":network"))
    implementation(project(":platform"))
    implementation(project(":database"))
    implementation(project(":local"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.taglib)

    testImplementation(libs.junit)
    testImplementation(project(":sync"))
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
