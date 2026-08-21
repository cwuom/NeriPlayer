package moe.ouom.neriplayer.core.lyricon

import android.content.Context
import android.os.SystemClock
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.service.addConnectionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.matchTranslationsToLineIndices
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.R

object LyriconManager {
    private var provider: LyriconProvider? = null
    @Volatile
    private var enabled: Boolean = false
    private var lastLyricIndex: Int = -1
    private var lyrics: List<LyricEntry>? = null
    private var translatedLyrics: List<LyricEntry>? = null
    private var translationMatchesByIndex: Map<Int, LyricEntry> = emptyMap()
    private var currentSong: SongItem? = null
    private var feedScope: CoroutineScope? = null
    private var feedJob: Job? = null
    @Volatile
    private var positionAnchor: LyriconPositionAnchor? = null
    @Volatile
    private var feedRunning: Boolean = false
    @Volatile
    private var songDurationMs: Long = 0L
    @Volatile
    private var playbackSpeed: Float = 1f

    fun initialize(context: Context) {
        if (provider != null) return
        try {
            if (SuperLyricHelper.isAvailable()) {
                SuperLyricHelper.registerPublisher()
            }
            provider = LyriconFactory.createProvider(
                context = context.applicationContext,
                // Lyricon 的状态栏歌词会优先使用 ProviderLogo。
                // 使用与前台通知相同的纯白透明图标，便于中心服务按状态栏颜色统一着色。
                logo = ProviderLogo.fromDrawable(
                    context.applicationContext,
                    R.drawable.ic_notification_small
                )
            )
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
            // 仅在真正播放时由 setPlaybackState(true) 启动 feed
        } else {
            stopFeedLoop()
            release()
        }
    }

    fun isInitialized(): Boolean = provider != null

    fun release() {
        stopFeedLoop()
        cancelFeedScope()
        clearPositionAnchor()
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
            // 锚点用真实媒体进度
            val mediaPositionMs = mediaLyriconPositionMs(
                positionMs = positionMs,
                durationMs = songDurationMs,
            )
            updatePositionAnchor(mediaPositionMs)
            // 显示层加 lead，补偿词幕延迟
            val displayPositionMs = displayLyriconPositionMs(
                mediaPositionMs = mediaPositionMs,
                durationMs = songDurationMs,
            )
            runCatching { provider?.player?.setPosition(displayPositionMs) }
            updateSuperLyric(displayPositionMs)
        } catch (e: Exception) {
            // NPLogger.e("LyriconManager", "setPosition failed", e)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val normalized = normalizeLyriconPlaybackSpeed(speed)
        if (normalized == playbackSpeed) return
        playbackSpeed = normalized
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        positionAnchor = rebaseLyriconPositionAnchor(
            anchor = positionAnchor,
            newSpeed = normalized,
            nowElapsedRealtimeNanos = nowNanos,
            durationMs = songDurationMs,
        ) ?: return
        // 改速后立刻对齐，显示层仍带 lead
        val mediaSynced = resolveLyriconFeedPosition(positionAnchor, nowNanos) ?: return
        val displaySynced = displayLyriconPositionMs(
            mediaPositionMs = mediaSynced,
            durationMs = songDurationMs,
        )
        runCatching { provider?.player?.setPosition(displaySynced) }
    }

    fun updateSong(song: SongItem, lyrics: List<LyricEntry>?, translatedLyrics: List<LyricEntry>?) {
        if (!enabled) return
        try {
            LyriconManager.lyrics = lyrics
            LyriconManager.translatedLyrics = translatedLyrics
            translationMatchesByIndex = if (lyrics.isNullOrEmpty()) {
                emptyMap()
            } else {
                matchTranslationsToLineIndices(
                    lines = lyrics,
                    translations = translatedLyrics.orEmpty().filter { it.text.isNotBlank() }
                )
            }
            currentSong = song
            songDurationMs = song.durationMs.coerceAtLeast(0L)
            lastLyricIndex = -1
            positionAnchor = positionAnchor?.copy(durationMs = songDurationMs)
            val lyriconLyrics = lyrics?.mapIndexed { index, entry ->
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
                    translation = translationMatchesByIndex[index]?.text
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


            val translation = translationMatchesByIndex[index]

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
        translationMatchesByIndex = emptyMap()
        currentSong = null
        songDurationMs = 0L
    }

    private fun updatePositionAnchor(positionMs: Long) {
        positionAnchor = LyriconPositionAnchor(
            positionMs = positionMs.coerceAtLeast(0L),
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            durationMs = songDurationMs,
            playbackSpeed = playbackSpeed,
        )
    }

    private fun clearPositionAnchor() {
        positionAnchor = null
    }

    private fun startFeedLoop() {
        if (feedJob?.isActive == true) {
            feedRunning = true
            return
        }
        val scope = feedScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { feedScope = it }
        feedRunning = true
        feedJob = scope.launch {
            try {
                while (isActive) {
                    val activeProvider = provider
                    if (activeProvider == null || !enabled) {
                        delay(LYRICON_FEED_INTERVAL_MS)
                        continue
                    }
                    val mediaPositionMs = resolveLyriconFeedPosition(
                        anchor = positionAnchor,
                        nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    )
                    if (mediaPositionMs != null) {
                        val displayPositionMs = displayLyriconPositionMs(
                            mediaPositionMs = mediaPositionMs,
                            durationMs = songDurationMs,
                        )
                        runCatching { activeProvider.player.setPosition(displayPositionMs) }
                    }
                    delay(LYRICON_FEED_INTERVAL_MS)
                }
            } finally {
                feedRunning = false
            }
        }
    }

    private fun stopFeedLoop() {
        feedJob?.cancel()
        feedJob = null
        feedRunning = false
        // 保留锚点，避免暂停/seek/恢复时跳到曲末
    }

    private fun cancelFeedScope() {
        feedScope?.cancel()
        feedScope = null
    }

    private fun ensurePublisherRegistered() {
        runCatching {
            if (SuperLyricHelper.isAvailable() && !SuperLyricHelper.isPublisherRegistered()) {
                SuperLyricHelper.registerPublisher()
            }
        }
    }
}
