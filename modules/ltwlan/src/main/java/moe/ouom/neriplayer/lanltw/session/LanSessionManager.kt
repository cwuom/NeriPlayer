package moe.ouom.neriplayer.lanltw.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.common.concurrent.RequestGeneration
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.lanltw.core.*
import moe.ouom.neriplayer.lanltw.discovery.LanDiscovery
import moe.ouom.neriplayer.lanltw.media.LanSessionPresentation
import moe.ouom.neriplayer.lanltw.playback.*
import moe.ouom.neriplayer.lanltw.transport.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** App-owned facade; all room/session mutations are serialized on Dispatchers.Main. */
class LanSessionManager(
    private val context: Context, http: OkHttpClient, private val playback: LanPlaybackPort,
    private val cloudActive: () -> Boolean, private val saveNudge: (Int) -> Unit,
    private val loadPermissions: suspend () -> LanPermissions = { LanPermissions() },
    private val savePermissions: (LanPermissions) -> Unit = {},
    private val clock: LanClock = LanClock { SystemClock.elapsedRealtimeNanos() / 1_000_000.0 }
) {
    private data class InitialPlayback(val track: ListenTogetherTrack, val position: Double, val playing: Boolean)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycle = Mutex()
    private val requests = RequestGeneration()
    private val connections = RequestGeneration()
    private val sourceRequests = RequestGeneration()
    private var sourceJob: Job? = null
    private val clientHttp = http.newBuilder().proxy(java.net.Proxy.NO_PROXY).connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).pingInterval(0, TimeUnit.SECONDS).build()
    private val keepAlive = LanKeepAlive(context)
    private val presentation = LanSessionPresentation(http, File(context.cacheDir, "lan-local"))
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
        mutable.update { it.copy(hosting = true) }
        val track = playback.currentTrack() ?: error("SOURCE")
        val initial = InitialPlayback(track, playback.positionSeconds(), playback.isPlaying())
        mutable.update { it.copy(preparing = initial.playing) }
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
        mutable.value = state.value.copy(address = address, port = port)
        discovery.advertise(id, name.trim(), port) { scope.launch { if (ticket.isCurrent) reportError("DISCOVERY") } }
        child.launch { while (isActive) { delay(2500); transport.expireHandshakes(); engine?.tick()?.forEach(transport::disconnect); keepAlive.renew() } }
        targetUrl = "http://127.0.0.1:$port/ws"
        hello = LanMessage("HELLO", protocolVersion = LAN_PROTOCOL_VERSION, roomId = id, name = name.trim(), hostToken = token)
        connect(initial)
    }
    fun join(address: String, roomId: String?, name: String) = replaceSession {
        requireStart(name)
        val url = (if (address.contains("://")) address.replace("ws://", "http://") else "http://$address").toHttpUrlOrNull() ?: error("ADDRESS")
        if (url.scheme != "http" || url.username.isNotEmpty() || url.password.isNotEmpty()) error("ADDRESS")
        targetUrl = url.newBuilder().encodedPath("/ws").query(null).fragment(null).build().toString()
        hello = LanMessage("HELLO", protocolVersion = LAN_PROTOCOL_VERSION, roomId = roomId, name = name.trim())
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
        playback.watchAudioFailure { code -> scope.launch { if (progressTicket.isCurrent) audioFailed(code) } }
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
                    scheduler = LanPlaybackScheduler(checkNotNull(sessionScope), playback, clock, { offset },
                        { state.value.nudgeMs }, { state.value.synced }, ::send, ::reportError, ::clearError, ::resolvePresentation,
                        onUnseekableAction = { audioFailed(LAN_AUDIO_NOT_SEEKABLE) })
                    block()
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    stopResources(); mutable.value = LanSessionState(nudgeMs = state.value.nudgeMs, error = "NETWORK")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    stopResources()
                    mutable.value = LanSessionState(nudgeMs = state.value.nudgeMs,
                        error = error.message?.takeIf { it in ERRORS } ?: "NETWORK")
                }
            }
        }
    }
    private fun connect(initial: InitialPlayback?) {
        val connectionTicket = connections.advance()
        val child = sessionScope ?: return
        val ticket = requests.capture()
        val incoming = Channel<Pair<LanMessage, Double>>(128)
        child.launch {
            try { for ((m, receivedAt) in incoming) { if (ticket.isCurrent && connectionTicket.isCurrent) receive(m, receivedAt, initial) } }
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
    private suspend fun receive(m: LanMessage, receivedAt: Double, initial: InitialPlayback?) {
        when (m.type) {
            "WELCOME" -> welcome(m, initial)
            "ROOM_EVENT" -> roomEvent(m)
            "NTP_RESPONSE" -> ntp(m, receivedAt)
            "SCHEDULED_ACTION" -> if (!state.value.audioSuspended || m.scheduledAction?.type != "PLAY") scheduler?.schedule(m)
            "LAN_PAUSED_STATE" -> scheduler?.paused(m)
            "LAN_ERROR" -> roomError(m.code ?: "NETWORK")
            "LIVENESS_PING" -> send(LanMessage("LIVENESS_PONG"))
            "LAN_HOST_PLAY" -> if (state.value.hosting) publishStream(m)
            "LAN_HOST_STEP" -> if (state.value.hosting) playback.step(if (m.trackTimeSeconds == -1.0) -1 else 1)
            "LAN_PRESENTATION" -> scheduler?.presentation(m)
        }
    }
    private fun roomError(code: String) {
        if (code == "VERSION") { leave(code); return }
        if (canRetryPlayback(code)) {
            sourceRequests.advance()
            sourceJob?.cancel()
            scheduler?.close()
        }
        reportError(code)
    }
    private fun audioFailed(code: String) {
        if (!state.value.active) return
        if (code == "AUDIO_SUSPENDED") { interruptPlayback(); return }
        if (code in FATAL_AUDIO_ERRORS) { leave(code); return }
        val mediaId = scheduler?.mediaId
        scheduler?.close()
        reportError(code)
        if (state.value.hosting && mediaId != null) send(LanMessage("LAN_STREAM_FAILED", mediaId = mediaId, code = code))
    }
    private fun interruptPlayback() {
        val current = state.value
        if (current.audioSuspended) return
        val roomMayPlay = current.room?.playback?.let { it.playing || it.preparing } ?: (current.hosting && current.preparing)
        if (!roomMayPlay && !playback.isPlaying() && scheduler?.playScheduled != true) return
        mutable.update { it.copy(audioSuspended = true) }
        scheduler?.interruptPlayback()
        if (current.hosting && connected) send(LanMessage("PAUSE"))
    }
    private fun roomEvent(m: LanMessage) {
        val event = m.event ?: return
        event.room?.let(::updateRoom)
        if (event.type == "LOAD_AUDIO_SOURCE") {
            mutable.value = state.value.copy(preparing = true, receivedBytes = 0, totalBytes = 0)
            scheduler?.load(m.copy(track = event.track, trackTimeSeconds = event.trackTimeSeconds,
                loadGeneration = event.loadGeneration, revision = event.revision, streamResource = event.streamResource))
        }
    }
    private fun welcome(m: LanMessage, initial: InitialPlayback?) {
        val room = m.room
        if (m.protocolVersion != LAN_PROTOCOL_VERSION || room == null || m.clientId == null) { leave("VERSION"); return }
        setMediaEndpoint(m)
        connected = true; attempts = 0; reconnect?.cancel(); responseAt = clock.nowMs()
        mutable.value = state.value.copy(phase = "SYNCING", selfId = m.clientId, room = room, error = null,
            preparing = room.playback.preparing)
        updateRoom(room)
        heartbeat?.cancel()
        heartbeat = sessionScope?.launch {
            val liveness = LanPeerLiveness(15_000.0)
            while (isActive) {
                if (liveness.check(clock.nowMs(), responseAt) == LanPeerLiveness.Status.EXPIRED) { lost(); return@launch }
                // 时钟探测的回包同时确认连接仍可用
                val group = probeCounter++
                probe(group, 0); delay(25); probe(group, 1)
                delay(if (state.value.synced) 2475 else 25)
            }
        }
        if (initial != null) {
            val startPlaying = initial.playing && !state.value.audioSuspended
            requestPlayback(if (startPlaying) "START" else "START_PAUSED", initial.track, initial.position)
        }
    }
    private fun setMediaEndpoint(m: LanMessage) {
        val port = m.mediaPort ?: return
        val token = m.mediaToken ?: return
        playback.mediaEndpoint(targetUrl, port, token)
        presentation.setEndpoint(targetUrl, port, token)
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
        mutable.update {
            val newPreparation = room.playback.preparing && room.playback.revision != it.room?.playback?.revision
            it.copy(room = room, preparing = newPreparation || it.preparing)
        }
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
    fun requestPlayback(intent: String, track: ListenTogetherTrack?, position: Double) {
        if (!connected) { reportError("NETWORK"); return }
        if (intent == "PLAY_PAUSE" && state.value.audioSuspended) {
            if (state.value.room?.playback?.playing == true) { sync(); return }
            mutable.update { it.copy(audioSuspended = false) }
        }
        if (intent == "PLAY_PAUSE" && syncUnseekableGuest()) return
        if (intent == "FINISHED") { sendTrackFinished(track); return }
        if (intent == "STEP") {
            send(LanMessage("LAN_REQUEST_STEP", audioSource = state.value.room?.playback?.track?.stableKey, trackTimeSeconds = position))
            return
        }
        if (intent == "NEXT" && track == null) { reportError("SOURCE"); return }
        if (intent == "PAUSE") {
            send(LanMessage("PAUSE", audioSource = state.value.room?.playback?.track?.stableKey, trackTimeSeconds = playback.positionSeconds()))
            return
        }
        val target = if (intent == "NEXT") track else state.value.room?.playback?.track ?: track
        val clean = target?.copy(streamUrl = null, streamUrls = emptyList(), mediaUri = null, coverUrl = null)
        val command = if (intent == "PLAY_PAUSE" && canRetryPlayback(state.value.error)) "RETRY" else intent
        send(LanMessage("PLAY", audioSource = clean?.stableKey, intent = command, track = clean, trackTimeSeconds = position))
    }
    private fun sendTrackFinished(track: ListenTogetherTrack?) {
        if (!state.value.hosting) return
        val player = scheduler ?: return
        val mediaId = player.mediaId ?: return
        val key = track?.stableKey ?: state.value.room?.playback?.track?.stableKey ?: return
        send(LanMessage("LAN_TRACK_FINISHED", audioSource = key, mediaId = mediaId, revision = player.playbackRevision))
    }
    private fun syncUnseekableGuest(): Boolean {
        val current = state.value
        if (current.hosting || current.error != LAN_AUDIO_NOT_SEEKABLE) return false
        val plan = current.room?.playback ?: return false
        if (!plan.playing && plan.positionSeconds == 0.0) return false
        sync()
        return true
    }

    private fun publishStream(message: LanMessage) {
        val target = message.track ?: return
        val generation = message.loadGeneration ?: return
        val ticket = sourceRequests.advance()
        sourceJob?.cancel()
        mutable.update { it.copy(preparing = true, receivedBytes = 0, totalBytes = 0, error = null) }
        sourceJob = sessionScope?.launch {
            try {
                val source = playback.prepareSharedSource(target, refresh = true)
                if (!ticket.isCurrent) return@launch
                send(LanMessage("LAN_STREAM_PUBLISHED", loadGeneration = generation, streamResource = source.streamResource))
                publishStreamPresentation(source)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!ticket.isCurrent) return@launch
                val code = error.message?.takeIf(::canRetryPlayback) ?: "SOURCE"
                send(LanMessage("LAN_STREAM_FAILED", loadGeneration = generation, code = code))
            }
        }
    }
    private suspend fun publishStreamPresentation(source: LanPreparedSource) {
        val mediaId = source.streamResource.mediaId
        try {
            flow {
                source.presentation?.let { emit(it) }
                emitAll(playback.sharedPresentationUpdates(source.track, mediaId))
            }.distinctUntilChanged().collect { value ->
                val resource = publishPresentation(value) ?: return@collect
                currentCoroutineContext().ensureActive()
                send(LanMessage("LAN_PRESENTATION_PUBLISHED", mediaId = mediaId, presentationResource = resource))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
    private suspend fun publishPresentation(value: LanSharedPresentation): LanFileResource? {
        val files = mediaServer ?: return null
        return try { presentation.publish(files, value) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    private suspend fun resolvePresentation(resource: LanFileResource): LanSharedPresentation? =
        withTimeoutOrNull(5000) {
            try { presentation.resolve(resource) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
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
    fun sync() {
        mutable.update { it.copy(audioSuspended = false) }
        scheduler?.resetRecovery()
        send(LanMessage("SYNC"))
    }
    fun reportError(code: String) {
        mutable.update {
            if (code == "LOADING") it.copy(error = null, preparing = true) else it.copy(error = code, preparing = false)
        }
    }
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
        presentation.clear()
        discovery.unregister(); keepAlive.release(); playback.end()
        val oldServer = server; server = null; engine = null
        val oldMediaServer = mediaServer; mediaServer = null
        val oldEstimator = estimator; estimator = null; pendingProbes.clear(); offset = 0.0; attempts = 0
        withContext(NonCancellable) { withContext(Dispatchers.IO) { oldServer?.stop(1500); oldMediaServer?.stop() }; oldEstimator?.close() }
    }
    private fun Double?.orZero(): Double = this ?: 0.0
    private fun localAddress(): String {
        // 多网卡时优先取活动局域网的地址，离线 Wi-Fi 或热点仍可使用本机接口
        return runCatching(::activeLanAddress).getOrNull() ?: NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress }?.hostAddress ?: "127.0.0.1"
    }
    private fun activeLanAddress(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress && !it.address.isLoopbackAddress }
            ?.address?.hostAddress
    }
    companion object {
        private val ERRORS = setOf("SERVICE_STOPPED", "NAME", "ADDRESS", "SOURCE", "OUTPUT", "CLOUD_ACTIVE", "AUDIO_SUSPENDED", "AUDIO_ENGINE", "MEMORY_LIMIT", "AUDIO_FORMAT")
        private val RETRYABLE_ERRORS = setOf("SOURCE", "AUDIO_DURATION", "AUDIO_CONTENT", "LOAD_TIMEOUT", "MEMORY_LIMIT",
            "FULL_AUDIO_REQUIRED", "AUDIO_FORMAT", "SOURCE_CHANGED", "LOCAL_READ", "LOCAL_STORAGE", "LOCAL_TRANSFER", LAN_AUDIO_NOT_SEEKABLE)
        fun canRetryPlayback(error: String?): Boolean = error in RETRYABLE_ERRORS
        private val FATAL_AUDIO_ERRORS = setOf("OUTPUT", "AUDIO_ENGINE", "SERVICE_STOPPED")
    }
}
