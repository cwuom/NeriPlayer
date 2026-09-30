package moe.ouom.neriplayer.core.player.service.notification

import moe.ouom.neriplayer.data.identity.stableKey

import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkSnapshot
import moe.ouom.neriplayer.core.player.service.presentation.PlaybackNotificationSnapshot
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.SleepTimerMode
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import moe.ouom.neriplayer.core.player.presentation.widget.buildPlaybackWidgetState
import moe.ouom.neriplayer.core.player.presentation.widget.playbackWidgetPresentationChanged

private const val PLAY_INTENT_INDEX = 0
private const val PAUSE_INTENT_INDEX = 1
private const val RESTORE_VOLUME_INTENT_INDEX = 2

internal class PlaybackNotificationActionChoice(
    val iconRes: Int,
    val titleRes: Int,
    val intentIndex: Int,
)

internal fun playbackControlActionChoice(
    audioRouteMuted: Boolean,
    playbackControlPlaying: Boolean,
): PlaybackNotificationActionChoice = when {
    audioRouteMuted -> PlaybackNotificationActionChoice(
        CoreCommonR.drawable.round_volume_up_24, CoreCommonR.string.player_restore_volume, RESTORE_VOLUME_INTENT_INDEX,
    )
    playbackControlPlaying -> PlaybackNotificationActionChoice(
        CoreCommonR.drawable.round_pause_24, CoreCommonR.string.player_pause, PAUSE_INTENT_INDEX,
    )
    else -> PlaybackNotificationActionChoice(
        CoreCommonR.drawable.round_play_arrow_24, CoreCommonR.string.player_play, PLAY_INTENT_INDEX,
    )
}

internal fun favoriteActionIcon(isFavorite: Boolean): Int =
    if (isFavorite) CoreCommonR.drawable.ic_baseline_favorite_24 else CoreCommonR.drawable.ic_outline_favorite_24

internal fun favoriteActionTitle(isFavorite: Boolean): Int =
    if (isFavorite) CoreCommonR.string.favorite_remove else CoreCommonR.string.favorite_add

internal fun floatingLyricsActionIcon(enabled: Boolean): Int =
    if (enabled) CoreCommonR.drawable.ic_lyrics_off_24 else CoreCommonR.drawable.ic_lyrics_24

internal fun floatingLyricsActionTitle(enabled: Boolean): Int =
    if (enabled) CoreCommonR.string.notification_hide_floating_lyrics else CoreCommonR.string.notification_show_floating_lyrics

internal fun serviceNotificationTitle(song: SongItem?): String = song?.displayName() ?: "NeriPlayer"

internal fun serviceNotificationText(
    song: SongItem?,
    timerState: SleepTimerState,
    remaining: String,
    localized: (Int, String?) -> String,
): String {
    val artist = song?.displayArtist().orEmpty()
    if (!timerState.isActive) return artist
    val timer = serviceTimerText(timerState.mode, remaining, localized)
    return artist.takeIf(String::isNotBlank)?.let { "$it | $timer" } ?: timer
}

private fun serviceTimerText(
    mode: SleepTimerMode,
    remaining: String,
    localized: (Int, String?) -> String,
): String = when (mode) {
    SleepTimerMode.COUNTDOWN -> localized(CoreCommonR.string.notification_timer_remaining, remaining)
    SleepTimerMode.COUNTDOWN_FINISH_CURRENT ->
        localized(CoreCommonR.string.notification_timer_finish_current_remaining, remaining)
    SleepTimerMode.FINISH_CURRENT -> localized(CoreCommonR.string.notification_stop_after_current, null)
    SleepTimerMode.FINISH_PLAYLIST -> localized(CoreCommonR.string.notification_stop_after_playlist, null)
}

