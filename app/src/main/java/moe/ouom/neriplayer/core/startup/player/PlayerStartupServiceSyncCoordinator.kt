package moe.ouom.neriplayer.core.startup.player

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import kotlin.time.Duration.Companion.milliseconds

internal class PlayerStartupServiceSyncCoordinator(
    private val isServiceReadyForPassiveLocalPlaybackSync: () -> Boolean,
    private val hasItems: () -> Boolean,
    private val hasLocalCurrentSong: () -> Boolean,
    private val isUsbExclusivePlaybackActiveForForegroundService: () -> Boolean,
    private val shouldRunPlaybackServiceInForeground: () -> Boolean,
    private val currentBootstrapServiceStart: () -> PlayerStartupServiceStart?,
    private val isServiceInstanceActiveForDiagnostics: () -> Boolean = { false },
    private val isServiceForegroundActiveForDiagnostics: () -> Boolean = { false },
    private val startService: (source: String, forceForeground: Boolean) -> Boolean,
    private val playbackCommandFlow: Flow<PlaybackCommand>? = null
) {
    private val pendingServiceStart = MutableStateFlow<PlayerStartupServiceStart?>(null)
    val pendingServiceStartFlow = pendingServiceStart.asStateFlow()

    suspend fun requestServiceStart(
        source: String,
        forceForeground: Boolean
    ): Boolean {
        if (
            !forceForeground &&
            PlayerStartupServiceSyncPlanner.isLocalPlaybackCommandSource(source)
        ) {
            delay(PlayerStartupServiceSyncPlanner.LOCAL_PLAYBACK_COMMAND_DELAY_MS.milliseconds)
        }
        return startServiceOrDefer(PlayerStartupServiceStart(source, forceForeground))
    }

    fun retryPendingServiceStart() {
        val request = pendingServiceStart.value ?: return
        if (!hasItems()) {
            pendingServiceStart.value = null
            return
        }
        val retryRequest = when (request.source) {
            PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE,
            PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE -> currentBootstrapServiceStart()
            else -> request.takeIf { shouldRunPlaybackServiceInForeground() }
        }
        if (retryRequest == null) {
            pendingServiceStart.value = null
            return
        }
        startServiceOrDefer(retryRequest)
    }

    private fun startServiceOrDefer(request: PlayerStartupServiceStart): Boolean {
        val plan = PlayerStartupServiceSyncPlanner.planServiceStart(
            source = request.source,
            forceForeground = request.forceForeground,
            serviceReady = isServiceReadyForPassiveLocalPlaybackSync(),
            hasItems = hasItems(),
            hasLocalCurrentSong = hasLocalCurrentSong(),
            usbExclusivePlaybackActive = isUsbExclusivePlaybackActiveForForegroundService()
        )
        if (!plan.shouldStartService) {
            NPLogger.d(
                "NERI-App",
                "Skipping audio service sync because active playback service is already tracking " +
                    "source=${plan.source} serviceInstance=${isServiceInstanceActiveForDiagnostics()} " +
                    "serviceForeground=${isServiceForegroundActiveForDiagnostics()}"
            )
            pendingServiceStart.value = null
            return true
        }
        NPLogger.d("NERI-App", "Starting audio service: source=${plan.source}")
        val started = startService(plan.source, plan.forceForeground)
        pendingServiceStart.value = if (started) null else request
        return started
    }

    suspend fun collectLocalPlaybackCommands() {
        val commands = playbackCommandFlow ?: return
        coroutineScope {
            commands.collect { command ->
                val serviceStart = PlayerStartupServiceSyncPlanner.planLocalPlaybackCommand(
                    command = command,
                    hasItems = hasItems(),
                    shouldRunServiceInForeground = shouldRunPlaybackServiceInForeground()
                ) ?: return@collect
                launch {
                    requestServiceStart(
                        source = serviceStart.source,
                        forceForeground = serviceStart.forceForeground
                    )
                }
            }
        }
    }
}
