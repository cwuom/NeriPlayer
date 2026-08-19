@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import android.os.SystemClock
import androidx.media3.common.Player
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.toPlaybackSongItem
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.metadata.applyManualSearchMetadata
import moe.ouom.neriplayer.core.player.metadata.normalizeCustomMetadataValue
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.metadata.SongMetadataRequestCoordinator
import moe.ouom.neriplayer.core.player.metadata.hasUsableLyrics
import moe.ouom.neriplayer.core.player.metadata.LocalMetadataWritePlaybackAction
import moe.ouom.neriplayer.core.player.metadata.resolveLocalCoverWriteReference
import moe.ouom.neriplayer.core.player.metadata.resolveLocalMetadataWritePlaybackAction
import moe.ouom.neriplayer.core.player.metadata.resolveRestoredBaseCoverUrl
import moe.ouom.neriplayer.core.player.metadata.shouldAutoMatchExternalLyrics
import moe.ouom.neriplayer.core.player.metadata.shouldSkipSongMetadataMutation
import moe.ouom.neriplayer.core.player.metadata.shouldWriteLocalCoverMetadata
import moe.ouom.neriplayer.core.player.metadata.toBasicSongDetails
import moe.ouom.neriplayer.core.player.metadata.withUpdatedLyricsPreservingOriginal
import moe.ouom.neriplayer.core.player.model.PersistedPlaybackState
import moe.ouom.neriplayer.core.player.model.PersistedState
import moe.ouom.neriplayer.core.player.model.toPersistedSongItem
import moe.ouom.neriplayer.core.player.model.toPlaybackState
import moe.ouom.neriplayer.core.player.model.withPlaybackState
import moe.ouom.neriplayer.core.player.playback.BiliVideoSkipPlaybackController
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.core.player.url.isCurrentListenTogetherFallbackMediaUrl
import moe.ouom.neriplayer.core.player.playlist.PlayerFavoritesController
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.core.player.source.toSongItem
import moe.ouom.neriplayer.listentogether.playback.ListenTogetherRestoredPlaybackAction
import moe.ouom.neriplayer.listentogether.playback.resolveListenTogetherRestoredPlaybackAction
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.isReadableLocalFile
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.settings.rebaseLyricUserOffsetMs
import moe.ouom.neriplayer.data.settings.shouldRebaseLyricOffsetForSource
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.util.io.writeTextAtomically
import java.io.File
import java.lang.reflect.Type
import java.util.concurrent.atomic.AtomicBoolean

internal fun PlayerManager.hasItemsImpl(): Boolean = currentPlaylist.isNotEmpty()

internal data class RestoredPlayerStateSnapshot(
    val playlist: List<SongItem>,
    val currentIndex: Int,
    val currentMediaUrl: String?,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
    val shuffleRestorePlaylist: List<SongItem>?,
    val shuffleRestoreIndex: Int,
    val resumePositionMs: Long,
    val shouldResumePlayback: Boolean,
    val persistedPlaybackState: PersistedPlaybackState,
    val originalPlaylistSize: Int,
    val persistedIndex: Int
)

private val songMetadataRequestCoordinator = SongMetadataRequestCoordinator()
private val songMetadataMutationMutex = Mutex()
private val favoriteMutationMutex = Mutex()

private data class LocalMetadataWritePlaybackSnapshot(
    val song: SongItem,
    val index: Int,
    val positionMs: Long,
    val commandSource: PlaybackCommandSource
)

internal enum class EditableMetadataWriteMode {
    APP_ONLY,
    BACKGROUND_LOCAL_WRITE
}

internal fun resolveEditableMetadataWriteMode(
    writeLocalMetadata: Boolean
): EditableMetadataWriteMode {
    return if (writeLocalMetadata) {
        EditableMetadataWriteMode.BACKGROUND_LOCAL_WRITE
    } else {
        EditableMetadataWriteMode.APP_ONLY
    }
}

private suspend fun <T> runSongMetadataMutation(block: suspend () -> T): T {
    return withContext(Dispatchers.IO) {
        songMetadataMutationMutex.withLock { block() }
    }
}

private fun PlayerManager.dispatchMetadataReplacementCompletion(
    onComplete: ((Boolean) -> Unit)?,
    applied: Boolean
) {
    val callback = onComplete ?: return
    application.mainExecutor.execute { callback(applied) }
}

private fun downloadedLocalFilesCoverCandidates(): List<SongItem> {
    return GlobalDownloadManager.downloadedSongs.value.map { it.toPlaybackSongItem() }
}

private fun buildPersistedPlaybackState(
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
    val currentSong = _currentSongFlow.value
    val restorePlaylistReference = shuffleRestorePlaylistReference
    return PersistedState(
        playlist = playlistReference.mapIndexed { index, song ->
            // 只给当前歌曲保留内嵌歌词, 避免大队列切歌时反复序列化整批长文本
            song.toPersistedSongItem(
                includeLyrics = shouldPersistEmbeddedLyrics(song) && index == playbackStateSnapshot.index
            )
        },
        index = playbackStateSnapshot.index,
        mediaUrl = playbackStateSnapshot.mediaUrl,
        positionMs = playbackStateSnapshot.positionMs,
        shouldResumePlayback = playbackStateSnapshot.shouldResumePlayback,
        repeatMode = playbackStateSnapshot.repeatMode,
        shuffleEnabled = playbackStateSnapshot.shuffleEnabled,
        shuffleRestorePlaylist = if (playbackStateSnapshot.shuffleEnabled == true) {
            restorePlaylistReference?.map { song ->
                song.toPersistedSongItem(
                    includeLyrics = shouldPersistEmbeddedLyrics(song) &&
                        currentSong?.sameIdentityAs(song) == true
                )
            }
        } else {
            null
        },
        shuffleRestoreIndex = if (playbackStateSnapshot.shuffleEnabled == true) {
            restorePlaylistReference
                ?.indexOfFirst { currentSong?.sameIdentityAs(it) == true }
                ?.takeIf { it >= 0 }
                ?: shuffleRestoreCurrentIndex.takeIf { it >= 0 }
        } else {
            null
        }
    )
}

private fun <T> PlayerManager.readJson(file: File, type: Type): T {
    file.inputStream().bufferedReader().use { reader ->
        return gson.fromJson(reader, type)
    }
}

internal class PlaybackQueueLegacyStore(
    private val stateFile: File,
    private val playbackStateFile: File,
    private val gson: com.google.gson.Gson
) {
    fun read(): PersistedState? {
        if (!stateFile.exists()) {
            return null
        }
        val type = object : TypeToken<PersistedState>() {}.type
        val legacyData: PersistedState = PlayerManager.readJson(stateFile, type)
        val playbackState = playbackStateFile.takeIf(File::exists)?.runCatching {
            PlayerManager.readJson<PersistedPlaybackState>(
                this,
                PersistedPlaybackState::class.java
            )
        }?.getOrNull()
        return playbackState?.let(legacyData::withPlaybackState) ?: legacyData
    }

    fun write(
        state: PersistedState,
        playbackState: PersistedPlaybackState
    ) {
        runCatching { playbackStateFile.delete() }
        stateFile.writeTextAtomically(gson.toJson(state))
        playbackStateFile.writeTextAtomically(gson.toJson(playbackState))
    }

    fun lastModified(): Long {
        return maxOf(
            stateFile.takeIf(File::exists)?.lastModified() ?: 0L,
            playbackStateFile.takeIf(File::exists)?.lastModified() ?: 0L
        )
    }

    fun clear() {
        runCatching { stateFile.delete() }
        runCatching { playbackStateFile.delete() }
    }
}

internal enum class PlaybackQueuePersistTarget {
    ROOM,
    LEGACY_JSON,
    NONE
}

internal suspend fun persistPlaybackQueueWithRoomFallback(
    roomStore: PlaybackQueueStateStore,
    legacyStore: PlaybackQueueLegacyStore,
    queueState: PersistedState?,
    playbackState: PersistedPlaybackState,
    shouldWriteQueueState: Boolean,
    shouldWritePlaybackState: Boolean,
    onRoomFailure: (Throwable) -> Unit = {}
): PlaybackQueuePersistTarget {
    if (!shouldWriteQueueState && !shouldWritePlaybackState) {
        return PlaybackQueuePersistTarget.NONE
    }
    val now = System.currentTimeMillis()
    if (queueState == null) {
        return runCatching {
            roomStore.clear(now)
            legacyStore.clear()
            PlaybackQueuePersistTarget.ROOM
        }.getOrElse { error ->
            onRoomFailure(error)
            legacyStore.write(
                PersistedState(playlist = emptyList(), index = -1)
                    .withPlaybackState(playbackState),
                playbackState
            )
            runCatching { roomStore.markLegacyJsonPrimary(now) }
                .onFailure { markerError ->
                    onRoomFailure(markerError)
                }
            PlaybackQueuePersistTarget.LEGACY_JSON
        }
    }

    return runCatching {
        if (shouldWriteQueueState) {
            roomStore.replaceSnapshot(queueState, now)
        }
        if (shouldWritePlaybackState && !shouldWriteQueueState) {
            roomStore.updatePlaybackState(playbackState, now)
        }
        PlaybackQueuePersistTarget.ROOM
    }.getOrElse { error ->
        onRoomFailure(error)
        legacyStore.write(queueState, playbackState)
        runCatching { roomStore.markLegacyJsonPrimary(now) }
            .onFailure { markerError ->
                onRoomFailure(markerError)
            }
        PlaybackQueuePersistTarget.LEGACY_JSON
    }
}

internal data class PlaybackQueueLegacySnapshot(
    val state: PersistedState,
    val updatedAt: Long
)

