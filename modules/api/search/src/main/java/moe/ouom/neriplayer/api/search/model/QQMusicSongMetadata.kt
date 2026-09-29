package moe.ouom.neriplayer.api.search.model

import moe.ouom.neriplayer.core.model.music.SongDetails

/** 元数据只请求一次，再由调用方并行读取各歌词来源 */
data class QQMusicSongMetadata(val details: SongDetails, val durationMs: Long)
