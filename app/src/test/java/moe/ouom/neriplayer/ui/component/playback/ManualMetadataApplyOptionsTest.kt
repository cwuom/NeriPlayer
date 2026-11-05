package moe.ouom.neriplayer.ui.component.playback

import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyOptions
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualMetadataApplyOptionsTest {
    @Test
    fun `explicit lyric preference overrides the metadata search platform`() {
        assertEquals(
            setOf(EditableLyricMatchSource.KUGOU),
            defaultMetadataLyricSources(LyricSourcePreference.Kugou, MusicPlatform.CLOUD_MUSIC)
        )
    }

    @Test
    fun `automatic lyrics follow the selected metadata platform`() {
        assertEquals(
            setOf(EditableLyricMatchSource.QQ_MUSIC),
            defaultMetadataLyricSources(LyricSourcePreference.Automatic, MusicPlatform.QQ_MUSIC)
        )
        assertEquals(
            setOf(EditableLyricMatchSource.CLOUD_MUSIC),
            defaultMetadataLyricSources(LyricSourcePreference.Automatic, MusicPlatform.CLOUD_MUSIC)
        )
    }

    @Test
    fun `requesting any lyric content requires a selected source`() {
        assertFalse(canApplyManualMetadataSelection(SongMetadataApplyOptions(lyricSources = emptySet())))
    }

    @Test
    fun `keeping every lyric field permits metadata without a lyric source`() {
        assertTrue(
            canApplyManualMetadataSelection(
                SongMetadataApplyOptions(
                    lyricSources = emptySet(),
                    originalLyrics = false,
                    translatedLyrics = false,
                    romanizedLyrics = false
                )
            )
        )
    }
}
