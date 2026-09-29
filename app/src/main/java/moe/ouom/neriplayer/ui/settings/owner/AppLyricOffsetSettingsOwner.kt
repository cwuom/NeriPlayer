package moe.ouom.neriplayer.ui.settings.owner

import moe.ouom.neriplayer.core.model.music.MusicPlatform
import moe.ouom.neriplayer.data.settings.lyrics.DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS
import moe.ouom.neriplayer.data.settings.lyrics.DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS

internal class AppLyricOffsetSettingsOwner(
    private val rebase: suspend (MusicPlatform, Long, Long) -> Unit,
    private val saveCloudOffset: suspend (Long) -> Unit,
    private val saveQqOffset: suspend (Long) -> Unit,
    private val resetOffsets: suspend () -> Unit
) {
    suspend fun changeCloudOffset(previous: Long, next: Long) {
        changeOffset(MusicPlatform.CLOUD_MUSIC, previous, next, saveCloudOffset)
    }

    suspend fun changeQqOffset(previous: Long, next: Long) {
        changeOffset(MusicPlatform.QQ_MUSIC, previous, next, saveQqOffset)
    }

    private suspend fun changeOffset(
        source: MusicPlatform,
        previous: Long,
        next: Long,
        save: suspend (Long) -> Unit
    ) {
        if (previous == next) return
        rebase(source, previous, next)
        try {
            save(next)
        } catch (error: Throwable) {
            rebase(source, next, previous)
            throw error
        }
    }

    suspend fun resetCloudAndQqOffsets(previousCloud: Long, previousQq: Long) {
        var cloudRebased = false
        var qqRebased = false
        try {
            rebase(MusicPlatform.CLOUD_MUSIC, previousCloud, DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS)
            cloudRebased = true
            rebase(MusicPlatform.QQ_MUSIC, previousQq, DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS)
            qqRebased = true
            resetOffsets()
        } catch (error: Throwable) {
            if (qqRebased) {
                rebase(MusicPlatform.QQ_MUSIC, DEFAULT_QQ_MUSIC_LYRIC_OFFSET_MS, previousQq)
            }
            if (cloudRebased) {
                rebase(MusicPlatform.CLOUD_MUSIC, DEFAULT_CLOUD_MUSIC_LYRIC_OFFSET_MS, previousCloud)
            }
            throw error
        }
    }
}
