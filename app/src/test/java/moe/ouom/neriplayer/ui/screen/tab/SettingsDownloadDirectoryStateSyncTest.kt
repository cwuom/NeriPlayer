package moe.ouom.neriplayer.ui.screen.tab

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DownloadDirectoryPermissionOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DownloadDirectorySummaryGateway
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DownloadDirectorySummaryOwner
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDownloadDirectoryStateSyncTest {
    @Test
    fun `default root publishes default summary without probing a provider`() = runTest {
        val summary = mutableStateOf("Old")
        val owner = DownloadDirectorySummaryOwner(
            gateway = object : DownloadDirectorySummaryGateway {
                override suspend fun describe(uri: String): Result<String>? =
                    error("unexpected probe")
            },
            directoryUri = null,
            defaultSummary = "Default",
            summaryState = summary
        )

        owner.refresh()

        assertEquals("Default", summary.value)
    }

    @Test
    fun `readable custom root replaces the visible summary`() = runTest {
        val summary = mutableStateOf("Old")
        val owner = DownloadDirectorySummaryOwner(
            gateway = FakeGateway(Result.success("Music")),
            directoryUri = "content://music",
            defaultSummary = "Default",
            summaryState = summary
        )

        owner.refresh()

        assertEquals("Music", summary.value)
    }

    @Test
    fun `timeout or provider failure retains the last readable summary`() = runTest {
        listOf(Result.failure<String>(IllegalStateException("provider")), null).forEach { result ->
            val summary = mutableStateOf("Previously read")
            val owner = DownloadDirectorySummaryOwner(
                gateway = FakeGateway(result),
                directoryUri = "content://music",
                defaultSummary = "Default",
                summaryState = summary
            )

            owner.refresh()

            assertEquals("Previously read", summary.value)
        }
    }

    @Test
    fun `permission owner publishes the resolved state for the selected root`() = runTest {
        val lost = mutableStateOf(false)
        val owner = DownloadDirectoryPermissionOwner("content://music", lost) { uri ->
            assertEquals("content://music", uri)
            true
        }

        owner.refresh()

        assertEquals(true, lost.value)
    }

    private class FakeGateway(private val result: Result<String>?) :
        DownloadDirectorySummaryGateway {
        override suspend fun describe(uri: String): Result<String>? = result
    }
}
