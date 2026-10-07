package moe.ouom.neriplayer.core.download.policy

import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedPlaybackHydrationPolicyTest {
    private val original = SongItem(
        id = 42L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 7L,
        durationMs = 180_000L,
        coverUrl = null
    )

    @Test
    fun `any lyric change hydrates the playing song immediately`() {
        listOf(
            original.copy(matchedLyric = "[00:01.00]matched"),
            original.copy(matchedTranslatedLyric = "[00:01.00]translated"),
            original.copy(matchedRomanizedLyric = "[00:01.00]romanized"),
            original.copy(originalLyric = "[00:01.00]original"),
            original.copy(originalTranslatedLyric = "[00:01.00]original translated"),
            original.copy(originalRomanizedLyric = "[00:01.00]original romanized")
        ).forEach { hydrated ->
            assertEquals(hydrated.toString(), 0L, resolveDownloadedPlaybackHydrationDelayMs(original, hydrated))
        }
    }

    @Test
    fun `visible metadata changes hydrate sooner than unchanged songs`() {
        assertEquals(
            GlobalDownloadManager.PLAYBACK_METADATA_HYDRATION_DELAY_MS,
            resolveDownloadedPlaybackHydrationDelayMs(original, original.copy(name = "Renamed"))
        )
        assertEquals(
            GlobalDownloadManager.LOCAL_PLAYBACK_METADATA_HYDRATION_DELAY_MS,
            resolveDownloadedPlaybackHydrationDelayMs(original, original.copy())
        )
    }

    @Test
    fun `expected artists list the displayed source and original artist once each`() {
        val song = original.copy(customArtist = "Custom", originalArtist = "Original")

        assertEquals(listOf("Custom", "Artist", "Original"), buildExpectedDownloadArtists(song).toList())
        assertEquals(
            listOf("Artist", "Original"),
            buildExpectedDownloadArtists(song.copy(customArtist = null)).toList()
        )
        assertEquals(listOf("Artist"), buildExpectedDownloadArtists(song.copy(customArtist = null, originalArtist = " ")).toList())
        assertEquals(listOf("Artist"), buildExpectedDownloadArtists(song.copy(customArtist = null, originalArtist = null)).toList())
    }
}
