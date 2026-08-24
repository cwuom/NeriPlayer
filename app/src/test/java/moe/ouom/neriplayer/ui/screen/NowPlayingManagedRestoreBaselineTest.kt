package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingManagedRestoreBaselineTest {
    @Test
    fun `managed restore baseline wins over current edited fields`() {
        val current = EditSongBaseline(
            title = "Edited title",
            artist = "Edited artist",
            coverUrl = "content://root/Covers/edited.jpg",
            lyric = "edited lyric",
            translatedLyric = "edited translation",
            romanizedLyric = "edited romanization"
        )
        val metadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "stable",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                title = "Original title",
                artist = "Original artist",
                coverReference = "content://root/Covers/base.jpg",
                originalLyric = "original lyric",
                translatedLyric = "original translation",
                romanizedLyric = "original romanization"
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides()
        )

        val restored = resolveManagedEditSongBaseline(
            current = current,
            metadata = metadata,
            coverReference = "content://root/Covers/base.jpg"
        )

        assertEquals("Original title", restored.title)
        assertEquals("Original artist", restored.artist)
        assertEquals("content://root/Covers/base.jpg", restored.coverUrl)
        assertEquals("original lyric", restored.lyric)
        assertEquals("original translation", restored.translatedLyric)
        assertEquals("original romanization", restored.romanizedLyric)
    }

    @Test
    fun `edit baseline prefers durable original fields over custom display fields`() {
        val song = SongItem(
            id = 1L,
            name = "Source title",
            artist = "Source artist",
            album = "Album",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = "content://root/Covers/base.jpg",
            customName = "Edited title",
            customArtist = "Edited artist",
            customCoverUrl = "content://root/Covers/edited.jpg",
            originalName = "Original title",
            originalArtist = "Original artist",
            originalCoverUrl = "content://root/Covers/original.jpg",
            originalLyric = "original lyric",
            originalTranslatedLyric = "original translation",
            originalRomanizedLyric = "original romanization"
        )

        val baseline = resolveEditSongBaselineFromSong(
            song = song,
            resolvedDisplayCoverUrl = song.coverUrl,
            displayedLyric = "edited lyric",
            displayedTranslatedLyric = "edited translation",
            displayedRomanizedLyric = "edited romanization"
        )

        assertEquals("Original title", baseline.title)
        assertEquals("Original artist", baseline.artist)
        assertEquals("content://root/Covers/original.jpg", baseline.coverUrl)
        assertEquals("original lyric", baseline.lyric)
        assertEquals("original translation", baseline.translatedLyric)
        assertEquals("original romanization", baseline.romanizedLyric)
    }
}
