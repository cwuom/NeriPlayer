package moe.ouom.neriplayer.data.sync.store.state.lyrics

import moe.ouom.neriplayer.data.sync.store.state.SyncDeletionStateStorage
import moe.ouom.neriplayer.data.sync.store.state.syncMutationLock
import moe.ouom.neriplayer.data.sync.store.state.KEY_LYRIC_OVERRIDES
import moe.ouom.neriplayer.data.sync.store.state.KEY_LEGACY_LYRIC_RECOVERY
import moe.ouom.neriplayer.data.sync.store.state.SyncMutationVersionStore
import moe.ouom.neriplayer.data.sync.store.state.KEY_SYNC_MUTATION_VERSION

import android.content.SharedPreferences
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy

internal class SyncLyricOverrideStore(
    private val preferences: SharedPreferences,
    private val files: SyncDeletionStateStorage,
    directory: File?
) {
    private val mutation = SyncMutationVersionStore(preferences)
    private val recovery = SyncLegacyLyricRecoveryStore(files)
    private val notifications = SyncLyricOverrideNotifications(preferences, directory, notificationMarker())
    val version: StateFlow<Long> = notifications.changes

    fun getLyricOverrides(): List<SyncSong> = synchronized(syncMutationLock) {
        SyncSongLyricMergePolicy.mergeOverrides(readCurrentOverrides())
    }

    private fun readCurrentOverrides(): List<SyncSong> {
        val type = object : TypeToken<List<SyncSong>>() {}.type
        val current = files.read<List<SyncSong>>(KEY_LYRIC_OVERRIDES, type).orEmpty()
        if (recovery.retain(current)) notifications.committed(notificationMarker())
        return current
    }

    fun retainLegacyLyrics(data: SyncData) = synchronized(syncMutationLock) {
        val songs = data.lyricOverrides.asSequence() + data.playlists.asSequence().flatMap { it.songs.asSequence() } +
            data.favoritePlaylists.asSequence().flatMap { it.songs.asSequence() } + data.recentPlays.asSequence().map { it.song }
        retainLegacyLyricCandidates(songs.filter(::hasUnconfirmedLegacyLyrics).toList())
    }

    fun retainLegacyLyricCandidates(candidates: List<SyncSong>) = synchronized(syncMutationLock) {
        if (recovery.retain(candidates)) notifications.committed(notificationMarker())
    }

    private fun notificationMarker(): String =
        "${files.marker(KEY_LYRIC_OVERRIDES).orEmpty()}|${files.marker(KEY_LEGACY_LYRIC_RECOVERY).orEmpty()}"

    fun recordLyricOverride(song: SyncSong) {
        synchronized(syncMutationLock) {
            val stored = readCurrentOverrides()
            val current = SyncSongLyricMergePolicy.mergeOverrides(stored)
            val merged = SyncSongLyricMergePolicy.mergeOverrides(current + song)
            // commit 失败也会更新偏好内存，等值重试仍需确认落盘
            check(files.commitEdit {
                if (merged != stored) {
                    files.write(this, KEY_LYRIC_OVERRIDES, merged)
                }
                if (merged != current) mutation.bump(this)
            }) { "Failed to persist user lyric override" }
            notifications.committed(notificationMarker())
        }
    }

    fun setLyricOverridesIfMutationVersion(expected: Long, overrides: List<SyncSong>): Boolean {
        return synchronized(syncMutationLock) {
            if (preferences.getLong(KEY_SYNC_MUTATION_VERSION, 0L) != expected) return@synchronized false
            val stored = readCurrentOverrides()
            val current = SyncSongLyricMergePolicy.mergeOverrides(stored)
            val merged = SyncSongLyricMergePolicy.mergeOverrides(current + overrides)
            // 相同内容可能来自上次失败提交，确认成功后再通知共享队列
            check(files.commitEdit {
                if (merged != stored) files.write(this, KEY_LYRIC_OVERRIDES, merged)
            }) { "Failed to persist synchronized lyric overrides" }
            notifications.committed(notificationMarker())
            true
        }
    }
}
