package moe.ouom.neriplayer.data.model.qqmusic.search

import moe.ouom.neriplayer.data.model.music.SongDetails

/** 元数据只请求一次，再由调用方并行读取各歌词来源 */
data class QQMusicSongMetadata(val details: SongDetails, val durationMs: Long)
