package moe.ouom.neriplayer.core.player.lan

import android.os.Looper
import java.io.File
import moe.ouom.neriplayer.data.ltw.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.core.player.url.resolveShareableListenTogetherStreamUrls
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate
import moe.ouom.neriplayer.lanltw.audio.LanWebAudioEngine
import moe.ouom.neriplayer.lanltw.playback.LanScheduledAudioPort
import moe.ouom.neriplayer.lanltw.core.LanNtpEstimator
import moe.ouom.neriplayer.data.model.lan.LanMessage
import androidx.media3.common.Player
import kotlinx.coroutines.*
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.ltw.PlayerListenTogetherSongMapper
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.playback.playImpl
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.lanltw.playback.LanPlaybackPort

class LanPlayerAdapter(private val hosting: () -> Boolean) : LanPlaybackPort, LanScheduledAudioPort {
    private val mediaVolume by lazy { LanMediaVolume(PlayerManager.application) }
    private var savedSpeed = 1f
    private var begun = false
    private var engine: LanWebAudioEngine? = null
    private var sourceKey: String? = null
    private var sharedUrls = emptyList<String>()
    override var preparedContentDigest: String? = null; private set
    private var preparedKey: String? = null
    private var currentFile: File? = null
    private val files = LanAudioFileLoader()
    private val presentation = LanAudioPresentation()
    private var audioFailure: (String) -> Unit = {}
    override var preparationError: String = "SOURCE"; private set
    override fun audioDiagnostics(): String = engine?.diagnostics() ?: "[]"
    override val outputLatencyMs: Double get() = engine?.state?.outputLatencyMs ?: 0.0
    override suspend fun initializeAudio() { checkNotNull(engine).initialize() }
    override suspend fun resetAudioClock() { checkNotNull(engine).resetClock() }
    override suspend fun receiveProbe(message: LanMessage, receivedAt: Double): LanNtpEstimator.Estimate? = checkNotNull(engine).receiveProbe(message, receivedAt)
    override suspend fun scheduleAudio(message: LanMessage, offsetMs: Double, nudgeMs: Int): Boolean = checkNotNull(engine).schedule(message, offsetMs, nudgeMs)
    override fun cancelAudioSchedule() { engine?.pause() }
    override fun changeAudioNudge(value: Int, delta: Int) { engine?.nudge(value, delta) }
    override fun watchAudioFailure(handler: (String) -> Unit) { audioFailure = handler }

