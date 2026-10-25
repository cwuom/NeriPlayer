package moe.ouom.neriplayer.core.player.persistence

import com.google.gson.Gson
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistedLocalSourceProvenanceTest {
    private val gson = Gson()
    private val preferred = "content://media/external/audio/media/7"
    private val song = SongItem(
        id = 7L, name = "Scanned", artist = "Artist", album = "Album", albumId = 0L,
        durationMs = 180_000L, coverUrl = null, mediaUri = preferred,
        localFilePath = "/storage/emulated/0/Music/scanned.flac", localFileName = "scanned.flac",
        channelId = "netease", audioId = "7", explicitLocalSourceReference = preferred
    )

    @Test
    fun `queue persistence retains only the selected explicit local source beside remote identity`() {
        listOf(
            song,
            song.copy(mediaUri = null, explicitLocalSourceReference = song.localFilePath),
            song.copy(mediaUri = "file:///storage/emulated/0/Music/scanned.flac", localFilePath = null,
                explicitLocalSourceReference = "file:///storage/emulated/0/Music/scanned.flac")
        ).forEach { selected ->
            val restored = selected.toPersistedSongItem(includeLyrics = false).toSongItem()

            assertEquals(selected.explicitLocalSourceReference, restored.explicitLocalSourceReference)
            assertEquals(selected.mediaUri, restored.mediaUri)
            assertEquals(selected.localFilePath, restored.localFilePath)
            assertEquals(selected.channelId, restored.channelId)
            assertEquals(selected.audioId, restored.audioId)
        }
    }

    @Test
    fun `serialized main and shuffle queues preserve explicit sources after reconstruction`() {
        val state = PersistedState(
            playlist = listOf(song.toPersistedSongItem()), index = 0, positionMs = 1_500L,
            shouldResumePlayback = true, shuffleEnabled = true,
            shuffleRestorePlaylist = listOf(song.toPersistedSongItem()), shuffleRestoreIndex = 0
        )

        val restored = gson.fromJson(gson.toJson(state), PersistedState::class.java)

        assertEquals(preferred, restored.playlist.single().toSongItem().explicitLocalSourceReference)
        assertEquals(preferred, restored.shuffleRestorePlaylist?.single()?.toSongItem()?.explicitLocalSourceReference)
        assertEquals(1_500L, restored.positionMs)
        assertEquals(true, restored.shouldResumePlayback)
    }

    @Test
    fun `legacy queue JSON does not infer explicit selection from local paths`() {
        val payload = gson.toJsonTree(song.toPersistedSongItem()).asJsonObject.apply {
            remove("explicitLocalSourceReference")
        }

        val restored = gson.fromJson(payload, PersistedSongItem::class.java).toSongItem()

        assertNull(restored.explicitLocalSourceReference)
        assertEquals(song.mediaUri, restored.mediaUri)
        assertEquals(song.localFilePath, restored.localFilePath)
    }

    @Test
    fun `stale alternate and remote markers cannot acquire explicit queue provenance`() {
        listOf(song.localFilePath, "content://media/external/audio/media/8", "https://example.org/song.flac", " ")
            .forEach { marker ->
                val selected = song.copy(explicitLocalSourceReference = marker)
                assertNull(selected.toPersistedSongItem().toSongItem().explicitLocalSourceReference)
                val payload = gson.toJsonTree(song.toPersistedSongItem()).asJsonObject.apply {
                    addProperty("explicitLocalSourceReference", marker)
                }
                assertNull(gson.fromJson(payload, PersistedSongItem::class.java).toSongItem().explicitLocalSourceReference)
            }
    }
}
