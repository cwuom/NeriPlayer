package moe.ouom.neriplayer.core.startup.player

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommand

internal class PlayerStartupServiceSyncCoordinator(
    private val awaitUiFrame: suspend () -> Unit,
    private val isServiceReadyForPassiveLocalPlaybackSync: () -> Boolean,
    private val hasItems: () -> Boolean,
    private val hasLocalCurrentSong: () -> Boolean,
    private val isUsbExclusivePlaybackActiveForForegroundService: () -> Boolean,
    private val shouldRunPlaybackServiceInForeground: () -> Boolean,
    private val isServiceInstanceActiveForDiagnostics: () -> Boolean = { false },
    private val isServiceForegroundActiveForDiagnostics: () -> Boolean = { false },
    private val startService: (source: String, forceForeground: Boolean) -> Unit,
    private val playbackCommandFlow: Flow<PlaybackCommand>? = null
) {
    suspend fun startServiceAfterUiFrame(
        source: String,
        forceForeground: Boolean
    ) {
        awaitUiFrame()
        if (PlayerStartupServiceSyncPlanner.isLocalPlaybackCommandSource(source)) {
            delay(PlayerStartupServiceSyncPlanner.LOCAL_PLAYBACK_COMMAND_DELAY_MS)
        }
        val plan = PlayerStartupServiceSyncPlanner.planServiceStart(
            source = source,
            forceForeground = forceForeground,
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
            return
        }
        NPLogger.d("NERI-App", "Starting audio service: source=${plan.source}")
        startService(plan.source, plan.forceForeground)
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
                    startServiceAfterUiFrame(
                        source = serviceStart.source,
                        forceForeground = serviceStart.forceForeground
                    )
                }
            }
        }
    }
}