private fun readLegacyPlaybackStateSnapshot(
    stateFile: File,
    playbackStateFile: File
): PlaybackQueueLegacySnapshot? {
    return runCatching {
        val legacyStore = PlaybackQueueLegacyStore(
            stateFile = stateFile,
            playbackStateFile = playbackStateFile,
            gson = PlayerManager.gson
        )
        val state = legacyStore.read() ?: return@runCatching null
        PlaybackQueueLegacySnapshot(
            state = state,
            updatedAt = legacyStore.lastModified()
        )
    }.onFailure { error ->
        NPLogger.w(
            "NERI-PlayerManager",
            "Failed to read legacy playback state: ${error.message}"
        )
    }.getOrNull()
}

internal fun selectRestoredPlaybackState(
    roomPrimary: Boolean,
    roomSnapshot: PlaybackQueueRoomSnapshot?,
    legacySnapshot: PlaybackQueueLegacySnapshot?
): PersistedState? {
    if (!roomPrimary || roomSnapshot == null) {
        return legacySnapshot?.state
    }
    if (legacySnapshot != null && legacySnapshot.updatedAt >= roomSnapshot.updatedAt) {
        return legacySnapshot.state
    }
    return roomSnapshot.state
}

private fun buildRestoredStateSnapshot(
    app: Application,
    data: PersistedState,
    keepLastPlaybackProgressEnabled: Boolean,
    keepPlaybackModeStateEnabled: Boolean
): RestoredPlayerStateSnapshot? {
    return runCatching {
        val playlist = data.playlist.map { persistedSong -> persistedSong.toSongItem() }
        val currentlyUnreadableLocalCount = playlist.count { song ->
            LocalSongSupport.isLocalSong(song, app) &&
                !PlayerManager.isRestorableLocalSong(song, app)
        }
        if (currentlyUnreadableLocalCount > 0) {
            NPLogger.w(
                "NERI-PlayerManager",
                "restoreState: keeping $currentlyUnreadableLocalCount local songs even though they are not readable yet"
            )
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
                !isPersistedLocalMediaUrl || PlayerManager.isRestorableLocalMediaUri(it, app)
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
            shuffleRestorePlaylist = if (keepPlaybackModeStateEnabled && data.shuffleEnabled == true) {
                data.shuffleRestorePlaylist?.map { persistedSong -> persistedSong.toSongItem() }
            } else {
                null
            },
            shuffleRestoreIndex = if (keepPlaybackModeStateEnabled && data.shuffleEnabled == true) {
                data.shuffleRestoreIndex ?: -1
            } else {
                -1
            },
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

internal suspend fun preloadRestoredStateSnapshot(
    app: Application,
    keepLastPlaybackProgressEnabled: Boolean,
    keepPlaybackModeStateEnabled: Boolean
): RestoredPlayerStateSnapshot? {
    val startupStateFile = File(app.filesDir, "last_playlist.json")
    val startupPlaybackStateFile = File(app.filesDir, "last_playback_state.json")
    return withContext(Dispatchers.IO) {
        val roomStore = PlaybackQueueRoomStore(
            NeriUserDataDatabase.getInstance(app.applicationContext)
        )
        val roomRead = runCatching { roomStore.readSnapshotIfRoomPrimary() }
            .onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "restoreState: Room read failed, trying legacy JSON: ${error.message}"
                )
            }
        val roomPrimary = runCatching { roomStore.isRoomPrimary() }
            .onFailure { error ->
                NPLogger.w(
                    "NERI-PlayerManager",
                    "restoreState: Room marker read failed: ${error.message}"
                )
            }
            .getOrDefault(false)
        val roomSnapshot = roomRead.getOrNull()
        val legacySnapshot = if (!roomPrimary ||
            roomSnapshot == null ||
            PlaybackQueueLegacyStore(
                stateFile = startupStateFile,
                playbackStateFile = startupPlaybackStateFile,
                gson = PlayerManager.gson
            ).lastModified() >= roomSnapshot.updatedAt
        ) {
            readLegacyPlaybackStateSnapshot(
                stateFile = startupStateFile,
                playbackStateFile = startupPlaybackStateFile
            )
        } else {
            null
        }
        val data = selectRestoredPlaybackState(
            roomPrimary = roomPrimary,
            roomSnapshot = roomSnapshot,
            legacySnapshot = legacySnapshot
        )
        val selectedLegacySnapshot = legacySnapshot
        if (data != null && selectedLegacySnapshot != null && data === selectedLegacySnapshot.state) {
            selectedLegacySnapshot.state.also { legacyData ->
                runCatching {
                    roomStore.replaceSnapshot(legacyData)
                }.onFailure { error ->
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "Failed to import legacy playback state into Room: ${error.message}"
                    )
                }
            }
        }
        if (data == null) {
            NPLogger.d("NERI-PlayerManager", "restoreState: no persisted playback state")
            null
        } else {
            buildRestoredStateSnapshot(
                app = app,
                data = data,
                keepLastPlaybackProgressEnabled = keepLastPlaybackProgressEnabled,
                keepPlaybackModeStateEnabled = keepPlaybackModeStateEnabled
            )
        }
    }
}

