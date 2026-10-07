package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalArtistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCoverDisplayFallbackTest {
    @Test
    fun `remote songs always resolve their cover fallback`() {
        assertTrue(song().shouldResolveLocalCoverFallback("neri-cover://kept"))
    }

    @Test
    fun `local songs resolve a fallback for missing remote or stale covers`() {
        val local = song(localFilePath = "/music/a.mp3")

        listOf(
            null,
            "  ",
            "https://img.example.com/a.jpg",
            "HTTP://img.example.com/a.jpg",
            "content://media/external/audio/albumart/1",
            "content://com.example.provider/cover/1",
            "FILE:///music/cover.jpg",
            "/data/user/0/app/files/cover.jpg"
        ).forEach { cover ->
            assertTrue(cover.toString(), local.shouldResolveLocalCoverFallback(cover))
        }
    }

    @Test
    fun `local songs keep covers that are neither remote nor stale`() {
        val local = song(localFilePath = "/music/a.mp3")

        assertFalse(local.shouldResolveLocalCoverFallback("neri-cover://kept"))
        assertFalse(local.shouldResolveLocalCoverFallback("android.resource://pkg/drawable/cover"))
    }

    @Test
    fun `artist covers use the first song with a displayable cover`() {
        val artist = LocalArtistSummary(
            name = "Artist",
            songs = listOf(
                song(id = 1L, customCoverUrl = " "),
                song(id = 2L, coverUrl = "content://media/external/audio/albumart/3"),
                song(id = 3L, coverUrl = ""),
                song(id = 4L, coverUrl = "https://img.example.com/4.jpg"),
                song(id = 5L, coverUrl = "https://img.example.com/5.jpg")
            )
        )

        assertEquals("https://img.example.com/4.jpg", artist.displayCoverUrl())
    }

    @Test
    fun `artists without displayable covers have no cover`() {
        assertNull(LocalArtistSummary(name = "Artist", songs = emptyList()).displayCoverUrl())
        assertNull(LocalArtistSummary(name = "Artist", songs = listOf(song(coverUrl = null))).displayCoverUrl())
    }

    @Test
    fun `identity media references prefer the normalized local path`() {
        assertEquals("/music/a.mp3", LocalSongSupport.identityMediaReference(song(localFilePath = " /music/a.mp3 ")))
        assertEquals(
            "content://media/external/audio/media/1",
            LocalSongSupport.identityMediaReference(
                song(localFilePath = "/music/a.mp3", mediaUri = "content://media/external/audio/media/1")
            )
        )
    }

    @Test
    fun `identity media references fall back to the media uri and then raw values`() {
        assertEquals(
            "/music/c.mp3",
            LocalSongSupport.identityMediaReference(song(localFilePath = "relative/a.mp3", mediaUri = "/music/c.mp3"))
        )
        assertEquals("relative/a.mp3", LocalSongSupport.identityMediaReference(song(localFilePath = "relative/a.mp3")))
        assertEquals(" ", LocalSongSupport.identityMediaReference(song(localFilePath = " ")))
        assertNull(LocalSongSupport.identityMediaReference(song()))
    }

    private fun song(
        id: Long = 1L,
        coverUrl: String? = null,
        customCoverUrl: String? = null,
        localFilePath: String? = null,
        mediaUri: String? = null
    ) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        durationMs = 1_000L,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        customCoverUrl = customCoverUrl,
        localFilePath = localFilePath
    )
}
