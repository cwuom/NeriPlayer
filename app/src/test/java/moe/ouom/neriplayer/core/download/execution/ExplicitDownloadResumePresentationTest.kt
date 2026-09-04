package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.core.download.ExplicitDownloadResumeCandidate
import moe.ouom.neriplayer.core.download.visibleExplicitResumeCandidates
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplicitDownloadResumePresentationTest {
    @Test
    fun `stopped operation remains visible after process restart`() {
        val candidate = candidate("operation-stopped", id = 11L)

        assertEquals(
            listOf(candidate),
            visibleExplicitResumeCandidates(
                candidates = listOf(candidate),
                activeSongKeys = emptySet()
            )
        )
    }

    @Test
    fun `visible candidates do not duplicate an in-memory task`() {
        val candidate = candidate("operation-active", id = 12L)

        assertEquals(
            emptyList<ExplicitDownloadResumeCandidate>(),
            visibleExplicitResumeCandidates(
                candidates = listOf(candidate),
                activeSongKeys = setOf(candidate.song.stableKey())
            )
        )
    }

    @Test
    fun `explicit resume keeps durable identity staging and network policy`() {
        val candidate = candidate("operation-preserved", id = 13L)
        val persisted = DownloadExecutionRequest(
            operationId = candidate.operationId,
            song = candidate.song,
            requiresWifiNetwork = false,
            attemptId = 17L,
            artifactLeaseId = "preserved-lease",
            userInitiated = true
        )

        val request = buildExplicitResumeRequest(candidate, persisted)

        assertEquals(candidate.operationId, request.operationId)
        assertEquals(candidate.song.stableKey(), request.song.stableKey())
        assertEquals(true, request.preserveStaging)
        assertEquals(true, request.userInitiated)
        assertEquals(false, request.requiresWifiNetwork)
        assertEquals(17L, request.attemptId)
        assertEquals("preserved-lease", request.artifactLeaseId)
    }

    private fun candidate(operationId: String, id: Long): ExplicitDownloadResumeCandidate {
        return ExplicitDownloadResumeCandidate(
            operationId = operationId,
            song = SongItem(
                id = id,
                name = "Song $id",
                artist = "Artist",
                album = "Album",
                albumId = id,
                durationMs = 180_000L,
                coverUrl = null
            ),
            queueOrder = id.toInt()
        )
    }
}
