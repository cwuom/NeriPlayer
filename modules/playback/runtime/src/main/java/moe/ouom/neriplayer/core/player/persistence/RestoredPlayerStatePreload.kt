@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import androidx.media3.common.Player
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lifecycle.initializeImpl
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatMode
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import kotlin.coroutines.coroutineContext

sealed interface RestoredPlayerStatePreload {
    data class Completed(val snapshot: RestoredPlayerStateSnapshot?) : RestoredPlayerStatePreload

    data object NeedsFallback : RestoredPlayerStatePreload
}

fun PlayerManager.initializePreloadedRestoration(
    app: Application,
    startupPlaybackPreferences: PlaybackPreferenceSnapshot,
    restorationPreload: RestoredPlayerStatePreload
) = initializeImpl(
    app = app,
    maxCacheSize = startupPlaybackPreferences.maxCacheSizeBytes,
    startupPlaybackPreferences = startupPlaybackPreferences,
    restorationPreload = restorationPreload
)

internal data class PlaybackQueueLegacySnapshot(
    val state: PersistedState,
    val updatedAt: Long
)

private fun readLegacyPlaybackStateSnapshot(
    stateFile: File,
    playbackStateFile: File
): Result<PlaybackQueueLegacySnapshot?> {
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
        if (error is CancellationException) throw error
        NPLogger.w(
            "NERI-PlayerManager",
            "Failed to read legacy playback state: ${error.message}"
        )
    }
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
    keepPlaybackModeStateEnabled: Boolean,
    readLyricOverrides: (Set<String>) -> List<SyncSong>
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
            when (val persistedRepeatMode = data.repeatMode) {
                Player.REPEAT_MODE_ALL,
                Player.REPEAT_MODE_ONE,
                Player.REPEAT_MODE_OFF -> persistedRepeatMode
                else -> Player.REPEAT_MODE_OFF
            }
        } else {
            Player.REPEAT_MODE_OFF
        }

        val snapshot = RestoredPlayerStateSnapshot(
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
            persistedIndex = data.index,
            heartbeatMode = data.heartbeatMode ?: HeartbeatMode.OFF
        )
        val identities = playbackLyricIdentityKeys(snapshot.playlist.getOrNull(snapshot.currentIndex),
            snapshot.playlist, snapshot.shuffleRestorePlaylist)
        PlaybackLyricOverrideProjection(readLyricOverrides(identities)).restoredSnapshot(snapshot)
    }.onFailure { error ->
        if (error is CancellationException) throw error
        NPLogger.w("NERI-PlayerManager", "Failed to restore state: ${error.message}")
    }.getOrNull()
}

suspend fun preloadRestoredPlayerState(
    app: Application,
    keepLastPlaybackProgressEnabled: Boolean,
    keepPlaybackModeStateEnabled: Boolean
): RestoredPlayerStatePreload {
    return preloadRestoredPlayerState(
        app = app,
        keepLastPlaybackProgressEnabled = keepLastPlaybackProgressEnabled,
        keepPlaybackModeStateEnabled = keepPlaybackModeStateEnabled,
        startupStateFile = File(app.filesDir, "last_playlist.json"),
        startupPlaybackStateFile = File(app.filesDir, "last_playback_state.json"),
        createRoomStore = { PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(app.applicationContext)) }
    )
}

