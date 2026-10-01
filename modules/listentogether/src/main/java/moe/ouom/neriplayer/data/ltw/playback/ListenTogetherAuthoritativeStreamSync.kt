package moe.ouom.neriplayer.data.ltw.playback

import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.session.state.normalized
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState

internal data class ListenTogetherStreamSyncDecision(
    val stableKey: String?,
    val streamUrl: String?,
    val reloadForStream: Boolean,
    val reloadForUnavailableStream: Boolean,
    val reloadForStall: Boolean
) {
    val requiresReload: Boolean get() = reloadForStream || reloadForUnavailableStream || reloadForStall
}

internal class ListenTogetherAuthoritativeStreamSync(
    private val playback: ListenTogetherPlaybackHost,
    songMapper: ListenTogetherSongMapper,
    private val isController: () -> Boolean,
    private val roomState: () -> ListenTogetherRoomState?
) : ListenTogetherSongMapper by songMapper {
    private var pendingStableKey: String? = null
    private var pendingStreamUrl: String? = null
    private var lastUnavailableReloadStableKey: String? = null

    fun inspect(state: ListenTogetherRoomState, targetSong: SongItem, previousSong: SongItem?, causeType: String?): ListenTogetherStreamSyncDecision {
        val stableKey = state.currentStableKey()
        resetChangedTrack(stableKey)
        val urls = state.authoritativeStreamUrlsForCurrentTrack()
        val localUrl = playback.currentMediaUrlFlow.value
        val localUsesStream = hasListenTogetherAuthoritativeStreamUrl(urls, localUrl)
        if (localUsesStream) clearPendingStream()
        val reloadForStream = shouldReloadStream(targetSong, previousSong, urls.firstOrNull(), localUrl, localUsesStream)
        val reloadForUnavailable = inspectUnavailableStream(stableKey, targetSong, causeType)
        return ListenTogetherStreamSyncDecision(
            stableKey, urls.firstOrNull(), reloadForStream, reloadForUnavailable,
            reloadForStall = isStalledPlayingTrack(state, targetSong, previousSong, causeType)
        )
    }

    fun onReload(decision: ListenTogetherStreamSyncDecision) {
        if (!decision.reloadForStream) return
        pendingStableKey = decision.stableKey
        pendingStreamUrl = decision.streamUrl
    }

    private fun resetChangedTrack(stableKey: String?) {
        if (pendingStableKey != stableKey) clearPendingStream()
        if (lastUnavailableReloadStableKey != stableKey) lastUnavailableReloadStableKey = null
    }

    private fun clearPendingStream() {
        pendingStableKey = null
        pendingStreamUrl = null
    }

    private fun shouldReloadStream(targetSong: SongItem, previousSong: SongItem?, remoteUrl: String?, localUrl: String?, localUsesStream: Boolean): Boolean {
        if (!mayReloadStream(targetSong, previousSong, localUsesStream)) return false
        return shouldReloadListenTogetherAuthoritativeStream(
            remoteStreamUrl = remoteUrl,
            localResolvedStreamUrl = localUrl,
            localPlaybackRequiresAuthoritativeStream = playback.currentPlaybackRequiresListenTogetherAuthoritativeStream(),
            localPlaybackResolutionPending = playback.isListenTogetherLocalResolutionPendingFor(targetSong),
            pendingAuthoritativeStreamUrl = pendingStreamUrl
        )
    }

    private fun mayReloadStream(targetSong: SongItem, previousSong: SongItem?, localUsesStream: Boolean): Boolean {
        if (isController()) return false
        if (!roomState()?.settings.normalized().shareAudioLinks) return false
        if (previousSong?.sameTrackAs(targetSong) != true) return false
        return !localUsesStream
    }

    private fun inspectUnavailableStream(stableKey: String?, targetSong: SongItem, causeType: String?): Boolean {
        val unavailable = isUnavailable(targetSong, causeType)
        val shouldClear = unavailable && !isController()
        if (shouldClear) clearPendingStream()
        val reload = shouldClear && shouldReloadForListenTogetherLinkUnavailable(
            isController = isController(),
            localPlaybackRequiresAuthoritativeStream = playback.currentPlaybackRequiresListenTogetherAuthoritativeStream(),
            controllerLinkConfirmedUnavailable = unavailable,
            alreadyReloadedForStableKey = lastUnavailableReloadStableKey == stableKey
        )
        if (reload) lastUnavailableReloadStableKey = stableKey
        if (!playback.currentPlaybackRequiresListenTogetherAuthoritativeStream()) lastUnavailableReloadStableKey = null
        return reload
    }

    private fun isUnavailable(song: SongItem, causeType: String?): Boolean =
        causeType == "LINK_UNAVAILABLE" && playback.isListenTogetherAuthoritativeStreamConfirmedUnavailable(song)

    private fun isStalledPlayingTrack(state: ListenTogetherRoomState, targetSong: SongItem, previousSong: SongItem?, causeType: String?): Boolean =
        causeType == "WATCHDOG_STALL" && state.playback.state == "playing" && previousSong?.sameTrackAs(targetSong) == true
}
