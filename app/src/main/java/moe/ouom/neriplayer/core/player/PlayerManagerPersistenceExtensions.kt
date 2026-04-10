@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.Player
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.AudioDownloadManager
import moe.ouom.neriplayer.core.player.metadata.applyManualSearchMetadata
import moe.ouom.neriplayer.core.player.metadata.normalizeCustomMetadataValue
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.model.PersistedPlaybackState
import moe.ouom.neriplayer.core.player.model.PersistedState
import moe.ouom.neriplayer.core.player.model.toPersistedSongItem
import moe.ouom.neriplayer.core.player.model.toPlaybackState
import moe.ouom.neriplayer.core.player.model.withPlaybackState
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.core.player.policy.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.source.toSongItem
import moe.ouom.neriplayer.core.player.state.blockingIo
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.ui.component.LyricEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import moe.ouom.neriplayer.data.model.sameIdentityAs
import java.io.File
import java.lang.reflect.Type

internal fun PlayerManager.hasItemsImpl(): Boolean = currentPlaylist.isNotEmpty()

internal data class RestoredPlayerStateSnapshot(
    val playlist: List<SongItem>,
    val currentIndex: Int,
    val currentMediaUrl: String?,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val resumePositionMs: Long,
    val shouldResumePlayback: Boolean,
    val persistedPlaybackState: PersistedPlaybackState,
    val originalPlaylistSize: Int,
    val persistedIndex: Int
)

private fun PlayerManager.buildPersistedPlaybackState(
    currentIndexSnapshot: Int,
    mediaUrlSnapshot: String?,
    positionMs: Long,
    shouldResumePlayback: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean
): PersistedPlaybackState {
    return PersistedPlaybackState(
        index = currentIndexSnapshot,
        mediaUrl = mediaUrlSnapshot,
        positionMs = positionMs,
        shouldResumePlayback = shouldResumePlayback,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled
    )
}

private fun PlayerManager.buildPersistedPlaylistState(
    playlistReference: List<SongItem>,
    playbackStateSnapshot: PersistedPlaybackState
): PersistedState {
    return PersistedState(
        playlist = playlistReference.mapIndexed { index, song ->
            // 只给当前歌曲保留内嵌歌词，避免大队列切歌时反复序列化整批长文本
            song.toPersistedSongItem(
                includeLyrics = shouldPersistEmbeddedLyrics(song) && index == playbackStateSnapshot.index
            )
        },
        index = playbackStateSnapshot.index,
        mediaUrl = playbackStateSnapshot.mediaUrl,
        positionMs = playbackStateSnapshot.positionMs,
        shouldResumePlayback = playbackStateSnapshot.shouldResumePlayback,
        repeatMode = playbackStateSnapshot.repeatMode,
        shuffleEnabled = playbackStateSnapshot.shuffleEnabled
    )
}

private fun PlayerManager.writeJson(file: File, payload: Any) {
    file.outputStream().bufferedWriter().use { writer ->
        gson.toJson(payload, writer)
    }
}

private fun <T> PlayerManager.readJson(file: File, type: Type): T {
    file.inputStream().bufferedReader().use { reader ->
        return gson.fromJson(reader, type)
    }
}

