package moe.ouom.neriplayer.lanltw.playback

import kotlinx.coroutines.*
import moe.ouom.neriplayer.data.model.lan.LanMessage
import moe.ouom.neriplayer.data.model.lan.LanSource
import moe.ouom.neriplayer.lanltw.core.LanClock
import moe.ouom.neriplayer.lanltw.core.LanSchedule

class LanPlaybackScheduler(
    private val scope: CoroutineScope, private val port: LanPlaybackPort, private val clock: LanClock,
    private val offset: () -> Double, private val nudge: () -> Int, private val synced: () -> Boolean,
    private val send: (LanMessage) -> Unit, private val error: (String) -> Unit, private val onReady: () -> Unit = {}
) {
    private val scheduledAudio = port as? LanScheduledAudioPort
    private var action: Job? = null
    private var loading: Job? = null
    private var retry: Job? = null
    private var revision = -1L
    private var gainJob: Job? = null
    private var lastGain = -1f
    private var pending: LanMessage? = null
    private fun accept(m: LanMessage): Boolean {
        val incoming = m.revision ?: return false
        if (incoming < revision) return false
        revision = incoming
        return true
    }
    fun load(m: LanMessage) {
        if (!accept(m)) return
        val track = m.track ?: return
        port.sharedLocalMedia(track, m.localMedia)
        port.sharedAudioSources(track, m.sourceUrls)
        action?.cancel(); scheduledAudio?.cancelAudioSchedule(); pending = null; loading?.cancel(); retry?.cancel()
        loading = scope.launch {
            val ready = port.prepare(track, m.trackTimeSeconds ?: 0.0)
            ensureActive()
            if (!ready) { error(port.preparationError); send(LanMessage("LAN_LOAD_FAILED", loadGeneration = m.loadGeneration, code = port.preparationError)); return@launch }
            withTimeoutOrNull(30_000) { while (!synced()) delay(25) }
            if (!synced()) { recover(); return@launch }
            send(LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(track.stableKey), loadGeneration = m.loadGeneration, contentDigest = port.preparedContentDigest))
        }
    }
    fun schedule(m: LanMessage) {
        if (!accept(m)) return
        val command = m.scheduledAction ?: return
        val track = command.track ?: return
        val at = m.serverTimeToExecute ?: return
        port.sharedLocalMedia(track, m.localMedia)
        port.sharedAudioSources(track, m.sourceUrls)
        action?.cancel(); retry?.cancel(); pending = m
        action = scope.launch {
            if (!synced()) { recover(); return@launch }
            if (scheduledAudio != null) {
                try {
                    loading?.join()
                    if (!port.ready(track)) {
                        if (!port.prepare(track, command.trackTimeSeconds)) { error(port.preparationError); return@launch }
                        recover(); return@launch
                    }
                    if (scheduledAudio.scheduleAudio(m, offset(), nudge())) onReady() else recover()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { recover() }
                return@launch
            }
            if (command.type == "PAUSE") {
                loading?.cancel()
                if (port.currentTrack()?.stableKey != track.stableKey && !port.prepare(track, command.trackTimeSeconds)) {
                    recover(); return@launch
                }
                delay(LanSchedule.pauseDelay(clock.nowMs(), offset(), nudge(), port.schedulingLeadMs, at))
                ensureActive(); port.pause(); pending = null
                return@launch
            }
            loading?.join()
            if (!port.ready(track) && !port.prepare(track, command.trackTimeSeconds)) { recover(); return@launch }
            val plan = LanSchedule.play(clock.nowMs(), offset(), nudge(), port.schedulingLeadMs, at, command.trackTimeSeconds)
            val deadline = clock.nowMs() + plan.delayMs
            port.pause(); port.seek(plan.positionSeconds)
            while (!port.ready(track) && clock.nowMs() < deadline) { delay(10) }
            delay((deadline - clock.nowMs()).coerceAtLeast(0.0).toLong())
            ensureActive()
            if (!port.ready(track)) { recover(); return@launch }
            port.play(); pending = null; onReady()
        }
    }
    fun paused(m: LanMessage) {
        if (!accept(m)) return
        scheduledAudio?.cancelAudioSchedule()
        val track = m.track ?: return
        port.sharedLocalMedia(track, m.localMedia)
        port.sharedAudioSources(track, m.sourceUrls)
        action?.cancel(); loading?.cancel(); pending = null
        action = scope.launch { if (port.prepare(track, m.trackTimeSeconds ?: 0.0)) port.pause() else error("SOURCE") }
    }
    fun nudgeChanged(delta: Int) {
        if (pending?.serverTimeToExecute?.let { clock.nowMs() + offset() >= it } == true) pending = null
        val next = pending
        if (next != null) { schedule(next); return }
        if (scheduledAudio != null) { scheduledAudio.changeAudioNudge(nudge(), delta); return }
        if (port.isPlaying()) port.seek((port.positionSeconds() + delta / 1000.0).coerceAtLeast(0.0))
    }
    fun gain(value: Float) {
        if (value == lastGain) return
        lastGain = value; gainJob?.cancel(); gainJob = scope.launch { port.setMediaVolume(value) }
    }
    private fun recover() {
        port.pause(); error("LOADING")
        if (retry?.isActive == true) return
        retry = scope.launch { delay(1000); send(LanMessage("SYNC")) }
    }
    fun close() { action?.cancel(); loading?.cancel(); retry?.cancel(); gainJob?.cancel(); pending = null; scheduledAudio?.cancelAudioSchedule(); port.pause() }
}
