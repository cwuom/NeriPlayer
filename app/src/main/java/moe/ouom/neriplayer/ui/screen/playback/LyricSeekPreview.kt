package moe.ouom.neriplayer.ui.screen.playback

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
    val normalizedUserId = sessionUserUuid.trimmedOrNull() ?: return fallbackRole
    val controllerId = controllerUserUuid.trimmedOrNull()
        ?: controllerUserId.trimmedOrNull()
        ?: return fallbackRole
    return if (normalizedUserId == controllerId) "controller" else "listener"
}

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)
