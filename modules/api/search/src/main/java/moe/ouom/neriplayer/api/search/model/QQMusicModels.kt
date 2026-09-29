package moe.ouom.neriplayer.api.search.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class QQMusicSearchResponse(val data: QQMusicSearchData?)
@Serializable internal data class QQMusicSearchData(val song: QQMusicSearchSong?)
@Serializable internal data class QQMusicSearchSong(val list: List<QQMusicSongSummary>?)
@Serializable internal data class QQMusicSongSummary(
    @SerialName("songmid") val songMid: String,
    @SerialName("songname") val songName: String,
    val singer: List<QQMusicArtist>,
    @SerialName("albummid") val albumMid: String?,
    @SerialName("albumname") val albumName: String?,
    val interval: Long // 歌曲时长 (秒)
)

@Serializable internal data class QQMusicArtist(val name: String)

@Serializable internal data class QQMusicDetailResponse(val data: QQMusicDetailData?)
@Serializable internal data class QQMusicDetailData(@SerialName("track_info") val trackInfo: QQMusicTrackInfo?)
@Serializable internal data class QQMusicTrackInfo(
    val mid: String,
    val name: String,
    val singer: List<QQMusicArtist>,
    val album: QQMusicAlbum,
    val interval: Long = 0L
)
@Serializable internal data class QQMusicAlbum(val name: String, val mid: String)

/**
 * 没有歌词时接口整个字段都不下发
 *
 * 可空但缺默认值在 kotlinx.serialization 里仍算必填, 会直接抛 MissingFieldException
 */
@Serializable data class QQMusicLyricResponse(
    val lyric: String? = null,
    val trans: String? = null
)

@Serializable data class QQMusicLyricContainer(
    val req: QQMusicLyricEnvelope? = null
)

@Serializable data class QQMusicLyricEnvelope(
    val code: Int = 0,
    val data: QQMusicLyricResponse? = null
)
