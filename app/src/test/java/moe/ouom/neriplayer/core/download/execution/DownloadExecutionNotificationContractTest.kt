package moe.ouom.neriplayer.core.download.execution

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadExecutionNotificationContractTest {
    @Test
    fun `notification content is user facing in every locale`() {
        val contentPattern = Regex(
            """<string name="download_execution_notification_content">([^<]*)</string>"""
        )
        notificationResourcePaths().forEach { path ->
            val content = contentPattern.find(source(path))?.groupValues?.get(1)
                ?: error("notification content is missing from $path")
            require(content.isNotBlank()) {
                "notification content is missing from $path"
            }
            assertFalse(content.contains("%"))
            assertFalse(content.contains("operation", ignoreCase = true))
            assertFalse(content.contains("download-pump", ignoreCase = true))
        }
    }

    @Test
    fun `both execution hosts use a quiet indeterminate progress notification`() {
        val notificationSource = source(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadExecutionNotification.kt"
        )
        assertTrue(notificationSource.contains("CATEGORY_PROGRESS"))
        assertTrue(notificationSource.contains("setOnlyAlertOnce(true)"))
        assertTrue(notificationSource.contains("setProgress(0, 0, true)"))

        val workerSource = source(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "ForegroundDownloadWorker.kt"
        )
        val uidtSource = source(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "UidtDownloadJobService.kt"
        )
        assertTrue(workerSource.contains("buildDownloadExecutionNotification(context)"))
        assertTrue(uidtSource.contains("buildDownloadExecutionNotification(this)"))
        assertFalse(workerSource.contains("download_execution_notification_content,"))
        assertFalse(uidtSource.contains("download_execution_notification_content,"))
    }

    private fun notificationResourcePaths(): List<String> = listOf(
        "app/src/main/res/values/strings_downloads.xml",
        "app/src/main/res/values-zh/strings_downloads.xml",
        "app/src/main/res/values-en/strings_downloads.xml"
    )

    private fun source(path: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
