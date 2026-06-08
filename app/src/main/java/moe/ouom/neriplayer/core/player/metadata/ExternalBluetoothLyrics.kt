package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.player.audio.isBluetoothOutputType
import moe.ouom.neriplayer.ui.component.LyricEntry

internal data class ExternalBluetoothMetadataText(
    val title: String,
    val artist: String,
    val displayTitle: String,
    val displaySubtitle: String
)

internal fun findExternalBluetoothLyricLine(
    lyrics: List<LyricEntry>,
    positionMs: Long,
    lyricOffsetMs: Long = 0L
): String? {
    if (lyrics.isEmpty()) return null
    val targetTimeMs = (positionMs + lyricOffsetMs).coerceAtLeast(0L)
    val index = findCurrentExternalBluetoothLyricIndex(lyrics, targetTimeMs)
    return lyrics.getOrNull(index)
        ?.text
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

internal fun shouldUseExternalBluetoothLyrics(
    enabled: Boolean,
    audioDeviceType: Int?,
    lyricLine: String?
): Boolean {
    return enabled &&
        audioDeviceType != null &&
        isBluetoothOutputType(audioDeviceType) &&
        !lyricLine.isNullOrBlank()
}

internal fun resolveExternalBluetoothMetadataText(
    normalTitle: String,
    normalArtist: String,
    lyricLine: String?,
    useBluetoothLyrics: Boolean
): ExternalBluetoothMetadataText {
    if (!useBluetoothLyrics || lyricLine.isNullOrBlank()) {
        return ExternalBluetoothMetadataText(
            title = normalTitle,
            artist = normalArtist,
            displayTitle = normalTitle,
            displaySubtitle = normalArtist
        )
    }

    val songInfo = listOf(normalTitle, normalArtist)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" - ")

    return ExternalBluetoothMetadataText(
        title = lyricLine,
        artist = songInfo,
        displayTitle = lyricLine,
        displaySubtitle = songInfo
    )
}

private fun findCurrentExternalBluetoothLyricIndex(
    lyrics: List<LyricEntry>,
    currentTimeMs: Long
): Int {
    var low = 0
    var high = lyrics.lastIndex
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lyrics[mid].startTimeMs <= currentTimeMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}
