
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

plugins.withId("com.android.application") {
    extensions.configure<ApplicationExtension> {
        buildFeatures.compose = true
    }
}

plugins.withId("com.android.library") {
    extensions.configure<LibraryExtension> {
        buildFeatures.compose = true
    }
}