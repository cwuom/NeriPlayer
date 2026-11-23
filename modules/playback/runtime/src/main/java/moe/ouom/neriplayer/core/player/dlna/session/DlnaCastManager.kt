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

    // Separate device-connection generation from per-track cast generation
    private val connectionGenerationCounter = AtomicLong(0L)
    private val trackGenerationCounter = AtomicLong(0L)

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

    private data class LocalHandoffSnapshot(
        val positionMs: Long,
        val wasPlaying: Boolean
    )

    private data class PendingCastRequest(
        val song: SongItem,
        val startPositionMs: Long,
        val autoPlay: Boolean,
        val trackGen: Long
    )

    private data class PendingVolumeCommand(
        val deviceUdn: String,
        val connectionGen: Long,
        val volume: Int
    )

    @Volatile
    private var desiredRemotePlaying = true
    @Volatile
    private var pendingHandoffSnapshot: LocalHandoffSnapshot? = null
    @Volatile
    private var rendererMediaConfirmed = false
    @Volatile
    private var pendingConnectCastRequest: PendingCastRequest? = null
    @Volatile
    private var currentTrackUriLoadedGen = -1L
    @Volatile
    private var pendingTrackSeekMs: Long? = null
    @Volatile
    private var pendingTrackSeekGen = -1L
    @Volatile
    private var pendingPausedSeekMs: Long? = null
    @Volatile
    private var pendingVolumeCommand: PendingVolumeCommand? = null
    @Volatile
    private var activeBoundNetwork: android.net.Network? = null

    private data class QueuedNextTrack(
        val forGenerationId: Long,
        val queueRevision: Long,
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

    internal sealed interface DlnaUrlResolution {
        data class Success(val result: SongUrlResult.Success) : DlnaUrlResolution
        data object RequiresLogin : DlnaUrlResolution
        data class Failure(val customMessage: String? = null) : DlnaUrlResolution
    }

    // Hook callbacks invoked on main thread to keep PlayerManager in sync
    internal interface PlayerSyncHost {
        fun onSwitchToDlnaPauseLocal()
        fun onSwitchBackToLocal(resumePositionMs: Long, wasPlaying: Boolean)
        fun onDlnaPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, durationMs: Long)
        fun onDlnaTrackEnded(generationId: Long)
        fun onDlnaGaplessTransitionedToNext(expectedSong: SongItem)
        fun peekNextAutoTrack(): SongItem?
        fun onDlnaCastError(message: String)
        suspend fun resolveSongUrlForDlna(song: SongItem, forPrequeue: Boolean = false): DlnaUrlResolution
        fun currentSong(): SongItem?
        fun currentPositionMs(): Long
        fun isLocalPlaying(): Boolean
    }

    @Volatile
    internal var playerHost: PlayerSyncHost? = null

    @Synchronized
    fun ensureInitialized(application: Application) {
        if (initialized) {
            lockController.registerNetworkCallback()
            return
        }
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
                    queuedNext?.streamPath?.takeIf { it.isNotBlank() }?.let { path ->
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
        ensureDefaultDeviceOptions(manualDevices)
        initialized = true
    }

    fun isCastingActive(): Boolean = _castStateFlow.value.isCastingActive

    fun isRemotePlaying(): Boolean {
        val state = _castStateFlow.value
        if (!state.isCastingActive) return false
        return if (rendererMediaConfirmed) {
            state.transportState == DlnaTransportState.PLAYING
        } else {
            desiredRemotePlaying
        }
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
                val knownForProbe = (_castStateFlow.value.discoveredDevices + prefsStore.loadManualDevices())
                    .distinctBy { it.udn }
                val found = discoveryClient.scanOnce(knownDevices = knownForProbe) { log ->
                    NPLogger.d(TAG, log)
                }
                val currentManuals = prefsStore.loadManualDevices()
                val currentManualUdns = currentManuals.mapTo(HashSet()) { it.udn }
                val filteredFound = found.filterNot { it.isManual && it.udn !in currentManualUdns }
                val merged = mergeDiscoveredDevices(currentManuals, filteredFound)
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
        if (_castStateFlow.value.activeDevice?.udn == udn && isCastingActive()) {
            invalidateQueuedNext()
        }
    }

    fun selectDevice(device: DlnaDevice) {
        if (!initialized) return
        val host = playerHost
        val currentSong = host?.currentSong()
        val previousState = _castStateFlow.value
        val previousDevice = previousState.activeDevice
        val wasPlayingLocal = host?.isLocalPlaying() == true
        val priorHandoff = pendingHandoffSnapshot
        val shouldAutoPlay = if (previousDevice != null) {
            if (rendererMediaConfirmed) {
                previousState.transportState == DlnaTransportState.PLAYING || desiredRemotePlaying
            } else {
                priorHandoff?.wasPlaying ?: desiredRemotePlaying
            }
        } else {
            wasPlayingLocal
        }
        val resumePosMs = if (previousDevice != null) {
            if (rendererMediaConfirmed) {
                previousState.remotePositionMs
            } else {
                pendingTrackSeekMs ?: priorHandoff?.positionMs ?: previousState.remotePositionMs
            }
        } else {
            host?.currentPositionMs() ?: 0L
        }

        // Capture handoff snapshot BEFORE pausing local player so canceling mid-connect restores exact state
        pendingHandoffSnapshot = if (previousDevice == null || !rendererMediaConfirmed) {
            priorHandoff ?: LocalHandoffSnapshot(positionMs = resumePosMs, wasPlaying = shouldAutoPlay)
        } else {
            LocalHandoffSnapshot(positionMs = resumePosMs, wasPlaying = shouldAutoPlay)
        }
        rendererMediaConfirmed = false

        // Immediately silence local playback before switching to DLNA so both never play together
        host?.onSwitchToDlnaPauseLocal()

        connectJob?.cancel()
        castJob?.cancel()
        nextPrequeueJob?.cancel()
        pollJob?.cancel()
        val connectionGen = connectionGenerationCounter.incrementAndGet()
        val initialTrackGen = trackGenerationCounter.incrementAndGet()
        queueRevisionCounter.incrementAndGet()
        desiredRemotePlaying = shouldAutoPlay
        userPausedOrStopped = !shouldAutoPlay
        currentTrackUriLoadedGen = -1L
        pendingTrackSeekMs = null
        pendingTrackSeekGen = -1L
        pendingPausedSeekMs = null
        pendingConnectCastRequest = currentSong?.let {
            PendingCastRequest(
                song = it,
                startPositionMs = resumePosMs,
                autoPlay = shouldAutoPlay,
                trackGen = initialTrackGen
            )
        }

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
                currentGenerationId = initialTrackGen,
                currentSnapshot = null,
                qualityReport = null,
                remotePositionMs = resumePosMs.coerceAtLeast(0L),
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
                if (connectionGen != connectionGenerationCounter.get()) return@launch

                mediaServer.start()
                if (connectionGen != connectionGenerationCounter.get()) return@launch
                lockController.acquireCastLocks()

                val refreshedDevice = discoveryClient.fetchDeviceDescription(
                    locationUrl = device.location,
                    fallbackUdn = device.udn,
                    isManual = device.isManual
                ) ?: device
                if (connectionGen != connectionGenerationCounter.get()) return@launch

                val sinkProtocolInfo = controlClient.getProtocolInfo(refreshedDevice).getOrDefault("")
                if (connectionGen != connectionGenerationCounter.get()) return@launch

                val connectedDevice = refreshedDevice.copy(
                    sinkProtocolInfo = sinkProtocolInfo,
                    supportsFlac = DlnaProtocolInfo.supportsFlac(sinkProtocolInfo),
                    supportsWav = DlnaProtocolInfo.supportsWav(sinkProtocolInfo),
                    online = true
                )

                // Read initial remote volume safely; NEVER auto-set to max!
                val volumeResult = controlClient.getVolume(connectedDevice)
                if (connectionGen != connectionGenerationCounter.get()) return@launch
                val initialVolume = volumeResult.getOrNull()
                val volumeSupported = volumeResult.isSuccess && connectedDevice.renderingControlUrl.isNotBlank()

                val genaCallbackPath = "/dlna/event/avtransport/${java.util.UUID.randomUUID().toString().replace("-", "")}"
                mediaServer.allowedPeerHost = connectedDevice.host
                mediaServer.expectedGenaCallbackPath = genaCallbackPath
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

                subscribeGenaIfAvailable(connectedDevice, connectionGen, genaCallbackPath)
                startPollingLoop(connectedDevice, connectionGen)

                val requestToCast = pendingConnectCastRequest
                pendingConnectCastRequest = null
                if (requestToCast != null && connectionGen == connectionGenerationCounter.get()) {
                    castJob = scope.launch {
                        castSongInternal(
                            song = requestToCast.song,
                            startPositionMs = requestToCast.startPositionMs,
                            autoPlay = desiredRemotePlaying,
                            preassignedGen = requestToCast.trackGen,
                            expectedConnectionGen = connectionGen
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(TAG, "selectDevice failed", e)
                if (connectionGen == connectionGenerationCounter.get()) {
                    pendingConnectCastRequest = null
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
        val handoff = pendingHandoffSnapshot
        val confirmed = rendererMediaConfirmed
        val resumePlaying = if (confirmed) {
            state.transportState == DlnaTransportState.PLAYING || desiredRemotePlaying
        } else {
            handoff?.wasPlaying ?: desiredRemotePlaying
        }
        val resumePosMs = if (confirmed) {
            state.remotePositionMs
        } else {
            pendingTrackSeekMs ?: handoff?.positionMs ?: state.remotePositionMs
        }

        connectJob?.cancel()
        castJob?.cancel()
        nextPrequeueJob?.cancel()
        pollJob?.cancel()
        connectionGenerationCounter.incrementAndGet()
        val retiredTrackGen = trackGenerationCounter.incrementAndGet()
        queueRevisionCounter.incrementAndGet()
        pendingHandoffSnapshot = null
        rendererMediaConfirmed = false
        pendingConnectCastRequest = null
        currentTrackUriLoadedGen = -1L
        pendingTrackSeekMs = null
        pendingTrackSeekGen = -1L
        userPausedOrStopped = true
        desiredRemotePlaying = false
        pendingPausedSeekMs = null
        pendingVolumeCommand = null

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
        mediaServer.stop()

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
                currentGenerationId = retiredTrackGen
            )
        }

        if (resumeLocalPlayback && active != null) {
            playerHost?.onSwitchBackToLocal(resumePosMs.coerceAtLeast(0L), resumePlaying)
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
        val state = _castStateFlow.value
        if (!state.isCastingActive) return
        castJob?.cancel()
        nextPrequeueJob?.cancel()
        desiredRemotePlaying = autoPlay
        userPausedOrStopped = !autoPlay
        pendingPausedSeekMs = null
        pendingTrackSeekMs = null
        pendingTrackSeekGen = -1L

        if (!rendererMediaConfirmed) {
            pendingHandoffSnapshot = LocalHandoffSnapshot(
                positionMs = startPositionMs.coerceAtLeast(0L),
                wasPlaying = autoPlay
            )
        }

        // If device handshake is still in progress, update pending request without aborting handshake
        if (state.connectionStatus == DlnaConnectionStatus.CONNECTING) {
            val newTrackGen = trackGenerationCounter.incrementAndGet()
            pendingConnectCastRequest = PendingCastRequest(
                song = song,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
                trackGen = newTrackGen
            )
            _castStateFlow.update {
                it.copy(
                    currentGenerationId = newTrackGen,
                    currentSnapshot = null,
                    qualityReport = null,
                    remotePositionMs = startPositionMs.coerceAtLeast(0L),
                    errorMessage = null
                )
            }
            return
        }

        val connGen = connectionGenerationCounter.get()
        castJob = scope.launch {
            castSongInternal(
                song = song,
                startPositionMs = startPositionMs,
                autoPlay = autoPlay,
                preassignedGen = null,
                expectedConnectionGen = connGen
            )
        }
    }

    fun invalidateQueuedNext() {
        if (!initialized || !isCastingActive()) return
        val newRev = queueRevisionCounter.incrementAndGet()
        nextPrequeueJob?.cancel()
        val device = _castStateFlow.value.activeDevice ?: return
        val activeGen = trackGenerationCounter.get()
        val connGen = connectionGenerationCounter.get()
        scope.launch {
            nextUriMutex.withLock {
                val oldNext = queuedNext
                queuedNext = null
                if (oldNext != null) {
                    if (oldNext.sentToRenderer && connectionGenerationCounter.get() == connGen) {
                        runCatching { controlClient.setNextAvTransportUri(device, "", "") }
                    }
                    if (oldNext.streamPath.isNotBlank() && oldNext.streamPath != activeStreamPath) {
                        mediaServer.unregisterStream(oldNext.streamPath)
                    }
                }
            }
            if (hasStartedPlayingInGeneration &&
                !userPausedOrStopped &&
                newRev == queueRevisionCounter.get() &&
                connGen == connectionGenerationCounter.get() &&
                activeGen == trackGenerationCounter.get()
            ) {
                scheduleNextTrackPrequeue(device, connGen, activeGen)
            }
        }
    }

    fun onQueueFinishedStop() {
        if (!initialized || !isCastingActive()) return
        val device = _castStateFlow.value.activeDevice ?: return
        castJob?.cancel()
        nextPrequeueJob?.cancel()
        pendingConnectCastRequest = null
        val stopGen = trackGenerationCounter.incrementAndGet()
        queueRevisionCounter.incrementAndGet()
        currentTrackUriLoadedGen = -1L
        pendingTrackSeekMs = null
        pendingTrackSeekGen = -1L
        clearQueuedNext()
        val oldPath = activeStreamPath
        activeStreamPath = null
        mediaServer.unregisterStream(oldPath)
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
                currentSnapshot = null,
                transportState = DlnaTransportState.STOPPED,
                remotePositionMs = 0L
            )
        }
        val expectedConnGen = connectionGenerationCounter.get()
        scope.launch {
            if (connectionGenerationCounter.get() == expectedConnGen) {
                controlClient.stop(device)
            }
        }
    }

    private suspend fun castSongInternal(
        song: SongItem,
        startPositionMs: Long,
        autoPlay: Boolean,
        preassignedGen: Long? = null,
        expectedConnectionGen: Long = connectionGenerationCounter.get()
    ) {
        if (expectedConnectionGen != connectionGenerationCounter.get()) return
        val device = _castStateFlow.value.activeDevice ?: return
        val host = playerHost ?: return
        val gen = preassignedGen ?: trackGenerationCounter.incrementAndGet()
        if (gen != trackGenerationCounter.get()) return

        nextPrequeueJob?.cancel()
        clearQueuedNext()
        currentTrackUriLoadedGen = -1L
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
                remotePositionMs = startPositionMs.coerceAtLeast(0L),
                statusMessage = localized(CoreCommonR.string.dlna_status_resolving_lossless),
                errorMessage = null
            )
        }

        val isStale = {
            gen != trackGenerationCounter.get() ||
                expectedConnectionGen != connectionGenerationCounter.get()
        }

        try {
            val urlResolution = host.resolveSongUrlForDlna(song, forPrequeue = false)
            if (isStale()) return
            val urlResult = when (urlResolution) {
                is DlnaUrlResolution.Success -> urlResolution.result
                DlnaUrlResolution.RequiresLogin -> {
                    failCastGeneration(gen, expectedConnectionGen, device, localized(CoreCommonR.string.dlna_err_login_required))
                    return
                }
                is DlnaUrlResolution.Failure -> {
                    val msg = urlResolution.customMessage
                        ?: localized(CoreCommonR.string.dlna_err_no_playable_url, song.name)
                    failCastGeneration(gen, expectedConnectionGen, device, msg)
                    return
                }
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
                isCancelled = isStale
            )
            if (isStale()) {
                if (outcome is LosslessSourceResolver.ResolutionOutcome.Ready) {
                    runCatching { outcome.streamContent.ephemeralFileToDeleteOnUnregister?.delete() }
                }
                return
            }

            when (outcome) {
                is LosslessSourceResolver.ResolutionOutcome.Blocked -> {
                    retireActiveTransportMedia(device)
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
                        if (!isStale()) {
                            host.onDlnaCastError(outcome.reason)
                        }
                    }
                    return
                }
                is LosslessSourceResolver.ResolutionOutcome.Failed -> {
                    failCastGeneration(gen, expectedConnectionGen, device, outcome.reason)
                    return
                }
                is LosslessSourceResolver.ResolutionOutcome.Ready -> {
                    mediaServer.start()
                    val lanIp = DlnaLanAddressResolver.resolveLanIpv4ForTarget(device.host)
                        ?: run {
                            runCatching { outcome.streamContent.ephemeralFileToDeleteOnUnregister?.delete() }
                            failCastGeneration(gen, expectedConnectionGen, device, localized(CoreCommonR.string.dlna_err_no_lan_ip))
                            return
                        }

                    val previousStreamPath = activeStreamPath
                    val newStreamPath = mediaServer.registerStream(
                        extension = outcome.streamExtension,
                        content = outcome.streamContent
                    )
                    if (isStale()) {
                        mediaServer.unregisterStream(newStreamPath)
                        return
                    }
                    val newCoverPath = outcome.coverBytes?.let(mediaServer::registerArtwork)
                    val snapshot = outcome.snapshot.copy(
                        streamPath = newStreamPath,
                        coverPath = newCoverPath
                    )
                    val streamUrl = mediaServer.buildUrl(lanIp, newStreamPath)
                    val coverUrl = newCoverPath?.let { mediaServer.buildUrl(lanIp, it) }
                    val didl = DidlLiteBuilder.buildDidlLite(snapshot, streamUrl, coverUrl)

                    activeStreamPath = newStreamPath
                    if (previousStreamPath != null && previousStreamPath != newStreamPath) {
                        mediaServer.unregisterStream(previousStreamPath)
                    }

                    // Stop any prior stream cleanly on renderer, then set new URI + DIDL
                    controlClient.stop(device)
                    val setRes = controlClient.setAvTransportUri(device, streamUrl, didl)
                    if (setRes.isFailure) {
                        failCastGeneration(
                            gen,
                            expectedConnectionGen,
                            device,
                            localized(
                                CoreCommonR.string.dlna_err_set_uri_failed,
                                setRes.exceptionOrNull()?.message.orEmpty()
                            )
                        )
                        return
                    }
                    if (isStale()) return

                    currentTrackUriLoadedGen = gen
                    rendererMediaConfirmed = true
                    pendingHandoffSnapshot = null

                    val effectiveStartPosMs = if (pendingTrackSeekGen == gen && pendingTrackSeekMs != null) {
                        val target = pendingTrackSeekMs!!
                        pendingTrackSeekMs = null
                        pendingTrackSeekGen = -1L
                        target
                    } else {
                        startPositionMs
                    }.coerceAtLeast(0L)

                    _castStateFlow.update {
                        it.copy(
                            currentSnapshot = snapshot,
                            qualityReport = outcome.qualityReport,
                            remoteDurationMs = snapshot.durationMs,
                            remotePositionMs = effectiveStartPosMs,
                            statusMessage = localized(
                                CoreCommonR.string.dlna_status_casting_to,
                                snapshot.title,
                                device.friendlyName
                            )
                        )
                    }

                    if (desiredRemotePlaying) {
                        val playRes = controlClient.play(device)
                        if (playRes.isFailure) {
                            failCastGeneration(
                                gen,
                                expectedConnectionGen,
                                device,
                                localized(
                                    CoreCommonR.string.dlna_err_play_failed,
                                    playRes.exceptionOrNull()?.message.orEmpty()
                                )
                            )
                            return
                        }
                        val postPlaySeekMs = if (pendingTrackSeekGen == gen && pendingTrackSeekMs != null) {
                            val latest = pendingTrackSeekMs!!
                            pendingTrackSeekMs = null
                            pendingTrackSeekGen = -1L
                            latest.coerceAtLeast(0L)
                        } else {
                            effectiveStartPosMs
                        }
                        if (postPlaySeekMs > 1_500L) {
                            delay(250L)
                            if (!isStale()) {
                                controlClient.seekRelTime(device, postPlaySeekMs)
                            }
                        }
                        if (isStale()) return
                        _castStateFlow.update {
                            it.copy(
                                transportState = DlnaTransportState.PLAYING,
                                remotePositionMs = postPlaySeekMs,
                                statusMessage = localized(CoreCommonR.string.dlna_status_playing_on, device.friendlyName)
                            )
                        }
                        withContext(Dispatchers.Main) {
                            if (!isStale()) {
                                host.onDlnaPlaybackStateChanged(
                                    isPlaying = true,
                                    positionMs = postPlaySeekMs,
                                    durationMs = snapshot.durationMs
                                )
                            }
                        }
                    } else {
                        if (effectiveStartPosMs > 1_500L) {
                            val seekRes = controlClient.seekRelTime(device, effectiveStartPosMs)
                            if (seekRes.isFailure) {
                                pendingPausedSeekMs = effectiveStartPosMs
                            }
                        }
                        if (!isStale()) {
                            _castStateFlow.update {
                                it.copy(transportState = DlnaTransportState.PAUSED_PLAYBACK)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NPLogger.e(TAG, "castSongInternal failed", e)
            if (!isStale()) {
                failCastGeneration(gen, expectedConnectionGen, device, e.message ?: "DLNA cast error")
            }
        }
    }

    fun playRemote() {
        val state = _castStateFlow.value
        val device = state.activeDevice ?: return
        desiredRemotePlaying = true
        userPausedOrStopped = false
        consecutiveStoppedPolls = 0
        consecutiveEndPausedPolls = 0
        if (!rendererMediaConfirmed) {
            pendingHandoffSnapshot = pendingHandoffSnapshot?.copy(wasPlaying = true)
        }
        if (state.connectionStatus == DlnaConnectionStatus.CONNECTING) {
            pendingConnectCastRequest = pendingConnectCastRequest?.copy(autoPlay = true)
            return
        }
        val expectedConnGen = connectionGenerationCounter.get()
        val expectedTrackGen = trackGenerationCounter.get()
        val expectedUdn = device.udn
        scope.launch {
            val current = _castStateFlow.value
            if (current.currentSnapshot == null ||
                currentTrackUriLoadedGen != expectedTrackGen ||
                current.transportState == DlnaTransportState.NO_MEDIA_PRESENT
            ) {
                if (castJob?.isActive == true) {
                    // castSongInternal is already running and will observe desiredRemotePlaying = true
                    return@launch
                }
                val song = playerHost?.currentSong() ?: return@launch
                val resumeMs = current.remotePositionMs.takeIf { it > 0L }
                    ?: (playerHost?.currentPositionMs() ?: 0L)
                castJob = scope.launch {
                    castSongInternal(
                        song = song,
                        startPositionMs = resumeMs,
                        autoPlay = true,
                        preassignedGen = null,
                        expectedConnectionGen = expectedConnGen
                    )
                }
                return@launch
            }
            controlClient.play(device).onSuccess {
                if (!isCurrentSessionAndTrack(expectedConnGen, expectedTrackGen, expectedUdn)) return@onSuccess
                val pendingSeek = pendingPausedSeekMs
                if (pendingSeek != null && pendingSeek > 1_500L) {
                    pendingPausedSeekMs = null
                    delay(200L)
                    if (isCurrentSessionAndTrack(expectedConnGen, expectedTrackGen, expectedUdn)) {
                        controlClient.seekRelTime(device, pendingSeek)
                    }
                }
                if (!isCurrentSessionAndTrack(expectedConnGen, expectedTrackGen, expectedUdn)) return@onSuccess
                _castStateFlow.update { it.copy(transportState = DlnaTransportState.PLAYING, errorMessage = null) }
                withContext(Dispatchers.Main) {
                    if (isCurrentSessionAndTrack(expectedConnGen, expectedTrackGen, expectedUdn)) {
                        playerHost?.onDlnaPlaybackStateChanged(
                            isPlaying = true,
                            positionMs = _castStateFlow.value.remotePositionMs,
                            durationMs = _castStateFlow.value.remoteDurationMs
                        )
                    }
                }
            }.onFailure { err ->
                if (isCurrentSessionAndTrack(expectedConnGen, expectedTrackGen, expectedUdn)) {
                    _castStateFlow.update { it.copy(errorMessage = err.message) }
                }
            }
        }
    }

    fun pauseRemote() {
        val state = _castStateFlow.value
        val device = state.activeDevice ?: return
        desiredRemotePlaying = false
        userPausedOrStopped = true
        consecutiveEndPausedPolls = 0
        if (!rendererMediaConfirmed) {
            pendingHandoffSnapshot = pendingHandoffSnapshot?.copy(wasPlaying = false)
        }
        if (state.connectionStatus == DlnaConnectionStatus.CONNECTING) {
            pendingConnectCastRequest = pendingConnectCastRequest?.copy(autoPlay = false)
            return
        }
        val expectedConnGen = connectionGenerationCounter.get()
        val expectedTrackGen = trackGenerationCounter.get()
        val expectedUdn = device.udn
        scope.launch {
            if (currentTrackUriLoadedGen != expectedTrackGen) {
                // castSongInternal will observe desiredRemotePlaying = false upon URI load
                return@launch
            }
            controlClient.pause(device).onSuccess {
                if (!isCurrentSessionAndTrack(expectedConnGen, expectedTrackGen, expectedUdn)) return@onSuccess
                _castStateFlow.update { it.copy(transportState = DlnaTransportState.PAUSED_PLAYBACK) }
                withContext(Dispatchers.Main) {
                    if (isCurrentSessionAndTrack(expectedConnGen, expectedTrackGen, expectedUdn)) {
                        playerHost?.onDlnaPlaybackStateChanged(
                            isPlaying = false,
                            positionMs = _castStateFlow.value.remotePositionMs,
                            durationMs = _castStateFlow.value.remoteDurationMs
                        )
                    }
                }
            }.onFailure { err ->
                if (isCurrentSessionAndTrack(expectedConnGen, expectedTrackGen, expectedUdn)) {
                    _castStateFlow.update { it.copy(errorMessage = err.message) }
                }
            }
        }
    }

    fun seekRemote(positionMs: Long) {
        val state = _castStateFlow.value
        val device = state.activeDevice ?: return
        val target = positionMs.coerceAtLeast(0L)
        pendingPausedSeekMs = null
        lastPlayingPositionMs = target
        consecutiveEndPausedPolls = 0
        _castStateFlow.update { it.copy(remotePositionMs = target) }
        if (!rendererMediaConfirmed) {
            pendingHandoffSnapshot = pendingHandoffSnapshot?.copy(positionMs = target)
        }
        if (state.connectionStatus == DlnaConnectionStatus.CONNECTING) {
            pendingConnectCastRequest = pendingConnectCastRequest?.copy(startPositionMs = target)
            return
        }
        val expectedConnGen = connectionGenerationCounter.get()
        val activeTrackGen = trackGenerationCounter.get()
        val expectedUdn = device.udn

        // If current track is still preparing/buffering before SetAVTransportURI, defer seek until loaded
        if (currentTrackUriLoadedGen != activeTrackGen || state.currentSnapshot == null) {
            pendingTrackSeekMs = target
            pendingTrackSeekGen = activeTrackGen
            return
        }

        scope.launch {
            controlClient.seekRelTime(device, target).onFailure { err ->
                if (!isCurrentSessionAndTrack(expectedConnGen, activeTrackGen, expectedUdn)) return@onFailure
                if (!desiredRemotePlaying || _castStateFlow.value.transportState == DlnaTransportState.PAUSED_PLAYBACK) {
                    pendingPausedSeekMs = target
                } else {
                    _castStateFlow.update { it.copy(errorMessage = err.message) }
                }
            }
        }
    }

    fun setRemoteVolume(volume: Int) {
        val device = _castStateFlow.value.activeDevice ?: return
        val connGen = connectionGenerationCounter.get()
        val clamped = DlnaCastStateMachine.clampRemoteVolume(volume)
        pendingVolumeCommand = PendingVolumeCommand(
            deviceUdn = device.udn,
            connectionGen = connGen,
            volume = clamped
        )
        _castStateFlow.update { it.copy(remoteVolume = clamped) }
        scope.launch {
            volumeMutex.withLock {
                val cmd = pendingVolumeCommand ?: return@withLock
                if (cmd.deviceUdn != device.udn || cmd.connectionGen != connGen) {
                    // Belongs to a newer device/session; leave untouched for that worker
                    return@withLock
                }
                pendingVolumeCommand = null
                if (_castStateFlow.value.activeDevice?.udn != device.udn ||
                    connectionGenerationCounter.get() != connGen
                ) {
                    return@withLock
                }
                controlClient.setVolume(device, cmd.volume).onFailure { err ->
                    if (connectionGenerationCounter.get() == connGen &&
                        _castStateFlow.value.activeDevice?.udn == device.udn
                    ) {
                        _castStateFlow.update { it.copy(errorMessage = err.message) }
                    }
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

    private fun startPollingLoop(device: DlnaDevice, connectionGen: Long) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive &&
                connectionGenerationCounter.get() == connectionGen &&
                _castStateFlow.value.activeDevice?.udn == device.udn
            ) {
                val activeTrackGen = trackGenerationCounter.get()
                val transport = controlClient.getTransportState(device).getOrNull()
                val posInfo = controlClient.getPositionInfo(device).getOrNull()
                val vol = if (_castStateFlow.value.volumeSupported) {
                    controlClient.getVolume(device).getOrNull()
                } else null

                if (connectionGenerationCounter.get() != connectionGen) break

                if (transport != null && currentTrackUriLoadedGen == activeTrackGen) {
                    val pendingNext = queuedNext
                    if (pendingNext != null &&
                        pendingNext.forGenerationId == activeTrackGen &&
                        pendingNext.sentToRenderer &&
                        posInfo != null &&
                        DlnaCastStateMachine.matchesNextTrackUri(
                            reportedTrackUri = posInfo.trackUri,
                            expectedNextUrl = pendingNext.streamUrl,
                            expectedNextPath = pendingNext.streamPath
                        )
                    ) {
                        promoteGaplessNextTrack(device, connectionGen, pendingNext, posInfo, vol)
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
                            generationId = activeTrackGen,
                            activeGenerationId = trackGenerationCounter.get(),
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
                                rendererMediaConfirmed = true
                                pendingHandoffSnapshot = null
                                if (firstStart || (queuedNext?.forGenerationId != activeTrackGen)) {
                                    scheduleNextTrackPrequeue(device, connectionGen, activeTrackGen)
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
                                if (isCurrentSessionAndTrack(connectionGen, activeTrackGen, device.udn)) {
                                    playerHost?.onDlnaPlaybackStateChanged(
                                        isPlaying = decision.isPlaying,
                                        positionMs = decision.positionMs,
                                        durationMs = decision.durationMs
                                    )
                                }
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
                                if (connectionGenerationCounter.get() == connectionGen) {
                                    playerHost?.onDlnaTrackEnded(decision.generationId)
                                }
                            }
                        }
                        DlnaCastStateMachine.PollDecision.KeepPolling -> {
                            if (vol != null) {
                                _castStateFlow.update { it.copy(remoteVolume = vol) }
                            }
                        }
                    }
                } else if (vol != null && connectionGenerationCounter.get() == connectionGen) {
                    _castStateFlow.update { it.copy(remoteVolume = vol) }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun scheduleNextTrackPrequeue(device: DlnaDevice, connGen: Long, forTrackGen: Long) {
        if (nextPrequeueJob?.isActive == true && queuedNext?.forGenerationId == forTrackGen) return
        val existing = queuedNext
        if (existing != null &&
            existing.forGenerationId == forTrackGen &&
            (existing.sentToRenderer || existing.rejectedByRenderer)
        ) {
            return
        }
        nextPrequeueJob?.cancel()
        val targetQueueRev = queueRevisionCounter.get()
        val isCancelled = {
            connGen != connectionGenerationCounter.get() ||
                forTrackGen != trackGenerationCounter.get() ||
                targetQueueRev != queueRevisionCounter.get() ||
                userPausedOrStopped
        }
        nextPrequeueJob = scope.launch {
            delay(1_200L)
            if (isCancelled()) return@launch
            val host = playerHost ?: return@launch
            val nextSong = withContext(Dispatchers.Main) { host.peekNextAutoTrack() } ?: return@launch
            if (isCancelled()) return@launch
            val urlResolution = host.resolveSongUrlForDlna(nextSong, forPrequeue = true)
            if (isCancelled() || urlResolution !is DlnaUrlResolution.Success) return@launch
            val options = _castStateFlow.value.optionsFor(device.udn)
            val outcome = sourceResolver.prepareForCast(
                song = nextSong,
                generationId = forTrackGen,
                urlResult = urlResolution.result,
                requestedLosslessQuality = isRequestedLosslessForSong(nextSong),
                device = device,
                options = options,
                isCancelled = isCancelled
            )
            if (isCancelled() || outcome !is LosslessSourceResolver.ResolutionOutcome.Ready) {
                if (outcome is LosslessSourceResolver.ResolutionOutcome.Ready) {
                    runCatching { outcome.streamContent.ephemeralFileToDeleteOnUnregister?.delete() }
                }
                return@launch
            }
            val lanIp = DlnaLanAddressResolver.resolveLanIpv4ForTarget(device.host) ?: run {
                runCatching { outcome.streamContent.ephemeralFileToDeleteOnUnregister?.delete() }
                return@launch
            }
            val nextStreamPath = mediaServer.registerStream(
                extension = outcome.streamExtension,
                content = outcome.streamContent
            )
            var streamHandedOff = false
            try {
                val nextCoverPath = outcome.coverBytes?.let(mediaServer::registerArtwork)
                val nextSnapshot = outcome.snapshot.copy(
                    streamPath = nextStreamPath,
                    coverPath = nextCoverPath
                )
                val nextStreamUrl = mediaServer.buildUrl(lanIp, nextStreamPath)
                val nextCoverUrl = nextCoverPath?.let { mediaServer.buildUrl(lanIp, it) }
                val nextDidl = DidlLiteBuilder.buildDidlLite(nextSnapshot, nextStreamUrl, nextCoverUrl)
                nextUriMutex.withLock {
                    if (isCancelled()) return@withLock
                    val sendRes = controlClient.setNextAvTransportUri(device, nextStreamUrl, nextDidl)
                    if (isCancelled()) {
                        if (sendRes.isSuccess && connGen == connectionGenerationCounter.get()) {
                            runCatching { controlClient.setNextAvTransportUri(device, "", "") }
                        }
                        return@withLock
                    }
                    if (sendRes.isSuccess) {
                        queuedNext = QueuedNextTrack(
                            forGenerationId = forTrackGen,
                            queueRevision = targetQueueRev,
                            song = nextSong,
                            snapshot = nextSnapshot,
                            qualityReport = outcome.qualityReport,
                            streamUrl = nextStreamUrl,
                            streamPath = nextStreamPath,
                            coverPath = nextCoverPath,
                            didl = nextDidl,
                            sentToRenderer = true
                        )
                        streamHandedOff = true
                    } else {
                        queuedNext = QueuedNextTrack(
                            forGenerationId = forTrackGen,
                            queueRevision = targetQueueRev,
                            song = nextSong,
                            snapshot = nextSnapshot,
                            qualityReport = outcome.qualityReport,
                            streamUrl = nextStreamUrl,
                            streamPath = "",
                            coverPath = nextCoverPath,
                            didl = nextDidl,
                            sentToRenderer = false,
                            rejectedByRenderer = true
                        )
                    }
                }
            } finally {
                if (!streamHandedOff) {
                    mediaServer.unregisterStream(nextStreamPath)
                }
            }
        }
    }

    private suspend fun promoteGaplessNextTrack(
        device: DlnaDevice,
        connectionGen: Long,
        observedNext: QueuedNextTrack,
        posInfo: UpnpControlClient.PositionInfo,
        vol: Int?
    ) {
        val newGen = nextUriMutex.withLock {
            val currentQueued = queuedNext
            if (connectionGenerationCounter.get() != connectionGen ||
                currentQueued !== observedNext ||
                currentQueued.queueRevision != queueRevisionCounter.get() ||
                currentQueued.forGenerationId != trackGenerationCounter.get()
            ) {
                return
            }
            val gen = trackGenerationCounter.incrementAndGet()
            currentTrackUriLoadedGen = gen
            val oldPath = activeStreamPath
            activeStreamPath = observedNext.streamPath
            queuedNext = null
            if (oldPath != null && oldPath != observedNext.streamPath) {
                mediaServer.unregisterStream(oldPath)
            }
            gen
        }
        consecutiveStoppedPolls = 0
        consecutiveEndPausedPolls = 0
        lastPlayingPositionMs = posInfo.positionMs.coerceAtLeast(0L)
        hasStartedPlayingInGeneration = true
        rendererMediaConfirmed = true
        pendingHandoffSnapshot = null
        val durMs = posInfo.durationMs.takeIf { it > 0L } ?: observedNext.snapshot.durationMs
        _castStateFlow.update {
            it.copy(
                currentGenerationId = newGen,
                currentSnapshot = observedNext.snapshot,
                qualityReport = observedNext.qualityReport,
                transportState = DlnaTransportState.PLAYING,
                remotePositionMs = posInfo.positionMs.coerceAtLeast(0L),
                remoteDurationMs = durMs,
                remoteVolume = vol ?: it.remoteVolume,
                statusMessage = localized(CoreCommonR.string.dlna_status_playing_on, device.friendlyName)
            )
        }
        withContext(Dispatchers.Main) {
            if (isCurrentSessionAndTrack(connectionGen, newGen, device.udn)) {
                playerHost?.onDlnaGaplessTransitionedToNext(observedNext.song)
                playerHost?.onDlnaPlaybackStateChanged(
                    isPlaying = true,
                    positionMs = posInfo.positionMs.coerceAtLeast(0L),
                    durationMs = durMs
                )
            }
        }
        scheduleNextTrackPrequeue(device, connectionGen, newGen)
    }

    private fun clearQueuedNext() {
        val old = queuedNext
        queuedNext = null
        val path = old?.streamPath?.takeIf { it.isNotBlank() }
        if (path != null && path != activeStreamPath) {
            mediaServer.unregisterStream(path)
        }
    }

    private fun subscribeGenaIfAvailable(device: DlnaDevice, connectionGen: Long, callbackPath: String) {
        val eventSubUrl = device.avTransportEventSubUrl.takeIf { it.isNotBlank() } ?: run {
            if (connectionGen == connectionGenerationCounter.get()) {
                mediaServer.clearGenaSubscriptions()
            }
            return
        }
        val lanIp = DlnaLanAddressResolver.resolveLanIpv4ForTarget(device.host) ?: run {
            if (connectionGen == connectionGenerationCounter.get()) {
                mediaServer.clearGenaSubscriptions()
            }
            return
        }
        val callbackUrl = mediaServer.buildUrl(lanIp, callbackPath)
        mediaServer.genaSubscriptionInFlight = true
        try {
            controlClient.subscribeGena(eventSubUrl, callbackUrl).onSuccess { sub ->
                if (connectionGen == connectionGenerationCounter.get()) {
                    activeGenaSid = sub.sid
                    activeGenaEventSubUrl = eventSubUrl
                    mediaServer.registerGenaSubscription(sub.sid)
                } else {
                    runCatching { controlClient.unsubscribeGena(eventSubUrl, sub.sid) }
                }
            }.onFailure {
                if (connectionGen == connectionGenerationCounter.get()) {
                    mediaServer.clearGenaSubscriptions()
                }
            }
        } finally {
            if (connectionGen == connectionGenerationCounter.get()) {
                mediaServer.genaSubscriptionInFlight = false
            }
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

    private fun retireActiveTransportMedia(device: DlnaDevice?) {
        val prevPath = activeStreamPath
        activeStreamPath = null
        mediaServer.unregisterStream(prevPath)
        clearQueuedNext()
        currentTrackUriLoadedGen = -1L
        pendingPausedSeekMs = null
        if (device != null) {
            runCatching { controlClient.stop(device) }
        }
    }

    private suspend fun failCastGeneration(
        gen: Long,
        expectedConnectionGen: Long,
        device: DlnaDevice?,
        message: String
    ) {
        if (gen != trackGenerationCounter.get() ||
            expectedConnectionGen != connectionGenerationCounter.get()
        ) {
            return
        }
        retireActiveTransportMedia(device)
        _castStateFlow.update {
            it.copy(
                currentSnapshot = null,
                transportState = DlnaTransportState.STOPPED,
                remotePositionMs = 0L,
                remoteDurationMs = 0L,
                errorMessage = message,
                statusMessage = null
            )
        }
        withContext(Dispatchers.Main) {
            if (gen == trackGenerationCounter.get() &&
                expectedConnectionGen == connectionGenerationCounter.get()
            ) {
                playerHost?.onDlnaCastError(message)
            }
        }
    }

    private fun isCurrentSessionAndTrack(
        expectedConnGen: Long,
        expectedTrackGen: Long,
        expectedUdn: String
    ): Boolean {
        return connectionGenerationCounter.get() == expectedConnGen &&
            trackGenerationCounter.get() == expectedTrackGen &&
            _castStateFlow.value.activeDevice?.udn == expectedUdn
    }

    private fun mergeDiscoveredDevices(
        manualDevices: List<DlnaDevice>,
        scanned: List<DlnaDevice>
    ): List<DlnaDevice> {
        val map = LinkedHashMap<String, DlnaDevice>()
        for (item in manualDevices) {
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
