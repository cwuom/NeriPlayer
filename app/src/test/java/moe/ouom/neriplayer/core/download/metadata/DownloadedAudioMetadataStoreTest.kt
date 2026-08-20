package moe.ouom.neriplayer.core.download.metadata

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Test

class DownloadedAudioMetadataStoreTest {

    @Test
    fun `restoring a custom cover clears the stale downloaded sidecar path`() {
        val customCover = "file:///data/user/0/app/files/custom_song_covers/custom.jpg"
        val staleDownloadedCover = "content://downloads/Covers/song-custom.jpg"
        val restoredSong = testSong().copy(
            coverUrl = "https://example.com/original.jpg",
            originalCoverUrl = "https://example.com/original.jpg"
        )

        assertNull(
            resolveDownloadedMetadataCoverReference(
                existingCoverReference = staleDownloadedCover,
                song = restoredSong,
                previousCustomCoverReference = customCover
            )
        )
    }

    @Test
    fun `restoring keeps a locally preserved original cover ahead of stale metadata`() {
        val originalCover = "file:///data/user/0/app/files/original_song_covers/original.jpg"
        val restoredSong = testSong().copy(
            coverUrl = originalCover,
            originalCoverUrl = originalCover
        )

        assertEquals(
            originalCover,
            resolveDownloadedMetadataCoverReference(
                existingCoverReference = "file:///data/user/0/app/files/custom_song_covers/custom.jpg",
                song = restoredSong,
                previousCustomCoverReference = "file:///data/user/0/app/files/custom_song_covers/custom.jpg"
            )
        )
    }

    @Test
    fun `patching cover reference preserves the other metadata fields`() {
        val patched = patchDownloadedMetadataCoverReference(
            rawMetadata = "{\"stableKey\":\"song-key\",\"lyricPath\":\"lyrics.lrc\"}",
            coverReference = "content://downloads/Covers/song.jpg"
        )

        assertNotNull(patched)
        val payload = JSONObject(patched.orEmpty())
        assertEquals("song-key", payload.getString("stableKey"))
        assertEquals("lyrics.lrc", payload.getString("lyricPath"))
        assertEquals(
            "content://downloads/Covers/song.jpg",
            payload.getString("coverPath")
        )
    }

    @Test
    fun `patching invalid metadata returns no payload`() {
        assertNull(
            patchDownloadedMetadataCoverReference(
                rawMetadata = "not-json",
                coverReference = "content://downloads/Covers/song.jpg"
            )
        )
    }

    @Test
    fun `restoring metadata keeps lyric content when the incoming song has no lyrics`() {
        val restored = preserveMissingDownloadedMetadataLyrics(
            song = testSong(),
            metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                matchedLyric = "[00:01.00]stored lyric",
                matchedTranslatedLyric = "stored translation",
                matchedRomanizedLyric = "stored romanization",
                originalLyric = "stored original"
            )
        )

        assertEquals("[00:01.00]stored lyric", restored.matchedLyric)
        assertEquals("stored translation", restored.matchedTranslatedLyric)
        assertEquals("stored romanization", restored.matchedRomanizedLyric)
        assertEquals("stored original", restored.originalLyric)
    }

    private fun testSong(): SongItem {
        return SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Netease",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null
        )
    }
}
