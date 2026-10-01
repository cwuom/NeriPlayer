package moe.ouom.neriplayer.data.settings.lyrics

import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference

import java.util.Locale

/**
 * 播放时优先使用的歌词来源。
 *
 * [LyricSourcePreference.Automatic] 保持既有行为: 平台自带歌词优先, 缺少逐词时再补 AMLL TTML
 * 其余取值会让播放期主动去对应平台按歌名、歌手和时长匹配歌词, 匹配失败时沿用原有歌词路径
 */

object LyricSourcePreferencePolicy {
    private val valuesByStorage = LyricSourcePreference.entries.associateBy { it.storageValue } + mapOf(
        "netease" to LyricSourcePreference.CloudMusic,
        "cloudmusic" to LyricSourcePreference.CloudMusic,
        "kugou_music" to LyricSourcePreference.Kugou,
        "qq" to LyricSourcePreference.QqMusic,
        "qqmusic" to LyricSourcePreference.QqMusic,
        "lrclib_net" to LyricSourcePreference.LrcLib,
        "amll" to LyricSourcePreference.AmllTtml,
        "amll_ttml_client" to LyricSourcePreference.AmllTtml
    )

    fun normalize(value: String): String = fromStorage(value).storageValue

    fun fromStorage(value: String?): LyricSourcePreference {
        val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
        return valuesByStorage[normalized] ?: LyricSourcePreference.Automatic
    }
}
