package moe.ouom.neriplayer.ui.viewmodel

import androidx.annotation.StringRes
import moe.ouom.neriplayer.api.sync.github.GitHubRateLimitException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.model.sync.SyncResultMessage
import moe.ouom.neriplayer.ui.i18n.UiText

internal fun syncResultText(result: SyncResult, webDav: Boolean): UiText = when (result.messageKind) {
    SyncResultMessage.MERGED -> feedbackText(if (webDav) CoreCommonR.string.webdav_sync_success_detail
        else CoreCommonR.string.github_sync_success_detail)
    SyncResultMessage.INITIAL_UPLOADED -> feedbackText(CoreCommonR.string.sync_initial_uploaded)
    SyncResultMessage.NO_CHANGE -> feedbackText(if (webDav) CoreCommonR.string.webdav_sync_no_change
        else CoreCommonR.string.github_sync_no_change)
    SyncResultMessage.LOCAL_CHANGES_PENDING -> feedbackText(CoreCommonR.string.sync_local_changes_pending)
    SyncResultMessage.CUSTOM -> UiText.Literal(result.message)
}

internal fun syncFailureText(error: Throwable?, @StringRes fallback: Int): UiText {
    if (error is GitHubRateLimitException) {
        val seconds = (error.retryAtMillis.coerceAtLeast(0L) - System.currentTimeMillis().coerceAtLeast(0L))
            .coerceAtLeast(0L) / 1_000L + 1L
        return UiText.Plural(
            CoreCommonR.plurals.github_rate_limit_message,
            seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            listOf(seconds, feedbackText(
                if (error.automaticRetryAllowed) CoreCommonR.string.github_rate_limit_continue
                else CoreCommonR.string.github_rate_limit_paused
            ))
        )
    }
    return error?.message?.let { UiText.Literal(it) } ?: feedbackText(fallback)
}
