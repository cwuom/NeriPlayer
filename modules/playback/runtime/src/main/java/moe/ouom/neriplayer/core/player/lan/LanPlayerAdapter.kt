@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.lan

import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.Player
import java.io.File
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lyrics.syncExternalBluetoothLyrics
import moe.ouom.neriplayer.core.player.ltw.PlayerListenTogetherSongMapper
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation
import moe.ouom.neriplayer.data.model.lan.LanStreamResource
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.lanltw.core.LanNtpEstimator
import moe.ouom.neriplayer.lanltw.media.LanMediaDownload
import moe.ouom.neriplayer.lanltw.media.LanMediaServer
import moe.ouom.neriplayer.lanltw.media.LanPresentationCodec
import moe.ouom.neriplayer.lanltw.playback.LanPlaybackPort
import moe.ouom.neriplayer.lanltw.playback.LanPreparedSource
import moe.ouom.neriplayer.lanltw.playback.LanScheduledAudioPort
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class LanPlayerAdapter(private val hosting: () -> Boolean) : LanPlaybackPort, LanScheduledAudioPort {
    private data class Incoming(val track: ListenTogetherTrack, val resource: LanStreamResource)
    private data class Endpoint(val control: HttpUrl, val port: Int, val token: String) {
        fun mediaUrl(id: String): HttpUrl = control.newBuilder().scheme("http").port(port)
            .username("").password("").encodedPath("/media").addPathSegment(id).query(null).fragment(null).build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaVolume by lazy { LanMediaVolume(PlayerManager.application) }
    private val coverDownloader by lazy { LanMediaDownload(OkHttpClient()) }
    private val guestCover = LanGuestCoverReceiver(scope)
    private val publisher = LanHostStreamPublisher()
    private val presentation = LanAudioPresentation()
    private var savedSpeed = 1f
    private var originalPlayback: LanGuestPlaybackSnapshot? = null
    private var engine: LanMedia3AudioEngine? = null
    private var ntp: LanNtpEstimator? = null
    private var incoming: Incoming? = null
    private var endpoint: Endpoint? = null
    private var audioFailure: (String) -> Unit = {}
    override var preparationError: String = "SOURCE"
        private set
    override val preparedMediaId: String? get() = engine?.let { audio -> audio.mediaId?.takeIf(audio::ready) }
    override val outputLatencyMs: Double get() = 0.0

    override fun audioDiagnostics(): String = engine?.diagnostics() ?: "[]"
    override suspend fun initializeAudio() { checkNotNull(engine) }
    override suspend fun resetAudioClock() { checkNotNull(ntp).reset() }
    override suspend fun receiveProbe(message: LanMessage, receivedAt: Double): LanNtpEstimator.Estimate? =
        checkNotNull(ntp).response(message, receivedAt)

    override suspend fun scheduleAudio(message: LanMessage, offsetMs: Double, nudgeMs: Int): Boolean {
        preparationError = "SOURCE"
        val action = message.scheduledAction ?: return false
        val source = incoming ?: return false
        val audio = engine ?: return false
        val deadline = message.serverTimeToExecute ?: return false
        if (action.audioSource != source.track.stableKey || audio.mediaId != source.resource.mediaId) return false
        return audio.schedule(action, deadline, offsetMs, nudgeMs)
    }

    override fun cancelAudioSchedule() { engine?.cancelSchedule() }
    override fun changeAudioNudge(value: Int, delta: Int) {
        val audio = engine ?: return
        if (audio.playing && audio.seekable) audio.seek((audio.positionSeconds * 1000).roundToLong() + delta)
    }

    override fun watchAudioFailure(handler: (String) -> Unit) { audioFailure = handler }

    private fun main() { check(Looper.myLooper() == Looper.getMainLooper()) }
    override fun currentTrack(): ListenTogetherTrack? = LanPlaybackBinding.track(PlayerManager.currentSongFlow.value)
    override fun positionSeconds(): Double = engine?.positionSeconds ?: PlayerManager.playbackPositionFlow.value / 1000.0
    override fun isPlaying(): Boolean = engine?.playing ?:
        (PlayerManager.isPlayerInitialized() && PlayerManager.player.isPlaying)
    override fun supported(): Boolean {
        main()
        PlayerManager.ensureInitialized()
        return PlayerManager.initialized && PlayerManager.isPlayerInitialized() && !PlayerManager.usbExclusivePlaybackEnabled
    }

    override fun hostMediaServer(server: LanMediaServer) { publisher.server = server }

    override fun mediaEndpoint(controlUrl: String, port: Int, token: String) {
        val next = Endpoint(controlUrl.toHttpUrl(), port, token)
        if (endpoint != next) {
            guestCover.cancelDownload()
            engine?.invalidate()
        }
        endpoint = next
    }

    override fun sharedStreamResource(track: ListenTogetherTrack, resource: LanStreamResource?) {
        main()
        val previous = incoming
        if (previous?.track?.stableKey != track.stableKey || previous.resource.mediaId != resource?.mediaId) {
            LanSharedPresentationStore.clear()
            clearCover()
            engine?.invalidate()
        }
        incoming = resource?.let { Incoming(track, it) }
    }

    override suspend fun prepareSharedSource(track: ListenTogetherTrack, refresh: Boolean): LanPreparedSource =
        publisher.offer(track, hostSong(track), refresh)

    override fun sharedPresentationUpdates(track: ListenTogetherTrack, mediaId: String): Flow<LanSharedPresentation> =
        publisher.presentationUpdates(track, mediaId, hostSong(track))

    private fun hostSong(track: ListenTogetherTrack): SongItem =
        matchingSong(LanPlaybackBinding.offeredQueue, track)
            ?: matchingSong(PlayerManager.currentQueueFlow.value, track)
            ?: sanitizedSong(track)

    private fun matchingSong(songs: List<SongItem>?, track: ListenTogetherTrack): SongItem? =
        songs?.firstOrNull { LanPlaybackBinding.track(it)?.stableKey == track.stableKey }

    override fun sharedPresentation(track: ListenTogetherTrack, presentation: LanSharedPresentation?) {
        if (presentation == null || incoming?.track?.stableKey != track.stableKey) return
        val previous = LanSharedPresentationStore.state.value?.takeIf { it.trackKey == track.stableKey }?.presentation
        LanSharedPresentationStore.publish(track.stableKey, presentation)
        val song = PlayerManager.currentSongFlow.value ?: return
        if (LanPlaybackBinding.track(song)?.stableKey == track.stableKey) {
            if (previous?.copy(cover = presentation.cover) != presentation) applyPresentation(song, presentation)
            else applyGuestCover(song, presentation)
        }
    }

    override suspend fun prepare(track: ListenTogetherTrack, positionSeconds: Double): Boolean {
        main()
        preparationError = "SOURCE"
        val audio = engine ?: return false
        val source = incoming?.takeIf { it.track.stableKey == track.stableKey } ?: return false
        val mediaEndpoint = endpoint ?: return false
        if (PlayerManager.usbExclusivePlaybackEnabled) { preparationError = "OUTPUT"; return false }
        val positionMs = (positionSeconds * 1000).roundToLong().coerceAtLeast(0)
        if (audio.mediaId != source.resource.mediaId) publishRoomSong(track, positionMs)
        if (!audio.prepare(source.resource.mediaId, mediaEndpoint.mediaUrl(source.resource.mediaId),
                mediaEndpoint.token, source.resource.mimeType, positionMs)) return false
        preparationError = when {
            isLanDefinitePreview(track.channelId, track.durationMs, audio.seekableDurationMs) -> "FULL_AUDIO_REQUIRED"
            positionMs > 0 && !audio.seekable -> "AUDIO_NOT_SEEKABLE"
            else -> return true
        }
        audio.invalidate()
        PlayerManager._playerPlaybackStateFlow.value = Player.STATE_IDLE
        PlayerManager.playbackProgressOwner.setDuration(track.durationMs)
        return false
    }

    private fun publishRoomSong(track: ListenTogetherTrack, positionMs: Long) {
        val offered = LanPlaybackBinding.offeredQueue
        val existing = PlayerManager.currentQueueFlow.value
        val queue = if (hosting()) offered.takeIf { matchingSong(it, track) != null }
            ?: existing.takeIf { matchingSong(it, track) != null } ?: listOf(sanitizedSong(track))
            else listOf(sanitizedSong(track))
        val index = queue.indexOfFirst { LanPlaybackBinding.track(it)?.stableKey == track.stableKey }.coerceAtLeast(0)
        val song = queue[index]
        PlayerManager.player.stop()
        PlayerManager.player.clearMediaItems()
        PlayerManager._currentMediaUrl.value = null
        PlayerManager.currentMediaUrlResolvedAtMs = 0
        PlayerManager._currentPlaybackAudioInfo.value = PlaybackAudioInfo(lanAudioSource(track.channelId))
        PlayerManager.setCurrentSongForPlayback(song)
        PlayerManager._playbackPositionMs.value = positionMs
        PlayerManager.applyRemoteQueueUpdate(queue, index)
        LanSharedPresentationStore.forSong(song)?.let { applyPresentation(song, it) }
    }

    private fun sanitizedSong(track: ListenTogetherTrack): SongItem {
        if (track.channelId == "local" || track.channelId == ServerSongRef.CHANNEL) return lanStreamSessionSong(track)
        return with(PlayerListenTogetherSongMapper) {
            track.copy(streamUrl = null, streamUrls = emptyList(), mediaUri = null).toSongItem()
        }
    }

    private fun applyPresentation(song: SongItem, shared: LanSharedPresentation) {
        val updated = if (isTemporary(song)) PlayerManager.updateQueuedSong(song) { item ->
            item.copy(name = shared.name, artist = shared.artist, album = shared.album,
                userLyricOffsetMs = shared.lyricOffsetMs).takeUnless { it == item }
        } else null
        if (updated != null) PlayerManager.setCurrentSongForPlayback(updated)
        else {
            PlayerManager.syncExternalBluetoothLyrics(song)
            PlayerManager.syncLyriconSong(song)
        }
        applyGuestCover(updated ?: song, shared)
    }

    private fun isTemporary(song: SongItem): Boolean =
        !hosting() || song.sourceStableKey?.startsWith("lan-session:") == true

    private fun applyGuestCover(song: SongItem, shared: LanSharedPresentation) {
        if (!isTemporary(song)) return
        val cover = shared.cover ?: run { clearCover(); return }
        val source = incoming ?: return
        val mediaEndpoint = endpoint ?: return
        val directory = File(PlayerManager.application.cacheDir, "lan-presentation")
        guestCover.show(cover, fetch = {
            coverDownloader.fetchResource(mediaEndpoint.control, mediaEndpoint.port, mediaEndpoint.token,
                cover, directory, LanPresentationCodec.MAX_BYTES)
        }) { file ->
            val current = PlayerManager.currentSongFlow.value ?: return@show false
            if (!isCurrentPresentation(source, current) || endpoint != mediaEndpoint) return@show false
            // 会话封面优先于成员本机保存的同曲图片
            updateSongCover(current, file.absolutePath)
            true
        }
    }

    private fun isCurrentPresentation(source: Incoming, song: SongItem?): Boolean =
        incoming?.resource?.mediaId == source.resource.mediaId && LanPlaybackBinding.track(song)?.stableKey == source.track.stableKey

    private fun clearCover() {
        val file = guestCover.clear() ?: return
        val song = PlayerManager.currentSongFlow.value ?: return
        if (song.customCoverUrl == file.absolutePath) updateSongCover(song, null)
    }

    private fun updateSongCover(song: SongItem, path: String?) {
        val updated = PlayerManager.updateQueuedSong(song) { item ->
            item.copy(coverUrl = path, customCoverUrl = path).takeUnless { it == item }
        }
        if (updated != null) PlayerManager.setCurrentSongForPlayback(updated, syncLyricon = false)
    }

    private fun updateAudioFormat(format: Format) {
        val source = incoming ?: return
        PlayerManager._currentPlaybackAudioInfo.value = lanStreamingAudioMetadata(source.track.channelId,
            source.resource.qualityKey, format) { PlayerManager.application.getString(it) }
    }

    override fun ready(track: ListenTogetherTrack): Boolean = incoming?.takeIf { it.track.stableKey == track.stableKey }
        ?.resource?.mediaId?.let { engine?.ready(it) } == true

    override fun play() { engine?.play() }
    override fun pause() { main(); cancelAudioSchedule() }
    override fun seek(positionSeconds: Double) {
        main()
        if (engine?.seek((positionSeconds * 1000).roundToLong()) == false) audioFailure("AUDIO_NOT_SEEKABLE")
    }
    override suspend fun setMediaVolume(value: Float) { main(); mediaVolume.apply(value) }
    override fun mediaVolume(): Float { main(); return mediaVolume.read() }
    override fun watchMediaVolume(changed: (Float) -> Unit) { main(); mediaVolume.start(changed) }

    override fun begin(onCommand: (String, ListenTogetherTrack?, Double) -> Unit, onStop: () -> Unit) {
        main()
        check(supported()) { "OUTPUT" }
        check(AudioPlayerService.startSyncService(PlayerManager.application, "lan_listen", forceForeground = true)) {
            "SERVICE_STOPPED"
        }
        val snapshot = LanGuestPlaybackSnapshot.capture()
        originalPlayback = snapshot
        if (hosting()) publisher.captureCurrentSource()
        savedSpeed = PlayerManager.player.playbackParameters.speed
        PlayerManager.setPlaybackSpeed(1f, persist = false)
        PlayerManager.pauseImpl(commandSource = PlaybackCommandSource.REMOTE_SYNC, allowFadeOut = false, debugReason = "lan_audio_owner")
        PlayerManager.stopProgressUpdates()
        if (!hosting()) {
            PlayerManager.scheduleStatePersist(positionMs = snapshot.positionMs, shouldResumePlayback = false, debounceMs = 0)
            LanPlaybackBinding.guestPlayback = snapshot
            PlayerManager.localPlaylistPlaybackSource = null
        }
        LanPlaybackBinding.externalAudio = true
        LanPlaybackBinding.interruptAudio = { reason -> audioFailure(reason) }
        engine = LanMedia3AudioEngine(PlayerManager.application, !PlayerManager.allowMixedPlaybackEnabled,
            presentation::update, ::updateAudioFormat, { LanPlaybackBinding.ended() }, { audioFailure("SOURCE") },
            { audioFailure("AUDIO_SUSPENDED") }, { preparationError = "AUDIO_NOT_SEEKABLE" })
        ntp = LanNtpEstimator()
        LanPlaybackBinding.command = onCommand
        LanPlaybackBinding.stopped = onStop
        LanPlaybackBinding.isHost = hosting
        if (hosting()) AudioPlayerService.publishCurrentLanArtwork()
    }

    override fun step(direction: Int) { main(); LanPlaybackBinding.step(direction, PlaybackCommandSource.LOCAL) }

    override fun end() {
        main()
        val beforeSession = originalPlayback ?: return
        originalPlayback = null
        PlayerManager.syncPlaybackStatsPlayingState(playing = false, reason = "lan_session_end")
        engine?.release(); engine = null
        ntp?.let { obsolete -> scope.launch { obsolete.close() } }; ntp = null
        endpoint = null; incoming = null
        publisher.clear()
        clearCover()
        mediaVolume.stop()
        val song = PlayerManager.currentSongFlow.value
        val shared = LanSharedPresentationStore.forSong(song) != null
        LanSharedPresentationStore.clear()
        val temporaryHostSong = song?.sourceStableKey?.startsWith("lan-session:") == true
        val restore = LanPlaybackBinding.guestPlayback ?: beforeSession.takeIf { temporaryHostSong }
        restore?.restore()
        LanPlaybackBinding.clear()
        LanPlaybackArtwork.update(null, null)
        PlayerManager._isPlayingFlow.value = false
        PlayerManager._playbackControlPlayingFlow.value = false
        PlayerManager._playWhenReadyFlow.value = false
        if (PlayerManager.initialized) {
            PlayerManager._playerPlaybackStateFlow.value = PlayerManager.player.playbackState
            PlayerManager.setPlaybackSpeed(savedSpeed, persist = false)
            if (shared && song === PlayerManager.currentSongFlow.value) {
                PlayerManager.syncExternalBluetoothLyrics(song)
                PlayerManager.syncLyriconSong(song)
            }
            PlayerManager.scheduleStatePersist(shouldResumePlayback = false, debounceMs = 0)
        }
    }
}
