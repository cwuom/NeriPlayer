package moe.ouom.neriplayer.ui.component.playlist

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.io.IOException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliHttpStatusException
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.data.model.playlist.PlaylistLoadFailure
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.i18n.asString

internal fun playlistLoadFailure(error: Throwable): PlaylistLoadFailure {
    val httpError = generateSequence(error) { it.cause }.take(8).filterIsInstance<BiliHttpStatusException>().firstOrNull()
    return when {
        httpError != null -> PlaylistLoadFailure.Http(httpError.statusCode)
        error is IOException -> PlaylistLoadFailure.Network
        else -> PlaylistLoadFailure.Unknown
    }
}

internal fun playlistLoadErrorText(failure: PlaylistLoadFailure): UiText = when (failure) {
    PlaylistLoadFailure.Offline -> UiText.Resource(AppR.string.playlist_error_offline_no_cache)
    PlaylistLoadFailure.Network -> UiText.Resource(AppR.string.playlist_error_network)
    PlaylistLoadFailure.Unknown -> UiText.Resource(AppR.string.playlist_error_unknown)
    PlaylistLoadFailure.Incomplete -> UiText.Resource(AppR.string.bili_favorites_incomplete)
    is PlaylistLoadFailure.Http -> if (failure.statusCode == 412) {
        UiText.Resource(AppR.string.playlist_error_bili_risk_control)
    } else {
        UiText.Resource(AppR.string.playlist_error_http, listOf(failure.statusCode))
    }
}

@Composable
internal fun PlaylistLoadErrorDialog(message: PlaylistLoadFailure, onRetry: () -> Unit, onBack: () -> Unit) {
    DensityScaledAlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(AppR.string.playlist_error_title)) },
        text = { Text(playlistLoadErrorText(message).asString()) },
        confirmButton = {
            HapticTextButton(onClick = onRetry) { Text(stringResource(CoreCommonR.string.action_retry)) }
        },
        dismissButton = {
            HapticTextButton(onClick = onBack) { Text(stringResource(CoreCommonR.string.action_back)) }
        }
    )
}
