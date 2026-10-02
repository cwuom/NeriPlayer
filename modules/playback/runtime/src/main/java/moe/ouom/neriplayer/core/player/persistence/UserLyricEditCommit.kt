package moe.ouom.neriplayer.core.player.persistence

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem

internal suspend fun recordUserLyricEditThenPublish(
    prepare: suspend () -> SongItem,
    record: suspend (SongItem) -> Unit,
    publish: suspend (SongItem) -> Unit
): Boolean {
    val updatedSong = try {
        prepare().also { record(it) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        NPLogger.e("PlayerManager", "歌词编辑记录保存失败，保留当前播放状态", error)
        return false
    }
    publish(updatedSong)
    return true
}
