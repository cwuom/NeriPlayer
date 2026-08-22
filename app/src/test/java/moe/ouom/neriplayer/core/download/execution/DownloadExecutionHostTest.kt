package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.junit.Test

class DownloadExecutionHostTest {
    @Test
    fun `operation ids are restricted to safe file names`() {
        assertEquals("operation-01", normalizeDownloadOperationId(" operation-01 "))
        assertNull(normalizeDownloadOperationId(""))
        assertNull(normalizeDownloadOperationId("../operation"))
        assertNull(normalizeDownloadOperationId("operation/id"))
        assertNull(normalizeDownloadOperationId("operation id"))
    }

    @Test
    fun `operation store round trips request and rejects mismatched identity`() {
        val directory = Files.createTempDirectory("download-execution").toFile()
        val store = DownloadExecutionOperationStore()
        val request = DownloadExecutionRequest(
            operationId = "operation-01",
            song = SongItem(
                id = 42L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 7L,
                durationMs = 1234L,
                coverUrl = "https://example.invalid/cover.jpg",
                sourceStableKey = "netease:42"
            )
        )

        store.saveTo(directory, request)

        val restored = store.readFrom(directory, request.operationId)
        assertNotNull(restored)
        val restoredRequest = restored!!
        assertEquals(request.operationId, restoredRequest.operationId)
        assertEquals(request.song.id, restoredRequest.song.id)
        assertEquals(request.song.name, restoredRequest.song.name)
        assertEquals(request.song.sourceStableKey, restoredRequest.song.sourceStableKey)
        assertTrue(DownloadExecutionOperationStore.fileName(request.operationId).endsWith(".json"))

        val tampered = directory.resolve(
            DownloadExecutionOperationStore.fileName(request.operationId)
        )
        tampered.writeText(
            directory.resolve(
                DownloadExecutionOperationStore.fileName(request.operationId)
            ).readText().replace("operation-01", "operation-02")
        )
        assertNull(store.readFrom(directory, "operation-01"))
    }

    @Test
    fun `UIDT job id is stable and never uses reserved low range`() {
        val first = UidtDownloadJobService.jobIdFor("operation-01")
        val second = UidtDownloadJobService.jobIdFor("operation-01")
        assertEquals(first, second)
        assertTrue(first >= 100_000)
    }

    @Test
    fun `execution forwards the durable operation id to the entry point`() = runTest {
        val directory = Files.createTempDirectory("download-execution-host").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val request = DownloadExecutionRequest(
            operationId = "operation-forwarded",
            song = sampleSong()
        )
        val store = DownloadExecutionOperationStore { directory }
        store.saveTo(directory, request)
        var forwardedOperationId: String? = null
        val entryPoint = DownloadOperationEntryPoint { _, operationId, _ ->
            forwardedOperationId = operationId
        }
        val host = DefaultDownloadExecutionHost(
            operationStore = store,
            entryPoint = entryPoint,
            sdkInt = 28
        )

        assertEquals(
            DownloadExecutionResult.Accepted,
            host.execute(context, request.operationId)
        )
        assertEquals(request.operationId, forwardedOperationId)
    }

    private fun sampleSong(): SongItem {
        return SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 7L,
            durationMs = 1234L,
            coverUrl = null
        )
    }
}
