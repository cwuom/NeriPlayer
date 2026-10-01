package moe.ouom.neriplayer.platform.search.api

import moe.ouom.neriplayer.data.model.music.SongDetails

/** 提供原平台歌词，供调用方单独判断候选来源 */
interface NativeLyricSearchApi : SearchApi {
    suspend fun getNativeSongInfo(id: String): SongDetails
}
