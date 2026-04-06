import com.android.build.api.dsl.CommonExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

fun configureCompose() {
    extensions.findByName("android")?.let { extension ->
        @Suppress("UNCHECKED_CAST")
        (extension as CommonExtension<*, *, *, *, *, *>).buildFeatures {
            compose = true
        }
    }
}

plugins.withId("com.android.application") { configureCompose() }
plugins.withId("com.android.library") { configureCompose() }
