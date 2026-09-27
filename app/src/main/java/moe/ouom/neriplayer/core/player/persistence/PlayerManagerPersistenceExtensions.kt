@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import moe.ouom.neriplayer.core.download.metadata.RestorableMetadataClearPolicy
import moe.ouom.neriplayer.core.download.model.toPlaybackSongItem
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.metadata.applyManualSearchMetadata
import moe.ouom.neriplayer.core.player.metadata.normalizeCustomMetadataValue
import moe.ouom.neriplayer.core.player.metadata.PlayerLyricsProvider
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.settings.LyricSourcePreference
import moe.ouom.neriplayer.core.player.metadata.SongMetadataRequestCoordinator
import moe.ouom.neriplayer.core.player.metadata.hasUsableLyrics
import moe.ouom.neriplayer.core.player.metadata.LocalMetadataWritePlaybackAction
import moe.ouom.neriplayer.core.player.metadata.resolveLocalCoverWriteReference
import moe.ouom.neriplayer.core.player.metadata.resolveLocalMetadataWritePlaybackAction
import moe.ouom.neriplayer.core.player.metadata.resolveRestoredBaseCoverUrl
import moe.ouom.neriplayer.core.player.metadata.shouldAutoMatchExternalLyrics
import moe.ouom.neriplayer.core.player.metadata.shouldMaterializeRemoteLocalCover
import moe.ouom.neriplayer.core.player.metadata.shouldSkipSongMetadataMutation
import moe.ouom.neriplayer.core.player.metadata.shouldWriteLocalCoverMetadata
import moe.ouom.neriplayer.core.player.metadata.toBasicSongDetails
import moe.ouom.neriplayer.core.player.metadata.withUpdatedLyricsPreservingOriginal
import moe.ouom.neriplayer.core.player.model.PersistedState
import moe.ouom.neriplayer.core.player.model.RestoredPlaybackState
import moe.ouom.neriplayer.core.player.playback.BiliVideoSkipPlaybackController
import moe.ouom.neriplayer.core.player.playback.playAtIndex
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
import moe.ouom.neriplayer.data.settings.saturatingAddLyricOffsetMs
import moe.ouom.neriplayer.data.settings.shouldRebaseLyricOffsetForSource
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import java.io.File
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
    val commandSource: PlaybackCommandSource,
    val requestToken: Long,
    val resumePlayback: Boolean
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

internal fun shouldPersistLyricsSidecarsSynchronously(
    writeLocalMetadata: Boolean,
    persistLocalSidecars: Boolean
): Boolean {
    return persistLocalSidecars && !writeLocalMetadata
}

internal fun shouldSyncDownloadedMetadataAfterLyricsUpdate(
    writeLocalMetadata: Boolean,
    isLocalSong: Boolean,
    syncDownloadedMetadata: Boolean
): Boolean {
    return syncDownloadedMetadata && (isLocalSong || !writeLocalMetadata)
}

internal fun shouldSyncDownloadedMetadataAfterMetadataUpdate(
    writeLocalMetadata: Boolean,
    isLocalSong: Boolean,
    syncDownloadedMetadata: Boolean = true
): Boolean {
    return syncDownloadedMetadata && (isLocalSong || !writeLocalMetadata)
}

