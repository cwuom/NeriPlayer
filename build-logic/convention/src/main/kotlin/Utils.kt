import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware

fun <T : Any> ExtensionAware.configure(name: String, block: T.() -> Unit) =
    extensions.configure(name, block)

fun Project.withAndroidApplication(block: Plugin<in Any>.() -> Unit) =
    plugins.withId("com.android.application", block)
