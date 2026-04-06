import org.gradle.api.JavaVersion
import org.gradle.api.Project
import java.io.File
import java.util.Properties

object Version {
    val java = JavaVersion.VERSION_17

    const val compileSdkVersion = 36
    const val minSdk = 28
    const val targetSdk = 36

    private const val defaultNdkVersion = "27.0.12077973"
    private const val defaultCMakeVersion = "3.28.0+"

    fun getNdkVersion(): String {
        return defaultNdkVersion
    }

    fun getCMakeVersion(): String {
        return defaultCMakeVersion
    }

    fun getLocalProperty(project: Project, propertyName: String): String? {
        val rootProject = project.rootProject
        val localProp = File(rootProject.projectDir, "local.properties")
        if (!localProp.exists()) {
            return null
        }
        val localProperties = Properties()
        localProp.inputStream().use {
            localProperties.load(it)
        }
        return localProperties.getProperty(propertyName, null)
    }

}