private fun loadRestoredStateSnapshot(
    app: Application,
    stateFile: File,
    playbackStateFile: File,
    keepLastPlaybackProgressEnabled: Boolean,
    keepPlaybackModeStateEnabled: Boolean
): RestoredPlayerStateSnapshot? {
    if (!stateFile.exists()) {
        NPLogger.d("NERI-PlayerManager", "restoreState: skipped because state file does not exist")
        return null
    }

    return runCatching {
        NPLogger.d(
            "NERI-PlayerManager",
            "restoreState: reading ${stateFile.absolutePath}"
        )
        val type = object : TypeToken<PersistedState>() {}.type
        val legacyData: PersistedState = PlayerManager.readJson(stateFile, type)
        val playbackState = playbackStateFile.takeIf(File::exists)?.runCatching {
            PlayerManager.readJson<PersistedPlaybackState>(
                this,
                PersistedPlaybackState::class.java
            )
        }?.getOrNull()
        val data = playbackState?.let(legacyData::withPlaybackState) ?: legacyData
        val playlist = data.playlist.map { persistedSong -> persistedSong.toSongItem() }
            .filter { song ->
                !LocalSongSupport.isLocalSong(song, app) || PlayerManager.isRestorableLocalSong(song)
            }
        val preferredSong = data.playlist.getOrNull(data.index)?.toSongItem()
        val currentIndex = when {
            playlist.isEmpty() -> -1
            preferredSong != null -> {
                playlist.indexOfFirst { it.sameIdentityAs(preferredSong) }
                    .takeIf { it >= 0 }
                    ?: data.index.coerceIn(0, playlist.lastIndex)
            }
            data.index in playlist.indices -> data.index
            else -> 0
        }
        val currentSong = playlist.getOrNull(currentIndex)
        val currentMediaUrl = if (currentSong == null) {
            null
        } else {
            data.mediaUrl?.takeIf {
                val isPersistedLocalMediaUrl =
                    it.startsWith("file://") ||
                        it.startsWith("content://") ||
                        it.startsWith("android.resource://") ||
                        it.startsWith("/")
                val isSongRestorable =
                    !LocalSongSupport.isLocalSong(currentSong, app) ||
                        PlayerManager.isRestorableLocalSong(currentSong)
                isSongRestorable &&
                    (!isPersistedLocalMediaUrl || PlayerManager.isReadableLocalMediaUri(it))
            }
        }
        val repeatMode = if (keepPlaybackModeStateEnabled) {
            when (data.repeatMode) {
                Player.REPEAT_MODE_ALL,
                Player.REPEAT_MODE_ONE,
                Player.REPEAT_MODE_OFF -> data.repeatMode
                else -> Player.REPEAT_MODE_OFF
            }
        } else {
            Player.REPEAT_MODE_OFF
        }

        RestoredPlayerStateSnapshot(
            playlist = playlist,
            currentIndex = currentIndex,
            currentMediaUrl = currentMediaUrl,
            repeatMode = repeatMode,
            shuffleEnabled = keepPlaybackModeStateEnabled && (data.shuffleEnabled == true),
            resumePositionMs = if (keepLastPlaybackProgressEnabled) {
                data.positionMs.coerceAtLeast(0L)
            } else {
                0L
            },
            shouldResumePlayback = data.shouldResumePlayback && currentIndex != -1,
            persistedPlaybackState = data.toPlaybackState(),
            originalPlaylistSize = data.playlist.size,
            persistedIndex = data.index
        )
    }.onFailure { error ->
        NPLogger.w("NERI-PlayerManager", "Failed to restore state: ${error.message}")
    }.getOrNull()
}

internal suspend fun PlayerManager.preloadRestoredStateSnapshot(
    app: Application,
    keepLastPlaybackProgressEnabled: Boolean,
    keepPlaybackModeStateEnabled: Boolean
): RestoredPlayerStateSnapshot? {
    val startupStateFile = File(app.filesDir, "last_playlist.json")
    val startupPlaybackStateFile = File(app.filesDir, "last_playback_state.json")
    return withContext(Dispatchers.IO) {
        loadRestoredStateSnapshot(
            app = app,
            stateFile = startupStateFile,
            playbackStateFile = startupPlaybackStateFile,
            keepLastPlaybackProgressEnabled = keepLastPlaybackProgressEnabled,
            keepPlaybackModeStateEnabled = keepPlaybackModeStateEnabled
        )
    }
}

internal fun PlayerManager.applyRestoredStateSnapshot(snapshot: RestoredPlayerStateSnapshot) {
    currentPlaylist = snapshot.playlist
    if (currentPlaylist.isEmpty()) {
        NPLogger.w(
            "NERI-PlayerManager",
            "restoreState: sanitized playlist became empty, originalSize=${snapshot.originalPlaylistSize}, persistedIndex=${snapshot.persistedIndex}"
        )
        currentIndex = -1
        _currentQueueFlow.value = emptyList()
        _currentSongFlow.value = null
        _currentMediaUrl.value = null
        _currentPlaybackAudioInfo.value = null
        _playbackPositionMs.value = 0L
        currentMediaUrlResolvedAtMs = 0L
        restoredResumePositionMs = 0L
        restoredShouldResumePlayback = false
        updateResumePlaybackRequested(false)
        return
    }

    currentIndex = snapshot.currentIndex
    _currentQueueFlow.value = currentPlaylist
    _currentSongFlow.value = currentPlaylist.getOrNull(currentIndex)
    _currentMediaUrl.value = snapshot.currentMediaUrl
    repeatModeSetting = snapshot.repeatMode
    syncExoRepeatMode()
    _repeatModeFlow.value = repeatModeSetting

    player.shuffleModeEnabled = snapshot.shuffleEnabled
    _shuffleModeFlow.value = snapshot.shuffleEnabled
    shuffleHistory.clear()
    shuffleFuture.clear()
    if (snapshot.shuffleEnabled) {
        rebuildShuffleBag(excludeIndex = currentIndex)
    } else {
        shuffleBag.clear()
    }

    restoredResumePositionMs = snapshot.resumePositionMs
    restoredShouldResumePlayback = snapshot.shouldResumePlayback
    updateResumePlaybackRequested(false)
    _playbackPositionMs.value = restoredResumePositionMs
    currentMediaUrlResolvedAtMs = 0L
    lastPersistedPlaylistReference = currentPlaylist
    lastPersistedPlaybackState = snapshot.persistedPlaybackState
    lastStatePersistAtMs = SystemClock.elapsedRealtime()
    NPLogger.d(
        "NERI-PlayerManager",
        "restoreState completed: queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, restoredResumePositionMs=$restoredResumePositionMs, restoredShouldResumePlayback=$restoredShouldResumePlayback, shuffle=${_shuffleModeFlow.value}, repeatMode=$repeatModeSetting, currentSong=${_currentSongFlow.value?.name}, mediaUrlPresent=${!_currentMediaUrl.value.isNullOrBlank()}"
    )
}