private fun loadRestoredStateSnapshot(
    app: Application,
    stateFile: File,
    playbackStateFile: File,
    keepLastPlaybackProgressEnabled: Boolean,
    keepPlaybackModeStateEnabled: Boolean
): RestoredPlayerStateSnapshot? {
    return runCatching {
        val data = runBlocking(Dispatchers.IO) {
            val database = NeriUserDataDatabase.getInstance(app.applicationContext)
            val roomStore = PlaybackQueueRoomStore(database)
            val roomPrimary = runCatching { roomStore.isRoomPrimary() }
                .onFailure { error ->
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "restoreState: Room marker read failed: ${error.message}"
                    )
                }
                .getOrDefault(false)
            val roomRead = if (roomPrimary) {
                runCatching { roomStore.readSnapshotIfRoomPrimary() }
                    .onFailure { error ->
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "restoreState: Room read failed, trying legacy JSON: ${error.message}"
                        )
                    }
            } else {
                null
            }
            val roomSnapshot = roomRead?.getOrNull()
            val legacySnapshot = if (!roomPrimary ||
                roomSnapshot == null ||
                PlaybackQueueLegacyStore(
                    stateFile = stateFile,
                    playbackStateFile = playbackStateFile,
                    gson = PlayerManager.gson
                ).lastModified() >= roomSnapshot.updatedAt
            ) {
                readLegacyPlaybackStateSnapshot(
                    stateFile = stateFile,
                    playbackStateFile = playbackStateFile
                )
            } else {
                null
            }
            val selected = selectRestoredPlaybackState(
                roomPrimary = roomPrimary,
                roomSnapshot = roomSnapshot,
                legacySnapshot = legacySnapshot
            )
            val selectedLegacySnapshot = legacySnapshot
            if (selected != null && selectedLegacySnapshot != null && selected === selectedLegacySnapshot.state) {
                selectedLegacySnapshot.state.also { legacyData ->
                    runCatching {
                        roomStore.replaceSnapshot(legacyData)
                    }.onFailure { error ->
                        NPLogger.w(
                            "NERI-PlayerManager",
                            "restoreState: failed to import legacy playback state: ${error.message}"
                        )
                    }
                }
            } else {
                selected
            }
        } ?: return@runCatching null
        buildRestoredStateSnapshot(
            app = app,
            data = data,
            keepLastPlaybackProgressEnabled = keepLastPlaybackProgressEnabled,
            keepPlaybackModeStateEnabled = keepPlaybackModeStateEnabled
        )
    }.onFailure { error ->
        NPLogger.w("NERI-PlayerManager", "Failed to restore state: ${error.message}")
    }.getOrNull()
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
        setCurrentSongForPlayback(null)
        _currentMediaUrl.value = null
        _currentPlaybackAudioInfo.value = null
        _playbackPositionMs.value = 0L
        currentMediaUrlResolvedAtMs = 0L
        shuffleRestorePlaylistReference = null
        shuffleRestoreCurrentIndex = -1
        restoredResumePositionMs = 0L
        restoredShouldResumePlayback = false
        updateResumePlaybackRequested(false)
        return
    }

    currentIndex = snapshot.currentIndex
    _currentQueueFlow.value = currentPlaylist
    setCurrentSongForPlayback(currentPlaylist.getOrNull(currentIndex))
    _currentMediaUrl.value = snapshot.currentMediaUrl
    repeatModeSetting = snapshot.repeatMode
    syncExoRepeatMode()
    _repeatModeFlow.value = repeatModeSetting

    player.shuffleModeEnabled = snapshot.shuffleEnabled
    _shuffleModeFlow.value = snapshot.shuffleEnabled
    if (snapshot.shuffleEnabled) {
        shuffleRestorePlaylistReference = snapshot.shuffleRestorePlaylist
        shuffleRestoreCurrentIndex = snapshot.shuffleRestoreIndex
    } else {
        shuffleRestorePlaylistReference = null
        shuffleRestoreCurrentIndex = -1
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

internal suspend fun PlayerManager.persistStateNow(
    positionMs: Long = _playbackPositionMs.value.coerceAtLeast(0L),
    shouldResumePlayback: Boolean = currentPlaylist.isNotEmpty() && shouldResumePlaybackSnapshot(),
    reason: String
): Boolean {
    if (!initialized) return false
    val pendingJob = scheduledStatePersistJob
    scheduledStatePersistJob = null
    pendingJob?.cancelAndJoin()
    NPLogger.d(
        "NERI-PlayerManager",
        "persistStateNow: reason=$reason positionMs=$positionMs shouldResume=$shouldResumePlayback"
    )
    persistState(
        positionMs = positionMs,
        shouldResumePlayback = shouldResumePlayback
    )
    return true
}

private suspend fun PlayerManager.updateCurrentFavorite(
    song: SongItem,
    add: Boolean,
    playlists: List<LocalPlaylist>
) {
    NPLogger.d(
        "NERI-PlayerManager",
        "updateCurrentFavorite(): action=${if (add) "add" else "remove"}, song=${song.name}/${song.id}, playlists=${playlists.size}, stack=[${debugStackHint()}]"
    )
    val updatedLists = PlayerFavoritesController.optimisticUpdateFavorites(
        playlists = playlists,
        add = add,
        song = song,
        application = application,
        favoritePlaylistName = getLocalizedString(R.string.favorite_my_music)
    )
    _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(updatedLists)

    try {
        if (add) {
            localRepo.addToFavorites(song)
        } else {
            localRepo.removeFromFavorites(song)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val action = if (add) "addToFavorites" else "removeFromFavorites"
        NPLogger.e("NERI-PlayerManager", "$action failed: ${error.message}", error)
        _playlistsFlow.value = PlayerFavoritesController.deepCopyPlaylists(localRepo.playlists.value)
    }
}

private fun PlayerManager.enqueueFavoriteMutation(
    song: SongItem,
    resolveAdd: (List<LocalPlaylist>) -> Boolean
) {
    ioScope.launch {
        favoriteMutationMutex.withLock {
            if (!localRepo.awaitInitialized()) {
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Favorite mutation skipped because local playlists failed to initialize"
                )
                return@withLock
            }
            val playlists = localRepo.playlists.value
            updateCurrentFavorite(
                song = song,
                add = resolveAdd(playlists),
                playlists = playlists
            )
        }
    }
}

internal fun PlayerManager.addCurrentToFavoritesImpl() {
    ensureInitialized()
    if (!initialized) return
    val song = _currentSongFlow.value ?: return
    enqueueFavoriteMutation(song) { true }
}

internal fun PlayerManager.removeCurrentFromFavoritesImpl() {
    ensureInitialized()
    if (!initialized) return
    val song = _currentSongFlow.value ?: return
    enqueueFavoriteMutation(song) { false }
}

internal fun PlayerManager.toggleCurrentFavoriteImpl() {
    ensureInitialized()
    if (!initialized) return
    val song = _currentSongFlow.value ?: return
    enqueueFavoriteMutation(song) { playlists ->
        val currentlyFavorite = PlayerFavoritesController.isFavorite(
            playlists,
            song,
            application
        )
        NPLogger.d(
            "NERI-PlayerManager",
            "toggleCurrentFavorite(): song=${song.name}/${song.id}, currentlyFavorite=$currentlyFavorite, stack=[${debugStackHint()}]"
        )
        !currentlyFavorite
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
        .takeUnless { isCurrentListenTogetherFallbackMediaUrl() }
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
                val roomStore = PlaybackQueueRoomStore(
                    NeriUserDataDatabase.getInstance(application.applicationContext)
                )
                val legacyStore = PlaybackQueueLegacyStore(
                    stateFile = stateFile,
                    playbackStateFile = playbackStateFile,
                    gson = gson
                )
                if (playlistReference.isEmpty()) {
                    restoredResumePositionMs = 0L
                    restoredShouldResumePlayback = false
                    val persistTarget = persistPlaybackQueueWithRoomFallback(
                        roomStore = roomStore,
                        legacyStore = legacyStore,
                        queueState = null,
                        playbackState = playbackStateSnapshot,
                        shouldWriteQueueState = true,
                        shouldWritePlaybackState = true,
                        onRoomFailure = { error ->
                            NPLogger.e(
                                "NERI-PlayerManager",
                                "persistState: Room clear failed; falling back to legacy JSON",
                                error
                            )
                        }
                    )
                    shuffleRestorePlaylistReference = null
                    shuffleRestoreCurrentIndex = -1
                    lastPersistedPlaylistReference = null
                    lastPersistedPlaybackState = null
                    lastStatePersistAtMs = SystemClock.elapsedRealtime()
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "persistState: cleared persisted playback state because queue is empty, target=$persistTarget"
                    )
                    return@withLock
                }

                val shouldWriteQueueState = playlistReference !== lastPersistedPlaylistReference
                val shouldWritePlaybackState =
                    shouldWriteQueueState ||
                        playbackStateSnapshot != lastPersistedPlaybackState ||
                        lastPersistedPlaybackState == null

                val queueState = if (shouldWriteQueueState || shouldWritePlaybackState) {
                    buildPersistedPlaylistState(
                        playlistReference = playlistReference,
                        playbackStateSnapshot = playbackStateSnapshot
                    )
                } else {
                    null
                }
                val persistTarget = persistPlaybackQueueWithRoomFallback(
                    roomStore = roomStore,
                    legacyStore = legacyStore,
                    queueState = queueState,
                    playbackState = playbackStateSnapshot,
                    shouldWriteQueueState = shouldWriteQueueState,
                    shouldWritePlaybackState = shouldWritePlaybackState,
                    onRoomFailure = { error ->
                        NPLogger.e(
                            "NERI-PlayerManager",
                            "persistState: Room write failed; falling back to legacy JSON",
                            error
                        )
                    }
                )

                if (shouldWriteQueueState) {
                    lastPersistedPlaylistReference = playlistReference
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "persistState: wrote $persistTarget queue state, queueSize=${playlistReference.size}, index=$currentIndexSnapshot"
                    )
                }

                if (shouldWritePlaybackState) {
                    lastPersistedPlaybackState = playbackStateSnapshot
                }

                if (shouldWriteQueueState || shouldWritePlaybackState) {
                    lastStatePersistAtMs = SystemClock.elapsedRealtime()
                }
            } catch (e: Exception) {
                lastPersistedPlaylistReference = null
                lastPersistedPlaybackState = null
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
    return PlayerLyricsProvider.getNeteaseLyrics(songId, neteaseClient, neteaseLyricsCache)
}

internal suspend fun PlayerManager.getNeteaseTranslatedLyricsImpl(songId: Long): List<LyricEntry> {
    return PlayerLyricsProvider.getNeteaseTranslatedLyrics(
        songId,
        neteaseClient,
        neteaseLyricsCache
    )
}

internal suspend fun PlayerManager.getNeteaseRomanizedLyricsImpl(songId: Long): List<LyricEntry> {
    return PlayerLyricsProvider.getNeteaseRomanizedLyrics(
        songId,
        neteaseClient,
        neteaseLyricsCache
    )
}

internal suspend fun PlayerManager.getPreferredNeteaseLyricContentImpl(songId: Long): String {
    return PlayerLyricsProvider.getPreferredNeteaseLyricContent(
        songId,
        neteaseClient,
        neteaseLyricsCache
    )
}

internal suspend fun PlayerManager.getPreferredNeteaseRomanizedLyricContentImpl(songId: Long): String {
    return PlayerLyricsProvider.getPreferredNeteaseRomanizedLyricContent(
        songId,
        neteaseClient,
        neteaseLyricsCache
    )
}

internal suspend fun PlayerManager.getTranslatedLyricsImpl(song: SongItem): List<LyricEntry> {
    return PlayerLyricsProvider.getTranslatedLyrics(
        song = song,
        application = application,
        neteaseClient = neteaseClient,
        neteaseLyricsCache = neteaseLyricsCache,
        editableLyricsMatcher = AppContainer.editableLyricsMatcher,
        ytMusicLyricsCache = ytMusicLyricsCache,
        biliSourceTag = BILI_SOURCE_TAG
    )
}

internal suspend fun PlayerManager.getRomanizedLyricsImpl(song: SongItem): List<LyricEntry> {
    return PlayerLyricsProvider.getRomanizedLyrics(
        song = song,
        application = application,
        neteaseClient = neteaseClient,
        neteaseLyricsCache = neteaseLyricsCache,
        biliSourceTag = BILI_SOURCE_TAG
    )
}

internal suspend fun PlayerManager.getLyricsImpl(song: SongItem): List<LyricEntry> {
    return PlayerLyricsProvider.getLyrics(
        song = song,
        application = application,
        neteaseClient = neteaseClient,
        neteaseLyricsCache = neteaseLyricsCache,
        youtubeMusicClient = youtubeMusicClient,
        lrcLibClient = lrcLibClient,
        editableLyricsMatcher = AppContainer.editableLyricsMatcher,
        amllTtmlClient = amllTtmlClient,
        amllLyricsEnabled = amllLyricsEnabled,
        ytMusicLyricsCache = ytMusicLyricsCache,
        biliSourceTag = BILI_SOURCE_TAG
    )
}

internal fun PlayerManager.playFromQueueImpl(
    index: Int,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    bypassLoudVolumeWarning: Boolean = false
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
    if (requestUsbExclusiveLoudPlaybackConfirmation(
            commandSource = commandSource,
            bypassWarning = bypassLoudVolumeWarning,
            continuePlayback = {
                playFromQueueImpl(
                    index = index,
                    commandSource = commandSource,
                    bypassLoudVolumeWarning = true
                )
            }
        )
    ) {
        return
    }

    currentIndex = index
    playAtIndex(index, commandSource = commandSource)
    emitPlaybackCommand(
        type = "PLAY_FROM_QUEUE",
        source = commandSource,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        positionMs = _playbackPositionMs.value
    )
}

