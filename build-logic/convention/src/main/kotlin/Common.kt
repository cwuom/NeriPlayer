import org.gradle.api.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Common {
    fun getBuildVersionCode(buildTimestamp: String? = null): Int {
        val buildDate = buildTimestamp?.let { value ->
            val timestamp = value.toLongOrNull()?.takeIf { it > 0L }
                ?: throw IllegalArgumentException("buildTimestamp must be positive epoch milliseconds")
            Date(timestamp)
        } ?: Date()
        val appVerCode: Int by lazy {
            val versionCode = SimpleDateFormat("yyMMddHH", Locale.ENGLISH).format(buildDate)
            versionCode.toInt()
        }
        return appVerCode
    }

    private fun getCurrentDate(project: Project): String {
        val override = project.findProperty("buildVersionTimestamp") as String?
        if (!override.isNullOrBlank()) {
            return override
        }

        val sdf = SimpleDateFormat("MMddHHmm", Locale.ENGLISH)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Taipei")
        return sdf.format(Date())
    }


    private fun getShortGitRevision(): String {
        val command = "git rev-parse --short HEAD"
        val processBuilder = ProcessBuilder(*command.split(" ").toTypedArray())
        val process = processBuilder.start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        return if (exitCode == 0) {
            output.trim()
        } else {
            "no_commit"
        }
    }

    fun getBuildVersionName(project: Project): String {
        return "${getShortGitRevision()}.${getCurrentDate(project)}"
    }
}

