package moe.ouom.neriplayer.lanltw.playback

import kotlinx.coroutines.*
import moe.ouom.neriplayer.data.model.lan.LanFileResource
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation
import moe.ouom.neriplayer.data.model.lan.LanStreamResource
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.lanltw.core.LAN_AUDIO_NOT_SEEKABLE
import moe.ouom.neriplayer.lanltw.core.LanClock
import moe.ouom.neriplayer.lanltw.core.LanSchedule

class LanPlaybackScheduler(
    private val scope: CoroutineScope, private val port: LanPlaybackPort, private val clock: LanClock,
    private val offset: () -> Double, private val nudge: () -> Int, private val synced: () -> Boolean,
    private val send: (LanMessage) -> Unit, private val error: (String) -> Unit, private val onReady: () -> Unit = {},
    private val resolvePresentation: suspend (LanFileResource) -> LanSharedPresentation? = { null },
    private val onUnseekableAction: () -> Unit = { error(LAN_AUDIO_NOT_SEEKABLE) }
) {
    private val scheduledAudio = port as? LanScheduledAudioPort
    private var action: Job? = null
    private var loading: Job? = null
    private var retry: Job? = null
    private var recoveryAttempts = 0
    private var presentationUpdate: Job? = null
    private var revision = -1L
    private var gainJob: Job? = null
    private var lastGain = -1f
    private var pending: LanMessage? = null
    var mediaId: String? = null
        private set
    val playbackRevision: Long get() = revision
    val playScheduled: Boolean get() {
        val plan = pending ?: return false
        return plan.scheduledAction?.type == "PLAY" &&
            (action?.isActive == true || (plan.serverTimeToExecute ?: 0.0) > clock.nowMs() + offset())
    }
    private var trackKey: String? = null
    private var requestedPresentation: LanFileResource? = null
    private var appliedPresentation: LanFileResource? = null
    private fun accept(m: LanMessage): Boolean {
        val incoming = m.revision ?: return false
        if (incoming < revision) return false
        if (incoming > revision) recoveryAttempts = 0
        revision = incoming
        return true
    }
    fun load(m: LanMessage) {
        val track = m.track ?: return
        val resource = m.streamResource?.takeIf(LanStreamResource::valid) ?: return error("SOURCE")
        if (m.revision == revision && loading?.isActive == true) return
        if (!accept(m)) return
        action?.cancel(); scheduledAudio?.cancelAudioSchedule(); pending = null; loading?.cancel(); retry?.cancel()
        bindStream(track, resource)
        refreshPresentation(track, m.presentationResource ?: resource.presentation)
        loading = scope.launch {
            val ready = port.prepare(track, m.trackTimeSeconds ?: 0.0)
            ensureActive()
            if (!ready) {
                error(port.preparationError)
                send(LanMessage("LAN_LOAD_FAILED", loadGeneration = m.loadGeneration, mediaId = resource.mediaId, code = port.preparationError))
                return@launch
            }
            if (!matchesStream(resource)) {
                send(LanMessage("LAN_LOAD_FAILED", loadGeneration = m.loadGeneration, mediaId = resource.mediaId, code = "AUDIO_CONTENT"))
                return@launch
            }
            withTimeoutOrNull(30_000) { while (!synced()) delay(25) }
            if (!synced()) { recover(); return@launch }
            send(LanMessage("AUDIO_SOURCE_READY", loadGeneration = m.loadGeneration, mediaId = resource.mediaId))
        }
    }
    fun schedule(m: LanMessage) {
        val command = m.scheduledAction ?: return
        val track = command.track ?: return
        val at = m.serverTimeToExecute ?: return
        val resource = m.streamResource?.takeIf(LanStreamResource::valid) ?: return error("SOURCE")
        if (!accept(m)) return
        action?.cancel(); retry?.cancel(); pending = m
        bindStream(track, resource)
        refreshPresentation(track, m.presentationResource ?: resource.presentation)
        action = scope.launch {
            if (!synced()) { recover(); return@launch }
            if (scheduledAudio != null) {
                try {
                    scheduleAudio(scheduledAudio, m, track, resource)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { recover() }
                return@launch
            }
            if (command.type == "PAUSE") {
                loading?.cancel()
                if (!prepareAction(track, command.trackTimeSeconds)) { handleActionFailure(::recover); return@launch }
                if (!matchesStream(resource)) return@launch
                delay(LanSchedule.pauseDelay(clock.nowMs(), offset(), nudge(), port.schedulingLeadMs, at))
                ensureActive(); port.pause(); port.seek(command.trackTimeSeconds); pending = null; playbackReady()
                return@launch
            }
            loading?.join()
            if (!prepareAction(track, command.trackTimeSeconds)) { handleActionFailure(::recover); return@launch }
            if (!matchesStream(resource)) return@launch
            val plan = LanSchedule.play(clock.nowMs(), offset(), nudge(), port.schedulingLeadMs, at, command.trackTimeSeconds)
            val deadline = clock.nowMs() + plan.delayMs
            port.pause(); port.seek(plan.positionSeconds)
            while (!port.ready(track) && clock.nowMs() < deadline) { delay(10) }
            delay((deadline - clock.nowMs()).coerceAtLeast(0.0).toLong())
            ensureActive()
            if (!port.ready(track)) { recover(); return@launch }
            port.play(); pending = null; playbackReady()
        }
    }
    private suspend fun scheduleAudio(audio: LanScheduledAudioPort, m: LanMessage, track: ListenTogetherTrack, resource: LanStreamResource) {
        val command = checkNotNull(m.scheduledAction)
        loading?.join()
        if (!prepareAction(track, command.trackTimeSeconds)) {
            handleActionFailure { error(port.preparationError) }
            return
        }
        if (!matchesStream(resource)) return
        if (audio.scheduleAudio(m, offset(), nudge())) playbackReady() else handleActionFailure(::recover)
    }
    private suspend fun prepareAction(track: ListenTogetherTrack, position: Double): Boolean {
        if (port.ready(track)) return true
        error("LOADING")
        return port.prepare(track, position)
    }
    fun paused(m: LanMessage) {
        val track = m.track ?: return
        val resource = m.streamResource?.takeIf(LanStreamResource::valid) ?: return error("SOURCE")
        if (!accept(m)) return
        scheduledAudio?.cancelAudioSchedule()
        action?.cancel(); loading?.cancel(); retry?.cancel(); pending = null
        bindStream(track, resource)
        refreshPresentation(track, m.presentationResource ?: resource.presentation)
        action = scope.launch {
            if (!port.prepare(track, m.trackTimeSeconds ?: 0.0)) {
                handleActionFailure { error(port.preparationError) }
                return@launch
            }
            if (matchesStream(resource)) { port.pause(); playbackReady() }
        }
    }
    private fun handleActionFailure(fallback: () -> Unit) {
        if (port.preparationError == LAN_AUDIO_NOT_SEEKABLE) {
            pending = null
            onUnseekableAction()
        } else fallback()
    }
    fun presentation(m: LanMessage) {
        if (m.mediaId == null || m.mediaId != mediaId) return
        val track = m.track ?: return
        if (track.stableKey != trackKey) return
        refreshPresentation(track, m.presentationResource)
    }
    private fun bindStream(track: ListenTogetherTrack, resource: LanStreamResource) {
        if (mediaId != resource.mediaId || trackKey != track.stableKey) {
            resetPresentation()
        }
        mediaId = resource.mediaId
        trackKey = track.stableKey
        port.sharedStreamResource(track, resource)
    }
    private fun resetPresentation() {
        presentationUpdate?.cancel()
        requestedPresentation = null
        appliedPresentation = null
    }
    private fun refreshPresentation(track: ListenTogetherTrack, resource: LanFileResource?) {
        if (resource == null || resource == appliedPresentation) return
        if (resource == requestedPresentation && presentationUpdate?.isActive == true) return
        requestedPresentation = resource
        presentationUpdate?.cancel()
        presentationUpdate = scope.launch {
            val presentation = fetchPresentation(resource) ?: return@launch
            ensureActive()
            appliedPresentation = resource
            port.sharedPresentation(track, presentation)
        }
    }
    private suspend fun fetchPresentation(resource: LanFileResource): LanSharedPresentation? {
        // a busy metadata transfer should not permanently hide a late cover
        repeat(2) {
            resolvePresentation(resource)?.let { return it }
            delay(1000)
        }
        return resolvePresentation(resource)
    }
    private fun matchesStream(resource: LanStreamResource): Boolean {
        if (resource.mediaId == port.preparedMediaId) return true
        pending = null
        port.pause()
        error("AUDIO_CONTENT")
        return false
    }
    fun nudgeChanged(delta: Int) {
        if (pending?.serverTimeToExecute?.let { clock.nowMs() + offset() >= it } == true) pending = null
        val next = pending
        // a replayed audio plan may predate the most recent cover update
        if (next != null) { schedule(next.copy(presentationResource = requestedPresentation)); return }
        if (scheduledAudio != null) { scheduledAudio.changeAudioNudge(nudge(), delta); return }
        if (port.isPlaying()) port.seek((port.positionSeconds() + delta / 1000.0).coerceAtLeast(0.0))
    }
    fun gain(value: Float) {
        if (value == lastGain) return
        lastGain = value; gainJob?.cancel(); gainJob = scope.launch { port.setMediaVolume(value) }
    }
    private fun playbackReady() {
        recoveryAttempts = 0
        onReady()
    }
    private fun recover() {
        if (retry?.isActive == true) return
        port.pause()
        if (++recoveryAttempts > 3) { error("LOAD_TIMEOUT"); return }
        error("LOADING")
        retry = scope.launch { delay(1000); send(LanMessage("SYNC")) }
    }
    fun resetRecovery() { recoveryAttempts = 0 }
    fun interruptPlayback() {
        action?.cancel()
        retry?.cancel()
        pending = null
        // 装载中的播放器已经暂停，保留这次读取以便房间收到就绪确认
        if (loading?.isActive != true || port.isPlaying()) {
            scheduledAudio?.cancelAudioSchedule()
            port.pause()
        }
    }
    fun close() {
        action?.cancel(); loading?.cancel(); retry?.cancel(); gainJob?.cancel(); resetPresentation()
        pending = null; resetRecovery(); scheduledAudio?.cancelAudioSchedule(); port.pause()
    }
}