internal fun PlayerManager.replaceCurrentInQueueAndPlayImpl(
    song: SongItem,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    bypassLoudVolumeWarning: Boolean = false
) {
    ensureInitialized()
    if (!initialized) return

    if (currentPlaylist.isEmpty() || currentIndex !in currentPlaylist.indices) {
        NPLogger.d(
            "NERI-PlayerManager",
            "replaceCurrentInQueueAndPlay(): queue empty, fallback to playPlaylist, song=${song.name}/${song.id}"
        )
        playPlaylist(listOf(song), 0, commandSource)
        return
    }

    if (shouldBlockLocalRoomControl(commandSource) ||
        shouldBlockLocalSongSwitch(song, commandSource)
    ) {
        return
    }
    if (requestUsbExclusiveLoudPlaybackConfirmation(
            commandSource = commandSource,
            bypassWarning = bypassLoudVolumeWarning,
            continuePlayback = {
                replaceCurrentInQueueAndPlayImpl(
                    song = song,
                    commandSource = commandSource,
                    bypassLoudVolumeWarning = true
                )
            }
        )
    ) {
        return
    }

    val oldIndex = currentIndex.coerceIn(currentPlaylist.indices)
    val newPlaylist = currentPlaylist.toMutableList()
    val existingIndex = newPlaylist.indexOfFirst { it.sameIdentityAs(song) }
    val targetIndex = if (existingIndex != -1 && existingIndex != oldIndex) {
        newPlaylist.removeAt(existingIndex)
        if (existingIndex < oldIndex) oldIndex - 1 else oldIndex
    } else {
        oldIndex
    }.coerceIn(newPlaylist.indices)

    newPlaylist[targetIndex] = song
    suppressAutoResumeForCurrentSession = false
    consecutivePlayFailures = 0
    currentPlaylist = newPlaylist
    _currentQueueFlow.value = currentPlaylist
    currentIndex = targetIndex
    bumpCurrentQueueDisplayRevision()

    NPLogger.d(
        "NERI-PlayerManager",
        "replaceCurrentInQueueAndPlay(): song=${song.name}/${song.id}, existingIndex=$existingIndex, targetIndex=$targetIndex, queueSize=${currentPlaylist.size}, source=$commandSource, stack=[${debugStackHint()}]"
    )
    playAtIndex(currentIndex, commandSource = commandSource)
    emitPlaybackCommand(
        type = "PLAY_FROM_QUEUE",
        source = commandSource,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        positionMs = _playbackPositionMs.value
    )
}

internal fun resolveQueueCurrentIndexAfterMove(
    currentIndex: Int,
    fromIndex: Int,
    toIndex: Int,
    queueSize: Int
): Int {
    if (queueSize <= 0) return -1
    if (currentIndex !in 0 until queueSize) return currentIndex
    if (fromIndex !in 0 until queueSize || toIndex !in 0 until queueSize) return currentIndex
    if (fromIndex == toIndex) return currentIndex
    return when {
        currentIndex == fromIndex -> toIndex
        fromIndex < currentIndex && currentIndex <= toIndex -> currentIndex - 1
        toIndex <= currentIndex && currentIndex < fromIndex -> currentIndex + 1
        else -> currentIndex
    }
}

internal fun resolveQueueCurrentIndexAfterRemoval(
    currentIndex: Int,
    removedIndex: Int,
    queueSize: Int
): Int {
    if (queueSize <= 0) return -1
    if (removedIndex !in 0 until queueSize) return currentIndex
    val newSize = queueSize - 1
    if (newSize <= 0) return -1
    if (currentIndex !in 0 until queueSize) return currentIndex
    return when {
        currentIndex == removedIndex -> removedIndex.coerceAtMost(newSize - 1)
        removedIndex < currentIndex -> currentIndex - 1
        else -> currentIndex
    }
}

