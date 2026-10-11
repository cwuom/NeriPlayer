package moe.ouom.neriplayer.ui.screen.download

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadTaskStatusPresentationTest {

    @Test
    fun `completed status follows the active theme instead of a fixed green`() {
        val legacyGreen = Color(0xFF4CAF50)
        val dynamicLike = lightColorScheme(tertiary = Color(0xFF7A5900))
        listOf(lightColorScheme(), darkColorScheme(), dynamicLike).forEach { scheme ->
            val tint = downloadTaskStatusTint(DownloadStatus.COMPLETED, scheme)
            assertEquals(scheme.tertiary, tint)
            assertNotEquals(legacyGreen, tint)
        }
    }

    @Test
    fun `each status keeps its icon and semantic tint`() {
        val scheme = lightColorScheme(
            primary = Color(0xFF000001),
            error = Color(0xFF000002),
            onSurfaceVariant = Color(0xFF000003),
            tertiary = Color(0xFF000004)
        )
        val expected = mapOf(
            DownloadStatus.QUEUED to (Icons.Default.Schedule to scheme.onSurfaceVariant),
            DownloadStatus.DOWNLOADING to (Icons.Default.CloudDownload to scheme.primary),
            DownloadStatus.WAITING_NETWORK to (Icons.Default.Schedule to scheme.onSurfaceVariant),
            DownloadStatus.COMPLETED to (Icons.Default.CheckCircle to scheme.tertiary),
            DownloadStatus.FAILED to (Icons.Default.Error to scheme.error),
            DownloadStatus.CANCELLED to (Icons.Default.Cancel to scheme.onSurfaceVariant)
        )
        assertEquals(DownloadStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, presentation) ->
            assertEquals(status.name, presentation.first, downloadTaskStatusIcon(status))
            assertEquals(status.name, presentation.second, downloadTaskStatusTint(status, scheme))
        }
    }
}
