package moe.ouom.neriplayer.data.ltw.session.control

import moe.ouom.neriplayer.data.ltw.compat.isListenTogetherMemberControlTargetCurrent
import moe.ouom.neriplayer.data.ltw.compat.shouldSuppressListenerControlWhileAwaitingStream
import moe.ouom.neriplayer.data.ltw.control.requestControlEventTypes
import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlaybackHost
import moe.ouom.neriplayer.data.ltw.playback.currentStableKey
import moe.ouom.neriplayer.data.ltw.playback.normalizedDirectStreamUrl
import moe.ouom.neriplayer.data.ltw.playback.requestedStableKey
import moe.ouom.neriplayer.data.ltw.playback.shouldWaitForListenTogetherAuthoritativeStreamPlayback
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState

internal enum class ListenTogetherListenerSuppressionReason { STALE_TARGET, AWAITING_STREAM }

internal class ListenTogetherListenerControlSuppression(
    private val playback: ListenTogetherPlaybackHost,
    songMapper: ListenTogetherSongMapper
) : ListenTogetherSongMapper by songMapper {
    fun reason(event: ListenTogetherEvent, isController: Boolean, room: ListenTogetherRoomState?): ListenTogetherListenerSuppressionReason? {
        if (isController || event.type !in requestControlEventTypes) return null
        if (!isListenTogetherMemberControlTargetCurrent(event.type, event.requestedStableKey(), room?.currentStableKey())) {
            return ListenTogetherListenerSuppressionReason.STALE_TARGET
        }
        val suppress = shouldSuppressListenerControlWhileAwaitingStream(event.type, awaitingStream(room), hasDirectStream())
        return if (suppress) ListenTogetherListenerSuppressionReason.AWAITING_STREAM else null
    }

    private fun awaitingStream(room: ListenTogetherRoomState?): Boolean {
        val target = room?.targetSongItem() ?: return false
        val current = playback.currentSongFlow.value
        return shouldWaitForListenTogetherAuthoritativeStreamPlayback(
            playback.shouldWaitForListenTogetherAuthoritativeStream(target), current?.sameTrackAs(target) == true,
            current?.streamUrl, playback.currentMediaUrlFlow.value
        )
    }

    private fun hasDirectStream(): Boolean =
        normalizedDirectStreamUrl(playback.currentSongFlow.value?.streamUrl) != null || normalizedDirectStreamUrl(playback.currentMediaUrlFlow.value) != null
}
