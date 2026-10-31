package moe.ouom.neriplayer.lanltw.core

import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

/** Single-owner state machine. The transport never owns playback state. */
class LanRoomEngine(
    val roomId: String, private val roomName: String, private val clock: LanClock,
    initialPermissions: LanPermissions = LanPermissions(),
    private val send: (String, LanMessage) -> Unit
) {
    private data class Peer(var member: LanMember, var timing: LanPeerTiming = LanPeerTiming(), var seen: Double)
    private data class Loading(val track: ListenTogetherTrack, val position: Double, val generation: Long, val deadline: Double, val loaded: MutableSet<String> = mutableSetOf(), val fingerprints: MutableMap<String, String> = mutableMapOf())
    private val peers = linkedMapOf<String, Peer>()
    private var permissions = initialPermissions
    private var globalVolume = 1f
    private var playback = LanPlayback()
    private var loading: Loading? = null
    private var revision = 0L
    private var currentSources = emptyList<String>()
    private var currentLocalMedia: LanLocalMedia? = null
    private var sourceFailure: String? = null
    fun snapshot() = LanRoom(roomId, roomName, peers.values.map { it.member }, permissions, globalVolume, playback)
    fun join(id: String, name: String, host: Boolean, initialVolume: Float = 1f): Boolean {
        if (peers.size >= 16 || name.isBlank() || name.length > 24) return false
        if (host && hostId() != null) return false
        peers[id] = Peer(LanMember(id, name, host, volume = LanRoomPolicy.volume(initialVolume) ?: 0f), seen = clock.nowMs())
        if (host) globalVolume = peers.getValue(id).member.volume
        send(id, LanMessage("WELCOME", clientId = id, room = snapshot()))
        broadcastState()
        return true
    }
    fun leave(id: String) {
        peers.remove(id)
        loading?.loaded?.remove(id)
        if (peers.isEmpty()) loading = null
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
        when (m.type) {
            "PLAY" -> requestPlay(id, peer.member.host, m)
            "PAUSE" -> requestPause(id, peer.member.host)
            "SYNC" -> sync(id)
            "AUDIO_SOURCE_LOADED" -> loaded(id, m)
            "LAN_LOAD_FAILED" -> loadFailed(m)
            "LAN_REQUEST_STEP" -> requestStep(id, peer.member.host, m)
            else -> configure(id, peer.member.host, m)
        }
    }
    fun tick(): List<String> {
        val expired = peers.filterValues { clock.nowMs() - it.seen > 60_000 }.keys.toList()
        expired.forEach(::leave)
        if (loading?.let { clock.nowMs() >= it.deadline } == true) cancelLoading("LOAD_TIMEOUT")
        broadcastState()
        return expired
    }
    private fun loadFailed(message: LanMessage) {
        val pending = loading ?: return
        if (message.loadGeneration != pending.generation) return
        val code = message.code?.takeIf { it in setOf("AUDIO_DURATION", "AUDIO_FORMAT", "MEMORY_LIMIT", "FULL_AUDIO_REQUIRED") } ?: "SOURCE"
        cancelLoading(code)
    }
    private fun requestPlay(id: String, host: Boolean, m: LanMessage) {
        val decision = LanRoomPolicy.play(host, permissions, playback, currentPosition(), m)
        val error = decision.error
        if (error != null) return reject(id, error)
        val track = requireNotNull(decision.track)
        if (!host) {
            val owner = hostId() ?: return reject(id, "STALE")
            send(owner, m.copy(type = "LAN_HOST_PLAY", track = track))
            return
        }
        if (!validMedia(track, m)) return reject(id, "FULL_AUDIO_REQUIRED")
        currentLocalMedia = m.localMedia
        currentSources = if (m.localMedia == null) m.sourceUrls.distinct().take(3) else emptyList()
        sourceFailure = null
        loading = Loading(track, decision.position, ++revision, clock.nowMs() + if (currentLocalMedia == null) 90_000 else 300_000)
        broadcast(LanMessage("ROOM_EVENT", event = LanRoomEvent("LOAD_AUDIO_SOURCE", audioSourceToPlay = LanSource(track.stableKey), track = track,
            trackTimeSeconds = decision.position, loadGeneration = revision, revision = revision, sourceUrls = currentSources, localMedia = currentLocalMedia)))
    }
    private fun validMedia(track: ListenTogetherTrack, message: LanMessage): Boolean {
        val local = message.localMedia
        if (track.channelId != "local") return local == null && validSources(message.sourceUrls)
        if (local == null) return false
        if (!local.valid()) return false
        return matchesLocalTrack(track, local) && message.sourceUrls.isEmpty()
    }
    private fun matchesLocalTrack(track: ListenTogetherTrack, media: LanLocalMedia): Boolean =
        media.stableKey == track.stableKey && media.durationMs == track.durationMs
    private fun validSources(urls: List<String>): Boolean = urls.size in 1..3 && urls.all { it.length <= 12000 && (it.startsWith("https://") || it.startsWith("http://")) }
    private fun hostId(): String? = peers.entries.firstOrNull { it.value.member.host }?.key
    private fun requestStep(id: String, host: Boolean, m: LanMessage) {
        if (!host && !permissions.next) return reject(id, "DENIED")
        if (m.audioSource != playback.track?.stableKey) return reject(id, "STALE")
        if (m.trackTimeSeconds !in listOf(-1.0, 1.0)) return reject(id, "INVALID")
        val owner = hostId() ?: return reject(id, "STALE")
        send(owner, LanMessage("LAN_HOST_STEP", trackTimeSeconds = m.trackTimeSeconds))
    }
    private fun requestPause(id: String, host: Boolean) {
        if (!host && !permissions.playPause) return reject(id, "DENIED")
        val at = executeAt()
        val target = pauseTarget(at) ?: return
        val track = target.first
        val position = target.second
        loading = null
        playback = LanPlayback(track, position, false, at, ++revision)
        broadcast(LanMessage("SCHEDULED_ACTION", scheduledAction = LanAction("PAUSE", track.stableKey, position, track), serverTimeToExecute = at, revision = revision, sourceUrls = currentSources, localMedia = currentLocalMedia))
        broadcastState()
    }
    private fun pauseTarget(at: Double): Pair<ListenTogetherTrack, Double>? {
        loading?.let { return it.track to it.position }
        val track = playback.track ?: return null
        val position = if (playback.playing) LanSchedule.position(playback.positionSeconds, playback.executeAtMs, at) else playback.positionSeconds
        return track to position
    }
    private fun loaded(id: String, m: LanMessage) {
        val pending = loading ?: return
        if (m.source?.url != pending.track.stableKey || m.loadGeneration != pending.generation) return
        val digest = m.contentDigest
        if (!acceptLocalDigest(digest)) return
        if (!recordFingerprint(pending, id, digest)) return
        pending.loaded.add(id)
        finishLoadingIfReady()
    }
    private fun recordFingerprint(pending: Loading, id: String, digest: String?): Boolean {
        if (digest == null) return true
        if (!digest.matches(Regex("[a-f0-9]{64}"))) return false
        pending.fingerprints[id] = digest
        return true
    }
    private fun acceptLocalDigest(digest: String?): Boolean {
        val media = currentLocalMedia ?: return true
        if (digest == media.sha256) return true
        cancelLoading("AUDIO_CONTENT")
        return false
    }
    private fun cancelLoading(code: String) {
        val pending = loading ?: return
        loading = null; sourceFailure = code; currentSources = emptyList(); currentLocalMedia = null
        playback = LanPlayback(pending.track, pending.position, false, clock.nowMs(), ++revision)
        broadcast(LanMessage("LAN_ERROR", code = code))
        broadcastState()
    }
    private fun finishLoadingIfReady() {
        val pending = loading ?: return
        if (peers.isNotEmpty() && pending.loaded.containsAll(peers.keys)) finishLoading()
    }
    private fun finishLoading() {
        val pending = loading ?: return
        if (pending.fingerprints.values.distinct().size > 1) { cancelLoading("AUDIO_CONTENT"); return }
        loading = null
        if (peers.isEmpty()) return
        val at = executeAt()
        playback = LanPlayback(pending.track, pending.position, true, at, pending.generation)
        broadcast(LanMessage("SCHEDULED_ACTION", scheduledAction = LanAction("PLAY", pending.track.stableKey, pending.position, pending.track),
            serverTimeToExecute = at, revision = pending.generation, sourceUrls = currentSources, localMedia = currentLocalMedia))
        broadcastState()
    }
    private fun sync(id: String) {
        sourceFailure?.let { reject(id, it); return }
        loading?.let { pending ->
            send(id, LanMessage("ROOM_EVENT", event = LanRoomEvent("LOAD_AUDIO_SOURCE",
                audioSourceToPlay = LanSource(pending.track.stableKey), track = pending.track,
                trackTimeSeconds = pending.position, loadGeneration = pending.generation,
                revision = pending.generation, sourceUrls = currentSources, localMedia = currentLocalMedia)))
            return
        }
        val track = playback.track ?: return
        if (!playback.playing) {
            send(id, LanMessage("LAN_PAUSED_STATE", track = track, trackTimeSeconds = playback.positionSeconds, revision = playback.revision, sourceUrls = currentSources, localMedia = currentLocalMedia))
            return
        }
        val at = executeAt(1500.0)
        send(id, LanMessage("SCHEDULED_ACTION", scheduledAction = LanAction("PLAY", track.stableKey,
            LanSchedule.position(playback.positionSeconds, playback.executeAtMs, at), track), serverTimeToExecute = at, revision = playback.revision, sourceUrls = currentSources, localMedia = currentLocalMedia))
    }
    private fun configure(id: String, host: Boolean, m: LanMessage) {
        if (m.type == "LIVENESS_PONG") return
        if (!host && !(m.type == "LAN_MEMBER_VOLUME" && m.clientId == id)) return reject(id, "DENIED")
        val error = applyConfiguration(m)
        if (error != null) reject(id, error) else broadcastState()
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
        if (peer.member.host) globalVolume = volume
        return null
    }
    private fun currentPosition(): Double = if (playback.playing) LanSchedule.position(playback.positionSeconds, playback.executeAtMs, clock.nowMs()) else playback.positionSeconds
    private fun executeAt(extra: Double = 0.0): Double {
        var maxRtt = 0.0
        var maxCompensation = 0.0
        for (peer in peers.values) {
            maxRtt = maxOf(maxRtt, peer.member.rttMs)
            maxCompensation = maxOf(maxCompensation, peer.timing.compensation)
        }
        return clock.nowMs() + LanSchedule.executionDelay(maxRtt, maxCompensation, extra)
    }
    private fun reject(id: String, code: String) = send(id, LanMessage("LAN_ERROR", code = code))
    private fun broadcast(message: LanMessage) = peers.keys.toList().forEach { send(it, message) }
    private fun broadcastState() { val state = snapshot(); broadcast(LanMessage("ROOM_EVENT", event = LanRoomEvent("CLIENT_CHANGE", clients = state.members, room = state))) }
}
