package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
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
}
