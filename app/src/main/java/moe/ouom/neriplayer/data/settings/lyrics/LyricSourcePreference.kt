package moe.ouom.neriplayer.data.settings.lyrics

import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference

import java.util.Locale

/**
 * 播放时优先使用的歌词来源。
 *
 * [Automatic] 保持改造前的既有行为: 平台自带歌词优先, 缺少逐词时再补 AMLL TTML。
 * 其余取值会让播放期主动去对应平台按歌名、歌手和时长匹配歌词, 匹配失败时沿用原有歌词路径
 */

object LyricSourcePreferencePolicy {
    fun normalize(value: String): String = fromStorage(value).storageValue

    fun fromStorage(value: String?): LyricSourcePreference {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            LyricSourcePreference.CloudMusic.storageValue,
            "netease",
            "cloudmusic" -> LyricSourcePreference.CloudMusic
            LyricSourcePreference.Kugou.storageValue,
            "kugou_music" -> LyricSourcePreference.Kugou
            LyricSourcePreference.QqMusic.storageValue,
            "qq",
            "qqmusic" -> LyricSourcePreference.QqMusic
            LyricSourcePreference.LrcLib.storageValue,
            "lrclib_net" -> LyricSourcePreference.LrcLib
            LyricSourcePreference.AmllTtml.storageValue,
            "amll",
            "amll_ttml_client" -> LyricSourcePreference.AmllTtml
            else -> LyricSourcePreference.Automatic
        }
    }
}
