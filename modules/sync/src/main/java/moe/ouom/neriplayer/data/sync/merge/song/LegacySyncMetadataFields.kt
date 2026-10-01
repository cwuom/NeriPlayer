package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncSong

internal class LegacySyncMetadataFields(private val candidates: List<SyncSong>) {
    fun requiredText(value: String, selector: (SyncSong) -> String): String = text(value, selector).orEmpty()

    fun text(value: String?, selector: (SyncSong) -> String?): String? {
        if (!value.isNullOrBlank()) return value
        return candidates.firstNotNullOfOrNull { usableText(selector(it)) }
    }

    fun nonZero(value: Long, selector: (SyncSong) -> Long): Long {
        if (value != 0L) return value
        return candidates.firstNotNullOfOrNull { selector(it).takeIf { candidate -> candidate != 0L } } ?: 0L
    }

    fun positive(value: Long, selector: (SyncSong) -> Long): Long {
        if (value > 0L) return value
        return candidates.firstNotNullOfOrNull { selector(it).takeIf { candidate -> candidate > 0L } } ?: 0L
    }

    private fun usableText(value: String?): String? = if (value.isNullOrBlank()) null else value
}