internal suspend fun preloadRestoredPlayerState(
    app: Application,
    keepLastPlaybackProgressEnabled: Boolean,
    keepPlaybackModeStateEnabled: Boolean,
    startupStateFile: File,
    startupPlaybackStateFile: File,
    createRoomStore: () -> PlaybackQueueRoomStore,
    readLyricOverrides: ((Set<String>) -> List<SyncSong>)? = null
): RestoredPlayerStatePreload {
    return withContext(Dispatchers.IO) {
        val readContext = coroutineContext
        readContext.ensureActive()
        val roomStore = createRoomStore()
        val roomRead = runCatching { roomStore.readSnapshotIfRoomPrimary() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                NPLogger.w(
                    "NERI-PlayerManager",
                    "restoreState: Room read failed, trying legacy JSON: ${error.message}"
                )
            }
        val roomPrimaryRead = runCatching { roomStore.isRoomPrimary() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                NPLogger.w(
                    "NERI-PlayerManager",
                    "restoreState: Room marker read failed: ${error.message}"
                )
            }
        val roomPrimary = roomPrimaryRead.getOrDefault(false)
        val roomSnapshot = roomRead.getOrNull()
        readContext.ensureActive()
        val legacyRead = if (!roomPrimary ||
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
            Result.success(null)
        }
        val legacySnapshot = legacyRead.getOrNull()
        readContext.ensureActive()
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
                    if (error is CancellationException) throw error
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "Failed to import legacy playback state into Room: ${error.message}"
                    )
                }
            }
        }
        if (data == null) {
            NPLogger.d("NERI-PlayerManager", "restoreState: no persisted playback state")
            // 只有两个旧文件均不存在时才能确认空，存在但不可读的状态仍保留恢复重试
            if (roomRead.isSuccess && roomPrimaryRead.isSuccess && legacyRead.isSuccess &&
                !startupStateFile.exists() && !startupPlaybackStateFile.exists()
            ) {
                RestoredPlayerStatePreload.Completed(null)
            } else {
                RestoredPlayerStatePreload.NeedsFallback
            }
        } else {
            val snapshot = buildRestoredStateSnapshot(
                app = app,
                data = data,
                keepLastPlaybackProgressEnabled = keepLastPlaybackProgressEnabled,
                keepPlaybackModeStateEnabled = keepPlaybackModeStateEnabled,
                readLyricOverrides = readLyricOverrides ?: { keys ->
                    SecureTokenStorage(app).getLyricOverridesForIdentityKeys(keys) { readContext.ensureActive() }
                }
            )
            snapshot?.let { RestoredPlayerStatePreload.Completed(it) } ?: RestoredPlayerStatePreload.NeedsFallback
        }
    }
}

internal fun loadRestoredStateSnapshot(
    app: Application,
    stateFile: File,
    playbackStateFile: File,
    keepLastPlaybackProgressEnabled: Boolean,
    keepPlaybackModeStateEnabled: Boolean,
    createRoomStore: () -> PlaybackQueueRoomStore = {
        PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(app.applicationContext))
    }
): RestoredPlayerStateSnapshot? {
    return runCatching {
        runBlocking(Dispatchers.IO) {
            val readContext = coroutineContext
            val roomStore = createRoomStore()
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
                ).getOrNull()
            } else {
                null
            }
            val selected = selectRestoredPlaybackState(
                roomPrimary = roomPrimary,
                roomSnapshot = roomSnapshot,
                legacySnapshot = legacySnapshot
            )
            val selectedLegacySnapshot = legacySnapshot
            val data = if (selected != null && selectedLegacySnapshot != null && selected === selectedLegacySnapshot.state) {
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
            if (data == null) return@runBlocking null
            buildRestoredStateSnapshot(
                app = app,
                data = data,
                keepLastPlaybackProgressEnabled = keepLastPlaybackProgressEnabled,
                keepPlaybackModeStateEnabled = keepPlaybackModeStateEnabled,
                readLyricOverrides = { keys ->
                    SecureTokenStorage(app).getLyricOverridesForIdentityKeys(keys) { readContext.ensureActive() }
                }
            )
        }
    }.onFailure { error ->
        if (error is CancellationException) throw error
        NPLogger.w("NERI-PlayerManager", "Failed to restore state: ${error.message}")
    }.getOrNull()
}

internal fun completeInitialRestoration(
    restorationPreload: RestoredPlayerStatePreload,
    applySnapshot: (RestoredPlayerStateSnapshot) -> Unit,
    restoreState: () -> Unit
) {
    when (restorationPreload) {
        is RestoredPlayerStatePreload.Completed -> restorationPreload.snapshot?.let(applySnapshot)
        RestoredPlayerStatePreload.NeedsFallback -> restoreState()
    }
}
