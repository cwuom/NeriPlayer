package moe.ouom.neriplayer.core.player.dlna.session

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.dlna.audio.LosslessSourceResolver
import moe.ouom.neriplayer.core.player.dlna.protocol.DidlLiteBuilder
import moe.ouom.neriplayer.core.player.dlna.protocol.DlnaProtocolInfo
import moe.ouom.neriplayer.core.player.dlna.protocol.SsdpDiscoveryClient
import moe.ouom.neriplayer.core.player.dlna.protocol.UpnpControlClient
import moe.ouom.neriplayer.core.player.dlna.server.DlnaLanAddressResolver
import moe.ouom.neriplayer.core.player.dlna.server.DlnaMediaServer
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaCastState
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaConnectionStatus
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDeviceOptions
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaTransportState
import java.util.concurrent.atomic.AtomicLong

/**
 * Integrated lossless DLNA casting controller for NeriPlayer (targeting Shanling EM7
 * and standard UPnP AVTransport/RenderingControl renderers).
 */
object DlnaCastManager {

    private const val TAG = "NERI-DlnaCast"
    private const val POLL_INTERVAL_MS = 1_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _castStateFlow = MutableStateFlow(DlnaCastState())
    val castStateFlow: StateFlow<DlnaCastState> = _castStateFlow

    private val generationCounter = AtomicLong(0L)

    @Volatile
    private var initialized = false
    private lateinit var app: Application
    private lateinit var prefsStore: DlnaPreferencesStore
    private lateinit var sourceResolver: LosslessSourceResolver
    private lateinit var lockController: DlnaNetworkAndLockController

    private val discoveryClient = SsdpDiscoveryClient()
    private val controlClient = UpnpControlClient()
    private val mediaServer = DlnaMediaServer(
        onGenaNotify = { _, values -> handleGenaNotify(values) },
        onLog = { msg -> NPLogger.d(TAG, msg) }
    )

    private var discoveryJob: Job? = null
    private var connectJob: Job? = null
    private var castJob: Job? = null
    private var nextPrequeueJob: Job? = null
    private var pollJob: Job? = null
    private var activeGenaSid: String? = null
    private var activeGenaEventSubUrl: String? = null
    private var activeStreamPath: String? = null
    private val queueRevisionCounter = AtomicLong(0L)
    private val nextUriMutex = Mutex()
    private val volumeMutex = Mutex()

    @Volatile
    private var desiredRemotePlaying = true
    @Volatile
    private var pendingPausedSeekMs: Long? = null
    @Volatile
    private var pendingVolumeTarget: Int? = null
    @Volatile
    private var activeBoundNetwork: android.net.Network? = null

    private data class QueuedNextTrack(
        val forGenerationId: Long,
        val song: SongItem,
        val snapshot: moe.ouom.neriplayer.data.model.playback.dlna.DlnaPlaybackSnapshot,
        val qualityReport: moe.ouom.neriplayer.data.model.playback.dlna.DlnaAudioQualityReport,
        val streamUrl: String,
        val streamPath: String,
        val coverPath: String?,
        val didl: String,
        val sentToRenderer: Boolean,
        val rejectedByRenderer: Boolean = false
    )

    @Volatile
    private var queuedNext: QueuedNextTrack? = null

    @Volatile
    private var userPausedOrStopped = false
    @Volatile
    private var hasStartedPlayingInGeneration = false
    @Volatile
    private var consecutiveStoppedPolls = 0
    @Volatile
    private var consecutiveEndPausedPolls = 0
    @Volatile
    private var lastPlayingPositionMs = 0L
    @Volatile
    private var lastObservedTransportState = DlnaTransportState.UNKNOWN

