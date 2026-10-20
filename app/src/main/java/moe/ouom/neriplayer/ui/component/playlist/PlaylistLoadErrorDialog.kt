package moe.ouom.neriplayer.ui.component.playlist

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.io.IOException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliHttpStatusException
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

internal fun playlistLoadErrorMessage(context: Context, error: Throwable): String {
    val httpError = generateSequence(error) { it.cause }.take(8).filterIsInstance<BiliHttpStatusException>().firstOrNull()
    return when {
        httpError?.statusCode == 412 -> context.getString(CoreCommonR.string.playlist_error_bili_risk_control)
        httpError != null -> context.getString(CoreCommonR.string.playlist_error_http, httpError.statusCode)
        error is IOException -> context.getString(CoreCommonR.string.playlist_error_network)
        else -> context.getString(CoreCommonR.string.playlist_error_unknown)
    }
}

internal fun playlistLoadErrorMessage(context: Context, rawMessage: String): String {
    val offlineMessage = context.getString(CoreCommonR.string.playlist_error_offline_no_cache)
    if (rawMessage == offlineMessage) return offlineMessage
    val summary = rawMessage.take(256)
    val statusCode = Regex("\\bHTTP\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
        .find(summary)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        statusCode == 412 -> context.getString(CoreCommonR.string.playlist_error_bili_risk_control)
        statusCode != null -> context.getString(CoreCommonR.string.playlist_error_http, statusCode)
        Regex("network|timeout|connect|网络", RegexOption.IGNORE_CASE).containsMatchIn(summary) ->
            context.getString(CoreCommonR.string.playlist_error_network)
        else -> context.getString(CoreCommonR.string.playlist_error_unknown)
    }
}

@Composable
internal fun PlaylistLoadErrorDialog(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    DensityScaledAlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(CoreCommonR.string.playlist_error_title)) },
        text = { Text(message) },
        confirmButton = {
            HapticTextButton(onClick = onRetry) { Text(stringResource(CoreCommonR.string.action_retry)) }
        },
        dismissButton = {
            HapticTextButton(onClick = onBack) { Text(stringResource(CoreCommonR.string.action_back)) }
        }
    )
}
