package moe.ouom.neriplayer.data.sync.store.state.lyrics

import moe.ouom.neriplayer.data.sync.store.state.SyncDeletionStateStorage
import moe.ouom.neriplayer.data.sync.store.state.KEY_LEGACY_LYRIC_RECOVERY

import com.google.gson.reflect.TypeToken
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.hasSyncLyricText

internal class SyncLegacyLyricRecoveryStore(private val files: SyncDeletionStateStorage) {
    fun retain(candidates: List<SyncSong>): Boolean {
        val unknown = candidates.filter(::hasUnconfirmedLegacyLyrics)
        if (unknown.isEmpty()) return false
        check(files.commitEdit {}) { "Failed to confirm legacy lyric recovery state" }
        val type = object : TypeToken<List<SyncSong>>() {}.type
        val current = files.read<List<SyncSong>>(KEY_LEGACY_LYRIC_RECOVERY, type).orEmpty()
        // 旧歌词没有可靠的先后关系，保留不同全文避免迁移时丢失
        val retained = (current + unknown).distinct()
        check(files.commitEdit {
            if (retained != current) files.write(this, KEY_LEGACY_LYRIC_RECOVERY, retained)
        }) { "Failed to persist legacy lyric recovery" }
        return retained != current
    }
}

internal fun hasUnconfirmedLegacyLyrics(song: SyncSong): Boolean {
    if (song.lyricSyncEdited != null) return false
    return song.hasSyncLyricText()
}
