package moe.ouom.neriplayer.core.player.presentation.widget

import android.content.Context
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import java.util.Locale

const val PLAYBACK_WIDGET_PROGRESS_MAX = 1000
const val PLAYBACK_WIDGET_POSITION_BUCKET_MS = 1_000L
const val PLAYBACK_WIDGET_PROGRESS_UPDATE_INTERVAL_MS = 15_000L

data class PlaybackWidgetState(
    val title: String,
    val subtitle: String,
    val status: String,
    val positionMs: Long,
    val elapsedText: String,
    val durationText: String,
    val progress: Int,
    val hasSong: Boolean,
    val isPlaying: Boolean,
    val isFavorite: Boolean,
    val canToggleFavorite: Boolean,
    val isFloatingLyricsEnabled: Boolean,
    val artworkReady: Boolean,
    val contentId: String = "",
    val coverId: String = "",
    val artworkPending: Boolean = false,
) {
    companion object {
        fun idle(context: Context): PlaybackWidgetState {
            return PlaybackWidgetState(
                title = context.getString(CoreCommonR.string.app_name),
                subtitle = context.getString(CoreCommonR.string.widget_playback_idle_subtitle),
                status = context.getString(CoreCommonR.string.widget_playback_ready),
                positionMs = 0L,
                elapsedText = "0:00",
                durationText = "0:00",
                progress = 0,
                hasSong = false,
                isPlaying = false,
                isFavorite = false,
                canToggleFavorite = false,
                isFloatingLyricsEnabled = false,
                artworkReady = false,
            )
        }
    }
}

fun buildPlaybackWidgetState(
    title: String,
    subtitle: String,
    status: String,
    positionMs: Long,
    durationMs: Long,
    hasSong: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    canToggleFavorite: Boolean,
    isFloatingLyricsEnabled: Boolean,
    artworkReady: Boolean,
    contentId: String = "",
    coverId: String = "",
    artworkPending: Boolean = false,
): PlaybackWidgetState {
    val bucketedPositionMs = playbackWidgetBucketedPositionMs(
        positionMs = positionMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
    )
    return PlaybackWidgetState(
        title = title,
        subtitle = subtitle,
        status = status,
        positionMs = bucketedPositionMs,
        elapsedText = formatPlaybackWidgetTime(bucketedPositionMs),
        durationText = formatPlaybackWidgetTime(durationMs),
        progress = playbackWidgetProgress(
            positionMs = bucketedPositionMs,
            durationMs = durationMs,
        ),
        hasSong = hasSong,
        isPlaying = isPlaying,
        isFavorite = isFavorite,
        canToggleFavorite = canToggleFavorite,
        isFloatingLyricsEnabled = isFloatingLyricsEnabled,
        artworkReady = artworkReady,
        contentId = contentId,
        coverId = coverId,
        artworkPending = artworkPending,
    )
}

fun shouldUseCachedPlaybackWidgetArtwork(state: PlaybackWidgetState): Boolean {
    return state.hasSong && state.artworkReady
}

fun shouldRetainPlaybackWidgetVisuals(state: PlaybackWidgetState): Boolean {
    return state.hasSong && !state.artworkReady && state.artworkPending
}

fun shouldShowPlaybackWidgetFallbackBackground(
    hasThemeBackground: Boolean,
): Boolean = !hasThemeBackground

fun playbackWidgetPresentationChanged(
    previous: PlaybackWidgetState?,
    current: PlaybackWidgetState,
): Boolean {
    return previous?.presentationKey() != current.presentationKey()
}

private fun PlaybackWidgetState.presentationKey(): PlaybackWidgetState =
    copy(positionMs = 0L, elapsedText = "", progress = 0)

fun shouldPartiallyUpdatePlaybackWidgetProgress(
    previous: PlaybackWidgetState?,
    current: PlaybackWidgetState,
): Boolean {
    return !playbackWidgetPresentationChanged(previous, current) &&
        current.hasSong &&
        current.isPlaying
}

fun playbackWidgetProgressRefreshBucket(positionMs: Long): Long {
    return positionMs.coerceAtLeast(0L) / PLAYBACK_WIDGET_PROGRESS_UPDATE_INTERVAL_MS
}

fun playbackWidgetBucketedPositionMs(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    bucketMs: Long = PLAYBACK_WIDGET_POSITION_BUCKET_MS,
): Long {
    val normalizedPositionMs = normalizedPlaybackWidgetPositionMs(positionMs, durationMs)
    if (!isPlaying || bucketMs <= 0L) {
        return normalizedPositionMs
    }
    return normalizedPositionMs / bucketMs * bucketMs
}

fun playbackWidgetProgress(
    positionMs: Long,
    durationMs: Long,
): Int {
    if (durationMs <= 0L) {
        return 0
    }
    val normalizedPositionMs = normalizedPlaybackWidgetPositionMs(positionMs, durationMs)
    return ((normalizedPositionMs * PLAYBACK_WIDGET_PROGRESS_MAX) / durationMs)
        .toInt()
        .coerceIn(0, PLAYBACK_WIDGET_PROGRESS_MAX)
}

fun formatPlaybackWidgetTime(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun normalizedPlaybackWidgetPositionMs(
    positionMs: Long,
    durationMs: Long,
): Long {
    val nonNegativePositionMs = positionMs.coerceAtLeast(0L)
    if (durationMs <= 0L) {
        return nonNegativePositionMs
    }
    return nonNegativePositionMs.coerceAtMost(durationMs)
}

data class PlaybackWidgetThemeColors(
    val backgroundStart: Int,
    val backgroundEnd: Int,
    val primaryControl: Int,
)

fun derivePlaybackWidgetThemeColors(seedColor: Int): PlaybackWidgetThemeColors {
    val opaqueSeed = seedColor or 0xFF000000.toInt()
    return PlaybackWidgetThemeColors(
        backgroundStart = scalePlaybackWidgetColor(opaqueSeed, 0.62f),
        backgroundEnd = scalePlaybackWidgetColor(opaqueSeed, 0.30f),
        primaryControl = normalizePlaybackWidgetAccent(opaqueSeed),
    )
}

private fun scalePlaybackWidgetColor(
    color: Int,
    factor: Float,
): Int {
    val safeFactor = factor.coerceAtLeast(0f)
    return playbackWidgetArgb(
        red = ((color ushr 16 and 0xFF) * safeFactor).toInt(),
        green = ((color ushr 8 and 0xFF) * safeFactor).toInt(),
        blue = ((color and 0xFF) * safeFactor).toInt(),
    )
}

private fun normalizePlaybackWidgetAccent(color: Int): Int {
    val maxChannel = maxOf(
        color ushr 16 and 0xFF,
        color ushr 8 and 0xFF,
        color and 0xFF,
    )
    if (maxChannel == 0) {
        return playbackWidgetArgb(112, 112, 112)
    }
    val factor = when {
        maxChannel < 132 -> 132f / maxChannel
        maxChannel > 218 -> 218f / maxChannel
        else -> 1f
    }
    return scalePlaybackWidgetColor(color, factor)
}

private fun playbackWidgetArgb(
    red: Int,
    green: Int,
    blue: Int,
): Int {
    return 0xFF000000.toInt() or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)
}
