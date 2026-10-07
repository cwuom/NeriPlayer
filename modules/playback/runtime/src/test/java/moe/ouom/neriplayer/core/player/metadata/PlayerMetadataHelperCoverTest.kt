package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMetadataHelperCoverTest {
    private val song = SongItem(
        id = 1L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 2L,
        durationMs = 180_000L,
        coverUrl = "https://img.example/base.jpg"
    )

    @Test
    fun `first lyric edit remembers the matched lyrics as originals`() {
        val updated = song.copy(
            matchedLyric = "old",
            matchedTranslatedLyric = "old-t",
            matchedRomanizedLyric = "old-r"
        ).withUpdatedLyricsPreservingOriginal(
            newLyrics = "new",
            newTranslatedLyric = null,
            newRomanizedLyric = "new-r",
            userEdited = false,
            revision = 5L
        )

        assertEquals("new", updated.matchedLyric)
        assertNull(updated.matchedTranslatedLyric)
        assertEquals("new-r", updated.matchedRomanizedLyric)
        assertEquals(false, updated.lyricSyncEdited)
        assertEquals(5L, updated.lyricSyncRevision)
        assertEquals("old", updated.originalLyric)
        assertEquals("old-t", updated.originalTranslatedLyric)
        assertEquals("old-r", updated.originalRomanizedLyric)
    }

    @Test
    fun `later lyric edits keep the first originals`() {
        val updated = song.copy(
            matchedLyric = "edited",
            originalLyric = "orig",
            originalTranslatedLyric = "orig-t",
            originalRomanizedLyric = "orig-r"
        ).withUpdatedLyricsPreservingOriginal(
            newLyrics = "edited again",
            newTranslatedLyric = "t",
            newRomanizedLyric = null,
            userEdited = true,
            revision = 9L
        )

        assertEquals("edited again", updated.matchedLyric)
        assertEquals(true, updated.lyricSyncEdited)
        assertEquals(9L, updated.lyricSyncRevision)
        assertEquals("orig", updated.originalLyric)
        assertEquals("orig-t", updated.originalTranslatedLyric)
        assertEquals("orig-r", updated.originalRomanizedLyric)
    }

    @Test
    fun `youtube tracks never auto match external lyrics through metadata replacement`() {
        assertFalse(shouldAutoMatchExternalLyrics(song, isYouTubeMusicTrack = false))
        assertFalse(shouldAutoMatchExternalLyrics(song, isYouTubeMusicTrack = true))
        assertFalse(shouldAutoMatchExternalLyrics(song.copy(matchedSongId = "123"), isYouTubeMusicTrack = true))
        assertFalse(shouldAutoMatchExternalLyrics(song.copy(matchedLyric = "[00:01.00]x"), isYouTubeMusicTrack = true))
        assertFalse(
            shouldAutoMatchExternalLyrics(song.copy(matchedLyric = "", customName = "n"), isYouTubeMusicTrack = true)
        )
        assertFalse(shouldAutoMatchExternalLyrics(song.copy(customArtist = "a"), isYouTubeMusicTrack = true))
        assertFalse(shouldAutoMatchExternalLyrics(song.copy(customCoverUrl = "c"), isYouTubeMusicTrack = true))
    }

    @Test
    fun `local cover writes use the trimmed reference except media store album art`() {
        assertEquals(
            "https://img.example/cover.jpg",
            resolveLocalCoverWriteReference(
                restoreBaseCover = false,
                requestedCoverReference = " https://img.example/cover.jpg ",
                restoredBaseCoverReference = "file:///sdcard/base.png"
            )
        )
        assertEquals(
            "file:///sdcard/base.png",
            resolveLocalCoverWriteReference(
                restoreBaseCover = true,
                requestedCoverReference = "https://img.example/cover.jpg",
                restoredBaseCoverReference = " file:///sdcard/base.png "
            )
        )
        assertNull(
            resolveLocalCoverWriteReference(
                restoreBaseCover = true,
                requestedCoverReference = null,
                restoredBaseCoverReference = "content://media/external/audio/albumart/12"
            )
        )
        assertEquals(
            "content://media/external/images/media/9",
            resolveLocalCoverWriteReference(
                restoreBaseCover = false,
                requestedCoverReference = "content://media/external/images/media/9",
                restoredBaseCoverReference = null
            )
        )
        assertNull(resolveLocalCoverWriteReference(false, "   ", null))
        assertNull(resolveLocalCoverWriteReference(false, null, "file:///sdcard/base.png"))
    }

    @Test
    fun `only local songs materialize remote covers when restoring or persisting`() {
        assertFalse(shouldMaterializeRemoteLocalCover(false, "https://img.example/c.jpg", true, true))
        assertFalse(shouldMaterializeRemoteLocalCover(true, "https://img.example/c.jpg", false, false))
        assertTrue(shouldMaterializeRemoteLocalCover(true, " HTTPS://img.example/c.jpg ", true, false))
        assertTrue(shouldMaterializeRemoteLocalCover(true, "http://img.example/c.jpg", false, true))
        assertFalse(shouldMaterializeRemoteLocalCover(true, "file:///sdcard/c.jpg", false, true))
        assertFalse(shouldMaterializeRemoteLocalCover(true, null, true, false))
    }
}