internal fun PlayerManager.moveQueueItemImpl(fromIndex: Int, toIndex: Int) {
    ensureInitialized()
    if (!initialized) return
    if (shouldBlockLocalRoomControl(PlaybackCommandSource.LOCAL)) return
    val queueSize = currentPlaylist.size
    if (queueSize <= 1) return
    if (fromIndex !in 0 until queueSize || toIndex !in 0 until queueSize) return
    if (fromIndex == toIndex) return

    val newPlaylist = currentPlaylist.toMutableList()
    newPlaylist.add(toIndex, newPlaylist.removeAt(fromIndex))
    val oldIndex = currentIndex
    currentPlaylist = newPlaylist
    _currentQueueFlow.value = currentPlaylist
    currentIndex = resolveQueueCurrentIndexAfterMove(
        currentIndex = oldIndex,
        fromIndex = fromIndex,
        toIndex = toIndex,
        queueSize = queueSize
    )
    bumpCurrentQueueDisplayRevision()

    NPLogger.d(
        "NERI-PlayerManager",
        "moveQueueItem(): from=$fromIndex, to=$toIndex, queueSize=$queueSize, oldIndex=$oldIndex, currentIndex=$currentIndex, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )
    emitQueueUpdateCommand()

    ioScope.launch {
        persistState()
    }
}

internal fun PlayerManager.removeQueueItemImpl(index: Int) {
    ensureInitialized()
    if (!initialized) return
    if (shouldBlockLocalRoomControl(PlaybackCommandSource.LOCAL)) return
    val queueSize = currentPlaylist.size
    if (index !in 0 until queueSize) return

    val oldIndex = currentIndex
    val removedSong = currentPlaylist[index]
    val removingCurrent = index == oldIndex
    val shouldContinuePlayback = removingCurrent && isTransportActiveWithoutInitialization()
    val newPlaylist = currentPlaylist.toMutableList()
    newPlaylist.removeAt(index)
    currentPlaylist = newPlaylist
    _currentQueueFlow.value = currentPlaylist
    currentIndex = resolveQueueCurrentIndexAfterRemoval(
        currentIndex = oldIndex,
        removedIndex = index,
        queueSize = queueSize
    )
    bumpCurrentQueueDisplayRevision()

    NPLogger.d(
        "NERI-PlayerManager",
        "removeQueueItem(): index=$index, removed=${removedSong.name}/${removedSong.id}, queueSize=${currentPlaylist.size}, oldIndex=$oldIndex, currentIndex=$currentIndex, continue=$shouldContinuePlayback, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )

    if (currentPlaylist.isEmpty()) {
        shuffleRestorePlaylistReference = null
        shuffleRestoreCurrentIndex = -1
        stopPlaybackPreservingQueue(clearMediaUrl = true)
        emitQueueUpdateCommand(shouldPlay = false)
        return
    }

    if (removingCurrent) {
        if (shouldContinuePlayback && currentIndex in currentPlaylist.indices) {
            playAtIndex(currentIndex, commandSource = PlaybackCommandSource.LOCAL)
            emitQueueUpdateCommand(shouldPlay = true)
        } else {
            stopPlaybackPreservingQueue(clearMediaUrl = true)
            emitQueueUpdateCommand(shouldPlay = false)
        }
        return
    }

    setCurrentSongForPlayback(currentPlaylist.getOrNull(currentIndex))
    emitQueueUpdateCommand()
    ioScope.launch {
        persistState()
    }
}

private fun queueStableKeyCounts(queue: List<SongItem>): Map<String, Int> {
    return queue.groupingBy { it.stableKey() }.eachCount()
}

internal fun resolveQueueCurrentIndexAfterReorder(
    queue: List<SongItem>,
    currentSong: SongItem?,
    submittedCurrentIndex: Int,
    fallbackCurrentIndex: Int
): Int {
    if (queue.isEmpty()) return -1
    if (currentSong != null) {
        if (
            submittedCurrentIndex in queue.indices &&
            queue[submittedCurrentIndex].sameIdentityAs(currentSong)
        ) {
            return submittedCurrentIndex
        }
        queue.indexOfFirst { candidate -> candidate.sameIdentityAs(currentSong) }
            .takeIf { it >= 0 }
            ?.let { return it }
    }
    return submittedCurrentIndex.takeIf { it in queue.indices }
        ?: fallbackCurrentIndex.coerceIn(queue.indices)
}

internal fun PlayerManager.reorderQueueImpl(
    queue: List<SongItem>,
    currentIndexInQueue: Int,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
) {
    ensureInitialized()
    if (!initialized) return
    if (shouldBlockLocalRoomControl(commandSource)) return
    if (queue.size != currentPlaylist.size) return
    if (queue.isEmpty()) return
    if (queueStableKeyCounts(queue) != queueStableKeyCounts(currentPlaylist)) {
        NPLogger.w(
            "NERI-PlayerManager",
            "reorderQueue(): rejected mismatched queue content, oldSize=${currentPlaylist.size}, newSize=${queue.size}"
        )
        return
    }

    val oldIndex = currentIndex
    val newIndex = resolveQueueCurrentIndexAfterReorder(
        queue = queue,
        currentSong = _currentSongFlow.value ?: currentPlaylist.getOrNull(oldIndex),
        submittedCurrentIndex = currentIndexInQueue,
        fallbackCurrentIndex = oldIndex
    )

    currentPlaylist = queue.toList()
    _currentQueueFlow.value = currentPlaylist
    currentIndex = newIndex.coerceIn(currentPlaylist.indices)
    bumpCurrentQueueDisplayRevision()

    NPLogger.d(
        "NERI-PlayerManager",
        "reorderQueue(): queueSize=${currentPlaylist.size}, oldIndex=$oldIndex, currentIndex=$currentIndex, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )

    emitPlaybackCommand(
        type = "SET_QUEUE",
        source = commandSource,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        positionMs = _playbackPositionMs.value
    )

    ioScope.launch {
        persistState()
    }
}

internal fun PlayerManager.addToQueueNextImpl(song: SongItem) {
    ensureInitialized()
    if (!initialized) return
    if (
        shouldBlockLocalRoomControl(PlaybackCommandSource.LOCAL) ||
        shouldBlockLocalSongSwitch(song, PlaybackCommandSource.LOCAL)
    ) {
        return
    }

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
    bumpCurrentQueueDisplayRevision()
    NPLogger.d(
        "NERI-PlayerManager",
        "addToQueueNext(): song=${song.name}/${song.id}, existingIndex=$existingIndex, insertIndex=$insertIndex, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )
    emitQueueUpdateCommand()

    ioScope.launch {
        persistState()
    }
}

internal fun PlayerManager.addToQueueEndImpl(song: SongItem) {
    ensureInitialized()
    if (!initialized) return
    if (
        shouldBlockLocalRoomControl(PlaybackCommandSource.LOCAL) ||
        shouldBlockLocalSongSwitch(song, PlaybackCommandSource.LOCAL)
    ) {
        return
    }
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
    bumpCurrentQueueDisplayRevision()

    NPLogger.d(
        "NERI-PlayerManager",
        "addToQueueEnd(): song=${song.name}/${song.id}, existingIndex=$existingIndex, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )
    emitQueueUpdateCommand()

    ioScope.launch {
        persistState()
    }
}

private fun PlayerManager.emitQueueUpdateCommand(shouldPlay: Boolean? = null) {
    emitPlaybackCommand(
        type = "SET_QUEUE",
        source = PlaybackCommandSource.LOCAL,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        positionMs = _playbackPositionMs.value,
        shouldPlay = shouldPlay
    )
}

internal fun PlayerManager.applyRemoteQueueUpdateImpl(
    queue: List<SongItem>,
    currentIndexInQueue: Int
) {
    ensureInitialized()
    if (!initialized) return

    if (queue.isEmpty()) {
        currentPlaylist = emptyList()
        _currentQueueFlow.value = emptyList()
        currentIndex = -1
        shuffleRestorePlaylistReference = null
        shuffleRestoreCurrentIndex = -1
        bumpCurrentQueueDisplayRevision()
        stopPlaybackPreservingQueue(clearMediaUrl = true)
    } else {
        currentPlaylist = queue.toList()
        _currentQueueFlow.value = currentPlaylist
        currentIndex = currentIndexInQueue.coerceIn(currentPlaylist.indices)
        bumpCurrentQueueDisplayRevision()
    }

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
    when (
        resolveListenTogetherRestoredPlaybackAction(
            restoredPlaybackRequested = restoredShouldResumePlayback,
            listenTogetherSessionActive = isListenTogetherActive(),
            currentUserIsController = isCurrentUserControllerInListenTogether()
        )
    ) {
        ListenTogetherRestoredPlaybackAction.SKIP -> return null
        ListenTogetherRestoredPlaybackAction.RESUME_LOCAL_PLAYBACK -> Unit
        ListenTogetherRestoredPlaybackAction.WAIT_FOR_AUTHORITATIVE_ROOM_STATE -> {
            NPLogger.d(
                "NERI-PlayerManager",
                "resumeRestoredPlaybackIfNeeded(): defer active Listen Together listener until room state is authoritative"
            )
            restoredShouldResumePlayback = false
            restoredResumePositionMs = 0L
            scheduleStatePersist(
                positionMs = _playbackPositionMs.value.coerceAtLeast(0L),
                shouldResumePlayback = false,
                debounceMs = 0L
            )
            return null
        }
    }
    if (currentPlaylist.isEmpty() || currentIndex !in currentPlaylist.indices) {
        NPLogger.w(
            "NERI-PlayerManager",
            "resumeRestoredPlaybackIfNeeded(): skipped, queueSize=${currentPlaylist.size}, currentIndex=$currentIndex"
        )
        return null
    }
    val resumeIndex = currentIndex
    val resumeSong = currentPlaylist[resumeIndex]
    if (isLocalSong(resumeSong) && !isRestorableLocalSong(resumeSong)) {
        NPLogger.w(
            "NERI-PlayerManager",
            "resumeRestoredPlaybackIfNeeded(): keep restored local progress because media is not readable yet, song=${resumeSong.name}"
        )
        return null
    }
    val resumePositionMs = restoredResumePositionMs.coerceAtLeast(0L)
    NPLogger.d(
        "NERI-PlayerManager",
        "resumeRestoredPlaybackIfNeeded(): resumeIndex=$resumeIndex, positionMs=$resumePositionMs, song=${resumeSong.name}, stack=[${debugStackHint()}]"
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
        ioScope.launch {
            runCatching {
                persistState(positionMs = positionMs, shouldResumePlayback = false)
            }.onFailure { error ->
                if (error is CancellationException) {
                    throw error
                }
                NPLogger.w(
                    "NERI-PlayerManager",
                    "forced auto-resume suppression persistence failed",
                    error
                )
            }
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
    isAuto: Boolean = false,
    onComplete: ((Boolean) -> Unit)? = null
) {
    val requestToken = songMetadataRequestCoordinator.begin(
        songKey = originalSong.stableKey(),
        isAuto = isAuto
    )
    if (requestToken == null) {
        dispatchMetadataReplacementCompletion(onComplete, applied = false)
        return
    }

    val applied = AtomicBoolean(false)
    val replacementJob = ioScope.launch {
        NPLogger.d(
            "NERI-PlayerManager",
            "replaceMetadataFromSearch: originalSong=${originalSong.name}, selectedId=${selectedSong.id}, source=${selectedSong.source}, isAuto=$isAuto, stack=[${debugStackHint()}]"
        )
        try {
            val platform = selectedSong.source
            if (
                platform == MusicPlatform.CLOUD_MUSIC &&
                AppContainer.neteaseCookieRepo.getAuthHealthOnce().state == SavedCookieAuthState.Missing
            ) {
                mainScope.launch {
                    AppFeedback.show(
                        context = application,
                        message = getLocalizedString(R.string.netease_login_required_metadata)
                    )
                }
                return@launch
            }

            val api = when (platform) {
                MusicPlatform.CLOUD_MUSIC -> cloudMusicSearchApi
                MusicPlatform.QQ_MUSIC -> qqMusicSearchApi
            }

            val (newDetails, usedSearchSummaryFallback) = try {
                api.getSongInfo(selectedSong.id) to false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isAuto) throw error
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Song detail lookup failed, applying search summary: selectedId=${selectedSong.id}, error=${error.message.orEmpty()}"
                )
                selectedSong.toBasicSongDetails() to true
            }
            applied.set(runSongMetadataMutation {
                if (!songMetadataRequestCoordinator.isLatest(requestToken)) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Skipping stale metadata replacement: song=${originalSong.name}, selectedId=${selectedSong.id}"
                    )
                    return@runSongMetadataMutation false
                }

                val latestOriginalSong = currentPlaylist.firstOrNull {
                    it.sameIdentityAs(originalSong)
                } ?: _currentSongFlow.value?.takeIf {
                    it.sameIdentityAs(originalSong)
                } ?: originalSong

                if (
                    isAuto &&
                    !shouldAutoMatchExternalLyrics(
                        song = latestOriginalSong,
                        isYouTubeMusicTrack = isYouTubeMusicTrack(latestOriginalSong)
                    )
                ) {
                    NPLogger.d(
                        "NERI-PlayerManager",
                        "Skipping obsolete auto metadata replacement: song=${latestOriginalSong.name}"
                    )
                    return@runSongMetadataMutation false
                }

                val updatedSong = if (isAuto) {
                    if (!newDetails.hasUsableLyrics()) {
                        NPLogger.d(
                            "NERI-PlayerManager",
                            "Skipping automatic metadata replacement without lyrics: selectedId=${selectedSong.id}"
                        )
                        return@runSongMetadataMutation false
                    }
                    latestOriginalSong.withUpdatedLyricsPreservingOriginal(
                        newLyrics = newDetails.lyric ?: latestOriginalSong.matchedLyric,
                        newTranslatedLyric = newDetails.translatedLyric
                            ?: latestOriginalSong.matchedTranslatedLyric
                    ).copy(
                        matchedLyricSource = selectedSong.source,
                        matchedSongId = selectedSong.id
                    )
                } else {
                    applyManualSearchMetadata(
                        originalSong = latestOriginalSong,
                        songName = newDetails.songName,
                        singer = newDetails.singer,
                        coverUrl = newDetails.coverUrl,
                        lyric = newDetails.lyric,
                        translatedLyric = newDetails.translatedLyric,
                        matchedSource = selectedSong.source,
                        matchedSongId = selectedSong.id,
                        useCustomOverride = shouldApplySearchMetadataAsCustomOverride(latestOriginalSong),
                        preserveExistingMatchedLyrics = usedSearchSummaryFallback
                    )
                }

                updateSongInAllPlaces(
                    originalSong = latestOriginalSong,
                    updatedSong = updatedSong,
                    triggerSync = !isAuto
                )
                true
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (songMetadataRequestCoordinator.isLatest(requestToken)) {
                mainScope.launch {
                    AppFeedback.show(
                        context = application,
                        message = getLocalizedString(R.string.toast_match_failed, e.message.orEmpty())
                    )
                    NPLogger.e(
                        "NERI-PlayerManager",
                        "replaceMetadataFromSearch failed: ${e.message}",
                        e
                    )
                }
            }
        }
    }
    replacementJob.invokeOnCompletion {
        songMetadataRequestCoordinator.complete(requestToken)
        dispatchMetadataReplacementCompletion(onComplete, applied.get())
    }
}

private fun PlayerManager.shouldApplySearchMetadataAsCustomOverride(song: SongItem): Boolean {
    return isLocalSong(song) || AudioDownloadManager.getLocalPlaybackUri(application, song) != null
}

private suspend fun PlayerManager.parkCurrentPlaybackForLocalMetadataWrite(
    targetSong: SongItem
): LocalMetadataWritePlaybackSnapshot? = withContext(Dispatchers.Main.immediate) {
    if (!isPlayerInitialized() || currentIndex !in currentPlaylist.indices) {
        return@withContext null
    }
    val action = resolveLocalMetadataWritePlaybackAction(
        isTargetCurrentSong = isCurrentSong(targetSong),
        hasLoadedMedia = player.currentMediaItem != null,
        shouldResumePlayback = player.isPlaying || player.playWhenReady
    )
    if (action == LocalMetadataWritePlaybackAction.NONE) {
        return@withContext null
    }

    val snapshot = if (action == LocalMetadataWritePlaybackAction.RELEASE_AND_RESUME) {
        LocalMetadataWritePlaybackSnapshot(
            song = _currentSongFlow.value ?: targetSong,
            index = currentIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            commandSource = activePlaybackCommandSource
        )
    } else {
        null
    }
    stopPlaybackPreservingQueue(clearMediaUrl = true)
    snapshot
}

private suspend fun PlayerManager.resumePlaybackAfterLocalMetadataWrite(
    snapshot: LocalMetadataWritePlaybackSnapshot?
) {
    if (snapshot == null) return
    withContext(Dispatchers.Main.immediate) {
        val resumeIndex = currentPlaylist.indexOfFirst { it.sameIdentityAs(snapshot.song) }
        if (
            !isPlayerInitialized() ||
                resumeIndex != currentIndex ||
                !isCurrentSong(snapshot.song)
        ) {
            return@withContext
        }
        playAtIndex(
            index = resumeIndex,
            resumePositionMs = snapshot.positionMs,
            commandSource = snapshot.commandSource,
            allowRememberedLongFormPosition = false
        )
    }
}

private suspend fun PlayerManager.writeLocalEditableMetadata(
    song: SongItem,
    coverReference: String? = song.customCoverUrl,
    writeCover: Boolean = coverReference != null,
    writeLyrics: Boolean = false
): LocalMediaMetadataWriteOutcome {
    val writableReference = resolveLocalMetadataWriteReference(song)
    val writableSong = writableReference?.let { playbackUri ->
        song.copy(
            mediaUri = playbackUri,
            localFilePath = if (playbackUri.startsWith('/')) playbackUri else null,
            localFileName = song.localFileName
                ?: playbackUri.substringAfterLast('/').takeIf(String::isNotBlank)
        )
    } ?: song
    if (!LocalSongSupport.isLocalSong(writableSong, application)) {
        return LocalMediaMetadataWriteOutcome.NOT_WRITABLE
    }
    val playbackSnapshot = parkCurrentPlaybackForLocalMetadataWrite(song)
    return try {
        LocalMediaSupport.writeEditableMetadata(
            context = application,
            song = writableSong,
            coverReference = coverReference,
            writeCover = writeCover,
            writeLyrics = writeLyrics
        )
    } finally {
        resumePlaybackAfterLocalMetadataWrite(playbackSnapshot)
    }
}

private fun PlayerManager.scheduleLocalEditableMetadataWrite(
    song: SongItem,
    coverReference: String?,
    writeCover: Boolean,
    writeLyrics: Boolean
) {
    ioScope.launch {
        val outcome = try {
            runSongMetadataMutation {
                writeLocalEditableMetadata(
                    song = song,
                    coverReference = coverReference,
                    writeCover = writeCover,
                    writeLyrics = writeLyrics
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.e(
                "PlayerManager",
                "后台回写本地元信息失败: ${error.message}",
                error
            )
            LocalMediaMetadataWriteOutcome.FAILED
        }

        if (outcome == LocalMediaMetadataWriteOutcome.SUCCESS) {
            runCatching {
                GlobalDownloadManager.syncDownloadedSongMetadata(song)
            }.onFailure { error ->
                NPLogger.w(
                    "PlayerManager",
                    "后台同步下载元数据失败: ${error.message}"
                )
            }
        }
        showLocalEditableMetadataWriteFeedback(
            outcome = outcome,
            downloadSyncOutcome =
                GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED
        )
    }
}

private suspend fun PlayerManager.resolveLocalMetadataWriteReference(
    song: SongItem
): String? {
    val mediaUri = song.mediaUri
    val directReference = when {
        mediaUri?.startsWith("content://", ignoreCase = true) == true -> mediaUri
        song.localFilePath?.let(::File)?.let(::isReadableLocalFile) == true -> {
            song.localFilePath
        }
        mediaUri?.startsWith("/", ignoreCase = false) == true &&
            isReadableLocalFile(File(mediaUri)) -> mediaUri
        else -> null
    }
    if (directReference != null) {
        return directReference
    }
    val downloadedPlaybackUri = AudioDownloadManager.getLocalPlaybackUri(application, song)
    val managedReference = if (downloadedPlaybackUri != null) {
        val cachedReference = ManagedDownloadStorage.peekDownloadedAudio(song)
            ?.reference
            ?.takeIf { reference ->
                ManagedDownloadStorage.isReferenceAccessible(application, reference)
            }
        cachedReference ?: try {
            ManagedDownloadStorage.findDownloadedAudio(
                context = application,
                song = song,
                forceRefresh = true
            )?.reference?.takeIf { reference ->
                ManagedDownloadStorage.isReferenceAccessible(application, reference)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                "PlayerManager",
                "resolve managed metadata write reference failed: ${error.message}"
            )
            null
        }
    } else {
        null
    }
    return managedReference ?: downloadedPlaybackUri
}

private suspend fun PlayerManager.resolveLocalSidecarWriteSong(song: SongItem): SongItem {
    val mediaUri = song.mediaUri
    val directReference = when {
        mediaUri?.startsWith("content://", ignoreCase = true) == true -> mediaUri
        song.localFilePath?.let(::File)?.let(::isReadableLocalFile) == true -> {
            song.localFilePath
        }
        mediaUri?.startsWith("/", ignoreCase = false) == true &&
            isReadableLocalFile(File(mediaUri)) -> mediaUri
        else -> null
    }
    if (directReference != null) {
        return song.copy(
            mediaUri = directReference,
            localFilePath = if (directReference.startsWith("/")) directReference else null,
            localFileName = song.localFileName
                ?: directReference.substringAfterLast('/').takeIf(String::isNotBlank)
        )
    }
    val playbackReference = resolveLocalMetadataWriteReference(song) ?: return song
    return song.copy(
        mediaUri = playbackReference,
        localFilePath = if (playbackReference.startsWith('/')) playbackReference else null,
        localFileName = song.localFileName
            ?: playbackReference.substringAfterLast('/').takeIf(String::isNotBlank)
    )
}

private fun PlayerManager.showLocalEditableMetadataWriteFeedback(
    outcome: LocalMediaMetadataWriteOutcome,
    downloadSyncOutcome: GlobalDownloadManager.DownloadedSongMetadataSyncOutcome
) {
    val messageResId = when (outcome) {
        LocalMediaMetadataWriteOutcome.SUCCESS -> when (downloadSyncOutcome) {
            GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.FAILED -> {
                R.string.local_song_metadata_write_catalog_sync_failed
            }
            else -> R.string.local_song_metadata_write_success
        }
        LocalMediaMetadataWriteOutcome.NOT_WRITABLE -> R.string.local_song_metadata_write_not_writable
        LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE -> {
            R.string.local_song_metadata_write_unsupported
        }
        LocalMediaMetadataWriteOutcome.FAILED -> R.string.local_song_metadata_write_failed
    }
    mainScope.launch {
        AppFeedback.show(
            context = application,
            message = getLocalizedString(messageResId)
        )
    }
}

internal suspend fun PlayerManager.updateSongCustomInfoImpl(
    originalSong: SongItem,
    customCoverUrl: String?,
    customName: String?,
    customArtist: String?,
    restoreBaseCover: Boolean = false,
    restoreBaseName: Boolean = false,
    restoreBaseArtist: Boolean = false,
    clearMatchedMetadata: Boolean = false,
    writeLocalMetadata: Boolean = false,
    writeLyrics: Boolean = false
) : Boolean = runSongMetadataMutation {
            NPLogger.d(
                "PlayerManager",
                "updateSongCustomInfo: id=${originalSong.id}, album='${originalSong.album}', customName=${customName?.take(32)}, customArtist=${customArtist?.take(32)}, customCoverUrl=${customCoverUrl?.take(64)}, restoreBase=[$restoreBaseName,$restoreBaseArtist,$restoreBaseCover], clearMatched=$clearMatchedMetadata, stack=[${debugStackHint()}]"
            )

            val currentSong = currentPlaylist.firstOrNull { it.sameIdentityAs(originalSong) }
                ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(originalSong) }
                ?: originalSong

            val baseName = currentSong.name
            val baseArtist = currentSong.artist
            val baseCoverUrl = currentSong.coverUrl
                ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) }
            val existingOriginalCoverUrl = currentSong.originalCoverUrl
                ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) }
            val requestedCoverReference = customCoverUrl
                .normalizedManualMetadataValue()
                ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) }
            val hasCustomCoverOverride = currentSong.customCoverUrl
                ?.normalizedManualMetadataValue()
                ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) } != null
            val coverChanged = writeLocalMetadata && if (restoreBaseCover) {
                hasCustomCoverOverride
            } else {
                shouldWriteLocalCoverMetadata(
                    restoreBaseCover = false,
                    nextCustomCover = requestedCoverReference,
                    previousCustomCover = currentSong.customCoverUrl
                )
            }
            val legacyOriginalCoverUrl = if (
                coverChanged && LocalSongSupport.isLocalSong(currentSong, application)
            ) {
                CustomSongCoverStorage.resolveLegacyOriginalCoverReference(
                    context = application,
                    song = currentSong,
                    references = listOf(
                        currentSong.originalCoverUrl,
                        currentSong.coverUrl
                    )
                )
            } else {
                null
            }
            val originalCoverReference = if (
                coverChanged && LocalSongSupport.isLocalSong(currentSong, application)
            ) {
                existingOriginalCoverUrl
                    ?.takeIf { reference ->
                        LocalSongSupport.isLocalMediaUri(reference) &&
                            reference != currentSong.customCoverUrl
                    }
                    ?: legacyOriginalCoverUrl
                    ?: LocalMediaSupport.resolveNearbyCoverUri(application, currentSong)
                    ?: LocalMediaSupport.peekCachedEmbeddedCoverUri(application, currentSong)
                    ?: LocalMediaSupport.resolveCoverUri(application, currentSong)
                    ?: existingOriginalCoverUrl?.takeIf { it != currentSong.customCoverUrl }
                    ?: baseCoverUrl?.takeIf { it != currentSong.customCoverUrl }
            } else {
                null
            }
            val preservedOriginalCoverUrl = if (
                coverChanged && LocalSongSupport.isLocalSong(currentSong, application)
            ) {
                legacyOriginalCoverUrl ?: CustomSongCoverStorage.persistOriginalCover(
                    context = application,
                    song = currentSong,
                    reference = originalCoverReference
                ) ?: existingOriginalCoverUrl
            } else {
                existingOriginalCoverUrl
            }
            val restoredBaseName = customName.normalizedManualMetadataValue()
                ?: currentSong.originalName
                ?: baseName
            val restoredBaseArtist = customArtist.normalizedManualMetadataValue()
                ?: currentSong.originalArtist
                ?: baseArtist
            val restoredBaseCoverUrl = if (restoreBaseCover) {
                resolveRestoredBaseCoverUrl(
                    originalCoverUrl = preservedOriginalCoverUrl ?: existingOriginalCoverUrl,
                    baseCoverUrl = baseCoverUrl,
                    currentCustomCoverUrl = currentSong.customCoverUrl
                        ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) }
                        ?: requestedCoverReference
                )
            } else {
                customCoverUrl.normalizedManualMetadataValue()
                    ?: preservedOriginalCoverUrl
            }
            val coverWriteReference = resolveLocalCoverWriteReference(
                restoreBaseCover = restoreBaseCover,
                requestedCoverReference = requestedCoverReference,
                restoredBaseCoverReference = restoredBaseCoverUrl
            )

            val nextBaseName = if (restoreBaseName) restoredBaseName else baseName
            val nextBaseArtist = if (restoreBaseArtist) restoredBaseArtist else baseArtist
            val nextBaseCoverUrl = if (restoreBaseCover) restoredBaseCoverUrl else baseCoverUrl
            val originalName = currentSong.originalName ?: nextBaseName
            val originalArtist = currentSong.originalArtist ?: nextBaseArtist
            val originalCoverUrl = preservedOriginalCoverUrl ?: nextBaseCoverUrl

            val normalizedCustomName = if (restoreBaseName) {
                null
            } else {
                normalizeCustomMetadataValue(
                    desiredValue = customName,
                    baseValue = baseName
                )
            }
            val normalizedCustomArtist = if (restoreBaseArtist) {
                null
            } else {
                normalizeCustomMetadataValue(
                    desiredValue = customArtist,
                    baseValue = baseArtist
                )
            }
            val normalizedCustomCoverUrl = if (restoreBaseCover) {
                null
            } else {
                normalizeCustomMetadataValue(
                    desiredValue = requestedCoverReference,
                    baseValue = baseCoverUrl
                )
            }

            val updatedSong = currentSong.copy(
                name = nextBaseName,
                artist = nextBaseArtist,
                coverUrl = nextBaseCoverUrl,
                customName = normalizedCustomName,
                customArtist = normalizedCustomArtist,
                customCoverUrl = normalizedCustomCoverUrl,
                originalName = originalName,
                originalArtist = originalArtist,
                originalCoverUrl = originalCoverUrl,
                matchedLyricSource = if (clearMatchedMetadata) null else currentSong.matchedLyricSource,
                matchedSongId = if (clearMatchedMetadata) null else currentSong.matchedSongId
            )

            if (shouldSkipSongMetadataMutation(currentSong, updatedSong, writeLyrics)) {
                NPLogger.d("PlayerManager", "skip unchanged song metadata mutation")
                return@runSongMetadataMutation true
            }

            updateSongInAllPlaces(
                originalSong = originalSong,
                updatedSong = updatedSong,
                triggerSync = true,
                syncDownloadedMetadata = !writeLocalMetadata,
                fastLocalUsageSync = true,
                deferPersistence = true
            )

            if (
                resolveEditableMetadataWriteMode(writeLocalMetadata) ==
                    EditableMetadataWriteMode.BACKGROUND_LOCAL_WRITE
            ) {
                scheduleLocalEditableMetadataWrite(
                    song = updatedSong,
                    coverReference = coverWriteReference,
                    writeCover = coverChanged,
                    writeLyrics = writeLyrics
                )
            }
            true
        }

