package moe.ouom.neriplayer.lanltw.core

import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.ltw.track.listenTogetherTrackKey

/** Input authorization and media identity rules, independent of room mutation. */
object LanRoomPolicy {
    data class PlayDecision(val track: ListenTogetherTrack? = null, val position: Double = 0.0, val error: String? = null)
    fun play(host: Boolean, permissions: LanPermissions, current: LanPlayback, currentPosition: Double, message: LanMessage): PlayDecision {
        val track = message.track ?: current.track ?: return PlayDecision(error = "SOURCE")
        if (!validIdentity(track) || !validMetadata(track)) return PlayDecision(error = "SOURCE")
        val intent = message.intent ?: "PLAY_PAUSE"
        if (!allowed(host, permissions, intent, track.stableKey != current.track?.stableKey)) return PlayDecision(error = "DENIED")
        return position(track, intent, message.trackTimeSeconds, currentPosition)
    }
    fun allowed(host: Boolean, permissions: LanPermissions, intent: String, changed: Boolean): Boolean {
        if (intent !in setOf("NEXT", "SEEK", "PLAY_PAUSE")) return false
        if (changed && intent != "NEXT") return false
        if (host) return true
        return when (intent) { "NEXT" -> permissions.next; "SEEK" -> permissions.seek; else -> permissions.playPause }
    }
    fun validIdentity(track: ListenTogetherTrack): Boolean =
        track.channelId in setOf("netease", "bilibili", "youtubeMusic", "local") &&
            track.stableKey == listenTogetherTrackKey(track.channelId, track.audioId, track.subAudioId, track.playlistContextId)
    fun validMetadata(track: ListenTogetherTrack): Boolean =
        track.stableKey.length in 1..512 && track.audioId.length in 1..256 && track.name.length <= 512 && track.durationMs >= 0
    private fun position(track: ListenTogetherTrack, intent: String, requested: Double?, current: Double): PlayDecision {
        val position = when (intent) { "NEXT" -> 0.0; "SEEK" -> requested; else -> resumePosition(track, current) }
        if (position == null || !position.isFinite() || position < 0) return PlayDecision(error = "INVALID")
        val bounded = if (track.durationMs > 0) position.coerceAtMost(track.durationMs / 1000.0) else position
        return PlayDecision(track.copy(streamUrl = null, streamUrls = emptyList(), mediaUri = null), bounded)
    }
    private fun resumePosition(track: ListenTogetherTrack, current: Double): Double =
        if (track.durationMs > 0 && current >= track.durationMs / 1000.0) 0.0 else current
    fun volume(value: Float?): Float? {
        if (value == null) return null
        return if (value.isFinite() && value in 0f..1f) value else null
    }
}
