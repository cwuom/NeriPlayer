package moe.ouom.neriplayer.ui.screen.server

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import org.junit.Assert.*
import org.junit.Test

class ServerSongDisplayTest {
    private val firstProfile = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc791"
    private val secondProfile = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc792"

    @Test fun `same raw song id and numeric id across accounts never share user metadata`() {
        val first = song(firstProfile)
        val second = song(secondProfile)
        val saved = first.copy(customName = "My title", customCoverUrl = "content://cover/mine")
        val result = resolveServerDisplaySongs(listOf(first, second), listOf(saved), emptyList(), null)
        assertEquals("My title", result[0].customName)
        assertNull(result[1].customName)
        assertEquals(second, result[1])
    }

    @Test fun `user metadata survives fresh server data while transport remains server owned`() {
        val source = song(firstProfile).copy(name = "New server title", durationMs = 234_000L)
        val saved = source.copy(name = "Old title", customName = "My title", customArtist = "My artist",
            matchedLyric = "User lyrics", userLyricOffsetMs = 150L, lyricSyncEdited = true,
            streamUrl = "https://stale.example.test/audio", localFilePath = "/stale/file.mp3")
        val result = resolveServerDisplaySongs(listOf(source), listOf(saved), emptyList(), null).single()
        assertEquals("New server title", result.name)
        assertEquals("My title", result.customName)
        assertEquals("My artist", result.customArtist)
        assertEquals("User lyrics", result.matchedLyric)
        assertEquals(150L, result.userLyricOffsetMs)
        assertEquals(234_000L, result.durationMs)
        assertEquals(source.mediaUri, result.mediaUri)
        assertEquals(source.audioId, result.audioId)
        assertNull(result.streamUrl)
        assertNull(result.localFilePath)
    }

    @Test fun `unmodified playback queue does not erase persisted custom title`() {
        val source = song(firstProfile)
        val saved = source.copy(customName = "My title")
        val result = resolveServerDisplaySongs(listOf(source), listOf(saved), listOf(source), source).single()
        assertEquals("My title", result.customName)
    }

    @Test fun `restoring original title on current song clears the old override`() {
        val source = song(firstProfile)
        val saved = source.copy(customName = "My title", originalName = source.name)
        val restored = saved.copy(customName = null)
        val result = resolveServerDisplaySongs(listOf(source), listOf(saved), listOf(saved), restored).single()
        assertNull(result.customName)
        assertEquals(source.name, result.name)
    }

    private fun song(profileId: String): SongItem {
        val ref = ServerSongRef(profileId, "same raw song / 中文")
        return SongItem(7L, "Server title", "Server artist", "Album", 0L, 200_000L, null,
            mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)
    }
}