private fun String?.normalizedManualMetadataValue(): String? {
    return this?.trim()?.takeIf { it.isNotBlank() }
}

internal fun PlayerManager.hydrateSongMetadataImpl(originalSong: SongItem, updatedSong: SongItem) {
    ioScope.launch {
        runSongMetadataMutation {
            NPLogger.d(
                "NERI-PlayerManager",
                "hydrateSongMetadata: original=${originalSong.name}/${originalSong.id}, updated=${updatedSong.name}/${updatedSong.id}, stack=[${debugStackHint()}]"
            )
            updateSongInAllPlaces(
                originalSong = originalSong,
                updatedSong = updatedSong,
                triggerSync = false
            )
        }
    }
}

internal suspend fun PlayerManager.updateUserLyricOffsetImpl(
    songToUpdate: SongItem,
    newOffset: Long
) = runSongMetadataMutation {
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
        setCurrentSongForPlayback(_currentSongFlow.value?.copy(userLyricOffsetMs = newOffset))
    }

    val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(songToUpdate) }
    if (latestSong != null) {
        runLocalPlaylistMutationSafely("updateUserLyricOffset") {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(
                    originalSong = songToUpdate,
                    newSongInfo = latestSong,
                    triggerSync = true
                )
            }
        }
    }

    persistState()
}

