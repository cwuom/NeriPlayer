import com.android.build.api.dsl.CommonExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

fun configureCompose() {
    val androidExtension = extensions.findByName("android") as? CommonExtension ?: return
    androidExtension.buildFeatures.compose = true
}

plugins.withId("com.android.application") { configureCompose() }
plugins.withId("com.android.library") { configureCompose() }
