package moe.ouom.neriplayer.ui.viewmodel

import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.transfer.TransferFailure
import moe.ouom.neriplayer.data.model.transfer.TransferFailureKind

internal fun feedbackValue(label: UiText, value: Any?): UiText =
    UiText.Resource(AppR.string.feedback_value, listOf(label, value))

internal fun feedbackLines(lines: List<UiText>): UiText = lines.reduce { previous, next ->
    UiText.Resource(AppR.string.feedback_lines, listOf(previous, next))
}

internal fun transferFailureText(error: Throwable): UiText {
    val kind = (error as? TransferFailure)?.kind
    val resource = when (kind) {
        TransferFailureKind.OUTPUT_UNAVAILABLE -> CoreCommonR.string.error_cannot_open_output
        TransferFailureKind.AUTH_STORAGE_UNAVAILABLE -> CoreCommonR.string.auth_storage_unavailable_message
        TransferFailureKind.AUTH_STORAGE_CONFIRMATION_REQUIRED -> CoreCommonR.string.auth_storage_compatibility_message
        TransferFailureKind.LOCAL_PLAYLIST_NOT_READY -> CoreCommonR.string.local_playlist_initialization_failed
        TransferFailureKind.PLAY_HISTORY_NOT_READY -> AppR.string.feedback_history_not_ready
        TransferFailureKind.PLAYBACK_STATS_NOT_READY -> AppR.string.feedback_stats_not_ready
        TransferFailureKind.BACKUP_INPUT_UNAVAILABLE -> AppR.string.feedback_backup_input_unavailable
        TransferFailureKind.NO_PLAYLIST_DATA -> AppR.string.feedback_backup_no_playlists
        null -> return UiText.Literal(error.message.orEmpty())
    }
    return UiText.Resource(resource)
}