internal fun shouldSyncDownloadedMetadataAfterPlaybackHydration(): Boolean = false

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
    publishCurrentQueue(snapshot.playlist, snapshot.currentIndex)
    if (currentPlaylist.isEmpty()) {
        NPLogger.w(
            "NERI-PlayerManager",
            "restoreState: sanitized playlist became empty, originalSize=${snapshot.originalPlaylistSize}, persistedIndex=${snapshot.persistedIndex}"
        )
        setCurrentSongForPlayback(null)
        _currentMediaUrl.value = null
        _currentPlaybackAudioInfo.value = null
        _playbackPositionMs.value = 0L
        currentMediaUrlResolvedAtMs = 0L
        shuffleRestorePlaylistReference = null
        shuffleRestoreCurrentIndex = -1
        clearRestoredPlayback()
        updateResumePlaybackRequested(false)
        return
    }

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

    setRestoredPlayback(snapshot.resumePositionMs, snapshot.shouldResumePlayback)
    updateResumePlaybackRequested(false)
    _playbackPositionMs.value = restoredResumePositionMs
    currentMediaUrlResolvedAtMs = 0L
    statePersistenceWriter.invalidate()
    lastStatePersistAtMs = SystemClock.elapsedRealtime()
    NPLogger.d(
        "NERI-PlayerManager",
        "restoreState completed: queueSize=${currentPlaylist.size}, currentIndex=$currentIndex, restoredResumePositionMs=$restoredResumePositionMs, restoredShouldResumePlayback=$restoredShouldResumePlayback, shuffle=${_shuffleModeFlow.value}, repeatMode=$repeatModeSetting, currentSong=${_currentSongFlow.value?.name}, mediaUrlPresent=${!_currentMediaUrl.value.isNullOrBlank()}"
    )
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

internal suspend fun PlayerManager.getTranslatedLyricsImpl(
    song: SongItem,
    skipPreferredSource: Boolean = false
): List<LyricEntry> {
    return PlayerLyricsProvider.getTranslatedLyrics(
        song = song,
        application = application,
        neteaseClient = neteaseClient,
        neteaseLyricsCache = neteaseLyricsCache,
        editableLyricsMatcher = AppContainer.editableLyricsMatcher,
        preferWordTimedLyrics = preferWordTimedLyrics,
        defaultLyricSource = if (skipPreferredSource) {
            LyricSourcePreference.Automatic
        } else {
            defaultLyricSource
        },
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
        editableLyricsMatcher = AppContainer.editableLyricsMatcher,
        preferWordTimedLyrics = preferWordTimedLyrics,
        defaultLyricSource = defaultLyricSource,
        biliSourceTag = BILI_SOURCE_TAG
    )
}

internal suspend fun PlayerManager.getLyricsImpl(
    song: SongItem,
    skipPreferredSource: Boolean = false
): List<LyricEntry> {
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
        preferWordTimedLyrics = preferWordTimedLyrics,
        defaultLyricSource = if (skipPreferredSource) {
            LyricSourcePreference.Automatic
        } else {
            defaultLyricSource
        },
        ytMusicLyricsCache = ytMusicLyricsCache,
        biliSourceTag = BILI_SOURCE_TAG
    )
}