internal suspend fun PlayerManager.rebaseUserLyricOffsetsForSourceImpl(
    targetSource: MusicPlatform,
    previousDefaultOffsetMs: Long,
    newDefaultOffsetMs: Long
) = runSongMetadataMutation {
    if (previousDefaultOffsetMs == newDefaultOffsetMs) {
        return@runSongMetadataMutation
    }
    NPLogger.d(
        "NERI-PlayerManager",
        "rebaseUserLyricOffsetsForSource: source=$targetSource old=$previousDefaultOffsetMs new=$newDefaultOffsetMs"
    )
    var queueChanged = false
    val rebasedPlaylist = currentPlaylist.map { song ->
        if (
            !shouldRebaseLyricOffsetForSource(
                lyricSource = song.matchedLyricSource,
                targetSource = targetSource,
                userOffsetMs = song.userLyricOffsetMs
            )
        ) {
            return@map song
        }
        queueChanged = true
        song.copy(
            userLyricOffsetMs = rebaseLyricUserOffsetMs(
                userOffsetMs = song.userLyricOffsetMs,
                previousDefaultOffsetMs = previousDefaultOffsetMs,
                newDefaultOffsetMs = newDefaultOffsetMs
            )
        )
    }
    if (queueChanged) {
        currentPlaylist = rebasedPlaylist
        _currentQueueFlow.value = rebasedPlaylist
    }

    val currentSong = _currentSongFlow.value
    val rebasedCurrentSong = currentSong
        ?.takeIf {
            shouldRebaseLyricOffsetForSource(
                lyricSource = it.matchedLyricSource,
                targetSource = targetSource,
                userOffsetMs = it.userLyricOffsetMs
            )
        }
        ?.let { song ->
            song.copy(
                userLyricOffsetMs = rebaseLyricUserOffsetMs(
                    userOffsetMs = song.userLyricOffsetMs,
                    previousDefaultOffsetMs = previousDefaultOffsetMs,
                    newDefaultOffsetMs = newDefaultOffsetMs
                )
            )
        }
    if (rebasedCurrentSong != null) {
        setCurrentSongForPlayback(rebasedCurrentSong)
    }

    val localUpdateSucceeded = runLocalPlaylistMutationSafely("rebaseLyricOffsetsForSource") {
        withContext(Dispatchers.IO) {
            localRepo.rebaseLyricOffsetsForSource(
                targetSource = targetSource,
                previousDefaultOffsetMs = previousDefaultOffsetMs,
                newDefaultOffsetMs = newDefaultOffsetMs
            )
        }
    }
    if (localUpdateSucceeded.isSuccess) {
        AppContainer.playlistUsageRepo.syncLocalEntries(
            playlists = localRepo.playlists.value,
            localFilesCoverCandidates = downloadedLocalFilesCoverCandidates()
        )
    }

    if (queueChanged || rebasedCurrentSong != null) {
        persistState()
    }
}