internal fun PlayerManager.scheduleStatePersist(
    positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
    shouldResumePlayback: Boolean = currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot(),
    debounceMs: Long = STATE_PERSIST_DEBOUNCE_MS
) {
    scheduledStatePersistJob?.cancel()
    scheduledStatePersistJob = ioScope.launch {
        if (debounceMs > 0L) {
            delay(debounceMs)
        }
        persistState(
            positionMs = positionMs,
            shouldResumePlayback = shouldResumePlayback
        )
    }
}

private fun PlayerManager.updateCurrentFavorite(song: SongItem, add: Boolean) {
    NPLogger.d(
        "NERI-PlayerManager",
        "updateCurrentFavorite(): action=${if (add) "add" else "remove"}, song=${song.name}/${song.id}, playlists=${_playlistsFlow.value.size}, stack=[${debugStackHint()}]"
    )
    val updatedLists = PlayerFavoritesController.optimisticUpdateFavorites(
        playlists = _playlistsFlow.value,
        add = add,
        song = song,
        application = application,
        favoritePlaylistName = getLocalizedString(R.string.favorite_my_music)
    )
    _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(updatedLists)

    ioScope.launch {
        try {
            if (add) {
                localRepo.addToFavorites(song)
            } else {
                localRepo.removeFromFavorites(song)
            }
        } catch (error: Exception) {
            val action = if (add) "addToFavorites" else "removeFromFavorites"
            NPLogger.e("NERI-PlayerManager", "$action failed: ${error.message}", error)
        }
    }
}

internal fun PlayerManager.addCurrentToFavoritesImpl() {
    ensureInitialized()
    if (!initialized) return
    val song = _currentSongFlow.value ?: return
    updateCurrentFavorite(song = song, add = true)
}

internal fun PlayerManager.removeCurrentFromFavoritesImpl() {
    ensureInitialized()
    if (!initialized) return
    val song = _currentSongFlow.value ?: return
    updateCurrentFavorite(song = song, add = false)
}

internal fun PlayerManager.toggleCurrentFavoriteImpl() {
    ensureInitialized()
    if (!initialized) return
    val song = _currentSongFlow.value ?: return
    val currentlyFavorite = PlayerFavoritesController.isFavorite(_playlistsFlow.value, song, application)
    NPLogger.d(
        "NERI-PlayerManager",
        "toggleCurrentFavorite(): song=${song.name}/${song.id}, currentlyFavorite=$currentlyFavorite, stack=[${debugStackHint()}]"
    )
    if (currentlyFavorite) {
        updateCurrentFavorite(song = song, add = false)
    } else {
        updateCurrentFavorite(song = song, add = true)
    }
}