internal suspend fun PlayerManager.getPreferredLyricSourceResultImpl(
    song: SongItem,
    preference: LyricSourcePreference
): PreferredLyricSourceResult? = PlayerLyricsProvider.tryGetPreferredLyricSourceResult(
    song = song,
    preference = preference,
    preferWordTimed = preferWordTimedLyrics,
    editableLyricsMatcher = AppContainer.editableLyricsMatcher,
    neteaseClient = neteaseClient,
    neteaseLyricsCache = neteaseLyricsCache
)

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
    val restored = restoredPlaybackSnapshot()
    if (restored !is RestoredPlaybackState.ResumePending) {
        NPLogger.d("NERI-PlayerManager", "resumeRestoredPlaybackIfNeeded(): skipped, restoredShouldResumePlayback=false")
        return null
    }
    when (
        resolveListenTogetherRestoredPlaybackAction(
            restoredPlaybackRequested = true,
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
            if (!consumeRestoredPlayback(restored)) return null
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
    val resumePositionMs = restored.positionMs
    NPLogger.d(
        "NERI-PlayerManager",
        "resumeRestoredPlaybackIfNeeded(): resumeIndex=$resumeIndex, positionMs=$resumePositionMs, song=${resumeSong.name}, stack=[${debugStackHint()}]"
    )
    if (!consumeRestoredPlayback(restored)) return null
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
    suppressRestoredAutoResume()
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
    scheduleStatePersist(positionMs = positionMs, shouldResumePlayback = false, debounceMs = 0L)
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
                    val selectedCoverUrl = newDetails.coverUrl?.let { coverUrl ->
                        if (
                            isLocalSong(latestOriginalSong) &&
                            CustomSongCoverStorage.isRemoteReference(coverUrl)
                        ) {
                            CustomSongCoverStorage.persistManuallySelectedRemoteCover(
                                context = application,
                                sourceUrl = coverUrl
                            ) ?: coverUrl
                        } else {
                            coverUrl
                        }
                    }
                    applyManualSearchMetadata(
                        originalSong = latestOriginalSong,
                        songName = newDetails.songName,
                        singer = newDetails.singer,
                        coverUrl = selectedCoverUrl,
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
        isCurrentSong = isCurrentSong(targetSong),
        hasActiveMedia = player.mediaItemCount > 0 ||
            pendingMediaLoadActive || playJob?.isActive == true,
        shouldResumePlayback = player.playWhenReady || player.isPlaying ||
            resumePlaybackRequested
    )
    if (action == LocalMetadataWritePlaybackAction.NONE) {
        return@withContext null
    }

    val snapshot = LocalMetadataWritePlaybackSnapshot(
        song = _currentSongFlow.value ?: targetSong,
        index = currentIndex,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        commandSource = activePlaybackCommandSource,
        requestToken = playbackRequestToken,
        resumePlayback = action == LocalMetadataWritePlaybackAction.RELEASE_AND_RESUME
    )
    stopPlaybackPreservingQueue(clearMediaUrl = true)
    snapshot.copy(requestToken = playbackRequestToken)
}

private suspend fun PlayerManager.resumePlaybackAfterLocalMetadataWrite(
    snapshot: LocalMetadataWritePlaybackSnapshot?
) {
    if (snapshot == null) return
    withContext(NonCancellable + Dispatchers.Main.immediate) {
        val resumeIndex = currentPlaylist.indexOfFirst { it.sameIdentityAs(snapshot.song) }
        if (
            !isPlayerInitialized() ||
                playbackRequestToken != snapshot.requestToken ||
                resumeIndex != currentIndex ||
                !isCurrentSong(snapshot.song)
        ) {
            return@withContext
        }
        playAtIndex(
            index = resumeIndex,
            resumePositionMs = snapshot.positionMs,
            commandSource = snapshot.commandSource,
            startPaused = !snapshot.resumePlayback,
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
        ?: return LocalMediaMetadataWriteOutcome.NOT_WRITABLE
    val writableSong = song.copy(
        mediaUri = writableReference,
        localFilePath = if (writableReference.startsWith('/')) writableReference else null,
        localFileName = song.localFileName
            ?: writableReference.substringAfterLast('/').takeIf(String::isNotBlank)
    )
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
        return AudioDownloadManager.resolvePermittedLocalPlaybackUri(
            context = application,
            song = song,
            rawLocalReference = directReference
        )
    }
    return AudioDownloadManager.getLocalPlaybackUri(application, song)
}

private suspend fun PlayerManager.resolveLocalSidecarWriteSong(song: SongItem): SongItem? {
    val playbackReference = resolveLocalMetadataWriteReference(song) ?: return null
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
        LocalMediaMetadataWriteOutcome.SIDECAR_ONLY -> {
            R.string.local_song_metadata_write_sidecar_only
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
    writeLyrics: Boolean = false,
    persistManualRemoteCover: Boolean = false,
    restoreBaseLyrics: Boolean = false
) : Boolean = runSongMetadataMutation {
            NPLogger.d(
                "PlayerManager",
                "updateSongCustomInfo: id=${originalSong.id}, album='${originalSong.album}', customName=${customName?.take(32)}, customArtist=${customArtist?.take(32)}, customCoverUrl=${customCoverUrl?.take(64)}, restoreBase=[$restoreBaseName,$restoreBaseArtist,$restoreBaseCover], clearMatched=$clearMatchedMetadata, stack=[${debugStackHint()}]"
            )

            val currentSong = currentPlaylist.firstOrNull { it.sameIdentityAs(originalSong) }
                ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(originalSong) }
                ?: originalSong
            val isLocalSong = LocalSongSupport.isLocalSong(currentSong, application)

            val baseName = currentSong.name
            val baseArtist = currentSong.artist
            val baseCoverUrl = currentSong.coverUrl
                ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) }
            val existingOriginalCoverUrl = currentSong.originalCoverUrl
                ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) }
            val requestedCoverInput = customCoverUrl
                .normalizedManualMetadataValue()
                ?.takeUnless { CustomSongCoverStorage.isDirectoryReference(it) }
            val requestedCoverReference = requestedCoverInput?.let { reference ->
                if (
                    shouldMaterializeRemoteLocalCover(
                        isLocalSong = isLocalSong,
                        requestedCoverReference = reference,
                        restoreBaseCover = restoreBaseCover,
                        persistManualRemoteCover = persistManualRemoteCover
                    )
                ) {
                    CustomSongCoverStorage.persistManuallySelectedRemoteCover(
                        context = application,
                        sourceUrl = reference
                    ) ?: return@runSongMetadataMutation false
                } else {
                    reference
                }
            }
            // 侧载封面是本地歌曲的应用内持久化格式, 不应依赖是否回写嵌入元信息
            val coverChanged = shouldWriteLocalCoverMetadata(
                restoreBaseCover = restoreBaseCover,
                nextCustomCover = requestedCoverReference,
                previousCustomCover = currentSong.customCoverUrl
            )
            val lightweightOriginalCoverUrl = existingOriginalCoverUrl
                ?.takeIf { it != currentSong.customCoverUrl }
                ?: baseCoverUrl?.takeIf { it != currentSong.customCoverUrl }
            val shouldResolveLocalCover = LocalSongSupport.isLocalSong(currentSong, application) &&
                (coverChanged || restoreBaseCover)
            val discoveredLocalCoverUrl = if (shouldResolveLocalCover) {
                sequenceOf(
                    // 下载歌曲的 Covers 侧载是本地恢复的第一来源, 不要让远程标签覆盖它
                    AudioDownloadManager.peekLocalCoverUri(currentSong),
                    LocalMediaSupport.resolveNearbyCoverUri(application, currentSong),
                    LocalMediaSupport.peekCachedEmbeddedCoverUri(application, currentSong),
                    LocalMediaSupport.resolveCoverUri(application, currentSong)
                ).firstOrNull { reference ->
                    !reference.isNullOrBlank() &&
                        !CustomSongCoverStorage.isRemoteReference(reference)
                }
            } else {
                null
            }
            val legacyOriginalCoverUrl = if (
                coverChanged &&
                    LocalSongSupport.isLocalSong(currentSong, application)
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
                if (writeLocalMetadata) {
                    discoveredLocalCoverUrl ?: lightweightOriginalCoverUrl
                } else {
                    existingOriginalCoverUrl
                        ?.takeIf { reference ->
                            LocalSongSupport.isLocalMediaUri(reference) &&
                                reference != currentSong.customCoverUrl
                        }
                        ?: legacyOriginalCoverUrl
                        ?: discoveredLocalCoverUrl
                        ?: lightweightOriginalCoverUrl
                }
            } else {
                null
            }
            val preservedOriginalCoverUrl = if (
                coverChanged && LocalSongSupport.isLocalSong(currentSong, application)
            ) {
                if (restoreBaseCover) {
                    existingOriginalCoverUrl
                } else {
                    legacyOriginalCoverUrl ?: CustomSongCoverStorage.persistOriginalCover(
                        context = application,
                        song = currentSong,
                        reference = originalCoverReference
                    ) ?: existingOriginalCoverUrl
                }
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
                        ?: requestedCoverReference,
                    preferredLocalCoverUrl = discoveredLocalCoverUrl
                        ?: requestedCoverReference
                            ?.takeUnless(CustomSongCoverStorage::isRemoteReference),
                    requestedRestoreCoverUrl = requestedCoverReference,
                    localOnly = isLocalSong
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
            // null is a deliberate clear when the user restores or removes a cover
            val shouldWriteCoverToAudio = coverChanged

            val nextBaseName = if (restoreBaseName) restoredBaseName else baseName
            val nextBaseArtist = if (restoreBaseArtist) restoredBaseArtist else baseArtist
            val nextBaseCoverUrl = if (restoreBaseCover) restoredBaseCoverUrl else baseCoverUrl
            val originalName = currentSong.originalName ?: nextBaseName
            val originalArtist = currentSong.originalArtist ?: nextBaseArtist
            val originalCoverUrl = if (restoreBaseCover && isLocalSong) {
                (nextBaseCoverUrl ?: preservedOriginalCoverUrl)
                    ?.takeUnless(CustomSongCoverStorage::isRemoteReference)
            } else {
                preservedOriginalCoverUrl ?: nextBaseCoverUrl
            }

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

            if (writeLocalMetadata && LocalSongSupport.isLocalSong(currentSong, application)) {
                val localWriteOutcome = writeLocalEditableMetadata(
                    song = updatedSong,
                    coverReference = coverWriteReference,
                    writeCover = shouldWriteCoverToAudio,
                    writeLyrics = writeLyrics
                )
                if (localWriteOutcome != LocalMediaMetadataWriteOutcome.SUCCESS) {
                    showLocalEditableMetadataWriteFeedback(
                        outcome = localWriteOutcome,
                        downloadSyncOutcome =
                            GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED
                    )
                    return@runSongMetadataMutation false
                }
            } else if (LocalSongSupport.isLocalSong(currentSong, application)) {
                val sidecarSong = resolveLocalSidecarWriteSong(updatedSong)
                if (sidecarSong == null) {
                    showLocalEditableMetadataWriteFeedback(
                        outcome = LocalMediaMetadataWriteOutcome.NOT_WRITABLE,
                        downloadSyncOutcome =
                            GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED
                    )
                    return@runSongMetadataMutation false
                }
                val coverSidecarWritten = if (shouldWriteCoverToAudio) {
                    LocalMediaSupport.writeLocalCoverSidecar(
                        context = application,
                        song = sidecarSong,
                        coverReference = coverWriteReference,
                        writeCover = true,
                        stableIdentityKey = sidecarSong.stableKey()
                    )
                } else {
                    true
                }
                val metadataSidecarWritten = LocalMediaSupport.writeLocalMetadataSidecar(
                    context = application,
                    song = sidecarSong,
                    writeLyrics = writeLyrics,
                    coverReference = coverWriteReference.takeIf { shouldWriteCoverToAudio },
                    clearCoverReference = shouldWriteCoverToAudio &&
                        coverWriteReference.isNullOrBlank()
                )
                if (!coverSidecarWritten || !metadataSidecarWritten) {
                    NPLogger.w(
                        "PlayerManager",
                        "本地侧载元数据写入未完成: cover=$coverSidecarWritten, " +
                            "metadata=$metadataSidecarWritten"
                    )
                    showLocalEditableMetadataWriteFeedback(
                        outcome = LocalMediaMetadataWriteOutcome.FAILED,
                        downloadSyncOutcome =
                            GlobalDownloadManager.DownloadedSongMetadataSyncOutcome.NOT_DOWNLOADED
                    )
                    return@runSongMetadataMutation false
                }
            }

            updateSongInAllPlaces(
                originalSong = originalSong,
                updatedSong = updatedSong,
                triggerSync = true,
                syncDownloadedMetadata = shouldSyncDownloadedMetadataAfterMetadataUpdate(
                    writeLocalMetadata = writeLocalMetadata,
                    isLocalSong = isLocalSong
                ),
                fastLocalUsageSync = true,
                clearRestorableOverrides = RestorableMetadataClearPolicy(
                    title = restoreBaseName,
                    artist = restoreBaseArtist,
                    cover = restoreBaseCover,
                    lyrics = restoreBaseLyrics || clearMatchedMetadata
                )
            )
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
                triggerSync = false,
                syncDownloadedMetadata = shouldSyncDownloadedMetadataAfterPlaybackHydration()
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
    updateQueuedSong(songToUpdate) { it.copy(userLyricOffsetMs = newOffset) }

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
        GlobalDownloadManager.syncDownloadedSongMetadataNow(
            song = latestSong,
            clearRestorableOverrides = RestorableMetadataClearPolicy(
                userLyricOffset = true
            )
        )
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
    val queueChanged = updateCurrentQueueSongs { playlist ->
        var changed = false
        val rebasedPlaylist = playlist.map { song ->
            if (
                !shouldRebaseLyricOffsetForSource(
                    lyricSource = song.matchedLyricSource,
                    targetSource = targetSource,
                    userOffsetMs = song.userLyricOffsetMs
                )
            ) {
                return@map song
            }
            changed = true
            song.copy(
                userLyricOffsetMs = rebaseLyricUserOffsetMs(
                    userOffsetMs = song.userLyricOffsetMs,
                    previousDefaultOffsetMs = previousDefaultOffsetMs,
                    newDefaultOffsetMs = newDefaultOffsetMs
                )
            )
        }
        rebasedPlaylist.takeIf { changed }
    } != null

    val currentSong = _currentSongFlow.value
    val currentSongForRebase = currentSong
        ?.takeIf {
            shouldRebaseLyricOffsetForSource(
                lyricSource = it.matchedLyricSource,
                targetSource = targetSource,
                userOffsetMs = it.userLyricOffsetMs
            )
        }
    val rebasedCurrentSong = currentSongForRebase
        ?.let { song ->
            song.copy(
                userLyricOffsetMs = rebaseLyricUserOffsetMs(
                    userOffsetMs = song.userLyricOffsetMs,
                    previousDefaultOffsetMs = previousDefaultOffsetMs,
                    newDefaultOffsetMs = newDefaultOffsetMs
                )
            )
        }
    if (currentSongForRebase != null && rebasedCurrentSong != null) {
        val hasPendingLyriconUpdate = hasPendingLyriconUpdate()
        setCurrentSongForPlayback(rebasedCurrentSong, syncLyricon = false)
        if (hasPendingLyriconUpdate) {
            syncLyriconSong(
                song = rebasedCurrentSong,
                lyricOffsetOverrideMs = saturatingAddLyricOffsetMs(
                    value = previousDefaultOffsetMs,
                    delta = currentSongForRebase.userLyricOffsetMs
                )
            )
        }
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
    updateQueuedSong(songToUpdate) { current ->
        current.withUpdatedLyricsPreservingOriginal(
            newLyrics = newLyrics
        )
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
    updateQueuedSong(songToUpdate) { current ->
        current.withUpdatedLyricsPreservingOriginal(
            newTranslatedLyric = newTranslatedLyrics
        )
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
    writeLocalMetadata: Boolean = false,
    persistLocalSidecars: Boolean = true,
    syncDownloadedMetadata: Boolean = true
) : Boolean = runSongMetadataMutation {
    val currentQueueSong = currentQueueSnapshot().playlist
        .firstOrNull { it.sameIdentityAs(songToUpdate) }
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
        val localSidecarRepairRequired =
            LocalSongSupport.isLocalSong(currentQueueSong, application) &&
            persistLocalSidecars &&
            LocalMediaSupport.needsLyricSidecarRepair(
                context = application,
                song = currentQueueSong
            )
        if (!localSidecarRepairRequired) {
            NPLogger.d("PlayerManager", "skip unchanged lyric sidecar mutation")
            return@runSongMetadataMutation true
        }
        NPLogger.d("PlayerManager", "rebuild missing lyric sidecars")
    }

    val updatedSong = currentQueueSong.withUpdatedLyricsPreservingOriginal(
        newLyrics = effectiveLyrics,
        newTranslatedLyric = effectiveTranslatedLyrics,
        newRomanizedLyric = effectiveRomanizedLyrics
    )
    val isLocalSong = LocalSongSupport.isLocalSong(updatedSong, application)
    val sidecarsWritten = if (isLocalSong && writeLocalMetadata) {
        val sidecarSong = resolveLocalSidecarWriteSong(updatedSong)
        if (sidecarSong == null) {
            false
        } else {
            runCatching {
                writeLocalEditableMetadata(
                    song = sidecarSong,
                    coverReference = sidecarSong.customCoverUrl,
                    writeCover = false,
                    writeLyrics = true
                ) == LocalMediaMetadataWriteOutcome.SUCCESS
            }.getOrElse { error ->
                NPLogger.w(
                    "PlayerManager",
                    "本地歌词与嵌入元数据同步写入失败: ${error.message}"
                )
                false
            }
        }
    } else if (
        isLocalSong && shouldPersistLyricsSidecarsSynchronously(
            writeLocalMetadata = writeLocalMetadata,
            persistLocalSidecars = persistLocalSidecars
        )
    ) {
        val sidecarSong = resolveLocalSidecarWriteSong(updatedSong)
        if (sidecarSong == null) {
            false
        } else {
            try {
                val lyricsWritten = LocalMediaSupport.writeLocalLyricsSidecars(
                    context = application,
                    song = sidecarSong
                )
                lyricsWritten && LocalMediaSupport.writeLocalMetadataSidecar(
                    context = application,
                    song = sidecarSong,
                    writeLyrics = true
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(
                    "PlayerManager",
                    "本地歌词侧载写入失败: ${error.message}"
                )
                false
            }
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

    val updatedQueueSong = updateQueuedSong(songToUpdate) { current ->
        current.withUpdatedLyricsPreservingOriginal(
            newLyrics = effectiveLyrics,
            newTranslatedLyric = effectiveTranslatedLyrics,
            newRomanizedLyric = effectiveRomanizedLyrics
        )
    }
    if (updatedQueueSong != null) {
        NPLogger.d("PlayerManager", "Queue song lyrics updated")
    }
    val activeSong = _currentSongFlow.value?.takeIf { it.sameIdentityAs(songToUpdate) }
    if (activeSong != null) {
        setCurrentSongForPlayback(
            activeSong.withUpdatedLyricsPreservingOriginal(
                newLyrics = effectiveLyrics,
                newTranslatedLyric = effectiveTranslatedLyrics,
                newRomanizedLyric = effectiveRomanizedLyrics
            )
        )
    }

    val latestSong = updatedQueueSong
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
                if (
                    shouldSyncDownloadedMetadataAfterLyricsUpdate(
                        writeLocalMetadata = writeLocalMetadata,
                        isLocalSong = isLocalSong,
                        syncDownloadedMetadata = syncDownloadedMetadata
                    )
                ) {
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
    NPLogger.d("PlayerManager", "updateSongLyricsAndTranslation completed")
    true
}

private suspend fun PlayerManager.updateSongInAllPlaces(
    originalSong: SongItem,
    updatedSong: SongItem,
    triggerSync: Boolean,
    syncDownloadedMetadata: Boolean = true,
    fastLocalUsageSync: Boolean = false,
    clearRestorableOverrides: RestorableMetadataClearPolicy =
        RestorableMetadataClearPolicy()
) {
    NPLogger.d(
        "NERI-PlayerManager",
        "updateSongInAllPlaces: original=${originalSong.name}/${originalSong.id}, updated=${updatedSong.name}/${updatedSong.id}, hasCurrentMatch=${isCurrentSong(originalSong)}, stack=[${debugStackHint()}]"
    )
    updateQueuedSong(originalSong) { updatedSong }

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
            GlobalDownloadManager.syncDownloadedSongMetadata(
                song = updatedSong,
                clearRestorableOverrides = clearRestorableOverrides
            )
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
    persistMetadata()
}
