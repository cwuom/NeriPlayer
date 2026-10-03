package moe.ouom.neriplayer.data.model.sync

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLegacyLyricPreservationTest {
    private val song = SongItem(id = 7L, name = "title", artist = "artist", album = "album",
        albumId = 3L, durationMs = 120_000L, coverUrl = null)

    @Test
    fun `each matched or original variant including empty text counts as a retained payload`() {
        assertFalse(song.hasSyncLyricText())
        assertFalse(SyncSong().hasSyncLyricText())
        val variants = listOf(
            song.copy(matchedLyric = ""), song.copy(matchedTranslatedLyric = ""),
            song.copy(matchedRomanizedLyric = ""), song.copy(originalLyric = ""),
            song.copy(originalTranslatedLyric = ""), song.copy(originalRomanizedLyric = "")
        )
        variants.forEach { variant ->
            assertTrue(variant.hasSyncLyricText())
            assertTrue(requireNotNull(variant.toLegacyLyricRecoveryCandidateOrNull()).hasSyncLyricText())
        }
    }

    @Test
    fun `unknown cache retains full local text without becoming a confirmed edit`() {
        val original = song.copy(matchedLyric = "cached original", matchedTranslatedLyric = "translation",
            matchedRomanizedLyric = "romanized", originalLyric = "baseline original",
            originalTranslatedLyric = "baseline translation", originalRomanizedLyric = "baseline romanized",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42", lyricSyncRevision = 999L,
            mediaUri = "https://example.invalid/media", channelId = "channel", audioId = "audio", subAudioId = "sub")
        val retained = requireNotNull(original.toLegacyLyricRecoveryCandidateOrNull())
        assertEquals(original.id, retained.id)
        assertEquals(original.album, retained.album)
        assertEquals(original.mediaUri, retained.mediaUri)
        assertEquals(original.channelId, retained.channelId)
        assertEquals(original.audioId, retained.audioId)
        assertEquals(original.subAudioId, retained.subAudioId)
        assertEquals(original.matchedLyric, retained.matchedLyric)
        assertEquals(original.matchedTranslatedLyric, retained.matchedTranslatedLyric)
        assertEquals(original.matchedRomanizedLyric, retained.matchedRomanizedLyric)
        assertEquals(original.originalLyric, retained.originalLyric)
        assertEquals(original.originalTranslatedLyric, retained.originalTranslatedLyric)
        assertEquals(original.originalRomanizedLyric, retained.originalRomanizedLyric)
        assertEquals("CLOUD_MUSIC", retained.matchedLyricSource)
        assertEquals("42", retained.matchedSongId)
        assertNull(retained.lyricSyncEdited)
        assertEquals(0L, retained.lyricSyncRevision)
    }

    @Test
    fun `auxiliary only old lyrics are preserved including explicit empty clearing text`() {
        val translation = requireNotNull(song.copy(matchedTranslatedLyric = "").toLegacyLyricRecoveryCandidateOrNull())
        assertNull(translation.matchedLyric)
        assertEquals("", translation.matchedTranslatedLyric)
        val romanized = requireNotNull(song.copy(matchedRomanizedLyric = "roma").toLegacyLyricRecoveryCandidateOrNull())
        assertEquals("roma", romanized.matchedRomanizedLyric)
    }

    @Test
    fun `confirmed edits and known caches do not enter unknown local cache`() {
        assertNull(song.copy(matchedLyric = "edit", lyricSyncEdited = true).toLegacyLyricRecoveryCandidateOrNull())
        assertNull(song.copy(matchedLyric = "cache", lyricSyncEdited = false).toLegacyLyricRecoveryCandidateOrNull())
    }

    @Test
    fun `baseline only legacy variants are retained without inventing matched text`() {
        val retained = requireNotNull(song.copy(originalLyric = "baseline", originalTranslatedLyric = "",
            originalRomanizedLyric = "romanized").toLegacyLyricRecoveryCandidateOrNull())
        assertEquals("baseline", retained.originalLyric)
        assertEquals("", retained.originalTranslatedLyric)
        assertEquals("romanized", retained.originalRomanizedLyric)
        assertNull(retained.matchedLyric)
        assertNull(retained.lyricSyncEdited)
        assertEquals(0L, retained.lyricSyncRevision)
    }
}
