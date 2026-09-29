package moe.ouom.neriplayer.data.model.netease.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class CloudMusicSearchResponse(val result: CloudMusicSearchResult?)
@Serializable data class CloudMusicSearchResult(val songs: List<CloudMusicSongSummary>? = null)
@Serializable data class CloudMusicSongSummary(
    val id: Long,
    val name: String,
    @SerialName("dt") val duration: Long,
    @SerialName("ar") val artists: List<CloudMusicArtist>,
    @SerialName("al") val album: CloudMusicAlbum
)

@Serializable data class CloudMusicSongDetail(
    val name: String,
    @SerialName("artists") val artists: List<CloudMusicArtist>,
    @SerialName("album") val album: CloudMusicAlbum,
)

@Serializable data class CloudMusicSongDetailResponse(val songs: List<CloudMusicSongDetail>)
@Serializable data class CloudMusicArtist(val name: String)
@Serializable data class CloudMusicAlbum(val name: String, val picUrl: String?)
@Serializable
data class CloudMusicLyricResponse(
    val lrc: CloudMusicLrc?,
    val tlyric: CloudMusicLrc? = null,
    val yrc: CloudMusicLrc? = null,
    val ytlrc: CloudMusicLrc? = null
)

@Serializable
data class CloudMusicLrc(val lyric: String?)
