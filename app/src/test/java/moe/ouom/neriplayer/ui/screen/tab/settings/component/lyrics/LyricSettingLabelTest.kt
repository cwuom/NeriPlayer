package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricSettingLabelTest {

    @Test
    fun `every lyric source preference has its own label`() {
        val expected = mapOf(
            LyricSourcePreference.Automatic to CoreCommonR.string.settings_lyric_source_automatic,
            LyricSourcePreference.CloudMusic to CoreCommonR.string.settings_lyric_source_cloud_music,
            LyricSourcePreference.Kugou to CoreCommonR.string.settings_lyric_source_kugou,
            LyricSourcePreference.QqMusic to CoreCommonR.string.settings_lyric_source_qq_music,
            LyricSourcePreference.LrcLib to CoreCommonR.string.settings_lyric_source_lrclib,
            LyricSourcePreference.AmllTtml to CoreCommonR.string.settings_lyric_source_amll_ttml
        )

        assertEquals(LyricSourcePreference.entries.toSet(), expected.keys)
        expected.forEach { (source, label) ->
            assertEquals(source.name, label, lyricSourcePreferenceLabel(source))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }

    @Test
    fun `every bluetooth metadata mode has its own label`() {
        val expected = mapOf(
            BluetoothMetadataMode.Lyrics to CoreCommonR.string.settings_bluetooth_mode_lyrics,
            BluetoothMetadataMode.SongInfo to CoreCommonR.string.settings_bluetooth_mode_song_info,
            BluetoothMetadataMode.SongAndLyrics to CoreCommonR.string.settings_bluetooth_mode_song_and_lyrics
        )

        assertEquals(BluetoothMetadataMode.entries.toSet(), expected.keys)
        expected.forEach { (mode, label) ->
            assertEquals(mode.name, label, bluetoothMetadataModeLabel(mode))
        }
        assertEquals(expected.size, expected.values.toSet().size)
    }
}
