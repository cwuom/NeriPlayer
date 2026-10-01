import com.android.build.api.dsl.LibraryExtension

plugins {
    id("build-logic.android.feature-library")
    alias(libs.plugins.ksp)
}

extensions.configure<LibraryExtension> {
    namespace = "moe.ouom.neriplayer.data.database"
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    sourceSets.getByName("androidTest").assets.directories.add("schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":data:model"))
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
}