    private fun main() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun currentTrack(): ListenTogetherTrack? = LanPlaybackBinding.track(PlayerManager.currentSongFlow.value)
    override fun positionSeconds(): Double { main(); return engine?.state?.positionSeconds ?: (PlayerManager.playbackPositionFlow.value / 1000.0) }
    override fun isPlaying(): Boolean = engine?.state?.playing == true
    override fun supported(): Boolean { main(); PlayerManager.ensureInitialized(); return PlayerManager.isPlayerInitialized() && !PlayerManager.usbExclusivePlaybackEnabled }
    override suspend fun resolveSharedAudioSources(track: ListenTogetherTrack): List<String> {
        main()
        if (sourceKey == track.stableKey && sharedUrls.isNotEmpty() && ready(track)) return sharedUrls
        val queue = LanPlaybackBinding.offeredQueue ?: PlayerManager.currentQueueFlow.value
        val song = queue.find { LanPlaybackBinding.track(it)?.stableKey == track.stableKey }
            ?: with(PlayerListenTogetherSongMapper) { track.copy(streamUrl = null, streamUrls = emptyList()).toSongItem() }
        val result = PlayerManager.resolveShareableListenTogetherStreamUrls(song)
        if (result.isPreviewOnly) error("FULL_AUDIO_REQUIRED")
        return trustedListenTogetherStreamUrls(track.channelId, result.streamUrls)
    }
    override fun sharedAudioSources(track: ListenTogetherTrack, urls: List<String>) {
        main()
        val clean = trustedListenTogetherStreamUrls(track.channelId, urls)
        if (sourceKey != track.stableKey || sharedUrls != clean) {
            preparedKey = null; engine?.invalidateMedia()
        }
        sourceKey = track.stableKey; sharedUrls = clean
    }
    override suspend fun prepare(track: ListenTogetherTrack, positionSeconds: Double): Boolean {
        main()
        if (!supported()) return false
        if (ready(track)) return true
        preparedKey = null; preparedContentDigest = null; engine?.invalidateMedia(); engine?.pause(); pauseNative()
        if (sourceKey != track.stableKey || sharedUrls.isEmpty()) { preparationError = "FULL_AUDIO_REQUIRED"; return false }
        // Resolve metadata only. Listeners must not ask their own account for a possibly truncated preview URL.
        val offered = LanPlaybackBinding.offeredQueue
        val existing = PlayerManager.currentQueueFlow.value
        val queue = offered?.takeIf { list -> list.any { LanPlaybackBinding.track(it)?.stableKey == track.stableKey } }
            ?: existing.takeIf { list -> list.any { LanPlaybackBinding.track(it)?.stableKey == track.stableKey } }
            ?: listOf(with(PlayerListenTogetherSongMapper) { track.copy(streamUrl = null, streamUrls = emptyList(), mediaUri = null).toSongItem() })
        val index = queue.indexOfFirst { LanPlaybackBinding.track(it)?.stableKey == track.stableKey }
        if (index < 0) { preparationError = "SOURCE"; return false }
        PlayerManager.applyRemoteQueueUpdate(queue, index)
        PlayerManager._currentMediaUrl.value = null
        PlayerManager._currentPlaybackAudioInfo.value = moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo(lanAudioSource(track.channelId))
        PlayerManager.setCurrentSongForPlayback(queue[index])
        PlayerManager.player.stop(); PlayerManager.player.clearMediaItems()
        PlayerManager.updateResumePlaybackRequested(false)
        PlayerManager.playbackProgressOwner.setDuration(track.durationMs)
        for (url in sharedUrls) {
            try {
                return withTimeout(90_000) {
                    files.clear(currentFile); currentFile = null
                    val downloaded = files.download(url, checkNotNull(engine).state.sampleRate, track.durationMs)
                    val file = downloaded.file
                    currentFile = file; ensureActive()
                    preparedContentDigest = withContext(Dispatchers.IO) {
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input -> val bytes = ByteArray(32768); while (true) { ensureActive(); val read = input.read(bytes); if (read < 0) break; digest.update(bytes, 0, read) } }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    val ready = checkNotNull(engine).load(track.stableKey, file, positionSeconds, track.durationMs)
                    ensureActive()
                    if (ready) {
                        preparedKey = track.stableKey
                        PlayerManager._currentPlaybackAudioInfo.value = lanAudioMetadata(track.channelId, url, downloaded.format) {
                            PlayerManager.application.getString(it)
                        }
                    }
                    ready
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                preparationError = error.message?.takeIf { it in setOf("MEMORY_LIMIT", "AUDIO_FORMAT", "AUDIO_SUSPENDED", "AUDIO_DURATION", "FULL_AUDIO_REQUIRED") } ?: "SOURCE"
            }
        }
        return false
    }
    override fun ready(track: ListenTogetherTrack): Boolean = preparedKey == track.stableKey && engine?.ready(track.stableKey) == true
    override fun play() { error("Web Audio requires an absolute scheduled action") }
    override fun pause() { main(); engine?.pause(); pauseNative() }
    private fun pauseNative() {
        if (PlayerManager.isPlayerInitialized()) {
            PlayerManager.pauseImpl(commandSource = PlaybackCommandSource.REMOTE_SYNC, allowFadeOut = false, debugReason = "lan_audio_owner")
            PlayerManager.stopProgressUpdates()
        }
    }
    override fun seek(positionSeconds: Double) { main(); engine?.position(positionSeconds) }
    override suspend fun setMediaVolume(value: Float) { main(); mediaVolume.apply(value) }
    override fun mediaVolume(): Float { main(); return mediaVolume.read() }
    override fun watchMediaVolume(changed: (Float) -> Unit) { main(); mediaVolume.start(changed) }
    override fun begin(onCommand: (String, ListenTogetherTrack?, Double) -> Unit, onStop: () -> Unit) {
        main(); if (!supported()) return
        moe.ouom.neriplayer.core.player.service.AudioPlayerService.startSyncService(PlayerManager.application, "lan_listen", forceForeground = true)
        files.clearAbandoned()
        begun = true; savedSpeed = PlayerManager.player.playbackParameters.speed
        PlayerManager.setPlaybackSpeed(1f, persist = false)
        pauseNative()
        LanPlaybackBinding.externalAudio = true
        LanPlaybackBinding.interruptAudio = { engine?.pause(); audioFailure("AUDIO_SUSPENDED") }
        engine = LanWebAudioEngine(PlayerManager.application, presentation::update,
            onEnded = { if (begun) LanPlaybackBinding.ended() },
            onFailure = { code -> if (begun) audioFailure(code) })
        LanPlaybackBinding.command = onCommand; LanPlaybackBinding.stopped = onStop; LanPlaybackBinding.isHost = hosting
    }
    override fun step(direction: Int) { main(); LanPlaybackBinding.step(direction, PlaybackCommandSource.LOCAL) }
    override fun end() {
        main(); if (!begun) return
        begun = false; pause(); engine?.destroy(); engine = null; preparedKey = null; sourceKey = null; sharedUrls = emptyList()
        files.clear(currentFile); currentFile = null; mediaVolume.stop(); LanPlaybackBinding.clear()
        PlayerManager._isPlayingFlow.value = false; PlayerManager._playbackControlPlayingFlow.value = false
        PlayerManager._playWhenReadyFlow.value = false
        if (PlayerManager.isPlayerInitialized()) { PlayerManager.setPlaybackSpeed(savedSpeed, persist = false) }
    }
}
