import com.android.build.gradle.LibraryExtension

plugins {
    id("build-logic.android.library")
}

extensions.findByType(LibraryExtension::class)?.run {
    sourceSets {
        getByName("main") {
            java.srcDirs("src/commonMain/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/commonTest/kotlin")
        }
        getByName("androidTest") {
            java.srcDirs("src/commonAndroidTest/kotlin")
        }
    }
}
