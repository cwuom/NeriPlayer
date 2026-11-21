package moe.ouom.neriplayer.lanltw.core

import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.lanltw.media.LanPresentationCodec

/** Single-owner state machine. The transport never owns playback state. */
class LanRoomEngine(
    val roomId: String, private val roomName: String, private val clock: LanClock,
    initialPermissions: LanPermissions = LanPermissions(),
    private val send: (String, LanMessage) -> Unit
) {
    private data class Peer(var member: LanMember, var timing: LanPeerTiming = LanPeerTiming(), var seen: Double,
        val liveness: LanPeerLiveness = LanPeerLiveness(60_000.0))
    private data class Preparation(val track: ListenTogetherTrack, val position: Double, val playing: Boolean,
        val generation: Long, val deadline: Double, val readyMembers: MutableSet<String> = mutableSetOf(),
        val unavailableMembers: MutableSet<String> = mutableSetOf())
    private val peers = linkedMapOf<String, Peer>()
    private var permissions = initialPermissions
    private var globalVolume = 1f
    private var playback = LanPlayback()
    private var awaitingSource: Preparation? = null
    private var loading: Preparation? = null
    private var revision = 0L
    private var currentStream: LanStreamResource? = null
    private var currentPresentation: LanFileResource? = null
    private var sourceFailure: String? = null
    private fun pendingPreparation() = awaitingSource ?: loading
    private fun displayedPlayback(): LanPlayback = pendingPreparation()?.let {
        LanPlayback(it.track, it.position, false, revision = it.generation, preparing = true)
    } ?: playback
    fun snapshot() = LanRoom(roomId, roomName, peers.values.map { it.member }, permissions, globalVolume, displayedPlayback())
    fun join(id: String, name: String, host: Boolean, initialVolume: Float = 1f): Boolean {
        if (peers.size >= 16 || name.isBlank() || name.length > 24) return false
        if (host && hostId() != null) return false
        peers[id] = Peer(LanMember(id, name, host, volume = LanRoomPolicy.volume(initialVolume) ?: 0f), seen = clock.nowMs())
        if (host) globalVolume = peers.getValue(id).member.volume
        send(id, LanMessage("WELCOME", protocolVersion = LAN_PROTOCOL_VERSION, clientId = id, room = snapshot()))
        broadcastState()
        return true
    }
    fun leave(id: String) {
        peers.remove(id)
        loading?.let { it.readyMembers.remove(id); it.unavailableMembers.remove(id) }
        if (peers.isEmpty()) { loading = null; awaitingSource = null }
        finishLoadingIfReady()
        broadcastState()
    }
    fun observe(id: String, rtt: Double?, compensation: Double?) {
        val peer = peers[id] ?: return
        peer.seen = clock.nowMs()
        peer.timing.reportRtt(rtt)
        peer.timing.reportCompensation(compensation)
        peer.member = peer.member.copy(rttMs = peer.timing.rtt)
    }

    fun request(id: String, m: LanMessage) {
        val peer = peers[id] ?: return
        peer.seen = clock.nowMs()
        val publication = streamHandlers[m.type]
        if (publication != null) {
            if (peer.member.host) publication(m) else reject(id, "DENIED")
            return
        }
        requestControl(id, peer.member.host, m)
    }
    private val streamHandlers: Map<String, (LanMessage) -> Unit> = mapOf(
        "LAN_STREAM_PUBLISHED" to ::streamPublished,
        "LAN_STREAM_FAILED" to ::streamFailed,
        "LAN_TRACK_FINISHED" to ::streamFinished,
        "LAN_PRESENTATION_PUBLISHED" to ::presentationPublished
    )
    private fun requestControl(id: String, host: Boolean, m: LanMessage) {
        when (m.type) {
            "PLAY" -> requestPlay(id, host, m)
            "PAUSE" -> requestPause(id, host)
            "SYNC" -> sync(id)
            "AUDIO_SOURCE_READY" -> ready(id, m)
            "LAN_LOAD_FAILED" -> loadFailed(id, host, m)
            "LAN_REQUEST_STEP" -> requestStep(id, host, m)
            else -> configure(id, host, m)
        }
    }
    fun tick(): List<String> {
        val now = clock.nowMs()
        val expired = peers.toList().mapNotNull { (id, peer) ->
            when (peer.liveness.check(now, peer.seen)) {
                LanPeerLiveness.Status.PROBE -> { send(id, LanMessage("LIVENESS_PING")); null }
                LanPeerLiveness.Status.EXPIRED -> id
                LanPeerLiveness.Status.HEALTHY -> null
            }
        }
        expired.forEach(::leave)
        pendingPreparation()?.takeIf { clock.nowMs() >= it.deadline }?.let(::expirePreparation)
        broadcastState()
        return expired
    }
    private fun expirePreparation(pending: Preparation) {
        if (!hostReady(pending)) {
            fail(pending.track, pending.position, "LOAD_TIMEOUT")
            return
        }
        val missing = peers.keys.filterNot(pending.readyMembers::contains)
        missing.filterNot(pending.unavailableMembers::contains).forEach { reject(it, "LOAD_TIMEOUT") }
        pending.unavailableMembers.addAll(missing)
        finishLoadingIfReady()
    }
    private fun loadFailed(id: String, host: Boolean, message: LanMessage) {
        val pending = loading ?: return
        if (!matchesLoad(pending, message)) return
        val code = streamError(message.code)
        if (host) {
            fail(pending.track, pending.position, code)
            return
        }
        pending.readyMembers.remove(id)
        pending.unavailableMembers.add(id)
        reject(id, code)
        finishLoadingIfReady()
    }
    private fun streamError(code: String?) = code?.takeIf {
        it in setOf("AUDIO_DURATION", "AUDIO_CONTENT", "AUDIO_FORMAT", "MEMORY_LIMIT", "FULL_AUDIO_REQUIRED", "LOAD_TIMEOUT",
            "SOURCE_CHANGED", "LOCAL_READ", "LOCAL_STORAGE", "LOCAL_TRANSFER", LAN_AUDIO_NOT_SEEKABLE)
    } ?: "SOURCE"
    private fun requestPlay(id: String, host: Boolean, m: LanMessage) {
        val current = displayedPlayback()
        val intent = m.intent ?: "PLAY_PAUSE"
        if (intent == "START" || intent == "START_PAUSED") {
            requestInitialPlayback(id, host, current, m)
            return
        }
        val command = if (intent == "RETRY") m.copy(intent = "PLAY_PAUSE") else m
        val decision = LanRoomPolicy.play(host, permissions, current, controlPosition(current), command)
        val error = decision.error
        if (error != null) return reject(id, error)
        val track = requireNotNull(decision.track)
        applyPlayIntent(id, intent, track, decision.position)
    }
    private fun requestInitialPlayback(id: String, host: Boolean, current: LanPlayback, message: LanMessage) {
        if (!host || current.track != null) return reject(id, "DENIED")
        val decision = LanRoomPolicy.play(true, permissions, current, 0.0, message.copy(intent = "NEXT"))
        val track = decision.track ?: return reject(id, decision.error ?: "SOURCE")
        requestHostStream(id, track, LanRoomPolicy.initialPosition(track, message.trackTimeSeconds), playing = message.intent == "START")
    }
    private fun applyPlayIntent(id: String, intent: String, track: ListenTogetherTrack, position: Double) {
        if (intent in setOf("PLAY_PAUSE", "RETRY") && resumePending()) return
        if (intent in setOf("PLAY_PAUSE", "SEEK") && canReuseStream()) {
            if (intent == "SEEK") startSeek(track, position) else resumePlayback(track, position)
            return
        }
        requestHostStream(id, track, position, shouldPlay(intent))
    }
    private fun shouldPlay(intent: String): Boolean = intent != "SEEK" || (pendingPreparation()?.playing ?: playback.playing)
    private fun resumePending(): Boolean {
        val pending = pendingPreparation() ?: return false
        val resumed = pending.copy(playing = true)
        if (awaitingSource != null) awaitingSource = resumed else loading = resumed
        return true
    }
    private fun canReuseStream(): Boolean = awaitingSource == null && currentStream != null
    private fun startSeek(track: ListenTogetherTrack, position: Double) {
        startLoading(Preparation(track, position, shouldPlay("SEEK"), ++revision, bufferingDeadline()))
    }
    private fun resumePlayback(track: ListenTogetherTrack, position: Double) {
        if (!playback.playing) publishPlan(track, position, true, ++revision)
    }
    private fun requestHostStream(id: String, track: ListenTogetherTrack, position: Double, playing: Boolean) {
        val owner = hostId() ?: return reject(id, "STALE")
        val pending = Preparation(track, position, playing, ++revision, clock.nowMs() + 45_000)
        loading = null; awaitingSource = pending; sourceFailure = null
        broadcastState()
        send(owner, LanMessage("LAN_HOST_PLAY", track = track, loadGeneration = pending.generation))
    }
    private fun awaitedPublication(message: LanMessage): Preparation? =
        awaitingSource?.takeIf { it.generation == message.loadGeneration }

    private fun streamPublished(m: LanMessage) {
        val pending = awaitedPublication(m) ?: return
        val stream = m.streamResource?.takeIf(LanStreamResource::valid)
        if (stream == null) return fail(pending.track, pending.position, "FULL_AUDIO_REQUIRED")
        currentStream = stream.copy(presentation = null)
        currentPresentation = validatedPresentation(stream.presentation)
        startLoading(pending.copy(deadline = bufferingDeadline()))
    }
    private fun startLoading(pending: Preparation) {
        awaitingSource = null
        loading = pending
        broadcast(loadMessage(pending))
        broadcastState()
    }
    private fun bufferingDeadline(): Double = clock.nowMs() + 15_000
    private fun streamFailed(m: LanMessage) {
        val pending = awaitedPublication(m)
        if (pending != null) {
            fail(pending.track, pending.position, streamError(m.code))
            return
        }
        val plan = publishedPlayback(m) ?: return
        val track = plan.track ?: return
        fail(track, controlPosition(plan), streamError(m.code))
    }
    private fun presentationPublished(m: LanMessage) {
        val plan = publishedPlayback(m) ?: return
        val resource = validatedPresentation(m.presentationResource) ?: return
        currentPresentation = resource
        broadcast(LanMessage("LAN_PRESENTATION", track = plan.track, mediaId = m.mediaId, presentationResource = resource))
    }
    private fun publishedPlayback(m: LanMessage): LanPlayback? =
        if (awaitingSource == null && matchesCurrentStream(m)) displayedPlayback() else null

    private fun streamFinished(m: LanMessage) {
        val plan = publishedPlayback(m) ?: return
        val track = plan.track ?: return
        if (!plan.playing || plan.revision != m.revision || track.stableKey != m.audioSource) return
        publishPlan(track, position = 0.0, playing = false, generation = ++revision)
    }

    private fun validatedPresentation(resource: LanFileResource?): LanFileResource? =
        resource?.takeIf(::validPresentation)
    private fun validPresentation(resource: LanFileResource): Boolean =
        resource.mimeType == "application/json" && resource.valid(LanPresentationCodec.MAX_BYTES)
    private fun loadMessage(pending: Preparation) = LanMessage("ROOM_EVENT",
        event = LanRoomEvent("LOAD_AUDIO_SOURCE",
            track = pending.track, trackTimeSeconds = pending.position, loadGeneration = pending.generation,
            revision = pending.generation, streamResource = currentStream),
        presentationResource = currentPresentation)
    private fun hostId(): String? = peers.entries.firstOrNull { it.value.member.host }?.key
    private fun requestStep(id: String, host: Boolean, m: LanMessage) {
        if (!host && !permissions.seek) return reject(id, "DENIED")
        if (m.audioSource != displayedPlayback().track?.stableKey) return reject(id, "STALE")
        if (m.trackTimeSeconds !in listOf(-1.0, 1.0)) return reject(id, "INVALID")
        val owner = hostId() ?: return reject(id, "STALE")
        send(owner, LanMessage("LAN_HOST_STEP", trackTimeSeconds = m.trackTimeSeconds))
    }
    private fun requestPause(id: String, host: Boolean) {
        if (!host && !permissions.playPause) return reject(id, "DENIED")
        loading?.let { loading = it.copy(playing = false); return }
        awaitingSource = awaitingSource?.copy(playing = false)
        if (playback.playing) pausePlayback()
    }
    private fun pausePlayback() {
        val track = playback.track ?: return
        val at = executeAt()
        val position = LanSchedule.position(playback.positionSeconds, playback.executeAtMs, at)
        val pauseRevision = if (awaitingSource == null) ++revision else playback.revision
        playback = LanPlayback(track, position, false, at, pauseRevision)
        broadcast(scheduledAction(playback, position, at))
        broadcastState()
    }
    private fun ready(id: String, m: LanMessage) {
        val pending = loading ?: return
        if (!matchesLoad(pending, m)) return
        pending.readyMembers.add(id)
        finishLoadingIfReady()
    }
    private fun matchesLoad(pending: Preparation, m: LanMessage): Boolean =
        m.loadGeneration == pending.generation && matchesCurrentStream(m)
    private fun matchesCurrentStream(m: LanMessage): Boolean = m.mediaId != null && m.mediaId == currentStream?.mediaId
    private fun fail(track: ListenTogetherTrack, position: Double, code: String) {
        loading = null; awaitingSource = null; sourceFailure = code; currentStream = null; currentPresentation = null
        val retryPosition = if (code == LAN_AUDIO_NOT_SEEKABLE) 0.0 else position
        playback = LanPlayback(track, retryPosition, false, clock.nowMs(), ++revision)
        broadcast(LanMessage("LAN_ERROR", code = code))
        broadcastState()
    }
    private fun finishLoadingIfReady() {
        val pending = loading ?: return
        if (!hostReady(pending) || !allMembersSettled(pending)) return
        loading = null
        publishPlan(pending.track, pending.position, pending.playing, pending.generation, pending.readyMembers)
    }
    private fun hostReady(pending: Preparation): Boolean =
        hostId()?.let(pending.readyMembers::contains) == true

    private fun allMembersSettled(pending: Preparation): Boolean =
        peers.keys.all { it in pending.readyMembers || it in pending.unavailableMembers }

    private fun publishPlan(track: ListenTogetherTrack, position: Double, playing: Boolean, generation: Long,
        participants: Collection<String> = peers.keys) {
        val at = executeAt(participants = participants)
        playback = LanPlayback(track, position, playing, at, generation)
        val action = scheduledAction(playback, position, at)
        participants.toList().forEach { send(it, action) }
        broadcastState()
    }
    private fun scheduledAction(plan: LanPlayback, position: Double, at: Double): LanMessage {
        val track = checkNotNull(plan.track)
        val action = if (plan.playing) "PLAY" else "PAUSE"
        return LanMessage("SCHEDULED_ACTION", scheduledAction = LanAction(action, track.stableKey, position, track),
            serverTimeToExecute = at, revision = plan.revision, streamResource = currentStream, presentationResource = currentPresentation)
    }
    private fun sync(id: String) {
        sourceFailure?.let { reject(id, it); return }
        if (awaitingSource != null) return
        loading?.let { pending ->
            send(id, loadMessage(pending))
            return
        }
        val track = playback.track ?: return
        if (!playback.playing) {
            send(id, LanMessage("LAN_PAUSED_STATE", track = track, trackTimeSeconds = playback.positionSeconds,
                revision = playback.revision, streamResource = currentStream, presentationResource = currentPresentation))
            return
        }
        val at = executeAt(1500.0, listOf(id))
        send(id, scheduledAction(playback, LanSchedule.position(playback.positionSeconds, playback.executeAtMs, at), at))
    }
    private fun configure(id: String, host: Boolean, m: LanMessage) {
        if (m.type == "LIVENESS_PONG") return
        if (!host && !memberMayConfigure(id, m)) return reject(id, "DENIED")
        val error = applyConfiguration(m)
        if (error != null) reject(id, error) else broadcastState()
    }
    private fun memberMayConfigure(id: String, m: LanMessage): Boolean = when (m.type) {
        "SET_GLOBAL_VOLUME" -> permissions.volume
        "LAN_MEMBER_VOLUME" -> peers[m.clientId]?.member?.let { permissions.allowsMemberDeviceVolume(id, it) } == true
        else -> false
    }
    private val configurationHandlers: Map<String, (LanMessage) -> String?> = mapOf(
        "LAN_PERMISSIONS" to { setPermissions(it.permissions) },
        "SET_GLOBAL_VOLUME" to { setGlobalVolume(it.volume) },
        "LAN_MEMBER_VOLUME" to { setMemberVolume(it.clientId, it.volume) }
    )
    private fun applyConfiguration(m: LanMessage): String? {
        val handler = configurationHandlers[m.type] ?: return "UNSUPPORTED"
        return handler(m)
    }
    private fun setPermissions(value: LanPermissions?): String? {
        if (value == null) return "INVALID"
        permissions = value; return null
    }
    private fun setGlobalVolume(value: Float?): String? {
        val volume = LanRoomPolicy.volume(value) ?: return "INVALID"
        globalVolume = volume
        peers.values.forEach { it.member = it.member.copy(volume = volume) }
        return null
    }
    private fun setMemberVolume(id: String?, value: Float?): String? {
        val peer = peers[id] ?: return "STALE"
        val volume = LanRoomPolicy.volume(value) ?: return "INVALID"
        peer.member = peer.member.copy(volume = volume)
        return null
    }
    private fun controlPosition(plan: LanPlayback): Double = if (plan.playing)
        LanSchedule.position(plan.positionSeconds, plan.executeAtMs, maxOf(clock.nowMs(), plan.executeAtMs)) else plan.positionSeconds
    private fun executeAt(extra: Double = 0.0, participants: Collection<String> = peers.keys): Double {
        var maxRtt = 0.0
        var maxCompensation = 0.0
        for (id in participants) {
            val peer = peers[id] ?: continue
            maxRtt = maxOf(maxRtt, peer.member.rttMs)
            maxCompensation = maxOf(maxCompensation, peer.timing.compensation)
        }
        return clock.nowMs() + LanSchedule.executionDelay(maxRtt, maxCompensation, extra)
    }
    private fun reject(id: String, code: String) = send(id, LanMessage("LAN_ERROR", code = code))
    private fun broadcast(message: LanMessage) = peers.keys.toList().forEach { send(it, message) }
    private fun broadcastState() { val state = snapshot(); broadcast(LanMessage("ROOM_EVENT", event = LanRoomEvent("CLIENT_CHANGE", clients = state.members, room = state))) }
}
