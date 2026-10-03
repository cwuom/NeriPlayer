@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.persistence

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sync.toLegacyLyricRecoveryCandidateOrNull
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage

private data class PlaybackLyricTargets(
    val current: SongItem?, val active: List<SongItem>, val shuffle: List<SongItem>?
) {
    fun hasSameReferences(other: PlaybackLyricTargets): Boolean =
        current === other.current && active === other.active && shuffle === other.shuffle
}

private fun PlayerManager.captureLyricTargets(): PlaybackLyricTargets {
    val session = queueStore.sessionSnapshot()
    return PlaybackLyricTargets(_currentSongFlow.value, session.queue.playlist, session.shuffleRestore?.playlist)
}

private fun PlayerManager.projectCommittedLyrics(projection: PlaybackLyricOverrideProjection): PlaybackStatePersistRequest? {
    val current = _currentSongFlow.value
    val updatedCurrent = current?.let(projection::song)
    val queueChanged = queueStore.projectSongs(projection::playlist)
    val currentChanged = updatedCurrent !== current
    if (currentChanged) setCurrentSongForPlayback(updatedCurrent)
    return if (queueChanged || currentChanged) prepareStatePersist() else null
}

internal suspend fun PlayerManager.applyCommittedLyricOverrides(storage: SecureTokenStorage) = runSongMetadataMutation {
    val readContext = coroutineContext
    applyFilteredPlaybackLyricOverrides(
        readTargets = { withContext(Dispatchers.Main.immediate) { captureLyricTargets() } },
        identityKeys = { playbackLyricIdentityKeys(it.current, it.active, it.shuffle) },
        readOverrides = { keys -> storage.getLyricOverridesForIdentityKeys(keys) { readContext.ensureActive() } },
        applyIfCurrent = { expected, projection ->
            // 先耐久保存旧全文，再确认同一组引用并应用已提交的远端版本
            withContext(Dispatchers.IO) {
                val legacy = mutableListOf<moe.ouom.neriplayer.data.model.sync.SyncSong>()
                playbackLyricIdentityKeys(expected.current, expected.active, expected.shuffle) {
                    it.toLegacyLyricRecoveryCandidateOrNull()?.let(legacy::add)
                }
                storage.retainLegacyLyricCandidates(legacy)
            }
            var request: PlaybackStatePersistRequest? = null
            val applied = withContext(Dispatchers.Main.immediate) commit@{
                if (!expected.hasSameReferences(captureLyricTargets())) return@commit false
                request = projectCommittedLyrics(projection)
                true
            }
            // 这里只保存播放状态，不调用仓库的用户修改入口，也不增加同步 mutation
            val persistence = request
            if (applied && persistence != null) persistStateNow(persistence, reason = "committed_lyric_override")
            applied
        }
    )
}
