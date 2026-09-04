package moe.ouom.neriplayer.data.model

import moe.ouom.neriplayer.data.local.playlist.model.LocalArtistSummary
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaModelExtensionsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `resolveDisplayCoverUrl prefers local cover over remote fallback on main thread`() {
        assertEquals(
            "content://covers/song.jpg",
            resolveDisplayCoverUrl(
                customCoverUrl = null,
                currentCoverUrl = "https://example.com/song.jpg",
                localCoverUrl = "content://covers/song.jpg",
                onMainThread = true
            )
        )
    }

    @Test
    fun `resolveDisplayCoverUrl keeps custom override above local and remote covers`() {
        assertEquals(
            "content://covers/custom.jpg",
            resolveDisplayCoverUrl(
                customCoverUrl = "content://covers/custom.jpg",
                currentCoverUrl = "https://example.com/song.jpg",
                localCoverUrl = "content://covers/song.jpg",
                onMainThread = true
            )
        )
    }

    @Test
    fun `resolveDisplayCoverUrl falls back to remote cover when local cover is unavailable`() {
        assertEquals(
            "https://example.com/song.jpg",
            resolveDisplayCoverUrl(
                customCoverUrl = null,
                currentCoverUrl = "https://example.com/song.jpg",
                localCoverUrl = null,
                onMainThread = true
            )
        )
    }

    @Test
    fun `local song with legacy MediaStore cover still resolves local fallback`() {
        val localSong = song(
            name = "legacy-media-store",
            coverUrl = "content://media/external/audio/albumart/42"
        ).copy(mediaUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.mp3")

        assertTrue(localSong.shouldResolveLocalCoverFallback(localSong.coverUrl))
    }

    @Test
    fun `local song with legacy file cover still resolves local fallback`() {
        val localSong = song(
            name = "legacy-file",
            coverUrl = "file:///storage/emulated/0/Music/cover.jpg"
        ).copy(mediaUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.mp3")

        assertTrue(localSong.shouldResolveLocalCoverFallback(localSong.coverUrl))
    }

    @Test
    fun `local song revalidates a SAF cover reference before using it`() {
        val localSong = song(
            name = "saf-cover",
            coverUrl = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fcover.jpg"
        ).copy(mediaUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.mp3")

        assertTrue(localSong.shouldResolveLocalCoverFallback(localSong.coverUrl))
    }

    @Test
    fun `directory custom cover falls back to the song cover`() {
        val directory = tempFolder.newFolder("cover-directory")
        val song = song(
            name = "directory-cover",
            coverUrl = "content://covers/song.jpg"
        ).copy(customCoverUrl = directory.toURI().toString())

        assertEquals("content://covers/song.jpg", song.displayCoverUrl())
    }

    @Test
    fun `local playlist cover follows display order and skips songs without cover`() {
        val playlist = LocalPlaylist(
            id = 1L,
            name = "cover",
            songs = mutableListOf(
                song(name = "newest", coverUrl = null),
                song(name = "middle", coverUrl = "content://covers/middle.jpg"),
                song(name = "oldest", coverUrl = "content://covers/oldest.jpg")
            )
        )

        assertEquals("content://covers/middle.jpg", playlist.displayCoverUrl())
    }

    @Test
    fun `local playlist cover can use additional downloaded candidate after own songs`() {
        val playlist = LocalPlaylist(
            id = 1L,
            name = "cover",
            songs = mutableListOf(
                song(name = "newest", coverUrl = null),
                song(name = "older", coverUrl = null)
            )
        )
        val downloadedSong = song(
            name = "downloaded",
            coverUrl = "file:///covers/downloaded.jpg"
        )

        assertEquals(
            "file:///covers/downloaded.jpg",
            playlist.displayCoverUrl(additionalCoverCandidates = listOf(downloadedSong))
        )
    }

    @Test
    fun `local artist cover follows display order and skips songs without cover`() {
        val artist = LocalArtistSummary(
            name = "artist",
            songs = listOf(
                song(name = "newest", coverUrl = null),
                song(name = "middle", coverUrl = "content://covers/middle.jpg"),
                song(name = "oldest", coverUrl = "content://covers/oldest.jpg")
            )
        )

        assertEquals("content://covers/middle.jpg", artist.displayCoverUrl())
    }

    private fun song(
        name: String,
        coverUrl: String?
    ): SongItem {
        return SongItem(
            id = name.hashCode().toLong(),
            name = name,
            artist = "artist",
            album = "album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = coverUrl
        )
    }
}
