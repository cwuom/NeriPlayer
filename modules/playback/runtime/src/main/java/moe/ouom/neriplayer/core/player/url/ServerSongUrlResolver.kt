package moe.ouom.neriplayer.core.player.url

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.subsonicErrorMessageRes

/** Resolve a server resource with the existing playback result and gated error feedback. */
internal suspend fun PlayerManager.resolveServerSongUrl(
    song: SongItem,
    forceRefresh: Boolean,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult = try {
    val result = PlayerDependencies.repositories.subsonicRepository
        ?.playback(song, forceRefresh) ?: SongUrlResult.Failure
    if (result is SongUrlResult.Success) {
        result.copy(audioInfo = result.audioInfo?.copy(
            qualityLabel = getLocalizedString(CoreCommonR.string.server_quality_original)))
    } else {
        sideEffects.emitError { postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.server_unavailable))) }
        SongUrlResult.Failure
    }
} catch (_: TimeoutCancellationException) {
    sideEffects.emitError { postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.server_timeout))) }
    SongUrlResult.Failure
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: SubsonicException) {
    sideEffects.emitError { postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(subsonicErrorMessageRes(error)))) }
    SongUrlResult.Failure
} catch (_: Exception) {
    sideEffects.emitError { postPlayerEvent(PlayerEvent.ShowError(getLocalizedString(CoreCommonR.string.server_request_failed))) }
    SongUrlResult.Failure
}
