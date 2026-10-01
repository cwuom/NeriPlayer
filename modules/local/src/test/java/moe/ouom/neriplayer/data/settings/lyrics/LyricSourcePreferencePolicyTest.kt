package moe.ouom.neriplayer.data.settings.lyrics

import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LyricSourcePreferencePolicyTest {
    @Test
    fun `every canonical storage value retains its source`() {
        LyricSourcePreference.entries.forEach { source ->
            assertEquals(source, LyricSourcePreferencePolicy.fromStorage(source.storageValue))
            assertEquals(source.storageValue, LyricSourcePreferencePolicy.normalize(source.storageValue))
            assertEquals(
                source,
                LyricSourcePreferencePolicy.fromStorage("  ${source.storageValue.uppercase(Locale.ROOT)}  ")
            )
        }
    }

    @Test
    fun `historical aliases restore their canonical source`() {
        val aliases = mapOf(
            "netease" to LyricSourcePreference.CloudMusic,
            "cloudmusic" to LyricSourcePreference.CloudMusic,
            "kugou_music" to LyricSourcePreference.Kugou,
            "qq" to LyricSourcePreference.QqMusic,
            "qqmusic" to LyricSourcePreference.QqMusic,
            "lrclib_net" to LyricSourcePreference.LrcLib,
            "amll" to LyricSourcePreference.AmllTtml,
            "amll_ttml_client" to LyricSourcePreference.AmllTtml
        )

        aliases.forEach { (alias, source) ->
            assertEquals(alias, source, LyricSourcePreferencePolicy.fromStorage(alias))
            assertEquals(alias, source.storageValue, LyricSourcePreferencePolicy.normalize(alias))
            assertEquals(
                alias,
                source,
                LyricSourcePreferencePolicy.fromStorage("\t${alias.uppercase(Locale.ROOT)} ")
            )
        }
    }

    @Test
    fun `missing blank and unknown values retain automatic behavior`() {
        listOf(null, "", " \t ", "future_source", "kugou-music").forEach { value ->
            assertEquals(value, LyricSourcePreference.Automatic, LyricSourcePreferencePolicy.fromStorage(value))
        }
        assertEquals("automatic", LyricSourcePreferencePolicy.normalize("future_source"))
    }
}
