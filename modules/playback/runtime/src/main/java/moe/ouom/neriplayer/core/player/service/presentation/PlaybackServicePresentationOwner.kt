package moe.ouom.neriplayer.core.player.service.presentation

import moe.ouom.neriplayer.data.identity.stableKey

import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackArtworkOwner
import moe.ouom.neriplayer.core.player.runtime.service.MediaSessionPlaybackStateThrottler
import moe.ouom.neriplayer.core.player.runtime.service.buildMediaSessionControlFingerprint
import moe.ouom.neriplayer.core.player.service.notification.ServiceWidgetInputs
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.core.player.service.notification.favoriteActionIcon
import moe.ouom.neriplayer.core.player.service.notification.favoriteActionTitle
import moe.ouom.neriplayer.core.player.service.notification.floatingLyricsActionIcon
import moe.ouom.neriplayer.core.player.service.notification.floatingLyricsActionTitle
import moe.ouom.neriplayer.core.player.service.notification.hasCurrentSongFavoriteStateChanged
import moe.ouom.neriplayer.core.player.service.notification.resolveFloatingLyricsExternalTargetEnabled
import moe.ouom.neriplayer.core.player.service.notification.serviceNotificationSnapshot
import moe.ouom.neriplayer.core.player.service.notification.serviceNotificationText
import moe.ouom.neriplayer.core.player.service.notification.servicePlaybackWidgetState
import moe.ouom.neriplayer.core.player.service.notification.shouldAllowExternalFavoriteToggle
import moe.ouom.neriplayer.core.player.service.notification.shouldUpdateServicePlaybackWidget
import moe.ouom.neriplayer.core.player.service.notification.shouldUseInteractiveFavoriteIntent
import android.app.Notification
import android.content.Intent
import android.media.AudioAttributes
import android.media.session.MediaSession
import android.media.session.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import moe.ouom.neriplayer.core.player.presentation.widget.playbackWidgetPresentationChanged
import moe.ouom.neriplayer.core.player.presentation.widget.shouldPartiallyUpdatePlaybackWidgetProgress
import moe.ouom.neriplayer.core.player.service.car.carQueueItemId
import moe.ouom.neriplayer.core.player.service.car.CAR_ACTION_TOGGLE_SHUFFLE
import moe.ouom.neriplayer.core.player.service.car.CAR_ACTION_CYCLE_REPEAT
import moe.ouom.neriplayer.data.model.settings.lyrics.BluetoothMetadataMode
import moe.ouom.neriplayer.common.R as CoreCommonR

internal data class PlaybackNotificationSnapshot(
    val songKey: String?,
    val title: String,
    val text: String,
    val isTransportActive: Boolean,
    val isPlaybackControlPlaying: Boolean,
    val isAudioRouteMuted: Boolean,
    val isFavorite: Boolean,
    val requiresInteractiveFavoriteConfirmation: Boolean,
    val largeIconReady: Boolean,
    val coverSource: String?,
    val statusBarLyricState: StatusBarLyricNotificationState,
    val floatingLyricsEnabled: Boolean,
)

internal data class PlaybackMetadataSnapshot(
    val songKey: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val displayTitle: String,
    val displaySubtitle: String,
    val displayDescription: String?,
    val durationMs: Long,
    val coverSource: String?,
    val largeIconReady: Boolean,
    val mediaId: String? = null,
    val trackNumber: Long = 0L,
    val numTracks: Long = 0L,
    val artworkUri: String? = null,
)

internal fun resolveServicePlaybackState(snapshot: PlaybackServicePlaybackSnapshot): Int = when {
    snapshot.buffering -> PlaybackState.STATE_BUFFERING
    snapshot.enginePlaying -> PlaybackState.STATE_PLAYING
    !snapshot.playerSongPresent && snapshot.song != null && snapshot.roomPlaying ->
        PlaybackState.STATE_BUFFERING
    else -> PlaybackState.STATE_PAUSED
}

internal fun servicePlaybackPositionMs(snapshot: PlaybackServicePlaybackSnapshot): Long =
    if (!snapshot.playerSongPresent && snapshot.song != null) snapshot.roomPositionMs
    else snapshot.playerPositionMs

internal fun servicePlaybackSpeed(state: Int, snapshot: PlaybackServicePlaybackSnapshot): Float =
    if (state == PlaybackState.STATE_PLAYING) snapshot.playbackSpeed else 0.0f

