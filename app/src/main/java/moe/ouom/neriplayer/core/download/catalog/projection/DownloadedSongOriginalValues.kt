package moe.ouom.neriplayer.core.download.catalog.projection

import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.data.model.SongItem

internal class DownloadedSongOriginalValues(
    private val existing: DownloadedSong,
    private val updated: SongItem,
    private val preservesExistingSource: Boolean
) {
    val name: String?
        get() = originalValue(existing.originalName, existing.name, updated.originalName)

    val artist: String?
        get() = originalValue(existing.originalArtist, existing.artist, updated.originalArtist)

    val coverUrl: String?
        get() = originalValue(existing.originalCoverUrl, existing.coverUrl, updated.originalCoverUrl)

    val lyric: String?
        get() = existing.originalLyric ?: updated.originalLyric

    val translatedLyric: String?
        get() = existing.originalTranslatedLyric ?: updated.originalTranslatedLyric

    val romanizedLyric: String?
        get() = existing.originalRomanizedLyric ?: updated.originalRomanizedLyric

    private fun originalValue(stored: String?, remoteDisplay: String?, edited: String?): String? {
        // 空字符串也是已保存的原始值，不能用空白判断覆盖
        if (stored != null) return stored
        if (preservesExistingSource && remoteDisplay != null) return remoteDisplay
        return edited
    }
}
