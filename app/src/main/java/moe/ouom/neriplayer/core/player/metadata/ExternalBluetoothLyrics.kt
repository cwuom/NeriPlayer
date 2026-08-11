package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.player.audio.isBluetoothOutputType
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.matchTranslationsToLineIndices

internal data class ExternalBluetoothMetadataText(
    val title: String,
    val artist: String,
    val album: String?,
    val displayTitle: String,
    val displaySubtitle: String,
    val displayDescription: String?
)

internal data class ExternalBluetoothLyricPayload(
    val lyric: String? = null,
    val translation: String? = null
)

internal const val EXTERNAL_BLUETOOTH_LYRIC_STALE_GRACE_MS = 1_500L
internal const val EXTERNAL_BLUETOOTH_METADATA_MAX_UTF8_BYTES = 240

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

internal fun findFloatingTranslatedLyricLine(
    lyrics: List<LyricEntry>,
    translations: List<LyricEntry>,
    positionMs: Long,
    lyricOffsetMs: Long = 0L,
    translationMatchesByIndex: Map<Int, LyricEntry>? = null
): String? {
    if (lyrics.isEmpty() || translations.isEmpty()) return null
    val targetTimeMs = (positionMs + lyricOffsetMs).coerceAtLeast(0L)
    val lyricIndex = findCurrentExternalBluetoothLyricIndex(lyrics, targetTimeMs)
    if (lyrics.getOrNull(lyricIndex)?.text.isNullOrBlank()) return null
    val matches = translationMatchesByIndex ?: matchTranslationsToLineIndices(
        lines = lyrics,
        translations = translations.filter { it.text.isNotBlank() }
    )
    return matches[lyricIndex]
        ?.text
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

internal fun resolveExternalBluetoothLyricPayload(
    lyricEnabled: Boolean,
    translationEnabled: Boolean,
    lyricLine: String?,
    translationLine: String?
): ExternalBluetoothLyricPayload {
    val lyric = lyricLine
        .takeIf { lyricEnabled }
        .sanitizeExternalBluetoothMetadataValue()
    val translation = translationLine
        .takeIf { translationEnabled }
        .sanitizeExternalBluetoothMetadataValue()
        ?.takeUnless { lyric != null && it == lyric }
    return ExternalBluetoothLyricPayload(
        lyric = lyric,
        translation = translation
    )
}

internal fun shouldUseExternalBluetoothLyrics(
    audioDeviceType: Int?,
    payload: ExternalBluetoothLyricPayload,
    forceSendLyrics: Boolean = false
): Boolean {
    val hasLyricPayload = !payload.lyric.isNullOrBlank() || !payload.translation.isNullOrBlank()
    return hasLyricPayload &&
        (forceSendLyrics || (audioDeviceType != null && isBluetoothOutputType(audioDeviceType)))
}

internal fun resolveExternalBluetoothMetadataText(
    normalTitle: String,
    normalArtist: String,
    payload: ExternalBluetoothLyricPayload,
    useBluetoothLyrics: Boolean
): ExternalBluetoothMetadataText {
    val lyric = payload.lyric.sanitizeExternalBluetoothMetadataValue()
    val translation = payload.translation
        .sanitizeExternalBluetoothMetadataValue()
        ?.takeUnless { lyric != null && it == lyric }
    val primaryLine = lyric ?: translation
    if (!useBluetoothLyrics || primaryLine == null) {
        return ExternalBluetoothMetadataText(
            title = normalTitle,
            artist = normalArtist,
            album = null,
            displayTitle = normalTitle,
            displaySubtitle = normalArtist,
            displayDescription = null
        )
    }

    val songInfo = listOf(normalTitle, normalArtist)
        .mapNotNull { it.sanitizeExternalBluetoothMetadataValue() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" - ")
    val secondaryLine = translation.takeIf { lyric != null }

    return ExternalBluetoothMetadataText(
        title = primaryLine,
        artist = secondaryLine ?: songInfo,
        album = songInfo.takeIf { secondaryLine != null && it.isNotEmpty() },
        displayTitle = primaryLine,
        displaySubtitle = secondaryLine ?: songInfo,
        displayDescription = songInfo.takeIf { secondaryLine != null && it.isNotEmpty() }
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
    if (result < 0) return result

    val line = lyrics[result]
    val activeUntilMs = line.endTimeMs
        .coerceAtLeast(line.startTimeMs)
        .saturatingAdd(EXTERNAL_BLUETOOTH_LYRIC_STALE_GRACE_MS)
    return result.takeIf { currentTimeMs <= activeUntilMs } ?: -1
}

private fun String?.sanitizeExternalBluetoothMetadataValue(): String? {
    val source = this ?: return null
    val normalized = buildString(source.length) {
        var pendingSpace = false
        source.forEach { character ->
            if (character.isWhitespace() || character.isISOControl()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) {
                    append(' ')
                    pendingSpace = false
                }
                append(character)
            }
        }
    }.trim()
    if (normalized.isEmpty()) return null
    return normalized.truncateExternalBluetoothMetadataUtf8()
}

private fun String.truncateExternalBluetoothMetadataUtf8(): String {
    if (toByteArray(Charsets.UTF_8).size <= EXTERNAL_BLUETOOTH_METADATA_MAX_UTF8_BYTES) {
        return this
    }

    val suffix = "…"
    val contentBudget = EXTERNAL_BLUETOOTH_METADATA_MAX_UTF8_BYTES -
        suffix.toByteArray(Charsets.UTF_8).size
    val prefix = buildString {
        var offset = 0
        var usedBytes = 0
        while (offset < this@truncateExternalBluetoothMetadataUtf8.length) {
            val codePoint = Character.codePointAt(
                this@truncateExternalBluetoothMetadataUtf8,
                offset
            )
            val codePointText = String(Character.toChars(codePoint))
            val codePointBytes = codePointText.toByteArray(Charsets.UTF_8).size
            if (usedBytes + codePointBytes > contentBudget) {
                break
            }
            append(codePointText)
            usedBytes += codePointBytes
            offset += Character.charCount(codePoint)
        }
    }.trimEnd()
    return prefix + suffix
}

private fun Long.saturatingAdd(value: Long): Long {
    return if (value > 0L && this > Long.MAX_VALUE - value) {
        Long.MAX_VALUE
    } else {
        this + value
    }
}