internal suspend fun PlayerManager.persistStateImpl(
    positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
    shouldResumePlayback: Boolean = currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot()
) {
    scheduledStatePersistJob = null
    val playlistReference = currentPlaylist
    val currentIndexSnapshot = currentIndex
    val mediaUrlSnapshot = _currentMediaUrl.value
    val persistedShouldResumePlayback =
        shouldResumePlayback && !suppressAutoResumeForCurrentSession
    val persistedPositionMs = if (keepLastPlaybackProgressEnabled) {
        positionMs.coerceAtLeast(0L)
    } else {
        0L
    }
    val persistedRepeatMode = if (keepPlaybackModeStateEnabled) {
        repeatModeSetting
    } else {
        Player.REPEAT_MODE_OFF
    }
    val persistedShuffleEnabled = keepPlaybackModeStateEnabled && _shuffleModeFlow.value
    val playbackStateSnapshot = buildPersistedPlaybackState(
        currentIndexSnapshot = currentIndexSnapshot,
        mediaUrlSnapshot = mediaUrlSnapshot,
        positionMs = persistedPositionMs,
        shouldResumePlayback = persistedShouldResumePlayback,
        repeatMode = persistedRepeatMode,
        shuffleEnabled = persistedShuffleEnabled
    )
    NPLogger.d(
        "NERI-PlayerManager",
        "persistState: queueSize=${playlistReference.size}, index=$currentIndexSnapshot, positionMs=$persistedPositionMs, shouldResume=$persistedShouldResumePlayback, repeatMode=$persistedRepeatMode, shuffle=$persistedShuffleEnabled, mediaUrlPresent=${!mediaUrlSnapshot.isNullOrBlank()}"
    )

    withContext(Dispatchers.IO) {
        statePersistMutex.withLock {
            try {
                if (playlistReference.isEmpty()) {
                    restoredResumePositionMs = 0L
                    restoredShouldResumePlayback = false
                    stateFile.delete()
                    playbackStateFile.delete()
                    lastPersistedPlaylistReference = null
                    lastPersistedPlaybackState = null
                    lastStatePersistAtMs = SystemClock.elapsedRealtime()
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "persistState: cleared persisted playback state because queue is empty"
                    )
                    return@withLock
                }

                val shouldWriteLegacyState = playlistReference !== lastPersistedPlaylistReference
                val shouldWritePlaybackState =
                    shouldWriteLegacyState ||
                        playbackStateSnapshot != lastPersistedPlaybackState ||
                        !playbackStateFile.exists()

                if (shouldWriteLegacyState) {
                    val data = buildPersistedPlaylistState(
                        playlistReference = playlistReference,
                        playbackStateSnapshot = playbackStateSnapshot
                    )
                    writeJson(stateFile, data)
                    lastPersistedPlaylistReference = playlistReference
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "persistState: wrote state file, path=${stateFile.absolutePath}, queueSize=${playlistReference.size}, index=$currentIndexSnapshot"
                    )
                }

                if (shouldWritePlaybackState) {
                    writeJson(playbackStateFile, playbackStateSnapshot)
                    lastPersistedPlaybackState = playbackStateSnapshot
                }

                if (shouldWriteLegacyState || shouldWritePlaybackState) {
                    lastStatePersistAtMs = SystemClock.elapsedRealtime()
                }
            } catch (e: Exception) {
                NPLogger.e("PlayerManager", "Failed to persist state", e)
            }
        }
    }
}

internal fun PlayerManager.addCurrentToPlaylistImpl(playlistId: Long) {
    ensureInitialized()
    if (!initialized) return
    val song = _currentSongFlow.value ?: return
    NPLogger.d(
        "NERI-PlayerManager",
        "addCurrentToPlaylist(): playlistId=$playlistId, song=${song.name}/${song.id}, stack=[${debugStackHint()}]"
    )
    ioScope.launch {
        try {
            localRepo.addSongToPlaylist(playlistId, song)
            NPLogger.d(
                "NERI-PlayerManager",
                "addCurrentToPlaylist(): completed, playlistId=$playlistId, song=${song.name}/${song.id}"
            )
        } catch (e: Exception) {
            NPLogger.e("NERI-PlayerManager", "addCurrentToPlaylist failed: ${e.message}", e)
        }
    }
}

internal fun PlayerManager.playBiliVideoAsAudioImpl(videos: List<BiliVideoItem>, startIndex: Int) {
    ensureInitialized()
    check(initialized) { "Call PlayerManager.initialize(application) first." }
    if (videos.isEmpty()) {
        NPLogger.w("NERI-Player", "playBiliVideoAsAudio called with EMPTY list")
        return
    }
    val songs = videos.map { it.toSongItem() }
    playPlaylist(songs, startIndex)
}

internal suspend fun PlayerManager.getNeteaseLyricsImpl(songId: Long): List<LyricEntry> {
    return PlayerLyricsProvider.getNeteaseLyrics(songId, neteaseClient)
}

internal suspend fun PlayerManager.getNeteaseTranslatedLyricsImpl(songId: Long): List<LyricEntry> {
    return PlayerLyricsProvider.getNeteaseTranslatedLyrics(songId, neteaseClient)
}

internal suspend fun PlayerManager.getTranslatedLyricsImpl(song: SongItem): List<LyricEntry> {
    return PlayerLyricsProvider.getTranslatedLyrics(
        song = song,
        application = application,
        neteaseClient = neteaseClient,
        biliSourceTag = BILI_SOURCE_TAG
    )
}

internal suspend fun PlayerManager.getLyricsImpl(song: SongItem): List<LyricEntry> {
    return PlayerLyricsProvider.getLyrics(
        song = song,
        application = application,
        neteaseClient = neteaseClient,
        youtubeMusicClient = youtubeMusicClient,
        lrcLibClient = lrcLibClient,
        ytMusicLyricsCache = ytMusicLyricsCache,
        biliSourceTag = BILI_SOURCE_TAG
    )
}

