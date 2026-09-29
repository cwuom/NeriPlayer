package moe.ouom.neriplayer.data.model.playback

import moe.ouom.neriplayer.data.model.SongItem

enum class PlaybackCommandSource {
    LOCAL,
    LOCAL_SAFETY,
    REMOTE_SYNC
}

data class PlaybackCommand(
    val type: String,
    val source: PlaybackCommandSource,
    val timestampMs: Long = System.currentTimeMillis(),
    val queue: List<SongItem>? = null,
    val currentIndex: Int? = null,
    val positionMs: Long? = null,
    val shouldPlay: Boolean? = null,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null,
    val force: Boolean = false
)
