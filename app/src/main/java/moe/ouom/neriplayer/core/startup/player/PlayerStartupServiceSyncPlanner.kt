package moe.ouom.neriplayer.core.startup.player

import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommand
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.policy.command.shouldSyncPlaybackServiceForLocalPlaybackCommand
import moe.ouom.neriplayer.core.player.service.shouldSkipLocalPlaybackSyncServiceStart

internal data class PlayerStartupServiceSyncPlan(
    val shouldStartService: Boolean,
    val source: String,
    val forceForeground: Boolean,
    val isLocalPlaybackCommand: Boolean
)

internal object PlayerStartupServiceSyncPlanner {
    const val LOCAL_PLAYBACK_COMMAND_SOURCE_PREFIX = "local_playback_command_"
    const val LOCAL_PLAYBACK_COMMAND_DELAY_MS = 450L

    fun planServiceStart(
        source: String,
        forceForeground: Boolean,
        serviceReady: Boolean,
        hasItems: Boolean,
        hasLocalCurrentSong: Boolean,
        usbExclusivePlaybackActive: Boolean
    ): PlayerStartupServiceSyncPlan {
        val shouldSkip = shouldSkipLocalPlaybackSyncServiceStart(
            source = source,
            serviceReady = serviceReady,
            hasItems = hasItems,
            hasLocalCurrentSong = hasLocalCurrentSong,
            usbExclusivePlaybackActive = usbExclusivePlaybackActive
        )
        return PlayerStartupServiceSyncPlan(
            shouldStartService = !shouldSkip,
            source = source,
            forceForeground = forceForeground,
            isLocalPlaybackCommand = isLocalPlaybackCommandSource(source)
        )
    }

    fun planLocalPlaybackCommand(
        command: PlaybackCommand,
        hasItems: Boolean,
        shouldRunServiceInForeground: Boolean
    ): PlayerStartupServiceStart? {
        if (command.source != PlaybackCommandSource.LOCAL) {
            return null
        }
        if (!shouldSyncPlaybackServiceForLocalPlaybackCommand(command.type)) {
            return null
        }
        if (!hasItems) {
            return null
        }
        return PlayerStartupServiceStart(
            source = LOCAL_PLAYBACK_COMMAND_SOURCE_PREFIX + command.type.lowercase(),
            forceForeground = shouldRunServiceInForeground
        )
    }

    fun isLocalPlaybackCommandSource(source: String): Boolean {
        return source.startsWith(LOCAL_PLAYBACK_COMMAND_SOURCE_PREFIX)
    }
}
