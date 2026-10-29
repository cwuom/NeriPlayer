package moe.ouom.neriplayer.lanltw.session

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.common.concurrent.RequestGeneration
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.lanltw.core.*
import moe.ouom.neriplayer.lanltw.discovery.LanDiscovery
import moe.ouom.neriplayer.lanltw.playback.*
import moe.ouom.neriplayer.lanltw.transport.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** App-owned facade; all room/session mutations are serialized on Dispatchers.Main. */
class LanSessionManager(
    context: Context, http: OkHttpClient, private val playback: LanPlaybackPort,
    private val cloudActive: () -> Boolean, private val saveNudge: (Int) -> Unit,
    private val loadPermissions: suspend () -> LanPermissions = { LanPermissions() },
    private val savePermissions: (LanPermissions) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycle = Mutex()
    private val requests = RequestGeneration()
    private val connections = RequestGeneration()
    private val sourceRequests = RequestGeneration()
    private var sourceJob: Job? = null
    private val clock = LanClock { SystemClock.elapsedRealtimeNanos() / 1_000_000.0 }
    private val clientHttp = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private val keepAlive = LanKeepAlive(context)
    val discovery = LanDiscovery(context)
    private val mutable = MutableStateFlow(LanSessionState())
    val state: StateFlow<LanSessionState> = mutable.asStateFlow()
    private var sessionScope: CoroutineScope? = null
    private var server: LanWebSocketServer? = null
    private var mediaServer: moe.ouom.neriplayer.lanltw.media.LanMediaServer? = null
    private var engine: LanRoomEngine? = null
    private var client: LanWebSocketClient? = null
    private var estimator: LanNtpEstimator? = null
    private var scheduler: LanPlaybackScheduler? = null
    private var offset = 0.0
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    private var connected = false
    private var attempts = 0
    private var targetUrl = ""
    private var hello = LanMessage("HELLO")
    @Volatile private var responseAt = 0.0
    private var probeCounter = 0
    private val pendingProbes = linkedMapOf<Pair<Int, Int>, Double>()
    fun audioDiagnostics(): String = (playback as? LanScheduledAudioPort)?.audioDiagnostics() ?: "[]"
    fun restoreNudge(value: Int) { if (!state.value.active) mutable.value = state.value.copy(nudgeMs = value.coerceIn(-2000, 2000)) }
    fun host(name: String) = replaceSession {
        val track = playback.currentTrack() ?: error("SOURCE")
        requireStart(name)
        val id = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val child = checkNotNull(sessionScope)
        val ticket = requests.capture()
        val files = moe.ouom.neriplayer.lanltw.media.LanMediaServer()
        mediaServer = files
        withContext(Dispatchers.IO) { files.start(30_000, true) }
        playback.hostMediaServer(files)
        val transport = LanWebSocketServer(id, token, clock,
            onJoin = { peer, nickname, host, volume -> child.launch { if (ticket.isCurrent) {
                if (!host && engine?.snapshot()?.members?.none { it.host } == true) server?.disconnect(peer)
                else if (engine?.join(peer, nickname, host, volume) != true) server?.disconnect(peer)
            } } },
            onMessage = { peer, message -> child.launch { if (ticket.isCurrent) {
                if (message.type == "NTP_REQUEST") engine?.observe(peer, message.clientRTT, message.clientCompensationMs)
                else engine?.request(peer, message)
            } } },
            onLeave = { peer -> child.launch { if (ticket.isCurrent) {
                val wasHost = engine?.snapshot()?.members?.any { it.id == peer && it.host } == true
                files.revoke(peer)
                engine?.leave(peer)
                if (wasHost) leave("NETWORK")
            } } },
            onFatal = { scope.launch { if (ticket.isCurrent) leave("NETWORK") } })
        server = transport
        engine = LanRoomEngine(id, name.trim(), clock, loadPermissions()) { peer, message ->
            val outgoing = if (message.type == "WELCOME") message.copy(mediaPort = files.listeningPort, mediaToken = files.grant(peer)) else message
            transport.sendTo(peer, outgoing)
        }
        withContext(Dispatchers.IO) { transport.start() }
        val port = withTimeout(6000) { transport.ready.await() }
        val address = withContext(Dispatchers.IO) { localAddress() }
        mutable.value = state.value.copy(hosting = true, address = address, port = port)
        discovery.advertise(id, name.trim(), port) { scope.launch { if (ticket.isCurrent) reportError("DISCOVERY") } }
        child.launch { while (isActive) { delay(2500); transport.expireHandshakes(); engine?.tick()?.forEach(transport::disconnect); keepAlive.renew() } }
        targetUrl = "http://127.0.0.1:$port/ws"
        hello = LanMessage("HELLO", roomId = id, name = name.trim(), hostToken = token, volume = playback.mediaVolume())
        connect(track)
    }
    fun join(address: String, roomId: String?, name: String) = replaceSession {
        requireStart(name)
        val url = (if (address.contains("://")) address.replace("ws://", "http://") else "http://$address").toHttpUrlOrNull() ?: error("ADDRESS")
        if (url.scheme != "http" || url.username.isNotEmpty() || url.password.isNotEmpty()) error("ADDRESS")
        targetUrl = url.newBuilder().encodedPath("/ws").query(null).fragment(null).build().toString()
        hello = LanMessage("HELLO", roomId = roomId, name = name.trim(), volume = playback.mediaVolume())
        mutable.value = state.value.copy(address = url.host, port = url.port)
        connect(null)
    }
    private suspend fun requireStart(name: String) {
        if (name.isBlank() || name.trim().length > 24) error("NAME")
        if (cloudActive()) error("CLOUD_ACTIVE")
        if (!playback.supported()) error("OUTPUT")
        val progressTicket = requests.capture()
        playback.watchTransferProgress { received, total -> scope.launch {
            if (progressTicket.isCurrent) mutable.update { it.copy(receivedBytes = received, totalBytes = total) }
        } }
        playback.watchAudioFailure { code -> scope.launch { if (state.value.active) leave(code) } }
        playback.begin(::requestPlayback) { leave("SERVICE_STOPPED") }
        (playback as? LanScheduledAudioPort)?.let { it.initializeAudio(); it.resetAudioClock() }
        playback.watchMediaVolume { value ->
            val self = state.value.selfId
            val previous = state.value.room?.members?.find { it.id == self }?.volume
            if (connected && self != null && previous != null && kotlin.math.abs(value - previous) > .001f) {
                send(LanMessage("LAN_MEMBER_VOLUME", clientId = self, volume = value))
            }
        }
    }
    private fun replaceSession(block: suspend () -> Unit) {
        val ticket = requests.advance()
        scope.launch {
            lifecycle.withLock {
                stopResources()
                if (!ticket.isCurrent) return@withLock
                try {
                    sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    mutable.value = LanSessionState(phase = "CONNECTING", nudgeMs = state.value.nudgeMs)
                    if (playback !is LanScheduledAudioPort) estimator = LanNtpEstimator().also { it.reset() }
                    keepAlive.acquire()
                    scheduler = LanPlaybackScheduler(checkNotNull(sessionScope), playback, clock, { offset }, { state.value.nudgeMs }, { state.value.synced }, ::send, ::reportError, ::clearError)
                    block()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { stopResources(); mutable.value = state.value.copy(phase = "IDLE", error = error.message?.takeIf { it in ERRORS } ?: "NETWORK") }
            }
        }
    }
    private fun connect(initialTrack: moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack?) {
        val connectionTicket = connections.advance()
        val child = sessionScope ?: return
        val ticket = requests.capture()
        val incoming = Channel<Pair<LanMessage, Double>>(128)
        child.launch {
            try { for ((m, receivedAt) in incoming) { if (ticket.isCurrent && connectionTicket.isCurrent) receive(m, receivedAt, initialTrack) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket.isCurrent) leave("NETWORK") }
        }
        val transport = LanWebSocketClient(clientHttp, clock)
        client?.close(); client = transport
        transport.connect(targetUrl, hello.copy(volume = playback.mediaVolume()), onOpen = {}, onMessage = { m, time ->
            // Liveness is transport activity, not completion of a JS clock-estimator callback.
            if (ticket.isCurrent && connectionTicket.isCurrent) responseAt = time
            if (!incoming.trySend(m to time).isSuccess) scope.launch { if (ticket.isCurrent && connectionTicket.isCurrent && client === transport) lost() }
        }, onFailure = { scope.launch { incoming.close(); if (ticket.isCurrent && connectionTicket.isCurrent && client === transport) lost() } })
        child.launch { delay(6000); if (!connected && client === transport) lost() }
    }
    private suspend fun receive(m: LanMessage, receivedAt: Double, initialTrack: moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack?) {
        when (m.type) {
            "WELCOME" -> welcome(m, initialTrack)
            "ROOM_EVENT" -> roomEvent(m)
            "NTP_RESPONSE" -> ntp(m, receivedAt)
            "SCHEDULED_ACTION" -> scheduler?.schedule(m)
            "LAN_PAUSED_STATE" -> scheduler?.paused(m)
            "LAN_ERROR" -> {
                val code = m.code ?: "NETWORK"
                if (code in setOf("SOURCE", "AUDIO_DURATION", "AUDIO_CONTENT", "LOAD_TIMEOUT", "MEMORY_LIMIT", "FULL_AUDIO_REQUIRED", "AUDIO_FORMAT")) scheduler?.close()
                reportError(code)
            }
            "LIVENESS_PING" -> send(LanMessage("LIVENESS_PONG"))
            "LAN_HOST_PLAY" -> if (state.value.hosting) requestPlayback(m.intent ?: "NEXT", m.track, m.trackTimeSeconds ?: 0.0)
            "LAN_HOST_STEP" -> if (state.value.hosting) playback.step(if (m.trackTimeSeconds == -1.0) -1 else 1)
        }
    }
    private fun roomEvent(m: LanMessage) {
        val event = m.event ?: return
        event.room?.let(::updateRoom)
        if (event.type == "LOAD_AUDIO_SOURCE") mutable.value = state.value.copy(preparing = true, receivedBytes = 0, totalBytes = 0)
        if (event.type == "LOAD_AUDIO_SOURCE" && event.track?.stableKey == event.audioSourceToPlay?.url) {
            scheduler?.load(m.copy(track = event.track, trackTimeSeconds = event.trackTimeSeconds,
                loadGeneration = event.loadGeneration, revision = event.revision, sourceUrls = event.sourceUrls, localMedia = event.localMedia))
        }
    }
    private fun welcome(m: LanMessage, initialTrack: moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack?) {
        if (m.protocolVersion != 2 || m.room == null || m.clientId == null) { leave("VERSION"); return }
        if (m.mediaPort != null && m.mediaToken != null) playback.mediaEndpoint(targetUrl, checkNotNull(m.mediaPort), checkNotNull(m.mediaToken))
        connected = true; attempts = 0; reconnect?.cancel(); responseAt = clock.nowMs()
        mutable.value = state.value.copy(phase = "SYNCING", selfId = m.clientId, room = m.room, error = null)
        updateRoom(checkNotNull(m.room))
        heartbeat?.cancel()
        heartbeat = sessionScope?.launch {
            while (isActive) {
                if (clock.nowMs() - responseAt > 15_000) { lost(); return@launch }
                val group = probeCounter++
                probe(group, 0); delay(25); probe(group, 1)
                delay(if (state.value.synced) 2475 else 25)
            }
        }
        if (initialTrack != null) requestPlayback("NEXT", initialTrack, 0.0)
    }
    private fun probe(group: Int, index: Int) {
        if (index == 0 && group % 24 == 0) keepAlive.renew()
        val now = clock.nowMs(); pendingProbes[group to index] = now
        while (pendingProbes.size > 128) pendingProbes.remove(pendingProbes.keys.first())
        send(LanMessage("NTP_REQUEST", t0 = now, probeGroupId = group, probeGroupIndex = index, clientRTT = state.value.rttMs,
            clientCompensationMs = ((playback as? LanScheduledAudioPort)?.outputLatencyMs.orZero() + state.value.nudgeMs).coerceAtLeast(0.0), clientNudgeMs = state.value.nudgeMs.toDouble()))
    }
    private suspend fun ntp(m: LanMessage, receivedAt: Double) {
        val sent = pendingProbes.remove((m.probeGroupId ?: return) to (m.probeGroupIndex ?: return)) ?: return
        val t1 = m.t1 ?: return; val t2 = m.t2 ?: return
        if (m.t0 != sent || t2 < t1) return
        val connection = connections.capture()
        val result = (if (playback is LanScheduledAudioPort) playback.receiveProbe(m, receivedAt) else estimator?.response(m, receivedAt)) ?: return
        if (!connection.isCurrent) return
        val firstSync = result.synced && !state.value.synced
        offset = result.offset
        mutable.value = state.value.copy(synced = result.synced, rttMs = result.rtt, phase = if (result.synced) "CONNECTED" else "SYNCING")
        if (firstSync) send(LanMessage("SYNC"))
    }
    private fun updateRoom(room: LanRoom) {
        if (room.id != (state.value.room?.id ?: room.id)) return
        mutable.value = state.value.copy(room = room)
        val member = room.members.find { it.id == state.value.selfId }
        if (member != null) scheduler?.gain(member.volume.coerceIn(0f, 1f))
    }
    private fun lost() {
        if (sessionScope == null || reconnect?.isActive == true) return
        connections.advance(); connected = false; heartbeat?.cancel(); scheduler?.close(); client?.close(); pendingProbes.clear()
        mutable.value = state.value.copy(phase = "RECONNECTING", synced = false, error = "NETWORK")
        if (state.value.hosting) { leave("NETWORK"); return }
        reconnect = sessionScope?.launch {
            if (++attempts >= 15) { leave("NETWORK"); return@launch }
            estimator?.reset(); (playback as? LanScheduledAudioPort)?.resetAudioClock(); offset = 0.0
            val base = (1000 * Math.pow(1.1, (attempts - 1).toDouble())).coerceAtMost(10000.0)
            delay((base * (1 + Random.nextDouble() * .15)).toLong())
            connect(null)
        }
    }
    fun requestPlayback(intent: String, track: moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack?, position: Double) {
        if (!connected) { reportError("NETWORK"); return }
        if (intent == "STEP") {
            send(LanMessage("LAN_REQUEST_STEP", audioSource = state.value.room?.playback?.track?.stableKey, trackTimeSeconds = position))
            return
        }
        if (intent == "NEXT" && track == null) { reportError("SOURCE"); return }
        sourceRequests.advance(); sourceJob?.cancel()
        if (intent == "PAUSE") {
            send(LanMessage("PAUSE", audioSource = state.value.room?.playback?.track?.stableKey, trackTimeSeconds = playback.positionSeconds()))
            return
        }
        if (!state.value.hosting) {
            send(LanMessage("PLAY", audioSource = track?.stableKey, intent = intent, track = track, trackTimeSeconds = position))
            return
        }
        val target = track ?: state.value.room?.playback?.track ?: return
        val ticket = sourceRequests.capture()
        mutable.update { it.copy(preparing = true, receivedBytes = 0, totalBytes = 0) }
        sourceJob = sessionScope?.launch {
            try {
                val source = playback.prepareSharedSource(target, refresh = intent == "NEXT")
                if (!ticket.isCurrent) return@launch
                if (source.urls.isEmpty() && source.localMedia == null) { reportError("FULL_AUDIO_REQUIRED"); return@launch }
                send(LanMessage("PLAY", audioSource = source.track.stableKey, intent = intent, track = source.track,
                    trackTimeSeconds = position, sourceUrls = source.urls, localMedia = source.localMedia))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportError(error.message?.takeIf { it in setOf("FULL_AUDIO_REQUIRED", "LOCAL_READ", "LOCAL_STORAGE", "LOCAL_COVER", "METADATA_LIMIT", "MEMORY_LIMIT", "AUDIO_FORMAT", "AUDIO_DURATION") } ?: "SOURCE") }
        }
    }

    fun setPermissions(value: LanPermissions) {
        if (!connected || !state.value.hosting) return
        savePermissions(value)
        send(LanMessage("LAN_PERMISSIONS", permissions = value))
    }
    fun setVolume(value: Float, member: String? = null) = send(LanMessage(if (member == null) "SET_GLOBAL_VOLUME" else "LAN_MEMBER_VOLUME", clientId = member, volume = value.coerceIn(0f, 1f)))
    fun nudge(delta: Int) {
        val previous = state.value.nudgeMs; val next = (previous + delta).coerceIn(-2000, 2000)
        mutable.value = state.value.copy(nudgeMs = next); saveNudge(next); scheduler?.nudgeChanged(next - previous)
    }
    fun sync() = send(LanMessage("SYNC"))
    fun reportError(code: String) { mutable.update { it.copy(error = code, preparing = if (code == "LOADING") it.preparing else false) } }
    fun clearError() { mutable.update { it.copy(error = null, preparing = false, receivedBytes = 0, totalBytes = 0) } }
    private fun send(message: LanMessage) { if (client?.send(message) != true && state.value.active) lost() }
    fun leave(error: String? = null) {
        requests.advance()
        scope.launch { lifecycle.withLock { stopResources(); mutable.value = LanSessionState(nudgeMs = state.value.nudgeMs, error = error) } }
    }
    private suspend fun stopResources() {
        sourceRequests.advance(); sourceJob?.cancel(); sourceJob = null
        connections.advance(); connected = false; sessionScope?.cancel(); sessionScope = null; heartbeat = null; reconnect = null
        client?.close(); client = null; scheduler?.close(); scheduler = null
        discovery.unregister(); keepAlive.release(); playback.end()
        val oldServer = server; server = null; engine = null
        val oldMediaServer = mediaServer; mediaServer = null
        val oldEstimator = estimator; estimator = null; pendingProbes.clear(); offset = 0.0; attempts = 0
        withContext(NonCancellable) { withContext(Dispatchers.IO) { oldServer?.stop(1500); oldMediaServer?.stop() }; oldEstimator?.close() }
    }
    private fun Double?.orZero(): Double = this ?: 0.0
    private fun localAddress(): String = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress ?: "127.0.0.1"
    companion object { private val ERRORS = setOf("SERVICE_STOPPED", "NAME", "ADDRESS", "SOURCE", "OUTPUT", "CLOUD_ACTIVE", "AUDIO_SUSPENDED", "AUDIO_ENGINE", "MEMORY_LIMIT", "AUDIO_FORMAT") }
}
