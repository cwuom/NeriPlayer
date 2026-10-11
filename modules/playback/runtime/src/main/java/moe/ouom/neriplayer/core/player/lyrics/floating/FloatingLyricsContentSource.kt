package moe.ouom.neriplayer.core.player.lyrics.floating

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.player.metadata.findCurrentExternalBluetoothLyricIndex
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry

internal data class FloatingLyricsContent(
    val songKey: String? = null,
    val lyric: String? = null,
    val translation: String? = null,
    val nextLyric: String? = null,
    val secondNextLyric: String? = null
)

/** Owns an atomic lyric window. Immutable timeline inputs are cached until the active index changes. */
internal class FloatingLyricsContentSource {
    private val mutableContent = MutableStateFlow(FloatingLyricsContent())
    val content: StateFlow<FloatingLyricsContent> = mutableContent.asStateFlow()
    private var cachedLyrics: List<LyricEntry>? = null
    private var cachedTranslations: Map<Int, LyricEntry>? = null
    private var cachedIndex = Int.MIN_VALUE
    private var cachedSongKey: String? = null

    @Synchronized
    fun update(
        songKey: String,
        lyrics: List<LyricEntry>,
        translations: Map<Int, LyricEntry>,
        positionMs: Long,
        offsetMs: Long
    ) {
        val targetMs = saturatingTargetTime(positionMs, offsetMs)
        val index = findCurrentExternalBluetoothLyricIndex(lyrics, targetMs)
        if (cachedLyrics === lyrics && cachedTranslations === translations &&
            cachedIndex == index && cachedSongKey == songKey
        ) return
        cachedLyrics = lyrics
        cachedTranslations = translations
        cachedIndex = index
        cachedSongKey = songKey
        val current = lyrics.getOrNull(index)?.text.visibleText()
        mutableContent.value = if (current == null) {
            FloatingLyricsContent(songKey = songKey)
        } else {
            val nextIndex = nextVisibleIndex(lyrics, index + 1)
            val secondIndex = nextVisibleIndex(lyrics, nextIndex.takeIf { it >= 0 }?.plus(1) ?: lyrics.size)
            FloatingLyricsContent(
                songKey = songKey,
                lyric = current,
                translation = translations[index]?.text.visibleText(),
                nextLyric = lyrics.getOrNull(nextIndex)?.text.visibleText(),
                secondNextLyric = lyrics.getOrNull(secondIndex)?.text.visibleText()
            )
        }
    }

    @Synchronized
    fun clear() {
        if (cachedLyrics == null && mutableContent.value.songKey == null) return
        cachedLyrics = null
        cachedTranslations = null
        cachedIndex = Int.MIN_VALUE
        cachedSongKey = null
        mutableContent.value = FloatingLyricsContent()
    }

    private fun nextVisibleIndex(lyrics: List<LyricEntry>, start: Int): Int {
        for (index in start until lyrics.size) {
            if (lyrics[index].text.isNotBlank()) return index
        }
        return -1
    }

    private fun String?.visibleText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun saturatingTargetTime(positionMs: Long, offsetMs: Long): Long = when {
        offsetMs > 0 && positionMs > Long.MAX_VALUE - offsetMs -> Long.MAX_VALUE
        offsetMs < 0 && positionMs < Long.MIN_VALUE - offsetMs -> 0L
        else -> (positionMs + offsetMs).coerceAtLeast(0L)
    }
}