internal suspend fun PlayerManager.updateSongLyricsImpl(
    songToUpdate: SongItem,
    newLyrics: String?
) = runSongMetadataMutation {
    NPLogger.d(
        "NERI-PlayerManager",
        "updateSongLyrics: song=${songToUpdate.name}, id=${songToUpdate.id}, lyricLength=${newLyrics?.length ?: 0}"
    )
    val queueIndex = queueIndexOf(songToUpdate)
    if (queueIndex != -1) {
        val updatedSong = currentPlaylist[queueIndex].withUpdatedLyricsPreservingOriginal(
            newLyrics = newLyrics
        )
        val newList = currentPlaylist.toMutableList()
        newList[queueIndex] = updatedSong
        currentPlaylist = newList
        _currentQueueFlow.value = currentPlaylist
    }

    if (isCurrentSong(songToUpdate)) {
        setCurrentSongForPlayback(
            _currentSongFlow.value?.withUpdatedLyricsPreservingOriginal(
                newLyrics = newLyrics
            )
        )
    }

    val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
    if (latestSong != null) {
        runLocalPlaylistMutationSafely("updateSongLyrics") {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(
                    originalSong = songToUpdate,
                    newSongInfo = latestSong,
                    triggerSync = true
                )
            }
        }
        GlobalDownloadManager.syncDownloadedSongMetadataNow(latestSong)
        AppContainer.playHistoryRepo.updateSongMetadata(songToUpdate, latestSong)
        AppContainer.playlistUsageRepo.syncLocalEntries(
            playlists = localRepo.playlists.value,
            localFilesCoverCandidates = downloadedLocalFilesCoverCandidates(),
            resolveLocalMetadataFallback = false
        )
    }

    persistState()
}

internal suspend fun PlayerManager.updateSongTranslatedLyricsImpl(
    songToUpdate: SongItem,
    newTranslatedLyrics: String?
) = runSongMetadataMutation {
    NPLogger.d(
        "NERI-PlayerManager",
        "updateSongTranslatedLyrics: song=${songToUpdate.name}, id=${songToUpdate.id}, translatedLength=${newTranslatedLyrics?.length ?: 0}"
    )
    val queueIndex = queueIndexOf(songToUpdate)
    if (queueIndex != -1) {
        val updatedSong = currentPlaylist[queueIndex].withUpdatedLyricsPreservingOriginal(
            newTranslatedLyric = newTranslatedLyrics
        )
        val newList = currentPlaylist.toMutableList()
        newList[queueIndex] = updatedSong
        currentPlaylist = newList
        _currentQueueFlow.value = currentPlaylist
    }

    if (isCurrentSong(songToUpdate)) {
        setCurrentSongForPlayback(
            _currentSongFlow.value?.withUpdatedLyricsPreservingOriginal(
                newTranslatedLyric = newTranslatedLyrics
            )
        )
    }

    val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
    if (latestSong != null) {
        runLocalPlaylistMutationSafely("updateSongTranslatedLyrics") {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(
                    originalSong = songToUpdate,
                    newSongInfo = latestSong,
                    triggerSync = true
                )
            }
        }
        GlobalDownloadManager.syncDownloadedSongMetadataNow(latestSong)
        AppContainer.playHistoryRepo.updateSongMetadata(songToUpdate, latestSong)
        AppContainer.playlistUsageRepo.syncLocalEntries(
            playlists = localRepo.playlists.value,
            localFilesCoverCandidates = downloadedLocalFilesCoverCandidates()
        )
    }

    persistState()
}

internal suspend fun PlayerManager.updateSongLyricsAndTranslationImpl(
    songToUpdate: SongItem,
    newLyrics: String?,
    newTranslatedLyrics: String?,
    newRomanizedLyrics: String? = null,
    writeLocalMetadata: Boolean = false
) : Boolean = runSongMetadataMutation {
    val queueIndex = queueIndexOf(songToUpdate)
    val currentQueueSong = currentPlaylist.getOrNull(queueIndex)
        ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(songToUpdate) }
        ?: songToUpdate
    val effectiveLyrics = newLyrics ?: currentQueueSong.matchedLyric
    val effectiveTranslatedLyrics = newTranslatedLyrics
        ?: currentQueueSong.matchedTranslatedLyric
    val effectiveRomanizedLyrics = newRomanizedLyrics
        ?: currentQueueSong.matchedRomanizedLyric

    val lyricsChanged = effectiveLyrics != currentQueueSong.matchedLyric ||
        effectiveTranslatedLyrics != currentQueueSong.matchedTranslatedLyric ||
        effectiveRomanizedLyrics != currentQueueSong.matchedRomanizedLyric
    if (!lyricsChanged && !writeLocalMetadata) {
        NPLogger.d("PlayerManager", "skip unchanged lyric sidecar mutation")
        return@runSongMetadataMutation true
    }

    val updatedSong = currentQueueSong.withUpdatedLyricsPreservingOriginal(
        newLyrics = effectiveLyrics,
        newTranslatedLyric = effectiveTranslatedLyrics,
        newRomanizedLyric = effectiveRomanizedLyrics
    )
    val isLocalSong = LocalSongSupport.isLocalSong(updatedSong, application)
    val sidecarsWritten = if (isLocalSong && !writeLocalMetadata) {
        val sidecarSong = resolveLocalSidecarWriteSong(updatedSong)
        runCatching {
            LocalMediaSupport.writeLocalLyricsSidecars(
                context = application,
                song = sidecarSong
            )
        }.getOrElse { error ->
            NPLogger.w(
                "PlayerManager",
                "本地歌词侧载写入失败: ${error.message}"
            )
            false
        }
    } else {
        true
    }
    if (!sidecarsWritten) {
        NPLogger.w(
            "PlayerManager",
            "本地歌词写入未完成: song=${updatedSong.name}, " +
                "sidecars=$sidecarsWritten"
        )
        return@runSongMetadataMutation false
    }

    if (queueIndex != -1) {
        val newList = currentPlaylist.toMutableList()
        newList[queueIndex] = updatedSong
        currentPlaylist = newList
        _currentQueueFlow.value = currentPlaylist
        NPLogger.d("PlayerManager", "Queue song lyrics updated")
    }
    if (isCurrentSong(songToUpdate)) {
        setCurrentSongForPlayback(updatedSong)
    }

    val latestSong = currentPlaylist.firstOrNull { it.sameIdentityAs(songToUpdate) }
        ?: updatedSong.takeIf { it.sameIdentityAs(songToUpdate) }
    if (latestSong == null) {
        NPLogger.w("PlayerManager", "歌词更新后未找到最新歌曲副本")
        return@runSongMetadataMutation false
    }

    // queue/current-song state is published immediately; catalog fanout stays off the editor's critical path
    ioScope.launch {
        runSongMetadataMutation {
            runCatching {
                runLocalPlaylistMutationSafely("updateSongLyricsAndTranslation") {
                    withContext(Dispatchers.IO) {
                        localRepo.updateSongMetadata(
                            originalSong = songToUpdate,
                            newSongInfo = latestSong,
                            triggerSync = true
                        )
                    }
                }
                if (!writeLocalMetadata || !isLocalSong) {
                    GlobalDownloadManager.syncDownloadedSongMetadata(latestSong)
                }
                AppContainer.playHistoryRepo.updateSongMetadata(songToUpdate, latestSong)
                AppContainer.playlistUsageRepo.syncLocalEntries(
                    playlists = localRepo.playlists.value,
                    localFilesCoverCandidates = downloadedLocalFilesCoverCandidates(),
                    resolveLocalMetadataFallback = false
                )
                persistState()
                NPLogger.d(
                    "PlayerManager",
                    "歌词更新已同步到本地仓库: id=${latestSong.id}, " +
                        "lyric=${latestSong.matchedLyric?.take(32)}, " +
                        "translated=${latestSong.matchedTranslatedLyric?.take(32)}"
                )
            }.onFailure { error ->
                NPLogger.e(
                    "PlayerManager",
                    "歌词更新后台同步失败: ${error.message}",
                    error
                )
            }
        }
    }
    if (
        isLocalSong &&
            resolveEditableMetadataWriteMode(writeLocalMetadata) ==
            EditableMetadataWriteMode.BACKGROUND_LOCAL_WRITE
    ) {
        scheduleLocalEditableMetadataWrite(
            song = latestSong,
            coverReference = latestSong.customCoverUrl,
            writeCover = false,
            writeLyrics = true
        )
    }
    NPLogger.d("PlayerManager", "updateSongLyricsAndTranslation completed")
    true
}

private suspend fun PlayerManager.updateSongInAllPlaces(
    originalSong: SongItem,
    updatedSong: SongItem,
    triggerSync: Boolean,
    syncDownloadedMetadata: Boolean = true,
    fastLocalUsageSync: Boolean = false,
    deferPersistence: Boolean = false
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
        setCurrentSongForPlayback(updatedSong)
        if (isBiliTrack(updatedSong) && !isListenTogetherActive()) {
            BiliVideoSkipPlaybackController.prepareActiveBiliTrackTarget(
                song = updatedSong,
                requestToken = playbackRequestToken,
                scope = ioScope
            )
        }
    }

    val persistMetadata: suspend () -> Unit = {
        runLocalPlaylistMutationSafely("updateSongInAllPlaces") {
            withContext(Dispatchers.IO) {
                localRepo.updateSongMetadata(
                    originalSong = originalSong,
                    newSongInfo = updatedSong,
                    triggerSync = triggerSync
                )
            }
        }
        if (syncDownloadedMetadata) {
            GlobalDownloadManager.syncDownloadedSongMetadata(updatedSong)
        }
        AppContainer.playHistoryRepo.updateSongMetadata(
            originalSong = originalSong,
            updatedSong = updatedSong,
            triggerSync = triggerSync
        )
        AppContainer.playlistUsageRepo.syncLocalEntries(
            playlists = localRepo.playlists.value,
            localFilesCoverCandidates = downloadedLocalFilesCoverCandidates(),
            resolveLocalMetadataFallback = !fastLocalUsageSync
        )
        persistState()
    }
    if (deferPersistence) {
        ioScope.launch {
            runSongMetadataMutation {
                runCatching { persistMetadata() }
                    .onFailure { error ->
                        NPLogger.e(
                            "PlayerManager",
                            "后台同步歌曲元数据失败: ${error.message}",
                            error
                        )
                    }
            }
        }
    } else {
        persistMetadata()
    }
}
