package moe.ouom.neriplayer.core.download.metadata

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Test

class DownloadedAudioMetadataStoreTest {

    @Test
    fun `metadata writes preserve the original download time`() {
        assertEquals(
            123L,
            resolveDownloadedAudioTime(existingTimeMs = 123L, fallbackTimeMs = 999L)
        )
        assertEquals(
            999L,
            resolveDownloadedAudioTime(existingTimeMs = null, fallbackTimeMs = 999L)
        )
        assertNull(resolveDownloadedAudioTime(existingTimeMs = 0L, fallbackTimeMs = 0L))
    }

    @Test
    fun `restorable offset keeps an existing value when incoming metadata omits it`() {
        assertEquals(
            -321L,
            resolveDownloadedUserLyricOffset(existingOffsetMs = -321L, incomingOffsetMs = 0L)
        )
        assertEquals(
            120L,
            resolveDownloadedUserLyricOffset(existingOffsetMs = -321L, incomingOffsetMs = 120L)
        )
        assertEquals(
            -120L,
            resolveDownloadedUserLyricOffset(existingOffsetMs = null, incomingOffsetMs = -120L)
        )
    }

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

    @Test
    fun `metadata persistence keeps existing edits when incoming song omits them`() {
        val previous = ManagedDownloadRestorableMetadata.Overrides(
            title = "Edited title",
            artist = "Edited artist",
            coverReference = "content://managed/Covers/edited.jpg",
            userLyricOffsetMs = -321L,
            originalLyric = "edited lyric",
            translatedLyric = "edited translation",
            romanizedLyric = "edited romanization"
        )

        val merged = mergeRestorableOverrides(
            previous = previous,
            song = testSong(),
            coverReference = null
        )

        assertEquals(previous, merged)
    }

    @Test
    fun `metadata persistence applies explicit non-null edits over the baseline`() {
        val merged = mergeRestorableOverrides(
            previous = ManagedDownloadRestorableMetadata.Overrides(),
            song = testSong().copy(
                customName = "Edited title",
                customArtist = "Edited artist",
                customCoverUrl = "content://managed/Covers/edited.jpg",
                matchedLyric = "edited lyric",
                matchedTranslatedLyric = "edited translation",
                matchedRomanizedLyric = "edited romanization",
                userLyricOffsetMs = -321L
            ),
            coverReference = "content://managed/Covers/sidecar.jpg"
        )

        assertEquals("Edited title", merged.title)
        assertEquals("Edited artist", merged.artist)
        assertEquals("content://managed/Covers/edited.jpg", merged.coverReference)
        assertEquals(-321L, merged.userLyricOffsetMs)
        assertEquals("edited lyric", merged.originalLyric)
        assertEquals("edited translation", merged.translatedLyric)
        assertEquals("edited romanization", merged.romanizedLyric)
    }

    @Test
    fun `restoring the baseline clears title artist cover and lyric overrides`() {
        val merged = mergeRestorableOverrides(
            previous = ManagedDownloadRestorableMetadata.Overrides(
                title = "Edited title",
                artist = "Edited artist",
                coverReference = "content://managed/Covers/edited.jpg",
                originalLyric = "edited lyric",
                translatedLyric = "edited translation",
                romanizedLyric = "edited romanization"
            ),
            song = testSong().copy(
                matchedLyric = "original lyric",
                matchedTranslatedLyric = "original translation",
                matchedRomanizedLyric = "original romanization"
            ),
            coverReference = "content://managed/Covers/base.jpg",
            clearRestorableOverrides = RestorableMetadataClearPolicy(
                title = true,
                artist = true,
                cover = true,
                lyrics = true
            )
        )

        assertNull(merged.title)
        assertNull(merged.artist)
        assertNull(merged.coverReference)
        assertNull(merged.originalLyric)
        assertNull(merged.translatedLyric)
        assertNull(merged.romanizedLyric)
    }

    @Test
    fun `restorable baseline is backfilled from downloaded sidecar lyrics`() {
        val baseline = mergeRestorableBaseline(
            existing = ManagedDownloadRestorableMetadata.Baseline(
                title = "Original title",
                artist = "Original artist"
            ),
            song = testSong(),
            coverReference = "content://managed/Covers/base.jpg",
            sidecarOriginalLyric = "[00:00.00]sidecar lyric",
            sidecarTranslatedLyric = "[00:00.00]sidecar translation",
            sidecarRomanizedLyric = "[00:00.00]sidecar romanization"
        )

        assertEquals("[00:00.00]sidecar lyric", baseline.originalLyric)
        assertEquals("[00:00.00]sidecar translation", baseline.translatedLyric)
        assertEquals("[00:00.00]sidecar romanization", baseline.romanizedLyric)
    }

    @Test
    fun `restorable baseline never replaces an existing lyric with sidecar content`() {
        val baseline = mergeRestorableBaseline(
            existing = ManagedDownloadRestorableMetadata.Baseline(
                originalLyric = "[00:00.00]authoritative lyric"
            ),
            song = testSong(),
            coverReference = null,
            sidecarOriginalLyric = "[00:00.00]stale sidecar lyric"
        )

        assertEquals("[00:00.00]authoritative lyric", baseline.originalLyric)
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