internal fun servicePlaybackControlPlaying(snapshot: PlaybackServicePlaybackSnapshot): Boolean =
    snapshot.enginePlaying || (snapshot.buffering && snapshot.playbackControlPlaying)

internal fun mediaSessionPlaybackActions(): Long =
    PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_SEEK_TO or
        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
        PlaybackState.ACTION_PLAY_FROM_SEARCH or
        PlaybackState.ACTION_PREPARE or
        PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID or
        PlaybackState.ACTION_PREPARE_FROM_SEARCH or
        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM

internal fun serviceFavoriteControlFingerprint(canToggleFavorite: Boolean, favorite: Boolean): Int = when {
    !canToggleFavorite -> 0
    favorite -> 2
    else -> 1
}

private data class PreparedServiceNotification(
    val snapshot: PlaybackNotificationSnapshot,
    val renderInputs: PlaybackServiceNotificationRenderInputs,
)

internal class PlaybackServicePresentationOwner(
    private val source: PlaybackServicePresentationSource,
    private val port: PlaybackServicePresentationPort,
    private val artwork: PlaybackArtworkOwner,
    private val scope: CoroutineScope,
) {
    private val playbackStateThrottler = MediaSessionPlaybackStateThrottler()
    private var lastNotificationSnapshot: PlaybackNotificationSnapshot? = null
    private var lastMetadataSnapshot: PlaybackMetadataSnapshot? = null
    private var lastWidgetState: PlaybackWidgetState? = null
    private var favoriteSongKeys: Set<String> = emptySet()
    private var bluetoothMode = BluetoothMetadataMode.SongAndLyrics
    private var metadataModeJob: Job? = null
    private var lastActiveQueueItemId = MediaSession.QueueItem.UNKNOWN_ID.toLong()
    private val carArtwork = CarArtworkPublicationOwner(scope, port) {
        invalidateMetadataSnapshot()
        updateMetadata()
    }

    fun initializeSession(callback: MediaSession.Callback) {
        port.initializeSession(callback)
        metadataModeJob?.cancel()
        metadataModeJob = scope.launch {
            source.bluetoothMetadataModes().collect { mode ->
                bluetoothMode = mode
                updateMetadata()
            }
        }
    }

    fun sessionOrNull(): MediaSession? = port.sessionOrNull()

    fun audioAttributes(): AudioAttributes = port.audioAttributes()

    fun releaseSessionAfterForegroundFailure(reason: String) = port.releaseSessionAfterForegroundFailure(reason)

    fun releaseSessionForDestroy() = port.releaseSessionForDestroy()

    fun buildBootstrapNotification(): Notification = port.buildBootstrapNotification()

    fun buildNotification(
        lyricState: StatusBarLyricNotificationState,
        floatingLyricsEnabled: Boolean,
    ): Notification = port.buildNotification(withShareUrl(prepareNotification(lyricState, floatingLyricsEnabled).renderInputs))

    fun dispatchMediaButtonIntent(intent: Intent?) = port.dispatchMediaButtonIntent(intent)

    fun invalidateMetadataSnapshot() {
        lastMetadataSnapshot = null
    }

    fun resetFavoriteSongKeys() {
        favoriteSongKeys = emptySet()
    }

    fun applyFloatingLyricsExternalAction(currentEnabled: Boolean, legacyHideAction: Boolean) {
        val target = resolveFloatingLyricsExternalTargetEnabled(currentEnabled, legacyHideAction)
        scope.launch { persistFloatingLyricsEnabled(target) }
    }

    private suspend fun persistFloatingLyricsEnabled(enabled: Boolean) {
        runCatching { source.setFloatingLyricsEnabled(enabled) }
            .onFailure { NPLogger.e("NERI-APS", "Failed to persist floating lyrics toggle from external surface", it) }
    }

    fun refreshFavoriteSongKeys(): Boolean {
        val previous = favoriteSongKeys
        val updated = source.favoriteSongKeys()
        favoriteSongKeys = updated
        return hasCurrentSongFavoriteStateChanged(
            currentSongKey = source.playback().song?.stableKey(),
            previousFavoriteSongKeys = previous,
            updatedFavoriteSongKeys = updated,
        )
    }

    fun canToggleFavorite(song: SongItem?): Boolean = shouldAllowExternalFavoriteToggle(
        localPlaylistsReady = source.localPlaylistsReady(),
        hasCurrentSong = song != null,
        requiresInteractiveConfirmation = requiresInteractiveFavoriteConfirmation(song),
    )

    fun updateNotification(
        force: Boolean,
        foregroundStarted: Boolean,
        lyricState: StatusBarLyricNotificationState,
        floatingLyricsEnabled: Boolean,
    ) {
        if (!foregroundStarted) return
        val prepared = prepareNotification(lyricState, floatingLyricsEnabled)
        updateWidget(force = false, floatingLyricsEnabled = floatingLyricsEnabled)
        if (!force && prepared.snapshot == lastNotificationSnapshot) return
        lastNotificationSnapshot = prepared.snapshot
        port.publishNotification(withShareUrl(prepared.renderInputs))
    }

    private fun withShareUrl(inputs: PlaybackServiceNotificationRenderInputs): PlaybackServiceNotificationRenderInputs =
        inputs.copy(shareUrl = inputs.song?.let(source::shareUrl))

    private fun prepareNotification(
        lyricState: StatusBarLyricNotificationState,
        floatingLyricsEnabled: Boolean,
    ): PreparedServiceNotification {
        val playback = source.playback()
        val song = playback.song
        val artworkSnapshot = artwork.snapshotFor(song)
        val playbackControlPlaying = servicePlaybackControlPlaying(playback)
        val text = notificationText(song)
        val favorite = isFavoriteSong(song)
        val interactiveFavorite = requiresInteractiveFavoriteConfirmation(song)
        val snapshot = serviceNotificationSnapshot(
            song = song,
            text = text,
            transportActive = playback.transportActive,
            playbackControlPlaying = playbackControlPlaying,
            audioRouteMuted = playback.audioRouteMuted,
            isFavorite = favorite,
            interactiveFavorite = interactiveFavorite,
            artwork = artworkSnapshot,
            lyricState = lyricState,
            floatingLyricsEnabled = floatingLyricsEnabled,
        )
        val renderInputs = PlaybackServiceNotificationRenderInputs(
            song = song,
            text = text,
            audioRouteMuted = playback.audioRouteMuted,
            playbackControlPlaying = playbackControlPlaying,
            favorite = favorite,
            interactiveFavorite = interactiveFavorite,
            floatingLyricsEnabled = floatingLyricsEnabled,
            lyricState = lyricState,
            artwork = artworkSnapshot.notificationBitmap,
            shareUrl = null,
        )
        return PreparedServiceNotification(snapshot, renderInputs)
    }

    fun updateWidget(force: Boolean, floatingLyricsEnabled: Boolean) {
        if (!port.hasInstalledWidgets()) return
        val playback = source.playback()
        val state = widgetState(playback, floatingLyricsEnabled)
        if (!shouldUpdateServicePlaybackWidget(force, lastWidgetState, state)) return
        lastWidgetState = state
        port.publishWidget(state, artwork.snapshotFor(playback.song).notificationBitmap)
    }

    fun updateWidgetProgress(floatingLyricsEnabled: Boolean) {
        if (!port.hasInstalledWidgets()) return
        val state = widgetState(source.playback(), floatingLyricsEnabled)
        if (playbackWidgetPresentationChanged(lastWidgetState, state)) {
            updateWidget(force = true, floatingLyricsEnabled = floatingLyricsEnabled)
            return
        }
        if (!shouldPartiallyUpdatePlaybackWidgetProgress(lastWidgetState, state)) return
        port.publishWidgetProgress(state)
    }

    fun updateMetadata() {
        val playback = source.playback()
        val song = playback.song
        val artworkSnapshot = artwork.observe(song)
        val metadataInputs = source.metadata()
        val text = serviceMetadataText(
            song = song,
            payload = metadataInputs.payload,
            audioDeviceType = metadataInputs.audioDeviceType,
            forceSendLyrics = metadataInputs.forceSendLyrics,
            mode = bluetoothMode,
            normalAlbum = metadataInputs.album,
        )
        val snapshot = serviceMetadataSnapshot(
            song, text, artworkSnapshot, playback.queue.size, playback.queueIndex,
            carArtwork.observe(song, artworkSnapshot),
            serviceMetadataMediaId(song, playback.queue, playback.queueIndex),
        )
        if (snapshot == lastMetadataSnapshot) return
        lastMetadataSnapshot = snapshot
        port.setMetadata(serviceMediaMetadata(snapshot, artworkSnapshot))
    }

    fun updatePlaybackState(force: Boolean, floatingLyricsEnabled: Boolean) {
        val playback = source.playback()
        val state = resolveServicePlaybackState(playback)
        val positionMs = servicePlaybackPositionMs(playback)
        val speed = servicePlaybackSpeed(state, playback)
        val song = playback.song
        val favorite = isFavoriteSong(song)
        val canToggleFavorite = canToggleFavorite(song)
        val fingerprint = buildMediaSessionControlFingerprint(
            favoriteControlFingerprint = serviceFavoriteControlFingerprint(canToggleFavorite, favorite),
            floatingLyricsEnabled = floatingLyricsEnabled,
        )
        val nowMs = port.elapsedRealtime()
        val activeQueueId = carQueueItemId(playback.queue, playback.queueIndex)
        if (activeQueueId == lastActiveQueueItemId &&
            !playbackStateThrottler.shouldDispatch(state, positionMs, speed, fingerprint, nowMs, force)) return
        port.setPlaybackState(buildPlaybackState(state, positionMs, speed, favorite, canToggleFavorite, floatingLyricsEnabled, activeQueueId))
        lastActiveQueueItemId = activeQueueId
        playbackStateThrottler.recordDispatch(state, positionMs, speed, fingerprint, nowMs)
    }

    private fun notificationText(song: SongItem?): String {
        val timer = source.timer()
        return serviceNotificationText(song, timer.state, timer.remaining, port::localizedString)
    }

    private fun isFavoriteSong(song: SongItem?): Boolean =
        song != null && song.stableKey() in favoriteSongKeys

    private fun requiresInteractiveFavoriteConfirmation(song: SongItem?): Boolean =
        shouldUseInteractiveFavoriteIntent(
            localPlaylistsReady = source.localPlaylistsReady(),
            hasCurrentSong = song != null,
            isFavorite = isFavoriteSong(song),
            isLocalSong = song?.let(source::isLocalSong) == true,
        )

    private fun widgetState(
        playback: PlaybackServicePlaybackSnapshot,
        floatingLyricsEnabled: Boolean,
    ): PlaybackWidgetState {
        val song = playback.song
        return servicePlaybackWidgetState(ServiceWidgetInputs(
            song = song,
            playerSongPresent = playback.playerSongPresent,
            playerPositionMs = playback.playerPositionMs,
            roomPositionMs = playback.roomPositionMs,
            buffering = playback.buffering,
            enginePlaying = playback.enginePlaying,
            playbackControlPlaying = playback.playbackControlPlaying,
            roomPlaying = playback.roomPlaying,
            favorite = isFavoriteSong(song),
            canToggleFavorite = canToggleFavorite(song),
            floatingLyricsEnabled = floatingLyricsEnabled,
            artwork = artwork.snapshotFor(song),
            labels = port.widgetLabels(),
        ))
    }

    private fun buildPlaybackState(
        state: Int,
        positionMs: Long,
        speed: Float,
        favorite: Boolean,
        canToggleFavorite: Boolean,
        floatingLyricsEnabled: Boolean,
        activeQueueItemId: Long,
    ): PlaybackState {
        val builder = PlaybackState.Builder()
            .setActions(mediaSessionPlaybackActions())
            .setState(state, positionMs, speed)
            .setActiveQueueItemId(activeQueueItemId)
        if (canToggleFavorite) builder.addCustomAction(favoriteCustomAction(favorite))
        builder.addCustomAction(floatingLyricsCustomAction(floatingLyricsEnabled))
        builder.addCustomAction(CAR_ACTION_TOGGLE_SHUFFLE, port.localizedString(CoreCommonR.string.car_action_shuffle, null), CoreCommonR.drawable.round_shuffle_24)
        builder.addCustomAction(CAR_ACTION_CYCLE_REPEAT, port.localizedString(CoreCommonR.string.car_action_repeat, null), CoreCommonR.drawable.round_repeat_24)
        return builder.build()
    }

    private fun favoriteCustomAction(favorite: Boolean): PlaybackState.CustomAction =
        PlaybackState.CustomAction.Builder(
            AudioPlayerService.ACTION_TOGGLE_FAV,
            port.localizedString(favoriteActionTitle(favorite), null),
            favoriteActionIcon(favorite),
        ).build()

    private fun floatingLyricsCustomAction(enabled: Boolean): PlaybackState.CustomAction =
        PlaybackState.CustomAction.Builder(
            AudioPlayerService.ACTION_TOGGLE_FLOATING_LYRICS,
            port.localizedString(floatingLyricsActionTitle(enabled), null),
            floatingLyricsActionIcon(enabled),
        ).build()
}
