package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRecoveryOperationIdentityTest {
    @Test
    fun `pending queue codec keeps operation identity`() {
        val song = SongItem(
            id = 701L,
            name = "Queued",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.invalid/701"
        )
        val entry = ManagedDownloadStorage.PendingDownloadQueueEntry(
            stableKey = song.stableKey(),
            song = song,
            order = 0,
            queuedAtMs = 42L,
            operationId = "operation-701"
        )

        val payload = ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
            entries = listOf(entry),
            updatedAtMs = 42L
        )

        val restored = ManagedDownloadStorageJsonCodec
            .parsePendingDownloadQueuePayload(payload)
            .single()

        assertEquals("operation-701", restored.operationId)
    }

    @Test
    fun `legacy operation song payload without artist or album remains resumable`() {
        val song = ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(
            """
            {
              "id": 39175763,
              "name": "Queued legacy song",
              "channelId": "netease",
              "audioId": "39175763"
            }
            """.trimIndent()
        ) ?: error("legacy operation song payload must remain decodable")

        assertEquals("", song.artist)
        assertEquals("", song.album)
        assertEquals("39175763|netease|", song.stableKey())
    }
}
