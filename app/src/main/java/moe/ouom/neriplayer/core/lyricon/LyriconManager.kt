package moe.ouom.neriplayer.core.lyricon

import android.content.Context
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.service.addConnectionListener
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger

object LyriconManager {
    private var provider: LyriconProvider? = null
    @Volatile
    private var enabled: Boolean = false

    fun initialize(context: Context) {
        if (provider != null) return
        try {
            provider = LyriconFactory.createProvider(context)
            provider?.register()

            provider?.service?.addConnectionListener {
                onConnected { NPLogger.d("LyriconManager", "Connected") }
                onReconnected { NPLogger.d("LyriconManager", "Reconnected") }
                onDisconnected { NPLogger.d("LyriconManager", "Disconnected") }
                onConnectTimeout { NPLogger.d("LyriconManager", "ConnectTimeout") }
            }
        } catch (e: Exception) {
            NPLogger.e("LyriconManager", "Failed to initialize LyriconProvider", e)
        }
    }

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
        if (!isEnabled) {
            setPlaybackState(false)
        }
    }

    fun isInitialized(): Boolean = provider != null

    fun setPlaybackState(isPlaying: Boolean) {
        if (!enabled && isPlaying) return
        try {
            provider?.player?.setPlaybackState(isPlaying)
        } catch (e: Exception) {
            NPLogger.e("LyriconManager", "setPlaybackState failed", e)
        }
    }

    fun setPosition(positionMs: Long) {
        if (!enabled) return
        try {
            provider?.player?.setPosition(positionMs)
        } catch (e: Exception) {
            // NPLogger.e("LyriconManager", "setPosition failed", e)
        }
    }

    fun updateSong(song: SongItem, lyrics: List<LyricEntry>?, translatedLyrics: List<LyricEntry>?) {
        if (!enabled) return
        try {
            val translationToleranceMs = 1_500L
            val lyriconLyrics = lyrics?.map { entry ->
                val words = if (entry.words != null) {
                    var currentIndex = 0
                    entry.words.mapNotNull { wordTiming ->
                        if (currentIndex + wordTiming.charCount <= entry.text.length) {
                            val wordText = entry.text.substring(currentIndex, currentIndex + wordTiming.charCount)
                            currentIndex += wordTiming.charCount
                            LyricWord(
                                text = wordText,
                                begin = wordTiming.startTimeMs,
                                end = wordTiming.endTimeMs
                            )
                        } else {
                            null
                        }
                    }
                } else null

                RichLyricLine(
                    begin = entry.startTimeMs,
                    end = entry.endTimeMs,
                    text = entry.text,
                    words = words,
                    translation = translatedLyrics
                        ?.firstOrNull { kotlin.math.abs(it.startTimeMs - entry.startTimeMs) <= translationToleranceMs }
                        ?.text
                )
            } ?: emptyList()

            val lyriconSong = Song(
                id = song.id.toString(),
                name = song.name,
                artist = song.artist,
                duration = song.durationMs,
                lyrics = lyriconLyrics
            )

            provider?.player?.setSong(lyriconSong)

            // Set translation display if available
            provider?.player?.setDisplayTranslation(translatedLyrics?.isNotEmpty() == true)

        } catch (e: Exception) {
            NPLogger.e("LyriconManager", "updateSong failed", e)
        }
    }
}
