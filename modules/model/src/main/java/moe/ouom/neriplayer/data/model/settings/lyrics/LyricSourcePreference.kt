package moe.ouom.neriplayer.data.model.settings.lyrics

import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource

enum class LyricSourcePreference(
    val storageValue: String
) {
    Automatic("automatic"),
    CloudMusic("cloud_music"),
    Kugou("kugou"),
    QqMusic("qq_music"),
    LrcLib("lrclib"),
    AmllTtml("amll_ttml");

    /**
     * 该来源对应的可编辑歌词匹配源; [Automatic] 没有固定来源, 返回 null。
     */
    val matchSource: EditableLyricMatchSource?
        get() = when (this) {
            Automatic -> null
            CloudMusic -> EditableLyricMatchSource.CLOUD_MUSIC
            Kugou -> EditableLyricMatchSource.KUGOU
            QqMusic -> EditableLyricMatchSource.QQ_MUSIC
            LrcLib -> EditableLyricMatchSource.LRCLIB
            AmllTtml -> EditableLyricMatchSource.AMLL_TTML
        }
}
