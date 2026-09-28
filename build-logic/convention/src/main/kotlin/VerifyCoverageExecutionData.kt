import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyCoverageExecutionData : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val executionData: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        requireCoverageExecutionData(executionData.files)
    }
}

internal fun requireCoverageExecutionData(files: Collection<File>) {
    if (files.isEmpty()) {
        throw GradleException("No coverage execution data registered")
    }
    val invalid = files.filter { !it.isFile || it.length() == 0L }.sortedBy(File::getPath)
    if (invalid.isNotEmpty()) {
        throw GradleException("Missing or empty coverage execution data:\n" + invalid.joinToString("\n"))
    }
}