internal fun PlayerManager.playFromQueueImpl(
    index: Int,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!initialized) return
    if (currentPlaylist.isEmpty()) return
    if (index !in currentPlaylist.indices) return
    val targetSong = currentPlaylist[index]
    NPLogger.d(
        "NERI-PlayerManager",
        "playFromQueue(): index=$index, source=$commandSource, currentIndex=$currentIndex, queueSize=${currentPlaylist.size}, target=${targetSong.name}/${targetSong.id}, stack=[${debugStackHint()}]"
    )
    if (shouldBlockLocalRoomControl(commandSource) ||
        shouldBlockLocalSongSwitch(targetSong, commandSource)
    ) {
        return
    }

    if (player.shuffleModeEnabled) {
        if (currentIndex != -1) shuffleHistory.add(currentIndex)
        shuffleFuture.clear()
        shuffleBag.remove(index)
    }

    currentIndex = index
    playAtIndex(index, commandSource = commandSource)
    emitPlaybackCommand(
        type = "PLAY_FROM_QUEUE",
        source = commandSource,
        currentIndex = currentIndex
    )
}

internal fun PlayerManager.addToQueueNextImpl(song: SongItem) {
    ensureInitialized()
    if (!initialized) return

    if (currentPlaylist.isEmpty()) {
        NPLogger.d(
            "NERI-PlayerManager",
            "addToQueueNext(): queue empty, fallback to playPlaylist, song=${song.name}/${song.id}"
        )
        playPlaylist(listOf(song), 0)
        return
    }

    val currentSong = _currentSongFlow.value
    val newPlaylist = currentPlaylist.toMutableList()
    var insertIndex = (currentIndex + 1).coerceIn(0, newPlaylist.size + 1)

    val existingIndex = newPlaylist.indexOfFirst { it.sameIdentityAs(song) }
    if (existingIndex != -1) {
        if (existingIndex < insertIndex) {
            insertIndex--
        }
        newPlaylist.removeAt(existingIndex)
    }

    insertIndex = insertIndex.coerceIn(0, newPlaylist.size)
    newPlaylist.add(insertIndex, song)

    currentPlaylist = newPlaylist
    _currentQueueFlow.value = currentPlaylist
    currentIndex = if (currentSong != null) {
        queueIndexOf(currentSong, newPlaylist).takeIf { it >= 0 }
            ?: currentIndex.coerceIn(0, newPlaylist.lastIndex)
    } else {
        currentIndex.coerceIn(0, newPlaylist.lastIndex)
    }
    if (player.shuffleModeEnabled) {
        val newSongRealIndex = queueIndexOf(song, newPlaylist)

        if (newSongRealIndex != -1) {
            shuffleBag.remove(newSongRealIndex)
            shuffleFuture.add(newSongRealIndex)
        }
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "addToQueueNext(): song=${song.name}/${song.id}, existingIndex=$existingIndex, insertIndex=$insertIndex, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )

    ioScope.launch {
        persistState()
    }
}

internal fun PlayerManager.addToQueueEndImpl(song: SongItem) {
    ensureInitialized()
    if (!initialized) return
    if (currentPlaylist.isEmpty()) {
        NPLogger.d(
            "NERI-PlayerManager",
            "addToQueueEnd(): queue empty, fallback to playPlaylist, song=${song.name}/${song.id}"
        )
        playPlaylist(listOf(song), 0)
        return
    }

    val currentSong = _currentSongFlow.value
    val newPlaylist = currentPlaylist.toMutableList()

    val existingIndex = newPlaylist.indexOfFirst { it.sameIdentityAs(song) }
    if (existingIndex != -1) {
        newPlaylist.removeAt(existingIndex)
    }

    newPlaylist.add(song)

    currentPlaylist = newPlaylist
    _currentQueueFlow.value = currentPlaylist
    currentIndex = if (currentSong != null) {
        queueIndexOf(currentSong, newPlaylist).takeIf { it >= 0 }
            ?: currentIndex.coerceIn(0, newPlaylist.lastIndex)
    } else {
        currentIndex.coerceIn(0, newPlaylist.lastIndex)
    }

    if (player.shuffleModeEnabled) {
        rebuildShuffleBag()
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "addToQueueEnd(): song=${song.name}/${song.id}, existingIndex=$existingIndex, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )

    ioScope.launch {
        persistState()
    }
}

internal fun PlayerManager.restoreState() {
    val snapshot = loadRestoredStateSnapshot(
        app = application,
        stateFile = stateFile,
        playbackStateFile = playbackStateFile,
        keepLastPlaybackProgressEnabled = keepLastPlaybackProgressEnabled,
        keepPlaybackModeStateEnabled = keepPlaybackModeStateEnabled
    ) ?: return
    applyRestoredStateSnapshot(snapshot)
}

internal fun PlayerManager.resumeRestoredPlaybackIfNeededImpl(): Long? {
    ensureInitialized()
    if (!initialized) {
        NPLogger.d("NERI-PlayerManager", "resumeRestoredPlaybackIfNeeded(): skipped, manager not initialized")
        return null
    }
    if (!restoredShouldResumePlayback) {
        NPLogger.d("NERI-PlayerManager", "resumeRestoredPlaybackIfNeeded(): skipped, restoredShouldResumePlayback=false")
        return null
    }
    if (currentPlaylist.isEmpty() || currentIndex !in currentPlaylist.indices) {
        NPLogger.w(
            "NERI-PlayerManager",
            "resumeRestoredPlaybackIfNeeded(): skipped, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex"
        )
        return null
    }
    val resumeIndex = currentIndex
    val resumePositionMs = restoredResumePositionMs.coerceAtLeast(0L)
    NPLogger.d(
        "NERI-PlayerManager",
        "resumeRestoredPlaybackIfNeeded(): resumeIndex=$resumeIndex, positionMs=$resumePositionMs, song=${currentPlaylist.getOrNull(resumeIndex)?.name}, stack=[${debugStackHint()}]"
    )
    restoredShouldResumePlayback = false
    restoredResumePositionMs = 0L
    lastStatePersistAtMs = SystemClock.elapsedRealtime()
    playAtIndex(
        resumeIndex,
        resumePositionMs = resumePositionMs,
        forceStartupProtectionFade = true
    )
    return resumePositionMs
}

internal fun PlayerManager.suppressFutureAutoResumeForCurrentSessionImpl(
    forcePersist: Boolean = false
) {
    ensureInitialized()
    if (!initialized || currentPlaylist.isEmpty()) return
    suppressAutoResumeForCurrentSession = true
    restoredShouldResumePlayback = false
    val positionMs = if (isPlayerInitialized()) {
        player.currentPosition.coerceAtLeast(0L)
    } else {
        _playbackPositionMs.value.coerceAtLeast(0L)
    }
    _playbackPositionMs.value = positionMs
    NPLogger.d(
        "NERI-PlayerManager",
        "suppressFutureAutoResumeForCurrentSession(): forcePersist=$forcePersist, positionMs=$positionMs, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, currentSong=${_currentSongFlow.value?.name}, stack=[${debugStackHint()}]"
    )
    if (forcePersist) {
        blockingIo {
            persistState(positionMs = positionMs, shouldResumePlayback = false)
        }
    } else {
        ioScope.launch {
            persistState(positionMs = positionMs, shouldResumePlayback = false)
        }
    }
}

internal fun PlayerManager.replaceMetadataFromSearchImpl(
    originalSong: SongItem,
    selectedSong: SongSearchInfo,
    isAuto: Boolean = false
) {
    ioScope.launch {
        NPLogger.d(
            "NERI-PlayerManager",
            "replaceMetadataFromSearch: originalSong=${originalSong.name}, selectedId=${selectedSong.id}, source=${selectedSong.source}, isAuto=$isAuto, stack=[${debugStackHint()}]"
        )
        val platform = selectedSong.source

        val api = when (platform) {
            MusicPlatform.CLOUD_MUSIC -> cloudMusicSearchApi
            MusicPlatform.QQ_MUSIC -> qqMusicSearchApi
        }

        try {
            val newDetails = api.getSongInfo(selectedSong.id)

            val updatedSong = if (isAuto) {
                originalSong.copy(
                    matchedLyric = newDetails.lyric ?: originalSong.matchedLyric,
                    matchedTranslatedLyric = newDetails.translatedLyric ?: originalSong.matchedTranslatedLyric,
                    matchedLyricSource = selectedSong.source,
                    matchedSongId = selectedSong.id
                )
            } else {
                applyManualSearchMetadata(
                    originalSong = originalSong,
                    songName = newDetails.songName,
                    singer = newDetails.singer,
                    coverUrl = newDetails.coverUrl,
                    lyric = newDetails.lyric,
                    translatedLyric = newDetails.translatedLyric,
                    matchedSource = selectedSong.source,
                    matchedSongId = selectedSong.id,
                    useCustomOverride = shouldApplySearchMetadataAsCustomOverride(originalSong)
                )
            }

            updateSongInAllPlaces(originalSong, updatedSong)
        } catch (e: Exception) {
            mainScope.launch {
                Toast.makeText(
                    application,
                    getLocalizedString(R.string.toast_match_failed, e.message.orEmpty()),
                    Toast.LENGTH_SHORT
                ).show()
                NPLogger.e("NERI-PlayerManager", "replaceMetadataFromSearch failed: ${e.message}", e)
            }
        }
    }
}

private fun PlayerManager.shouldApplySearchMetadataAsCustomOverride(song: SongItem): Boolean {
    return isLocalSong(song) || AudioDownloadManager.getLocalPlaybackUri(application, song) != null
}

internal fun PlayerManager.updateSongCustomInfoImpl(
    originalSong: SongItem,
    customCoverUrl: String?,
    customName: String?,
    customArtist: String?
) {
    ioScope.launch {
        NPLogger.d(
            "PlayerManager",
            "updateSongCustomInfo: id=${originalSong.id}, album='${originalSong.album}', customName=${customName?.take(32)}, customArtist=${customArtist?.take(32)}, customCoverUrl=${customCoverUrl?.take(64)}, stack=[${debugStackHint()}]"
        )

        val currentSong = currentPlaylist.firstOrNull { it.sameIdentityAs(originalSong) }
            ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(originalSong) }
            ?: originalSong

        val baseName = currentSong.name
        val baseArtist = currentSong.artist
        val baseCoverUrl = currentSong.coverUrl
        val originalName = currentSong.originalName ?: baseName
        val originalArtist = currentSong.originalArtist ?: baseArtist
        val originalCoverUrl = currentSong.originalCoverUrl ?: baseCoverUrl

        val normalizedCustomName = normalizeCustomMetadataValue(
            desiredValue = customName,
            baseValue = baseName
        )
        val normalizedCustomArtist = normalizeCustomMetadataValue(
            desiredValue = customArtist,
            baseValue = baseArtist
        )
        val normalizedCustomCoverUrl = normalizeCustomMetadataValue(
            desiredValue = customCoverUrl,
            baseValue = baseCoverUrl
        )

        val updatedSong = currentSong.copy(
            customName = normalizedCustomName,
            customArtist = normalizedCustomArtist,
            customCoverUrl = normalizedCustomCoverUrl,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl
        )

        updateSongInAllPlaces(originalSong, updatedSong)
    }
}

internal fun PlayerManager.hydrateSongMetadataImpl(originalSong: SongItem, updatedSong: SongItem) {
    ioScope.launch {
        NPLogger.d(
            "NERI-PlayerManager",
            "hydrateSongMetadata: original=${originalSong.name}/${originalSong.id}, updated=${updatedSong.name}/${updatedSong.id}, stack=[${debugStackHint()}]"
        )
        updateSongInAllPlaces(originalSong, updatedSong)
    }
}

internal suspend fun PlayerManager.updateUserLyricOffsetImpl(
    songToUpdate: SongItem,
    newOffset: Long
) {
    NPLogger.d(
        "NERI-PlayerManager",
        "updateUserLyricOffset: song=${songToUpdate.name}, id=${songToUpdate.id}, newOffset=$newOffset"
    )
    val queueIndex = queueIndexOf(songToUpdate)
    if (queueIndex != -1) {
        val updatedSong = currentPlaylist[queueIndex].copy(userLyricOffsetMs = newOffset)
        val newList = currentPlaylist.toMutableList()
        newList[queueIndex] = updatedSong
        currentPlaylist = newList
        _currentQueueFlow.value = currentPlaylist
    }

    if (isCurrentSong(songToUpdate)) {
        _currentSongFlow.value = _currentSongFlow.value?.copy(userLyricOffsetMs = newOffset)
    }

    val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(songToUpdate) }
    if (latestSong != null) {
        withContext(Dispatchers.IO) {
            localRepo.updateSongMetadata(songToUpdate, latestSong)
        }
    }

    persistState()
}

