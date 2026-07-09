package moe.ouom.neriplayer.ui.screen

import kotlin.math.abs

internal const val LyricSeekPreviewSettleToleranceMs = 280L

internal fun resolveLyricPreviewTimeMs(
    isDraggingSlider: Boolean,
    sliderPreviewPositionMs: Long,
    pendingSeekPreviewPositionMs: Long?,
    playbackPositionMs: Long
): Long {
    return when {
        isDraggingSlider -> sliderPreviewPositionMs
        pendingSeekPreviewPositionMs != null -> pendingSeekPreviewPositionMs
        else -> playbackPositionMs
    }.coerceAtLeast(0L)
}

internal fun shouldReleaseLyricSeekPreview(
    playbackPositionMs: Long,
    pendingSeekPreviewPositionMs: Long,
    toleranceMs: Long = LyricSeekPreviewSettleToleranceMs
): Boolean {
    return abs(playbackPositionMs - pendingSeekPreviewPositionMs) <= toleranceMs
}

internal fun shouldAnimateAdvancedLyricsFromPlayback(
    isPlaying: Boolean,
    isDraggingSlider: Boolean,
    pendingSeekPreviewPositionMs: Long?
): Boolean {
    return isPlaying && !isDraggingSlider && pendingSeekPreviewPositionMs == null
}

internal fun resolveListenTogetherProgressSeekEnabled(
    sessionUserUuid: String?,
    fallbackRole: String?,
    roomId: String?,
    controllerUserUuid: String?,
    controllerUserId: String?,
    allowMemberControl: Boolean?
): Boolean {
    if (roomId.isNullOrBlank()) return true
    if (allowMemberControl != false) return true
    return resolveListenTogetherProgressRole(
        sessionUserUuid = sessionUserUuid,
        fallbackRole = fallbackRole,
        controllerUserUuid = controllerUserUuid,
        controllerUserId = controllerUserId
    ) == "controller"
}

private fun resolveListenTogetherProgressRole(
    sessionUserUuid: String?,
    fallbackRole: String?,
    controllerUserUuid: String?,
    controllerUserId: String?
): String? {
    val normalizedUserId = sessionUserUuid?.trim()?.takeIf { it.isNotBlank() }
    val controllerId = controllerUserUuid?.trim()?.takeIf { it.isNotBlank() }
        ?: controllerUserId?.trim()?.takeIf { it.isNotBlank() }
    return when {
        normalizedUserId != null && controllerId != null -> {
            if (normalizedUserId == controllerId) "controller" else "listener"
        }
        else -> fallbackRole
    }
}
