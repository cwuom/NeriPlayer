package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongIdentityLookupTest {

    @Test
    fun `empty lookup contains nothing`() {
        val lookup = SongIdentityLookup(emptyList())

        assertFalse(lookup.contains(remoteSong(id = 1L)))
        assertFalse(lookup.contains(localSong(id = 2L, audioId = "audio-2", mediaUri = "content://tree/a.mp3")))
    }

    @Test
    fun `remote songs are found by identity only`() {
        val lookup = SongIdentityLookup(listOf(remoteSong(id = 10L), remoteSong(id = 11L)))

        assertTrue(lookup.contains(remoteSong(id = 11L)))
        assertFalse(lookup.contains(remoteSong(id = 12L)))
    }

    @Test
    fun `local song is found through a shared duplicate key after its reference moved`() {
        val stored = localSong(id = 1L, audioId = "audio-5", mediaUri = "content://tree-a/song.mp3")
        val moved = localSong(id = 2L, audioId = "audio-5", mediaUri = "content://tree-b/song.mp3")
        val lookup = SongIdentityLookup(listOf(stored))

        assertTrue(lookup.contains(moved))
    }

    @Test
    fun `local song without a shared duplicate key is not considered present`() {
        val lookup = SongIdentityLookup(
            listOf(localSong(id = 1L, audioId = "audio-5", mediaUri = "content://tree-a/song.mp3"))
        )

        assertFalse(
            lookup.contains(localSong(id = 3L, audioId = "audio-6", mediaUri = "content://tree-a/other.mp3"))
        )
    }

    @Test
    fun `local query is not matched against a lookup built only from remote songs`() {
        val lookup = SongIdentityLookup(listOf(remoteSong(id = 10L)))

        assertFalse(lookup.contains(localSong(id = 4L, audioId = "audio-7", mediaUri = "content://tree/c.mp3")))
    }

    @Test
    fun `remote query never falls back to local duplicate keys`() {
        val lookup = SongIdentityLookup(
            listOf(localSong(id = 1L, audioId = "audio-5", mediaUri = "content://tree-a/song.mp3"))
        )

        assertFalse(lookup.contains(remoteSong(id = 99L)))
    }

    private fun remoteSong(id: Long) = SongItem(
        id = id,
        name = "Remote $id",
        artist = "Artist",
        album = "Album",
        albumId = 100L,
        durationMs = 200_000L,
        coverUrl = null
    )

    private fun localSong(id: Long, audioId: String, mediaUri: String) = SongItem(
        id = id,
        name = "Local $id",
        artist = "Artist",
        album = "__local_files__",
        albumId = 0L,
        durationMs = 200_000L,
        coverUrl = null,
        mediaUri = mediaUri,
        channelId = "local",
        audioId = audioId
    )
}