internal suspend fun PlayerManager.updateSongLyricsImpl(
    songToUpdate: SongItem,
    newLyrics: String?
) {
    NPLogger.d(
        "NERI-PlayerManager",
        "updateSongLyrics: song=${songToUpdate.name}, id=${songToUpdate.id}, lyricLength=${newLyrics?.length ?: 0}"
    )
    val queueIndex = queueIndexOf(songToUpdate)
    if (queueIndex != -1) {
        val updatedSong = currentPlaylist[queueIndex].copy(matchedLyric = newLyrics)
        val newList = currentPlaylist.toMutableList()
        newList[queueIndex] = updatedSong
        currentPlaylist = newList
        _currentQueueFlow.value = currentPlaylist
    }

    if (isCurrentSong(songToUpdate)) {
        _currentSongFlow.value = _currentSongFlow.value?.copy(matchedLyric = newLyrics)
    }

    val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
    if (latestSong != null) {
        withContext(Dispatchers.IO) {
            localRepo.updateSongMetadata(songToUpdate, latestSong)
        }
        GlobalDownloadManager.syncDownloadedSongMetadata(latestSong)
        AppContainer.playHistoryRepo.updateSongMetadata(songToUpdate, latestSong)
        AppContainer.playlistUsageRepo.syncLocalEntries(localRepo.playlists.value)
    }

    persistState()
}

