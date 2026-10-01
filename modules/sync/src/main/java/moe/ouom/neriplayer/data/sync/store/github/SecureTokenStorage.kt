package moe.ouom.neriplayer.data.sync.store.github

import android.content.SharedPreferences
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.config.GitHubSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.store.preferences.PlayHistoryUpdateMode
import android.content.Context
import moe.ouom.neriplayer.data.sync.store.state.*
import moe.ouom.neriplayer.data.sync.store.secure.EncryptedSyncPreferences

class SecureTokenStorage internal constructor(encryptedPrefs: SharedPreferences) {
    constructor(context: Context) : this(EncryptedSyncPreferences.open(context, GITHUB_PREFS_NAME, "NERI-SecureTokenStorage"))

    private val configuration = GitHubSyncConfigurationStore(encryptedPrefs)
    private val device = SyncDeviceStateStore(encryptedPrefs)
    private val mutation = SyncMutationVersionStore(encryptedPrefs)
    private val playlists = SyncPlaylistDeletionStore(encryptedPrefs)
    private val recent = SyncRecentPlayDeletionStore(encryptedPrefs)
    private val usage = SyncPlaylistUsageDeletionStore(encryptedPrefs)
    private val songs = SyncPlaylistSongDeletionStore(encryptedPrefs)
    private val playlistMutation = SyncPlaylistMutationStore(encryptedPrefs, playlists, songs)
    private val deletionState = SyncDeletionStateCommitter(encryptedPrefs)

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

    fun setTokenWarningDismissed(dismissed: Boolean) = configuration.setTokenWarningDismissed(dismissed)

    fun isTokenWarningDismissed(): Boolean = configuration.isTokenWarningDismissed()

    fun setDataSaverMode(enabled: Boolean) = configuration.setDataSaverMode(enabled)

    fun isDataSaverMode(): Boolean = configuration.isDataSaverMode()

    fun getSyncMutationVersion(): Long = mutation.getSyncMutationVersion()

    fun markSyncMutation(): Long = mutation.markSyncMutation()

    fun snapshot(): GitHubSyncConfigSnapshot = configuration.snapshot()

    fun restore(snapshot: GitHubSyncConfigSnapshot) = configuration.restore(snapshot)

}
