plugins {
    id("build-logic.android.feature-library")
    id("build-logic.android.module-quality")
}
android {
    namespace = "moe.ouom.neriplayer.lanltw"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}
dependencies {
    api(project(":model"))
    implementation(project(":common"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.java.websocket)
    api(libs.nanohttpd)
    implementation(libs.androidx.webkit)
    compileOnly(libs.androidx.annotation)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
}