internal suspend fun PlayerManager.updateSongTranslatedLyricsImpl(
    songToUpdate: SongItem,
    newTranslatedLyrics: String?
) {
    NPLogger.d(
        "NERI-PlayerManager",
        "updateSongTranslatedLyrics: song=${songToUpdate.name}, id=${songToUpdate.id}, translatedLength=${newTranslatedLyrics?.length ?: 0}"
    )
    val queueIndex = queueIndexOf(songToUpdate)
    if (queueIndex != -1) {
        val updatedSong = currentPlaylist[queueIndex].copy(
            matchedTranslatedLyric = newTranslatedLyrics
        )
        val newList = currentPlaylist.toMutableList()
        newList[queueIndex] = updatedSong
        currentPlaylist = newList
        _currentQueueFlow.value = currentPlaylist
    }

    if (isCurrentSong(songToUpdate)) {
        _currentSongFlow.value =
            _currentSongFlow.value?.copy(matchedTranslatedLyric = newTranslatedLyrics)
    }

    val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
    if (latestSong != null) {
        withContext(Dispatchers.IO) {
            localRepo.updateSongMetadata(songToUpdate, latestSong)
        }
        GlobalDownloadManager.syncDownloadedSongMetadata(latestSong)
        AppContainer.playHistoryRepo.updateSongMetadata(songToUpdate, latestSong)
        AppContainer.playlistUsageRepo.syncLocalEntries(localRepo.playlists.value)
    }

    persistState()
}

