package moe.ouom.neriplayer.core.startup.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem

internal class PlayerStartupHistoryRecorder(
    private val currentSongFlow: StateFlow<SongItem?>,
    private val recordSong: (SongItem) -> Unit,
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS
) {
    suspend fun run() {
        var lastRecordedSongKey: String? = null
        currentSongFlow
            .drop(1)
            .filterNotNull()
            .collect { song ->
                val songKey = song.stableKey()
                if (songKey == lastRecordedSongKey) {
                    return@collect
                }
                lastRecordedSongKey = songKey
                delay(settleDelayMs)
                if (currentSongFlow.value?.stableKey() != songKey) {
                    return@collect
                }
                recordSong(song)
            }
    }

    companion object {
        const val DEFAULT_SETTLE_DELAY_MS = 700L
    }
}
