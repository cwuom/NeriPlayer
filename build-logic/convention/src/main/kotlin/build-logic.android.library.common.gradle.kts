import com.android.build.api.dsl.LibraryExtension

plugins {
    id("build-logic.android.library")
}

extensions.findByType(LibraryExtension::class)?.run {
    sourceSets {
        getByName("main") {
            kotlin.directories.add("src/commonMain/kotlin")
        }
        getByName("test") {
            kotlin.directories.add("src/commonTest/kotlin")
        }
        getByName("androidTest") {
            kotlin.directories.add("src/commonAndroidTest/kotlin")
        }
    }
}