internal suspend fun PlayerManager.updateSongLyricsAndTranslationImpl(
    songToUpdate: SongItem,
    newLyrics: String?,
    newTranslatedLyrics: String?
) {
    val queueIndex = queueIndexOf(songToUpdate)

    if (queueIndex != -1) {
        val updatedSong = currentPlaylist[queueIndex].copy(
            matchedLyric = newLyrics,
            matchedTranslatedLyric = newTranslatedLyrics
        )
        val newList = currentPlaylist.toMutableList()
        newList[queueIndex] = updatedSong
        currentPlaylist = newList
        _currentQueueFlow.value = currentPlaylist
        NPLogger.e("PlayerManager", "Queue song updated")
    } else {
        NPLogger.e("PlayerManager", "Song to update was not found in queue")
    }

    NPLogger.e(
        "PlayerManager",
        "Current playing song: id=${_currentSongFlow.value?.id}, album='${_currentSongFlow.value?.album}'"
    )
    if (isCurrentSong(songToUpdate)) {
        val beforeUpdate = _currentSongFlow.value?.matchedLyric
        _currentSongFlow.value = _currentSongFlow.value?.copy(
            matchedLyric = newLyrics,
            matchedTranslatedLyric = newTranslatedLyrics
        )
        NPLogger.e(
            "PlayerManager",
            "Current song lyrics updated: before=${beforeUpdate?.take(50)}, after=${_currentSongFlow.value?.matchedLyric?.take(50)}"
        )
    } else {
        NPLogger.e("PlayerManager", "Current song does not match target update")
    }

    val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
    if (latestSong != null) {
        withContext(Dispatchers.IO) {
            localRepo.updateSongMetadata(songToUpdate, latestSong)
        }
        GlobalDownloadManager.syncDownloadedSongMetadata(latestSong)
        AppContainer.playHistoryRepo.updateSongMetadata(songToUpdate, latestSong)
        AppContainer.playlistUsageRepo.syncLocalEntries(localRepo.playlists.value)
        NPLogger.d(
            "PlayerManager",
            "歌词更新已同步到本地仓库: id=${latestSong.id}, lyric=${latestSong.matchedLyric?.take(32)}, translated=${latestSong.matchedTranslatedLyric?.take(32)}"
        )
    } else {
        NPLogger.e("PlayerManager", "歌词更新后未找到最新歌曲副本，跳过本地仓库同步")
    }

    persistState()
    NPLogger.d("PlayerManager", "updateSongLyricsAndTranslation completed")
}

private suspend fun PlayerManager.updateSongInAllPlaces(
    originalSong: SongItem,
    updatedSong: SongItem
) {
    NPLogger.d(
        "NERI-PlayerManager",
        "updateSongInAllPlaces: original=${originalSong.name}/${originalSong.id}, updated=${updatedSong.name}/${updatedSong.id}, hasCurrentMatch=${isCurrentSong(originalSong)}, stack=[${debugStackHint()}]"
    )
    val queueIndex = queueIndexOf(originalSong)
    if (queueIndex != -1) {
        val newList = currentPlaylist.toMutableList()
        newList[queueIndex] = updatedSong
        currentPlaylist = newList
        _currentQueueFlow.value = currentPlaylist
    }

    if (isCurrentSong(originalSong)) {
        _currentSongFlow.value = updatedSong
    }

    withContext(Dispatchers.IO) {
        localRepo.updateSongMetadata(originalSong, updatedSong)
    }
    GlobalDownloadManager.syncDownloadedSongMetadata(updatedSong)
    AppContainer.playHistoryRepo.updateSongMetadata(originalSong, updatedSong)
    AppContainer.playlistUsageRepo.syncLocalEntries(localRepo.playlists.value)

    persistState()
}
