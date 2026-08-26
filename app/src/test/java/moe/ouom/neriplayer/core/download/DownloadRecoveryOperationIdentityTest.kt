package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class DownloadRecoveryOperationIdentityTest {
    @Test
    fun `pending queue codec keeps operation identity and mobile permission`() {
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
            operationId = "operation-701",
            requiresWifiNetwork = false
        )

        val payload = ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
            entries = listOf(entry),
            updatedAtMs = 42L
        )

        val restored = ManagedDownloadStorageJsonCodec
            .parsePendingDownloadQueuePayload(payload)
            .single()

        assertEquals("operation-701", restored.operationId)
        assertFalse(restored.requiresWifiNetwork)
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

    @Test
    fun `legacy pending queue defaults to Wi-Fi protection when the policy field is absent`() {
        val song = SongItem(
            id = 702L,
            name = "Legacy queued",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.invalid/702"
        )
        val entry = ManagedDownloadStorage.PendingDownloadQueueEntry(
            stableKey = song.stableKey(),
            song = song,
            order = 0,
            queuedAtMs = 42L
        )
        val payload = JSONObject(
            ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(
                entries = listOf(entry),
                updatedAtMs = 42L
            )
        ).apply {
            getJSONArray("entries").getJSONObject(0).remove("requiresWifiNetwork")
        }.toString()

        val restored = ManagedDownloadStorageJsonCodec
            .parsePendingDownloadQueuePayload(payload)
            .single()

        assertTrue(restored.requiresWifiNetwork)
    }
}
