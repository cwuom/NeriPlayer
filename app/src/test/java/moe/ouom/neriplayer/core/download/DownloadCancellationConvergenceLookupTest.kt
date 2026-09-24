package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.manager.batch.loadCancellationConvergenceRequests
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadCancellationConvergenceLookupTest {
    @Test
    fun `convergence reads only captured operations and ignores terminal or foreign rows`() = runBlocking {
        val target = song(1)
        val foreign = song(2)
        val requested = setOf("target", "completed", "foreign", "mismatch")
        var queriedIds = emptySet<String>()
        val snapshots = mapOf(
            "target" to snapshot("target", target, "CANCEL_REQUESTED"),
            "completed" to snapshot("completed", target, "COMPLETED"),
            "foreign" to snapshot("foreign", foreign, "CANCEL_REQUESTED"),
            "mismatch" to snapshot("replacement", target, "CANCEL_REQUESTED"),
            "unrelated" to snapshot("unrelated", target, "CANCEL_REQUESTED")
        )

        val result = loadCancellationConvergenceRequests(target.stableKey(), requested) { ids ->
            queriedIds = ids.toSet()
            snapshots
        }

        assertEquals(requested, queriedIds)
        assertEquals(listOf("target"), result.map(DownloadExecutionRequest::operationId))
    }

    private fun snapshot(
        operationId: String,
        song: SongItem,
        state: String
    ) = DownloadExecutionRoomStore.OperationSnapshot(
        request = DownloadExecutionRequest(operationId = operationId, song = song),
        state = state
    )

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song$id",
        artist = "artist",
        album = "album",
        albumId = 0,
        durationMs = 0,
        coverUrl = null
    )
}
