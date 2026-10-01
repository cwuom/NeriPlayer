package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.data.identity.stableKey

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.download.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.manager.batch.loadCancellationConvergenceRequests
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadCancellationConvergenceLookupTest {
    @Test
    fun `already cancelled operations retain their request for artifact cleanup`() = runBlocking {
        val target = song(1)
        val result = loadCancellationConvergenceRequests(target.stableKey(), setOf("cancelled")) {
            mapOf("cancelled" to snapshot("cancelled", target, "CANCELLED"))
        }

        assertEquals(listOf("cancelled"), result.map(DownloadExecutionRequest::operationId))
    }

    @Test
    fun `convergence reads only captured operations and ignores terminal or foreign rows`() = runBlocking {
        val target = song(1)
        val foreign = song(2)
        val requested = setOf("target", "completed", "finalized", "foreign", "mismatch")
        var queriedIds = emptySet<String>()
        val snapshots = mapOf(
            "target" to snapshot("target", target, "CANCEL_REQUESTED"),
            "completed" to snapshot("completed", target, "COMPLETED"),
            "finalized" to snapshot("finalized", target, "FINALIZED"),
            "foreign" to snapshot("foreign", foreign, "CANCELLED"),
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
