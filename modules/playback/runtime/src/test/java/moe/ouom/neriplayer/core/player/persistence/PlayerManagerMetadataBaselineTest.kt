package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.core.player.metadata.withUpdatedLyricsPreservingOriginal
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.LocalMetadataSidecar
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerManagerMetadataBaselineTest {
    private val song = SongItem(1L, "song", "artist", "__local_files__", 0L, 1000L, null)

    @Test
    fun `resolved empty local lyrics stay empty after repeated manual edits`() {
        val prepared = song.withLocalMetadataOriginals(
            metadata = null,
            lyrics = LocalLyricsScanMetadata(null, null, null, sourceResolved = true, embeddedSourceResolved = true)
        )

        val updated = prepared.withUpdatedLyricsPreservingOriginal(
            newLyrics = "first edit",
            newTranslatedLyric = "first translation",
            newRomanizedLyric = "first romanization"
        ).withUpdatedLyricsPreservingOriginal(newLyrics = "second edit")

        assertEquals("", updated.originalLyric)
        assertEquals("", updated.originalTranslatedLyric)
        assertEquals("", updated.originalRomanizedLyric)
    }

    @Test
    fun `unresolved local lyrics remain unknown`() {
        val prepared = song.withLocalMetadataOriginals(
            metadata = null,
            lyrics = LocalLyricsScanMetadata(null, null, null, sourceResolved = false)
        )

        assertNull(prepared.originalLyric)
        assertNull(prepared.originalTranslatedLyric)
        assertNull(prepared.originalRomanizedLyric)
    }

    @Test
    fun `sidecar scan alone cannot confirm absent embedded lyrics`() {
        val prepared = song.withLocalMetadataOriginals(
            metadata = null,
            lyrics = LocalLyricsScanMetadata(null, null, null, sourceResolved = true)
        )

        assertNull(prepared.originalLyric)
        assertNull(prepared.originalTranslatedLyric)
        assertNull(prepared.originalRomanizedLyric)
    }

    @Test
    fun `an unreadable sidecar does not become an empty original after an embedded probe`() {
        val prepared = song.withLocalMetadataOriginals(
            metadata = null,
            lyrics = LocalLyricsScanMetadata(
                null, null, null,
                hasOriginalSidecar = true,
                sourceResolved = true,
                embeddedSourceResolved = true
            )
        )

        assertNull(prepared.originalLyric)
        assertEquals("", prepared.originalTranslatedLyric)
        assertEquals("", prepared.originalRomanizedLyric)
    }

    @Test
    fun `an explicitly empty matched lyric remains a known empty original`() {
        val prepared = song.copy(matchedLyric = "").withLocalMetadataOriginals(
            metadata = null,
            lyrics = null
        )

        assertEquals("", prepared.originalLyric)
        assertNull(prepared.originalTranslatedLyric)
        assertNull(prepared.originalRomanizedLyric)
    }

    @Test
    fun `first local edit preserves lyrics read before replacement`() {
        val prepared = song.withLocalMetadataOriginals(
            metadata = null,
            lyrics = LocalLyricsScanMetadata("local lyric", "local translation", "local romanization", sourceResolved = true)
        )

        val updated = prepared.withUpdatedLyricsPreservingOriginal(newLyrics = "edited lyric")

        assertEquals("local lyric", updated.originalLyric)
        assertEquals("local translation", updated.originalTranslatedLyric)
        assertEquals("local romanization", updated.originalRomanizedLyric)
    }

    @Test
    fun `metadata originals survive the current edited lyric sidecars`() {
        val prepared = song.copy(
            originalLyric = "stale hydrated edit",
            originalCoverUrl = "file:///edited-cover.jpg",
            lyricSyncEdited = true
        ).withLocalMetadataOriginals(
            metadata = metadata(),
            lyrics = LocalLyricsScanMetadata("edited lyric", "edited translation", "edited romanization", sourceResolved = true)
        )

        assertEquals("base lyric", prepared.originalLyric)
        assertEquals("", prepared.originalTranslatedLyric)
        assertEquals("base romanization", prepared.originalRomanizedLyric)
        assertEquals("file:///base-cover.jpg", prepared.originalCoverUrl)
    }

    @Test
    fun `confirmed edits with missing originals do not recapture edited sidecars`() {
        val prepared = song.copy(lyricSyncEdited = true).withLocalMetadataOriginals(
            metadata = null,
            lyrics = LocalLyricsScanMetadata("edited lyric", "edited translation", "edited romanization", sourceResolved = true)
        )

        assertNull(prepared.originalLyric)
        assertNull(prepared.originalTranslatedLyric)
        assertNull(prepared.originalRomanizedLyric)
    }

    @Test
    fun `publishing a prepared edit carries its originals into stale song copies`() {
        val prepared = song.withLocalMetadataOriginals(
            metadata = metadata(),
            lyrics = null
        ).withUpdatedLyricsPreservingOriginal(newLyrics = "edited lyric")

        val published = song.withOriginalLyricsFrom(prepared)
            .withUpdatedLyricsPreservingOriginal(newLyrics = "edited lyric")

        assertEquals("base lyric", published.originalLyric)
        assertEquals("", published.originalTranslatedLyric)
        assertEquals("base romanization", published.originalRomanizedLyric)
    }

    private fun metadata() = LocalMetadataSidecar(
        reference = "file:///song.mp3.npmeta",
        originalCoverUrl = "file:///base-cover.jpg",
        hasLyricOverride = true,
        hasTranslatedLyricOverride = true,
        hasRomanizedLyricOverride = true,
        matchedLyric = "edited lyric",
        matchedTranslatedLyric = "edited translation",
        originalLyric = "base lyric",
        originalTranslatedLyric = "",
        matchedRomanizedLyric = "edited romanization",
        originalRomanizedLyric = "base romanization"
    )
}