    // Hook callbacks invoked on main thread to keep PlayerManager in sync
    internal interface PlayerSyncHost {
        fun onSwitchToDlnaPauseLocal()
        fun onSwitchBackToLocal(resumePositionMs: Long, wasPlaying: Boolean)
        fun onDlnaPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, durationMs: Long)
        fun onDlnaTrackEnded(generationId: Long)
        fun onDlnaGaplessTransitionedToNext(expectedSong: SongItem)
        fun peekNextAutoTrack(): SongItem?
        fun onDlnaCastError(message: String)
        suspend fun resolveSongUrlForDlna(song: SongItem): SongUrlResult
        fun currentSong(): SongItem?
        fun currentPositionMs(): Long
        fun isLocalPlaying(): Boolean
    }

    @Volatile
    internal var playerHost: PlayerSyncHost? = null

    @Synchronized
    fun ensureInitialized(application: Application) {
        if (initialized) return
        app = application
        prefsStore = DlnaPreferencesStore(application)
        sourceResolver = LosslessSourceResolver(
            context = application,
            okHttpClient = runCatching { PlayerDependencies.repositories.sharedOkHttpClient }
                .getOrElse { okhttp3.OkHttpClient() },
            biliCookieHeaderProvider = {
                runCatching {
                    PlayerDependencies.repositories.biliCookieRepo.getCookiesOnce()
                        .entries
                        .joinToString("; ") { "${it.key}=${it.value}" }
                }.getOrDefault("")
            },
            getLocalizedString = { resId, args -> localized(resId, *args) }
        ).also { resolver ->
            resolver.pinnedPathsProvider = {
                buildSet {
                    activeStreamPath?.let { path ->
                        mediaServer.getStreamBackingFile(path)?.absolutePath?.let(::add)
                    }
                    queuedNext?.streamPath?.let { path ->
                        mediaServer.getStreamBackingFile(path)?.absolutePath?.let(::add)
                    }
                }
            }
        }
        lockController = DlnaNetworkAndLockController(
            context = application,
            onLanNetworkAvailable = {
                NPLogger.d(TAG, "LAN network available")
                if (_castStateFlow.value.isDiscovering) {
                    refreshDiscovery()
                }
            },
            onLanNetworkLost = { lostNetwork ->
                NPLogger.w(TAG, "LAN network lost during DLNA session")
                handleLanLost(lostNetwork)
            }
        )
        lockController.registerNetworkCallback()
        val savedOptions = prefsStore.loadAllDeviceOptions()
        val manualDevices = prefsStore.loadManualDevices()
        _castStateFlow.update {
            it.copy(
                initialized = true,
                discoveredDevices = manualDevices,
                deviceOptionsByUdn = savedOptions
            )
        }
        initialized = true
    }

    fun isCastingActive(): Boolean = _castStateFlow.value.isCastingActive

    fun isRemotePlaying(): Boolean {
        val state = _castStateFlow.value
        return state.isCastingActive && state.transportState == DlnaTransportState.PLAYING
    }

    fun isRemoteBuffering(): Boolean {
        val state = _castStateFlow.value
        return state.isCastingActive && state.transportState == DlnaTransportState.TRANSITIONING
    }

    fun refreshDiscovery() {
        if (!initialized) return
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            _castStateFlow.update { it.copy(isDiscovering = true, errorMessage = null) }
            lockController.acquireMulticastLock()
            try {
                val known = (_castStateFlow.value.discoveredDevices + prefsStore.loadManualDevices())
                    .distinctBy { it.udn }
                val found = discoveryClient.scanOnce(knownDevices = known) { log ->
                    NPLogger.d(TAG, log)
                }
                val merged = mergeDiscoveredDevices(known, found)
                ensureDefaultDeviceOptions(merged)
                _castStateFlow.update { state ->
                    val updatedActive = state.activeDevice?.let { active ->
                        merged.firstOrNull { it.udn == active.udn } ?: active
                    }
                    state.copy(
                        isDiscovering = false,
                        discoveredDevices = merged,
                        activeDevice = updatedActive
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(TAG, "SSDP discovery failed", e)
                _castStateFlow.update {
                    it.copy(isDiscovering = false, errorMessage = e.message)
                }
            } finally {
                lockController.releaseMulticastLock()
            }
        }
    }

    fun addManualDevice(addressOrUrl: String, onComplete: (Result<DlnaDevice>) -> Unit = {}) {
        if (!initialized) {
            onComplete(Result.failure(IllegalStateException("DlnaCastManager not initialized")))
            return
        }
        scope.launch {
            val result = discoveryClient.probeManualDevice(addressOrUrl) { log ->
                NPLogger.d(TAG, log)
            }
            result.onSuccess { device ->
                prefsStore.saveManualDevice(device)
                ensureDefaultDeviceOptions(listOf(device))
                _castStateFlow.update { state ->
                    val updated = (state.discoveredDevices.filterNot { it.udn == device.udn } + device)
                    state.copy(discoveredDevices = updated, errorMessage = null)
                }
            }.onFailure { err ->
                _castStateFlow.update { it.copy(errorMessage = err.message) }
            }
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    fun removeManualDevice(udn: String) {
        if (!initialized) return
        if (_castStateFlow.value.activeDevice?.udn == udn) {
            switchToLocalPlayback()
        }
        prefsStore.removeManualDevice(udn)
        _castStateFlow.update { state ->
            state.copy(discoveredDevices = state.discoveredDevices.filterNot { it.udn == udn })
        }
    }

    fun updateDeviceOptions(udn: String, transform: (DlnaDeviceOptions) -> DlnaDeviceOptions) {
        if (!initialized) return
        val current = _castStateFlow.value.optionsFor(udn)
        val updated = transform(current)
        prefsStore.saveDeviceOptions(udn, updated)
        _castStateFlow.update { state ->
            state.copy(deviceOptionsByUdn = state.deviceOptionsByUdn + (udn to updated))
        }
    }

    fun selectDevice(device: DlnaDevice) {
        if (!initialized) return
        val host = playerHost
        val currentSong = host?.currentSong()
        val previousState = _castStateFlow.value
        val previousDevice = previousState.activeDevice
        val wasPlayingLocal = host?.isLocalPlaying() == true
        val shouldAutoPlay = if (previousDevice != null) {
            previousState.transportState == DlnaTransportState.PLAYING || desiredRemotePlaying
        } else {
            wasPlayingLocal
        }
        val resumePosMs = if (previousDevice != null) {
            previousState.remotePositionMs
        } else {
            host?.currentPositionMs() ?: 0L
        }

        // Immediately silence local playback before switching to DLNA so both never play together
        host?.onSwitchToDlnaPauseLocal()

        connectJob?.cancel()
        castJob?.cancel()
        nextPrequeueJob?.cancel()
        pollJob?.cancel()
        val selectionGen = generationCounter.incrementAndGet()
        queueRevisionCounter.incrementAndGet()
        desiredRemotePlaying = shouldAutoPlay
        pendingPausedSeekMs = null

        val prevSid = activeGenaSid
        val prevEventSubUrl = activeGenaEventSubUrl
        val prevStreamPath = activeStreamPath
        val prevQueued = queuedNext
        activeGenaSid = null
        activeGenaEventSubUrl = null
        activeStreamPath = null
        queuedNext = null
        mediaServer.clearGenaSubscriptions()
        mediaServer.unregisterStream(prevStreamPath)
        mediaServer.unregisterStream(prevQueued?.streamPath)

        _castStateFlow.update {
            it.copy(
                activeDevice = device,
                connectionStatus = DlnaConnectionStatus.CONNECTING,
                transportState = DlnaTransportState.TRANSITIONING,
                currentGenerationId = selectionGen,
                errorMessage = null,
                statusMessage = localized(CoreCommonR.string.dlna_status_connecting_device, device.friendlyName)
            )
        }

        connectJob = scope.launch {
            try {
                if (previousDevice != null && previousDevice.udn != device.udn) {
                    if (!prevSid.isNullOrBlank() && !prevEventSubUrl.isNullOrBlank()) {
                        runCatching { controlClient.unsubscribeGena(prevEventSubUrl, prevSid) }
                    }
                    runCatching { controlClient.stop(previousDevice) }
                }
                if (selectionGen != generationCounter.get()) return@launch

                mediaServer.start()
                if (selectionGen != generationCounter.get()) return@launch
                lockController.acquireCastLocks()

                val refreshedDevice = discoveryClient.fetchDeviceDescription(
                    locationUrl = device.location,
                    fallbackUdn = device.udn,
                    isManual = device.isManual
                ) ?: device
                if (selectionGen != generationCounter.get()) return@launch

                val sinkProtocolInfo = controlClient.getProtocolInfo(refreshedDevice).getOrDefault("")
                if (selectionGen != generationCounter.get()) return@launch

                val connectedDevice = refreshedDevice.copy(
                    sinkProtocolInfo = sinkProtocolInfo,
                    supportsFlac = DlnaProtocolInfo.supportsFlac(sinkProtocolInfo),
                    supportsWav = DlnaProtocolInfo.supportsWav(sinkProtocolInfo),
                    online = true
                )

                // Read initial remote volume safely; NEVER auto-set to max!
                val volumeResult = controlClient.getVolume(connectedDevice)
                if (selectionGen != generationCounter.get()) return@launch
                val initialVolume = volumeResult.getOrNull()
                val volumeSupported = volumeResult.isSuccess && connectedDevice.renderingControlUrl.isNotBlank()

                mediaServer.allowedPeerHost = connectedDevice.host
                mediaServer.expectedGenaCallbackPath = "/dlna/event/avtransport/$selectionGen"
                activeBoundNetwork = null

                _castStateFlow.update { state ->
                    val list = state.discoveredDevices.map {
                        if (it.udn == connectedDevice.udn) connectedDevice else it
                    }
                    state.copy(
                        activeDevice = connectedDevice,
                        discoveredDevices = list,
                        connectionStatus = DlnaConnectionStatus.CONNECTED,
                        remoteVolume = initialVolume,
                        volumeSupported = volumeSupported,
                        statusMessage = localized(CoreCommonR.string.dlna_status_connected_device, connectedDevice.friendlyName),
                        errorMessage = null
                    )
                }

                subscribeGenaIfAvailable(connectedDevice, selectionGen)
                startPollingLoop(connectedDevice)

                if (currentSong != null && selectionGen == generationCounter.get()) {
                    castSongInternal(
                        song = currentSong,
                        startPositionMs = resumePosMs,
                        autoPlay = desiredRemotePlaying
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(TAG, "selectDevice failed", e)
                if (selectionGen == generationCounter.get()) {
                    lockController.releaseCastLocks()
                    mediaServer.allowedPeerHost = null
                    mediaServer.clearGenaSubscriptions()
                    _castStateFlow.update {
                        it.copy(
                            activeDevice = null,
                            connectionStatus = DlnaConnectionStatus.ERROR,
                            transportState = DlnaTransportState.UNKNOWN,
                            statusMessage = null,
                            errorMessage = e.message ?: "DLNA connection failed"
                        )
                    }
                }
            }
        }
    }

    fun switchToLocalPlayback(resumeLocalPlayback: Boolean = true) {
        val state = _castStateFlow.value
        val active = state.activeDevice
        val wasPlayingRemote = state.transportState == DlnaTransportState.PLAYING
        val remotePositionMs = state.remotePositionMs

        connectJob?.cancel()
        castJob?.cancel()
        nextPrequeueJob?.cancel()
        pollJob?.cancel()
        val retiredGen = generationCounter.incrementAndGet()
        queueRevisionCounter.incrementAndGet()
        userPausedOrStopped = true
        desiredRemotePlaying = false
        pendingPausedSeekMs = null

        val oldStreamPath = activeStreamPath
        val oldQueued = queuedNext
        val oldSid = activeGenaSid
        val oldEventSubUrl = activeGenaEventSubUrl
        activeStreamPath = null
        queuedNext = null
        activeGenaSid = null
        activeGenaEventSubUrl = null
        activeBoundNetwork = null
        mediaServer.clearGenaSubscriptions()
        mediaServer.allowedPeerHost = null
        mediaServer.unregisterStream(oldStreamPath)
        mediaServer.unregisterStream(oldQueued?.streamPath)

        if (active != null) {
            scope.launch {
                if (!oldSid.isNullOrBlank() && !oldEventSubUrl.isNullOrBlank()) {
                    runCatching { controlClient.unsubscribeGena(oldEventSubUrl, oldSid) }
                }
                runCatching { controlClient.stop(active) }
            }
        }
        lockController.releaseCastLocks()

        _castStateFlow.update {
            it.copy(
                activeDevice = null,
                connectionStatus = DlnaConnectionStatus.DISCONNECTED,
                transportState = DlnaTransportState.UNKNOWN,
                remoteVolume = null,
                volumeSupported = false,
                currentSnapshot = null,
                qualityReport = null,
                statusMessage = null,
                errorMessage = null,
                currentGenerationId = retiredGen
            )
        }

        if (resumeLocalPlayback && active != null) {
            playerHost?.onSwitchBackToLocal(remotePositionMs, wasPlayingRemote)
        }
    }

    fun disableStrictLosslessAndRetryCurrent(
        expectedDeviceUdn: String? = null,
        expectedSongKey: String? = null
    ) {
        val state = _castStateFlow.value
        val active = state.activeDevice ?: return
        if (expectedDeviceUdn != null && active.udn != expectedDeviceUdn) return
        val report = state.qualityReport
        if (expectedSongKey != null && report?.songKey != expectedSongKey) return
        val song = playerHost?.currentSong() ?: return
        if (expectedSongKey != null && song.stableKey() != expectedSongKey) return
        updateDeviceOptions(active.udn) { it.copy(strictLosslessMode = false) }
        val resumeMs = state.remotePositionMs.takeIf { it > 0L } ?: (playerHost?.currentPositionMs() ?: 0L)
        castSong(song = song, startPositionMs = resumeMs, autoPlay = true)
    }

    fun castSong(song: SongItem, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        if (!isCastingActive()) return
        castJob?.cancel()
        nextPrequeueJob?.cancel()
        castJob = scope.launch {
            castSongInternal(song, startPositionMs, autoPlay)
        }
    }

    fun invalidateQueuedNext() {
        if (!initialized || !isCastingActive()) return
        val newRev = queueRevisionCounter.incrementAndGet()
        nextPrequeueJob?.cancel()
        val oldNext = queuedNext
        queuedNext = null
        val device = _castStateFlow.value.activeDevice ?: return
        val activeGen = generationCounter.get()
        if (oldNext != null) {
            scope.launch {
                nextUriMutex.withLock {
                    if (oldNext.sentToRenderer) {
                        runCatching { controlClient.setNextAvTransportUri(device, "", "") }
                    }
                    if (oldNext.streamPath != activeStreamPath) {
                        mediaServer.unregisterStream(oldNext.streamPath)
                    }
                }
            }
        }
        if (hasStartedPlayingInGeneration && !userPausedOrStopped && newRev == queueRevisionCounter.get()) {
            scheduleNextTrackPrequeue(device, activeGen)
        }
    }

    fun onQueueFinishedStop() {
        if (!initialized || !isCastingActive()) return
        val device = _castStateFlow.value.activeDevice ?: return
        connectJob?.cancel()
        castJob?.cancel()
        nextPrequeueJob?.cancel()
        val stopGen = generationCounter.incrementAndGet()
        queueRevisionCounter.incrementAndGet()
        clearQueuedNext()
        userPausedOrStopped = true
        desiredRemotePlaying = false
        pendingPausedSeekMs = null
        hasStartedPlayingInGeneration = false
        consecutiveStoppedPolls = 0
        consecutiveEndPausedPolls = 0
        lastPlayingPositionMs = 0L
        _castStateFlow.update {
            it.copy(
                currentGenerationId = stopGen,
                transportState = DlnaTransportState.STOPPED,
                remotePositionMs = 0L
            )
        }
        scope.launch {
            controlClient.stop(device)
        }
    }

    private suspend fun castSongInternal(
        song: SongItem,
        startPositionMs: Long,
        autoPlay: Boolean
    ) {
        val device = _castStateFlow.value.activeDevice ?: return
        val host = playerHost ?: return
        val gen = generationCounter.incrementAndGet()
        nextPrequeueJob?.cancel()
        clearQueuedNext()
        desiredRemotePlaying = autoPlay
        pendingPausedSeekMs = null
        userPausedOrStopped = !autoPlay
        hasStartedPlayingInGeneration = false
        consecutiveStoppedPolls = 0
        consecutiveEndPausedPolls = 0
        lastPlayingPositionMs = 0L
        lastObservedTransportState = DlnaTransportState.TRANSITIONING

        _castStateFlow.update {
            it.copy(
                currentGenerationId = gen,
                transportState = DlnaTransportState.TRANSITIONING,
                statusMessage = localized(CoreCommonR.string.dlna_status_resolving_lossless),
                errorMessage = null
            )
        }

        try {
            val urlResult = host.resolveSongUrlForDlna(song)
            if (gen != generationCounter.get()) return
            if (urlResult !is SongUrlResult.Success) {
                val msg = when (urlResult) {
                    SongUrlResult.RequiresLogin -> localized(CoreCommonR.string.dlna_err_login_required)
                    else -> localized(CoreCommonR.string.dlna_err_no_playable_url, song.name)
                }
                failCastGeneration(gen, msg)
                return
            }

            val options = _castStateFlow.value.optionsFor(device.udn)
            val requestedLossless = isRequestedLosslessForSong(song)
            val outcome = sourceResolver.prepareForCast(
                song = song,
                generationId = gen,
                urlResult = urlResult,
                requestedLosslessQuality = requestedLossless,
                device = device,
                options = options,
                isCancelled = { gen != generationCounter.get() }
            )
            if (gen != generationCounter.get()) return

            when (outcome) {
                is LosslessSourceResolver.ResolutionOutcome.Blocked -> {
                    val prevPath = activeStreamPath
                    activeStreamPath = null
                    mediaServer.unregisterStream(prevPath)
                    pendingPausedSeekMs = null
                    runCatching { controlClient.stop(device) }
                    _castStateFlow.update {
                        it.copy(
                            currentSnapshot = null,
                            qualityReport = outcome.qualityReport,
                            transportState = DlnaTransportState.STOPPED,
                            remotePositionMs = 0L,
                            remoteDurationMs = 0L,
                            errorMessage = outcome.reason,
                            statusMessage = null
                        )
                    }
                    withContext(Dispatchers.Main) {
                        host.onDlnaCastError(outcome.reason)
                    }
                    return
                }
                is LosslessSourceResolver.ResolutionOutcome.Failed -> {
                    failCastGeneration(gen, outcome.reason)
                    return
                }
                is LosslessSourceResolver.ResolutionOutcome.Ready -> {
                    mediaServer.start()
                    val lanIp = DlnaLanAddressResolver.resolveLanIpv4ForTarget(device.host)
                        ?: run {
                            failCastGeneration(gen, localized(CoreCommonR.string.dlna_err_no_lan_ip))
                            return
                        }

                    val previousStreamPath = activeStreamPath
                    val newStreamPath = mediaServer.registerStream(
                        extension = outcome.streamExtension,
                        content = outcome.streamContent
                    )
                    val newCoverPath = outcome.coverBytes?.let(mediaServer::registerArtwork)
                    val snapshot = outcome.snapshot.copy(
                        streamPath = newStreamPath,
                        coverPath = newCoverPath
                    )
                    val streamUrl = mediaServer.buildUrl(lanIp, newStreamPath)
                    val coverUrl = newCoverPath?.let { mediaServer.buildUrl(lanIp, it) }
                    val didl = DidlLiteBuilder.buildDidlLite(snapshot, streamUrl, coverUrl)

                    activeStreamPath = newStreamPath
                    // Retire previous stream after short overlap or replacement
                    if (previousStreamPath != null && previousStreamPath != newStreamPath) {
                        mediaServer.unregisterStream(previousStreamPath)
                    }

                    _castStateFlow.update {
                        it.copy(
                            currentSnapshot = snapshot,
                            qualityReport = outcome.qualityReport,
                            remoteDurationMs = snapshot.durationMs,
                            remotePositionMs = startPositionMs.coerceAtLeast(0L),
                            statusMessage = localized(
                                CoreCommonR.string.dlna_status_casting_to,
                                snapshot.title,
                                device.friendlyName
                            )
                        )
                    }

                    // Stop any prior stream cleanly on renderer, set new URI + DIDL, then Play
                    controlClient.stop(device)
                    val setRes = controlClient.setAvTransportUri(device, streamUrl, didl)
                    if (setRes.isFailure) {
                        failCastGeneration(
                            gen,
                            localized(
                                CoreCommonR.string.dlna_err_set_uri_failed,
                                setRes.exceptionOrNull()?.message.orEmpty()
                            )
                        )
                        return
                    }
                    if (gen != generationCounter.get()) return

                    if (desiredRemotePlaying) {
                        val playRes = controlClient.play(device)
                        if (playRes.isFailure) {
                            failCastGeneration(
                                gen,
                                localized(
                                    CoreCommonR.string.dlna_err_play_failed,
                                    playRes.exceptionOrNull()?.message.orEmpty()
                                )
                            )
                            return
                        }
                        if (startPositionMs > 1_500L) {
                            delay(250L)
                            if (gen == generationCounter.get()) {
                                controlClient.seekRelTime(device, startPositionMs)
                            }
                        }
                        _castStateFlow.update {
                            it.copy(
                                transportState = DlnaTransportState.PLAYING,
                                statusMessage = localized(CoreCommonR.string.dlna_status_playing_on, device.friendlyName)
                            )
                        }
                        withContext(Dispatchers.Main) {
                            host.onDlnaPlaybackStateChanged(
                                isPlaying = true,
                                positionMs = startPositionMs,
                                durationMs = snapshot.durationMs
                            )
                        }
                    } else {
                        if (startPositionMs > 1_500L) {
                            val seekRes = controlClient.seekRelTime(device, startPositionMs)
                            if (seekRes.isFailure) {
                                pendingPausedSeekMs = startPositionMs
                            }
                        }
                        _castStateFlow.update {
                            it.copy(transportState = DlnaTransportState.PAUSED_PLAYBACK)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NPLogger.e(TAG, "castSongInternal failed", e)
            if (gen == generationCounter.get()) {
                failCastGeneration(gen, e.message ?: "DLNA cast error")
            }
        }
    }

    fun playRemote() {
        val device = _castStateFlow.value.activeDevice ?: return
        desiredRemotePlaying = true
        userPausedOrStopped = false
        consecutiveStoppedPolls = 0
        consecutiveEndPausedPolls = 0
        scope.launch {
            val state = _castStateFlow.value
            if (state.currentSnapshot == null || state.transportState == DlnaTransportState.NO_MEDIA_PRESENT) {
                playerHost?.currentSong()?.let { castSongInternal(it, state.remotePositionMs, autoPlay = true) }
                return@launch
            }
            controlClient.play(device).onSuccess {
                val pendingSeek = pendingPausedSeekMs
                if (pendingSeek != null && pendingSeek > 1_500L) {
                    pendingPausedSeekMs = null
                    delay(200L)
                    controlClient.seekRelTime(device, pendingSeek)
                }
                _castStateFlow.update { it.copy(transportState = DlnaTransportState.PLAYING, errorMessage = null) }
                withContext(Dispatchers.Main) {
                    playerHost?.onDlnaPlaybackStateChanged(
                        isPlaying = true,
                        positionMs = _castStateFlow.value.remotePositionMs,
                        durationMs = _castStateFlow.value.remoteDurationMs
                    )
                }
            }.onFailure { err ->
                _castStateFlow.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun pauseRemote() {
        val device = _castStateFlow.value.activeDevice ?: return
        desiredRemotePlaying = false
        userPausedOrStopped = true
        consecutiveEndPausedPolls = 0
        scope.launch {
            controlClient.pause(device).onSuccess {
                _castStateFlow.update { it.copy(transportState = DlnaTransportState.PAUSED_PLAYBACK) }
                withContext(Dispatchers.Main) {
                    playerHost?.onDlnaPlaybackStateChanged(
                        isPlaying = false,
                        positionMs = _castStateFlow.value.remotePositionMs,
                        durationMs = _castStateFlow.value.remoteDurationMs
                    )
                }
            }.onFailure { err ->
                _castStateFlow.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun seekRemote(positionMs: Long) {
        val device = _castStateFlow.value.activeDevice ?: return
        val target = positionMs.coerceAtLeast(0L)
        pendingPausedSeekMs = null
        lastPlayingPositionMs = target
        consecutiveEndPausedPolls = 0
        _castStateFlow.update { it.copy(remotePositionMs = target) }
        scope.launch {
            controlClient.seekRelTime(device, target).onFailure { err ->
                _castStateFlow.update { it.copy(errorMessage = err.message) }
            }
        }
    }

    fun setRemoteVolume(volume: Int) {
        val device = _castStateFlow.value.activeDevice ?: return
        val clamped = DlnaCastStateMachine.clampRemoteVolume(volume)
        pendingVolumeTarget = clamped
        _castStateFlow.update { it.copy(remoteVolume = clamped) }
        scope.launch {
            volumeMutex.withLock {
                val target = pendingVolumeTarget ?: return@withLock
                pendingVolumeTarget = null
                if (_castStateFlow.value.activeDevice?.udn != device.udn) return@withLock
                controlClient.setVolume(device, target).onFailure { err ->
                    _castStateFlow.update { it.copy(errorMessage = err.message) }
                }
            }
        }
    }

    fun release() {
        if (!initialized) return
        discoveryJob?.cancel()
        connectJob?.cancel()
        castJob?.cancel()
        nextPrequeueJob?.cancel()
        pollJob?.cancel()
        switchToLocalPlayback(resumeLocalPlayback = false)
        mediaServer.stop()
        lockController.releaseAll()
    }

    private fun startPollingLoop(device: DlnaDevice) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && _castStateFlow.value.activeDevice?.udn == device.udn) {
                val activeGen = generationCounter.get()
                val transport = controlClient.getTransportState(device).getOrNull()
                val posInfo = controlClient.getPositionInfo(device).getOrNull()
                val vol = if (_castStateFlow.value.volumeSupported) {
                    controlClient.getVolume(device).getOrNull()
                } else null

                if (transport != null) {
                    val pendingNext = queuedNext
                    if (pendingNext != null &&
                        pendingNext.forGenerationId == activeGen &&
                        pendingNext.sentToRenderer &&
                        posInfo != null &&
                        DlnaCastStateMachine.matchesNextTrackUri(
                            reportedTrackUri = posInfo.trackUri,
                            expectedNextUrl = pendingNext.streamUrl,
                            expectedNextPath = pendingNext.streamPath
                        )
                    ) {
                        promoteGaplessNextTrack(device, pendingNext, posInfo, vol)
                        lastObservedTransportState = transport
                        delay(POLL_INTERVAL_MS)
                        continue
                    }

                    val stoppedLike = transport == DlnaTransportState.STOPPED ||
                        transport == DlnaTransportState.NO_MEDIA_PRESENT
                    if (stoppedLike) consecutiveStoppedPolls++ else consecutiveStoppedPolls = 0

                    val posMs = posInfo?.positionMs ?: _castStateFlow.value.remotePositionMs
                    val durMs = posInfo?.durationMs?.takeIf { it > 0L }
                        ?: _castStateFlow.value.currentSnapshot?.durationMs
                        ?: _castStateFlow.value.remoteDurationMs

                    if (transport == DlnaTransportState.PLAYING && posMs > 0L) {
                        lastPlayingPositionMs = posMs
                    }
                    if (!userPausedOrStopped &&
                        hasStartedPlayingInGeneration &&
                        transport == DlnaTransportState.PAUSED_PLAYBACK &&
                        DlnaCastStateMachine.isPausedAtTrackEnd(posMs, durMs, lastPlayingPositionMs)
                    ) {
                        consecutiveEndPausedPolls++
                    } else {
                        consecutiveEndPausedPolls = 0
                    }

                    val decision = DlnaCastStateMachine.evaluatePoll(
                        DlnaCastStateMachine.PollObservation(
                            previousState = lastObservedTransportState,
                            currentState = transport,
                            positionMs = posMs,
                            durationMs = durMs,
                            userPausedOrStopped = userPausedOrStopped,
                            generationId = activeGen,
                            activeGenerationId = generationCounter.get(),
                            hasStartedPlayingInGeneration = hasStartedPlayingInGeneration,
                            consecutiveStoppedPolls = consecutiveStoppedPolls,
                            lastPlayingPositionMs = lastPlayingPositionMs,
                            consecutiveEndPausedPolls = consecutiveEndPausedPolls
                        )
                    )
                    lastObservedTransportState = transport

                    when (decision) {
                        is DlnaCastStateMachine.PollDecision.SyncPlayback -> {
                            if (decision.markStartedPlaying) {
                                val firstStart = !hasStartedPlayingInGeneration
                                hasStartedPlayingInGeneration = true
                                if (firstStart || (queuedNext?.forGenerationId != activeGen)) {
                                    scheduleNextTrackPrequeue(device, activeGen)
                                }
                            }
                            _castStateFlow.update {
                                it.copy(
                                    transportState = transport,
                                    remotePositionMs = decision.positionMs,
                                    remoteDurationMs = decision.durationMs,
                                    remoteVolume = vol ?: it.remoteVolume
                                )
                            }
                            withContext(Dispatchers.Main) {
                                playerHost?.onDlnaPlaybackStateChanged(
                                    isPlaying = decision.isPlaying,
                                    positionMs = decision.positionMs,
                                    durationMs = decision.durationMs
                                )
                            }
                        }
                        is DlnaCastStateMachine.PollDecision.TrackEnded -> {
                            hasStartedPlayingInGeneration = false
                            consecutiveStoppedPolls = 0
                            consecutiveEndPausedPolls = 0
                            lastPlayingPositionMs = 0L
                            nextPrequeueJob?.cancel()
                            clearQueuedNext()
                            _castStateFlow.update { it.copy(transportState = DlnaTransportState.STOPPED) }
                            withContext(Dispatchers.Main) {
                                playerHost?.onDlnaTrackEnded(decision.generationId)
                            }
                        }
                        DlnaCastStateMachine.PollDecision.KeepPolling -> {
                            if (vol != null) {
                                _castStateFlow.update { it.copy(remoteVolume = vol) }
                            }
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun scheduleNextTrackPrequeue(device: DlnaDevice, forGen: Long) {
        if (nextPrequeueJob?.isActive == true && queuedNext?.forGenerationId == forGen) return
        val existing = queuedNext
        if (existing != null && existing.forGenerationId == forGen && (existing.sentToRenderer || existing.rejectedByRenderer)) {
            return
        }
        nextPrequeueJob?.cancel()
        val targetQueueRev = queueRevisionCounter.get()
        nextPrequeueJob = scope.launch {
            delay(1_200L)
            if (forGen != generationCounter.get() || targetQueueRev != queueRevisionCounter.get() || userPausedOrStopped) return@launch
            val host = playerHost ?: return@launch
            val nextSong = withContext(Dispatchers.Main) { host.peekNextAutoTrack() } ?: return@launch
            if (forGen != generationCounter.get() || targetQueueRev != queueRevisionCounter.get()) return@launch
            val urlResult = host.resolveSongUrlForDlna(nextSong)
            if (forGen != generationCounter.get() || targetQueueRev != queueRevisionCounter.get() || urlResult !is SongUrlResult.Success) return@launch
            val options = _castStateFlow.value.optionsFor(device.udn)
            val outcome = sourceResolver.prepareForCast(
                song = nextSong,
                generationId = forGen,
                urlResult = urlResult,
                requestedLosslessQuality = isRequestedLosslessForSong(nextSong),
                device = device,
                options = options,
                isCancelled = { forGen != generationCounter.get() || targetQueueRev != queueRevisionCounter.get() }
            )
            if (forGen != generationCounter.get() || targetQueueRev != queueRevisionCounter.get()) return@launch
            if (outcome !is LosslessSourceResolver.ResolutionOutcome.Ready) return@launch
            val lanIp = DlnaLanAddressResolver.resolveLanIpv4ForTarget(device.host) ?: return@launch
            val nextStreamPath = mediaServer.registerStream(
                extension = outcome.streamExtension,
                content = outcome.streamContent
            )
            val nextCoverPath = outcome.coverBytes?.let(mediaServer::registerArtwork)
            val nextSnapshot = outcome.snapshot.copy(
                streamPath = nextStreamPath,
                coverPath = nextCoverPath
            )
            val nextStreamUrl = mediaServer.buildUrl(lanIp, nextStreamPath)
            val nextCoverUrl = nextCoverPath?.let { mediaServer.buildUrl(lanIp, it) }
            val nextDidl = DidlLiteBuilder.buildDidlLite(nextSnapshot, nextStreamUrl, nextCoverUrl)
            nextUriMutex.withLock {
                if (forGen != generationCounter.get() || targetQueueRev != queueRevisionCounter.get()) {
                    mediaServer.unregisterStream(nextStreamPath)
                    return@launch
                }
                val sendRes = controlClient.setNextAvTransportUri(device, nextStreamUrl, nextDidl)
                if (forGen != generationCounter.get() || targetQueueRev != queueRevisionCounter.get()) {
                    if (sendRes.isSuccess) {
                        runCatching { controlClient.setNextAvTransportUri(device, "", "") }
                    }
                    mediaServer.unregisterStream(nextStreamPath)
                    return@launch
                }
                if (sendRes.isSuccess) {
                    queuedNext = QueuedNextTrack(
                        forGenerationId = forGen,
                        song = nextSong,
                        snapshot = nextSnapshot,
                        qualityReport = outcome.qualityReport,
                        streamUrl = nextStreamUrl,
                        streamPath = nextStreamPath,
                        coverPath = nextCoverPath,
                        didl = nextDidl,
                        sentToRenderer = true
                    )
                } else {
                    mediaServer.unregisterStream(nextStreamPath)
                    queuedNext = QueuedNextTrack(
                        forGenerationId = forGen,
                        song = nextSong,
                        snapshot = nextSnapshot,
                        qualityReport = outcome.qualityReport,
                        streamUrl = nextStreamUrl,
                        streamPath = nextStreamPath,
                        coverPath = nextCoverPath,
                        didl = nextDidl,
                        sentToRenderer = false,
                        rejectedByRenderer = true
                    )
                }
            }
        }
    }

    private suspend fun promoteGaplessNextTrack(
        device: DlnaDevice,
        promoted: QueuedNextTrack,
        posInfo: UpnpControlClient.PositionInfo,
        vol: Int?
    ) {
        val newGen = generationCounter.incrementAndGet()
        val oldPath = activeStreamPath
        activeStreamPath = promoted.streamPath
        queuedNext = null
        if (oldPath != null && oldPath != promoted.streamPath) {
            mediaServer.unregisterStream(oldPath)
        }
        consecutiveStoppedPolls = 0
        consecutiveEndPausedPolls = 0
        lastPlayingPositionMs = posInfo.positionMs.coerceAtLeast(0L)
        hasStartedPlayingInGeneration = true
        val durMs = posInfo.durationMs.takeIf { it > 0L } ?: promoted.snapshot.durationMs
        _castStateFlow.update {
            it.copy(
                currentGenerationId = newGen,
                currentSnapshot = promoted.snapshot,
                qualityReport = promoted.qualityReport,
                transportState = DlnaTransportState.PLAYING,
                remotePositionMs = posInfo.positionMs.coerceAtLeast(0L),
                remoteDurationMs = durMs,
                remoteVolume = vol ?: it.remoteVolume,
                statusMessage = localized(CoreCommonR.string.dlna_status_playing_on, device.friendlyName)
            )
        }
        withContext(Dispatchers.Main) {
            playerHost?.onDlnaGaplessTransitionedToNext(promoted.song)
            playerHost?.onDlnaPlaybackStateChanged(
                isPlaying = true,
                positionMs = posInfo.positionMs.coerceAtLeast(0L),
                durationMs = durMs
            )
        }
        scheduleNextTrackPrequeue(device, newGen)
    }

    private fun clearQueuedNext() {
        val old = queuedNext
        queuedNext = null
        val path = old?.streamPath
        if (path != null && path != activeStreamPath) {
            mediaServer.unregisterStream(path)
        }
    }

    private fun subscribeGenaIfAvailable(device: DlnaDevice, selectionGen: Long) {
        val eventSubUrl = device.avTransportEventSubUrl.takeIf { it.isNotBlank() } ?: return
        val lanIp = DlnaLanAddressResolver.resolveLanIpv4ForTarget(device.host) ?: return
        val callbackPath = "/dlna/event/avtransport/$selectionGen"
        val callbackUrl = mediaServer.buildUrl(lanIp, callbackPath)
        controlClient.subscribeGena(eventSubUrl, callbackUrl).onSuccess { sub ->
            if (selectionGen == generationCounter.get()) {
                activeGenaSid = sub.sid
                activeGenaEventSubUrl = eventSubUrl
                mediaServer.registerGenaSubscription(sub.sid)
            } else {
                runCatching { controlClient.unsubscribeGena(eventSubUrl, sub.sid) }
            }
        }
    }

    private fun unsubscribeGena() {
        val sid = activeGenaSid
        val url = activeGenaEventSubUrl
        activeGenaSid = null
        activeGenaEventSubUrl = null
        if (!sid.isNullOrBlank()) {
            mediaServer.unregisterGenaSubscription(sid)
        }
        if (!sid.isNullOrBlank() && !url.isNullOrBlank()) {
            controlClient.unsubscribeGena(url, sid)
        }
    }

    private fun handleGenaNotify(values: Map<String, String>) {
        val transportRaw = values["TransportState"]
        val volumeRaw = values["Volume"]?.toIntOrNull()
        _castStateFlow.update { state ->
            state.copy(
                transportState = if (transportRaw != null) {
                    controlClient.parseTransportState(transportRaw)
                } else {
                    state.transportState
                },
                remoteVolume = volumeRaw?.let(DlnaCastStateMachine::clampRemoteVolume) ?: state.remoteVolume
            )
        }
    }

    private fun handleLanLost(lostNetwork: android.net.Network?) {
        val state = _castStateFlow.value
        if (!state.isCastingActive) return
        val bound = activeBoundNetwork
        if (lostNetwork != null && bound != null && lostNetwork != bound) {
            return
        }
        val activeHost = state.activeDevice?.host?.takeIf { it.isNotBlank() }
        if (lostNetwork != null && activeHost != null &&
            DlnaLanAddressResolver.resolveLanIpv4ForTarget(activeHost) != null
        ) {
            // Renderer is still reachable via another active LAN interface
            return
        }
        val msg = localized(CoreCommonR.string.dlna_err_lan_lost)
        _castStateFlow.update {
            it.copy(
                connectionStatus = DlnaConnectionStatus.ERROR,
                errorMessage = msg
            )
        }
        playerHost?.onDlnaCastError(msg)
    }

    private fun localized(resId: Int, vararg args: Any): String {
        if (!initialized) return ""
        val context = LanguageManager.applyLanguage(app)
        return if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
    }

    private suspend fun failCastGeneration(gen: Long, message: String) {
        if (gen != generationCounter.get()) return
        _castStateFlow.update {
            it.copy(
                transportState = DlnaTransportState.STOPPED,
                errorMessage = message,
                statusMessage = null
            )
        }
        withContext(Dispatchers.Main) {
            playerHost?.onDlnaCastError(message)
        }
    }

    private fun mergeDiscoveredDevices(
        existing: List<DlnaDevice>,
        scanned: List<DlnaDevice>
    ): List<DlnaDevice> {
        val map = LinkedHashMap<String, DlnaDevice>()
        for (item in existing) {
            if (item.isManual) {
                map[item.udn] = item.copy(online = false)
            }
        }
        for (item in scanned) {
            map[item.udn] = item
        }
        return map.values.toList()
    }

    private fun ensureDefaultDeviceOptions(devices: List<DlnaDevice>) {
        val missing = devices.filter { !_castStateFlow.value.deviceOptionsByUdn.containsKey(it.udn) }
        if (missing.isEmpty()) return
        val additions = LinkedHashMap<String, DlnaDeviceOptions>()
        missing.forEach { device ->
            val defaults = prefsStore.defaultOptionsForDevice(device)
            prefsStore.saveDeviceOptions(device.udn, defaults)
            additions[device.udn] = defaults
        }
        _castStateFlow.update { state ->
            state.copy(deviceOptionsByUdn = state.deviceOptionsByUdn + additions)
        }
    }

    private fun isRequestedLosslessForSong(song: SongItem): Boolean {
        return when {
            PlayerManager.isYouTubeMusicTrack(song) -> false
            PlayerManager.isBiliTrack(song) ->
                PlayerManager.effectiveBiliQuality() in setOf("lossless", "hires")
            else ->
                PlayerManager.effectiveNeteaseQuality() in setOf(
                    "lossless",
                    "hires",
                    "jyeffect",
                    "sky",
                    "jymaster"
                )
        }
    }
}