internal fun serviceNotificationSnapshot(
    song: SongItem?,
    text: String,
    transportActive: Boolean,
    playbackControlPlaying: Boolean,
    audioRouteMuted: Boolean,
    isFavorite: Boolean,
    interactiveFavorite: Boolean,
    artwork: PlaybackArtworkSnapshot,
    lyricState: StatusBarLyricNotificationState,
    floatingLyricsEnabled: Boolean,
): PlaybackNotificationSnapshot = PlaybackNotificationSnapshot(
    songKey = serviceNotificationSongKey(song),
    title = serviceNotificationTitle(song),
    text = text,
    isTransportActive = transportActive,
    isPlaybackControlPlaying = playbackControlPlaying,
    isAudioRouteMuted = audioRouteMuted,
    isFavorite = isFavorite,
    requiresInteractiveFavoriteConfirmation = interactiveFavorite,
    largeIconReady = artwork.notificationReady,
    coverSource = artwork.coverSource,
    statusBarLyricState = lyricState,
    floatingLyricsEnabled = floatingLyricsEnabled,
)

internal class ServiceWidgetLabels(
    val appName: String,
    val idleSubtitle: String,
    val buffering: String,
    val playing: String,
    val paused: String,
    val ready: String,
)

internal class ServiceWidgetInputs(
    val song: SongItem?,
    val playerSongPresent: Boolean,
    val playerPositionMs: Long,
    val roomPositionMs: Long,
    val buffering: Boolean,
    val transportActive: Boolean,
    val roomPlaying: Boolean,
    val favorite: Boolean,
    val canToggleFavorite: Boolean,
    val floatingLyricsEnabled: Boolean,
    val artwork: PlaybackArtworkSnapshot,
    val labels: ServiceWidgetLabels,
)

internal fun servicePlaybackWidgetState(input: ServiceWidgetInputs): PlaybackWidgetState {
    val song = input.song
    val fallback = usesRoomSongFallback(input)
    val playing = isServiceWidgetPlaying(input, fallback)
    return buildPlaybackWidgetState(
        title = serviceWidgetTitle(song, input.labels),
        subtitle = serviceWidgetSubtitle(song, input.labels),
        status = serviceWidgetStatus(input, playing),
        positionMs = serviceWidgetPosition(input, fallback),
        durationMs = serviceWidgetDuration(song),
        hasSong = hasServiceWidgetSong(song),
        isPlaying = playing,
        isFavorite = input.favorite,
        canToggleFavorite = input.canToggleFavorite,
        isFloatingLyricsEnabled = input.floatingLyricsEnabled,
        artworkReady = input.artwork.notificationReady,
        contentId = serviceWidgetContentId(song),
        coverId = input.artwork.coverSource.orEmpty(),
        artworkPending = input.artwork.pending,
    )
}

private fun usesRoomSongFallback(input: ServiceWidgetInputs): Boolean =
    !input.playerSongPresent && input.song != null

private fun isServiceWidgetPlaying(input: ServiceWidgetInputs, fallback: Boolean): Boolean =
    input.transportActive || (fallback && input.roomPlaying)

private fun serviceWidgetPosition(input: ServiceWidgetInputs, fallback: Boolean): Long =
    if (fallback) input.roomPositionMs else input.playerPositionMs

private fun hasServiceWidgetSong(song: SongItem?): Boolean = song != null

private fun serviceWidgetTitle(song: SongItem?, labels: ServiceWidgetLabels): String =
    song?.displayName() ?: labels.appName

private fun serviceWidgetSubtitle(song: SongItem?, labels: ServiceWidgetLabels): String =
    song?.displayArtist()?.takeIf(String::isNotBlank) ?: labels.idleSubtitle

private fun serviceWidgetDuration(song: SongItem?): Long = song?.durationMs ?: 0L

private fun serviceWidgetContentId(song: SongItem?): String = song?.stableKey().orEmpty()

private fun serviceNotificationSongKey(song: SongItem?): String? = song?.stableKey()

private fun serviceWidgetStatus(input: ServiceWidgetInputs, playing: Boolean): String = when {
    input.buffering -> input.labels.buffering
    playing -> input.labels.playing
    input.song != null -> input.labels.paused
    else -> input.labels.ready
}

internal fun shouldUpdateServicePlaybackWidget(
    force: Boolean,
    previous: PlaybackWidgetState?,
    next: PlaybackWidgetState,
): Boolean = force || playbackWidgetPresentationChanged(previous, next)
