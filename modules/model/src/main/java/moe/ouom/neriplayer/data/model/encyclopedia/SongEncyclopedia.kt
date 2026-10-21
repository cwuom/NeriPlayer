package moe.ouom.neriplayer.data.model.encyclopedia

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform

/**
 * 百科请求的歌曲标识。
 *
 * 平台层（modules/platform）不能依赖 modules/local 的 stableKey，因此这里用纯数据字段拼装；
 * UI 与平台层共用同一函数，保证代际校验比较的是同一个键。
 */
fun SongItem.encyclopediaSongKey(): String = "$id|$album|${mediaUri.orEmpty()}"

/**
 * 歌曲百科的聚合数据。
 *
 * [songKey] 是发起请求时歌曲的稳定标识，用于在 UI 侧校验返回数据是否仍对应当前歌曲，
 * 避免快速切歌时旧请求覆盖新歌。[source] 保留百科数据的平台来源标识。
 */
data class SongEncyclopedia(
    val songKey: String,
    val source: MusicPlatform,
    val basicInfo: EncyclopediaBasicInfo,
    val musicInfo: EncyclopediaMusicInfo? = null,
    val credits: EncyclopediaCredits? = null,
    val description: String? = null,
    val awards: List<EncyclopediaAward> = emptyList(),
    val appearances: List<String> = emptyList(),
    // ---- 以下为九模块扩展字段，全部默认空/null 保证向后兼容 ----
    val publishTimeMs: Long? = null, // 发行日期
    val albumCompany: String? = null, // 专辑发行公司
    val albumId: Long? = null, // 专辑 id（用于跳转专辑页）
    val albumPicUrl: String? = null, // 专辑封面
    val artistId: Long? = null, // 主歌手 id
    val artist: EncyclopediaArtistInfo? = null,
    val similarSongs: List<EncyclopediaSimilarSong> = emptyList(),
    val relatedPlaylists: List<EncyclopediaRelatedPlaylist> = emptyList(),
    val fetchedAtMs: Long
)

/** 基础信息，来源于歌曲本身的元数据，不依赖网络。 */
data class EncyclopediaBasicInfo(
    val name: String,
    val artists: List<String> = emptyList(),
    val album: String? = null
)

/**
 * 音乐属性。
 *
 * [bpm] 仅在数据源明确给出数值时填充，缺失一律保持 null，禁止按歌名/歌手推断。
 * [instruments] 仅收录数据源标注的乐器角色，不做推测。
 */
data class EncyclopediaMusicInfo(
    val genres: List<String> = emptyList(),
    val bpm: Int? = null,
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val instruments: List<String> = emptyList()
)

/** 创作团队。[otherRoles] 承载作词/作曲/编曲/制作人之外的角色（乐器、录音、混音等）。 */
data class EncyclopediaCredits(
    val lyricists: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val arrangers: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val otherRoles: List<EncyclopediaCreditRole> = emptyList()
)

/** 单个创作角色及其署名。 */
data class EncyclopediaCreditRole(
    val role: String,
    val names: List<String>
)

/** 获奖记录。[detail] 是数据源给出的奖项名次或类别。 */
data class EncyclopediaAward(
    val title: String,
    val detail: String? = null
)

/** 相似歌曲（模块7）。全部字段来自 `/api/song/play/about/block/page` 的相似歌曲板块。 */
data class EncyclopediaSimilarSong(
    val songId: Long,
    val name: String,
    val artist: String,
    val coverUrl: String? = null,
    val albumName: String? = null,
    val durationMs: Long? = null
)

/** 相关歌单（模块8）。[trackCount] 接口不给，恒为 null，禁止伪造。 */
data class EncyclopediaRelatedPlaylist(
    val playlistId: Long,
    val name: String,
    val coverUrl: String? = null,
    val playCount: Long? = null,
    val trackCount: Int? = null
)

/**
 * 艺人简要信息（模块5）。
 *
 * [fansCount] 恒为 null：粉丝数接口不可用，数据源未给出即不展示，禁止按热度推断。
 */
data class EncyclopediaArtistInfo(
    val artistId: Long,
    val name: String,
    val picUrl: String? = null,
    val briefDesc: String? = null,
    val fansCount: Long? = null,
    val albumSize: Int? = null,
    val musicSize: Int? = null
)
