package moe.ouom.neriplayer.core.lyricon

import android.content.Context
import android.os.SystemClock
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.service.addConnectionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger

private const val LYRICON_FEED_INTERVAL_MS = 200L

object LyriconManager {
    private var provider: LyriconProvider? = null
    @Volatile
    private var enabled: Boolean = false
    private var lastLyricIndex: Int = -1
    private var lyrics: List<LyricEntry>? = null
    private var translatedLyrics: List<LyricEntry>? = null
    private var currentSong: SongItem? = null
    private var feedScope: CoroutineScope? = null
    private var feedJob: Job? = null
    private var lastKnownPositionMs: Long = 0L
    private var lastKnownElapsedRealtimeMs: Long = 0L
    private var songDurationMs: Long = 0L

    fun initialize(context: Context) {
        if (provider != null) return
        try {
            if (SuperLyricHelper.isAvailable()) {
                SuperLyricHelper.registerPublisher()
            }
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
        if (isEnabled) {
            ensurePublisherRegistered()
            startFeedLoop()
        } else {
            stopFeedLoop()
            release()
        }
    }

    fun isInitialized(): Boolean = provider != null

    fun release() {
        stopFeedLoop()
        resetSuperLyricState()
        enabled = false
        runCatching { provider?.player?.setPlaybackState(false) }
        runCatching { provider?.unregister() }
        runCatching { provider?.destroy() }
        provider = null
        runCatching {
            if (SuperLyricHelper.isAvailable() && SuperLyricHelper.isPublisherRegistered()) {
                SuperLyricHelper.unregisterPublisher()
            }
        }
    }

    fun setPlaybackState(isPlaying: Boolean) {
        if (!enabled && isPlaying) return
        try {
            provider?.player?.setPlaybackState(isPlaying)
            if (isPlaying) {
                startFeedLoop()
            } else {
                stopFeedLoop()
            }
        } catch (e: Exception) {
            NPLogger.e("LyriconManager", "setPlaybackState failed", e)
        }
    }

    fun setPosition(positionMs: Long) {
        if (!enabled) return
        try {
            lastKnownPositionMs = positionMs
            lastKnownElapsedRealtimeMs = SystemClock.elapsedRealtime()
            updateSuperLyric(positionMs)
        } catch (e: Exception) {
            // NPLogger.e("LyriconManager", "setPosition failed", e)
        }
    }

    fun updateSong(song: SongItem, lyrics: List<LyricEntry>?, translatedLyrics: List<LyricEntry>?) {
        if (!enabled) return
        try {
            val translationToleranceMs = 1_500L
            LyriconManager.lyrics = lyrics
            LyriconManager.translatedLyrics = translatedLyrics
            currentSong = song
            songDurationMs = song.durationMs
            lastLyricIndex = -1
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

            // 有翻译时才让第三方歌词组件展示翻译
            provider?.player?.setDisplayTranslation(translatedLyrics?.isNotEmpty() == true)

        } catch (e: Exception) {
            NPLogger.e("LyriconManager", "updateSong failed", e)
        }
    }

    private fun updateSuperLyric(positionMs: Long) {
        try {
            if (!SuperLyricHelper.isAvailable()) return

            val lyricList = lyrics ?: return
            val song = currentSong ?: return

            val index = lyricList.indexOfLast {
                it.startTimeMs <= positionMs
            }

            if (index < 0) return

            // 避免同一句歌词被进度轮询重复发送
            if (index == lastLyricIndex) return

            lastLyricIndex = index

            val line = lyricList.getOrNull(index) ?: return


            val translation = translatedLyrics
                ?.firstOrNull {
                    kotlin.math.abs(
                        it.startTimeMs - line.startTimeMs
                    ) <= 1500
                }

            var currentIndex = 0
            val words = line.words
                ?.mapNotNull { wordTiming ->
                    if (currentIndex + wordTiming.charCount <= line.text.length) {
                        val wordText =
                            line.text.substring(currentIndex, currentIndex + wordTiming.charCount)
                        currentIndex += wordTiming.charCount
                        SuperLyricWord(
                            wordText,
                            wordTiming.startTimeMs,
                            wordTiming.endTimeMs
                        )
                    } else {
                        null
                    }
                }
                ?: emptyList()


            val data = SuperLyricData()
                .setTitle(song.name)
                .setArtist(song.artist)
                .setAlbum(song.album)
                .setLyric(
                    SuperLyricLine(
                        line.text,
                        words.toTypedArray(),
                        line.startTimeMs,
                        line.endTimeMs
                    )
                )
                .setTranslation(
                    SuperLyricLine(
                        translation?.text ?: ""
                    )
                )


            SuperLyricHelper.sendLyric(data)
        } catch (e: Exception) {
            NPLogger.e("LyriconManager", "updateSuperLyric failed", e)
        }
    }

    private fun resetSuperLyricState() {
        lastLyricIndex = -1
        lyrics = null
        translatedLyrics = null
        currentSong = null
        songDurationMs = 0L
        lastKnownPositionMs = 0L
        lastKnownElapsedRealtimeMs = 0L
    }

    private fun startFeedLoop() {
        if (feedJob?.isActive == true) return
        val scope = feedScope ?: CoroutineScope(SupervisorJob()).also { feedScope = it }
        feedJob = scope.launch {
            while (isActive) {
                val provider = provider
                if (provider == null || !enabled) {
                    delay(LYRICON_FEED_INTERVAL_MS)
                    continue
                }
                val positionMs = resolveFeedPosition()
                runCatching { provider.player.setPosition(positionMs) }
                delay(LYRICON_FEED_INTERVAL_MS)
            }
        }
    }

    private fun resolveFeedPosition(): Long {
        val elapsed = SystemClock.elapsedRealtime() - lastKnownElapsedRealtimeMs
        val positionMs = lastKnownPositionMs + elapsed
        return if (songDurationMs > 0L) {
            positionMs.coerceAtMost(songDurationMs)
        } else {
            positionMs
        }
    }

    private fun stopFeedLoop() {
        feedJob?.cancel()
        feedJob = null
        lastKnownPositionMs = 0L
        lastKnownElapsedRealtimeMs = 0L
    }

    private fun ensurePublisherRegistered() {
        runCatching {
            if (SuperLyricHelper.isAvailable() && !SuperLyricHelper.isPublisherRegistered()) {
                SuperLyricHelper.registerPublisher()
            }
        }
    }
}
