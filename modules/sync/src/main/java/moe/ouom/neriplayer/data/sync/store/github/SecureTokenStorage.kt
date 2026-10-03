package moe.ouom.neriplayer.data.sync.store.github

import android.content.SharedPreferences
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.config.GitHubSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.store.preferences.PlayHistoryUpdateMode
import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import moe.ouom.neriplayer.data.sync.store.state.*
import moe.ouom.neriplayer.data.sync.store.state.lyrics.SyncLyricOverrideStore
import moe.ouom.neriplayer.data.sync.store.state.lyrics.SyncLyricOverrideLookup
import moe.ouom.neriplayer.data.sync.store.secure.EncryptedSyncPreferences

class SecureTokenStorage internal constructor(
    encryptedPrefs: SharedPreferences,
    directory: File? = null,
    syncDirectory: ((File) -> Unit)? = null
) {
    constructor(context: Context) : this(
        EncryptedSyncPreferences.open(context, GITHUB_PREFS_NAME, "NERI-SecureTokenStorage", recoverOnFailure = false),
        File(context.noBackupFilesDir, "sync-deletions"),
        { path ->
            val descriptor = Os.open(path.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        }
    )

    private val deletionFiles = if (syncDirectory == null) SyncDeletionStateStorage(encryptedPrefs, directory)
        else SyncDeletionStateStorage(encryptedPrefs, directory, syncDirectory)

    private val configuration = GitHubSyncConfigurationStore(encryptedPrefs)
    private val device = SyncDeviceStateStore(encryptedPrefs)
    private val mutation = SyncMutationVersionStore(encryptedPrefs)
    private val playlists = SyncPlaylistDeletionStore(encryptedPrefs, deletionFiles)
    private val recent = SyncRecentPlayDeletionStore(encryptedPrefs, deletionFiles)
    private val usage = SyncPlaylistUsageDeletionStore(encryptedPrefs, deletionFiles)
    private val songs = SyncPlaylistSongDeletionStore(encryptedPrefs, deletionFiles)
    private val playlistMutation = SyncPlaylistMutationStore(encryptedPrefs, playlists, songs, deletionFiles)
    private val deletionState = SyncDeletionStateCommitter(encryptedPrefs, deletionFiles)
    private val lyricOverrides = SyncLyricOverrideStore(encryptedPrefs, deletionFiles, directory)
    private val lyricLookup = SyncLyricOverrideLookup(deletionFiles)
    private val legacyLyricArchiveReceipts = SyncLegacyLyricArchiveReceiptStore(encryptedPrefs, deletionFiles)
    private val legacyLyricOptimization = SyncLegacyLyricOptimizationStore(encryptedPrefs, deletionFiles)

    fun saveToken(token: String) = configuration.saveToken(token)

    fun getToken(): String? = configuration.getToken()

    fun clearToken() = configuration.clearToken()

    fun saveRepository(owner: String, name: String) = configuration.saveRepository(owner, name)

    fun getRepoOwner(): String? = configuration.getRepoOwner()

    fun getRepoName(): String? = configuration.getRepoName()

    fun saveDeviceId(deviceId: String) = device.saveDeviceId(deviceId)

    fun getDeviceId(): String? = device.getDeviceId()

    fun getOrCreateDeviceId(): String = device.getOrCreateDeviceId()

    fun nextSyncCausalTokens(count: Int): List<SyncCausalToken> = device.nextSyncCausalTokens(count)

    fun saveLastSyncTime(timestamp: Long) = configuration.saveLastSyncTime(timestamp)

    fun getLastSyncTime(): Long = configuration.getLastSyncTime()

    fun saveLastCompletedSyncTime(timestamp: Long) = configuration.saveLastCompletedSyncTime(timestamp)

    /** 配置保存或清除后，旧会话不再写入确认元数据 */
    fun captureSyncMetadataGuard(): (() -> Unit) -> Boolean = configuration.captureSyncMetadataGuard()

    fun getLastCompletedSyncTime(): Long = configuration.getLastCompletedSyncTime()

    fun observeLastCompletedSyncTime(): Flow<Long> = configuration.observeLastCompletedSyncTime()

    fun setAutoSyncEnabled(enabled: Boolean) = configuration.setAutoSyncEnabled(enabled)

    fun isAutoSyncEnabled(): Boolean = configuration.isAutoSyncEnabled()

    fun saveLastRemoteSha(sha: String) = configuration.saveLastRemoteSha(sha)

    fun getLastRemoteSha(): String? = configuration.getLastRemoteSha()

    @Deprecated("Use PlayHistorySyncPreferences instead")
    fun setPlayHistoryUpdateMode(mode: PlayHistoryUpdateMode) = configuration.setPlayHistoryUpdateMode(mode)

    @Deprecated("Use PlayHistorySyncPreferences instead")
    fun getPlayHistoryUpdateMode(): PlayHistoryUpdateMode = configuration.getPlayHistoryUpdateMode()

    fun getLegacyPlayHistoryUpdateModeName(): String? = configuration.getLegacyPlayHistoryUpdateModeName()

    fun isConfigured(): Boolean = configuration.isConfigured()

    fun clearAll() = configuration.clearAll()

    fun addDeletedPlaylistId(playlistId: Long) = playlists.addDeletedPlaylistId(playlistId)

    fun getDeletedPlaylistIds(): Set<Long> = playlists.getDeletedPlaylistIds()

    fun getDeletedPlaylistTimestamps(): Map<Long, Long> = playlists.getDeletedPlaylistTimestamps()

    fun clearDeletedPlaylistIds() = playlists.clearDeletedPlaylistIds()

    fun removeDeletedPlaylistIds(playlistIds: Set<Long>) = playlists.removeDeletedPlaylistIds(playlistIds)

    fun setPlaylistDeletionStateIfMutationVersion(expected: Long, snapshots: List<SyncPlaylist>, clearRestored: Boolean = true): Boolean =
        playlists.setPlaylistDeletionStateIfMutationVersion(expected, snapshots, clearRestored)

    fun applyPlaylistSyncMutation(
        addedSongDeletions: List<SyncPlaylistSongDeletion>,
        removedSongDeletions: List<Pair<Long, Collection<SongIdentity>>>,
        deletedPlaylistIds: List<Long>,
        clearedPlaylistDeletionIds: List<Long>,
        restoredPlaylistIds: Set<Long>
    ): Long = playlistMutation.applyPlaylistSyncMutation(addedSongDeletions, removedSongDeletions, deletedPlaylistIds, clearedPlaylistDeletionIds, restoredPlaylistIds)

    fun getRecentPlayDeletions(): List<SyncRecentPlayDeletion> = recent.getRecentPlayDeletions()

    fun setRecentPlayDeletions(deletions: List<SyncRecentPlayDeletion>) = recent.setRecentPlayDeletions(deletions)

    fun addRecentPlayDeletions(deletions: List<SyncRecentPlayDeletion>) = recent.addRecentPlayDeletions(deletions)

    fun removeRecentPlayDeletion(identity: SongIdentity) = recent.removeRecentPlayDeletion(identity)

    fun getPlaylistUsageDeletions(): Map<String, Long> = usage.getPlaylistUsageDeletions()

    fun getPlaylistUsageDeletionsConfirmed(): Map<String, Long> = usage.getPlaylistUsageDeletionsConfirmed()

    fun getPlaylistUsageDeletionBarriersConfirmed(): List<SyncPlaylistUsageDeletion> = usage.getPlaylistUsageDeletionBarriersConfirmed()

    fun mergePlaylistUsageDeletionBarriers(deletions: List<SyncPlaylistUsageDeletion>) = usage.mergePlaylistUsageDeletionBarriers(deletions)

    fun mergePlaylistUsageDeletionBarriersIfMutationVersion(expected: Long, deletions: List<SyncPlaylistUsageDeletion>): Boolean =
        usage.mergePlaylistUsageDeletionBarriersIfMutationVersion(expected, deletions)

    fun addPlaylistUsageDeletion(playlistKey: String, deletedAt: Long = System.currentTimeMillis()) = usage.addPlaylistUsageDeletion(playlistKey, deletedAt)

    fun removePlaylistUsageDeletion(playlistKey: String, bumpVersion: Boolean = true) = usage.removePlaylistUsageDeletion(playlistKey, bumpVersion)

    fun getPlaylistSongDeletions(): List<SyncPlaylistSongDeletion> = songs.getPlaylistSongDeletions()

    fun setPlaylistSongDeletions(deletions: List<SyncPlaylistSongDeletion>) = songs.setPlaylistSongDeletions(deletions)

    fun addPlaylistSongDeletions(deletions: List<SyncPlaylistSongDeletion>) = songs.addPlaylistSongDeletions(deletions)

    fun removePlaylistSongDeletions(
        playlistId: Long,
        identities: Collection<SongIdentity>
    ) = songs.removePlaylistSongDeletions(playlistId, identities)

    fun removePlaylistSongDeletionsForPlaylist(playlistId: Long) = songs.removePlaylistSongDeletionsForPlaylist(playlistId)

    fun setDeletionStateIfMutationVersion(
        expectedMutationVersion: Long,
        recentPlayDeletions: List<SyncRecentPlayDeletion>,
        playlistSongDeletions: List<SyncPlaylistSongDeletion>
    ): Boolean = deletionState.setDeletionStateIfMutationVersion(expectedMutationVersion, recentPlayDeletions, playlistSongDeletions)

    fun getLyricOverrides(): List<SyncSong> = lyricOverrides.getLyricOverrides()

    fun isLegacyLyricOptimizationEnabled(): Boolean = legacyLyricOptimization.isEnabled()

    fun setLegacyLyricOptimizationEnabled(enabled: Boolean) = legacyLyricOptimization.setEnabled(enabled)

    fun retainLegacyLyrics(data: moe.ouom.neriplayer.data.model.sync.SyncData) = lyricOverrides.retainLegacyLyrics(data)

    fun retainLegacyLyricCandidates(candidates: List<SyncSong>) = lyricOverrides.retainLegacyLyricCandidates(candidates)

    fun isLegacyLyricArchiveRecovered(namespace: String, sourceHash: String): Boolean =
        legacyLyricArchiveReceipts.isCompleted(namespace, sourceHash)

    fun markLegacyLyricArchiveRecovered(namespace: String, sourceHash: String) =
        legacyLyricArchiveReceipts.markCompleted(namespace, sourceHash)

    fun getLegacyLyricCandidatesForIdentityKey(identityKey: String, checkActive: () -> Unit = {}): List<SyncSong> =
        lyricLookup.readLegacy(identityKey, checkActive)

    fun getLegacyLyricCandidates(checkActive: () -> Unit = {}): List<SyncSong> = lyricLookup.readLegacy(null, checkActive)

    fun getLyricOverridesForIdentityKeys(identityKeys: Set<String>, checkActive: () -> Unit = {}): List<SyncSong> =
        lyricLookup.read(identityKeys, checkActive)

    val lyricOverridesVersion: StateFlow<Long> = lyricOverrides.version

    fun recordLyricOverride(song: SyncSong) = lyricOverrides.recordLyricOverride(song)

    fun setLyricOverridesIfMutationVersion(expected: Long, overrides: List<SyncSong>): Boolean =
        lyricOverrides.setLyricOverridesIfMutationVersion(expected, overrides)

    fun setTokenWarningDismissed(dismissed: Boolean) = configuration.setTokenWarningDismissed(dismissed)

    fun isTokenWarningDismissed(): Boolean = configuration.isTokenWarningDismissed()

    fun setDataSaverMode(enabled: Boolean) = configuration.setDataSaverMode(enabled)

    fun isDataSaverMode(): Boolean = configuration.isDataSaverMode()

    fun getSyncMutationVersion(): Long = mutation.getSyncMutationVersion()

    fun markSyncMutation(): Long = mutation.markSyncMutation()

    fun snapshot(): GitHubSyncConfigSnapshot = configuration.snapshot()

    fun restore(snapshot: GitHubSyncConfigSnapshot) = configuration.restore(snapshot)

}
