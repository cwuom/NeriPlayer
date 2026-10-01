package moe.ouom.neriplayer.data.model.bilibili.playback

import org.json.JSONObject

const val FNVAL_DASH = 1 shl 4
const val FNVAL_DOLBY = 1 shl 8

data class PlayOptions(
    /** 画质 qn; DASH 下此参数基本无效 (会返回所有可用轨) */
    val qn: Int? = null,
    /** 流格式标识, 推荐: DASH + Dolby, 确保能下发普通音轨与杜比音轨 */
    val fnval: Int = FNVAL_DASH or FNVAL_DOLBY,
    val fnver: Int = 0,
    /** 允许 4K (配合 qn=120 & fourk=1) , 对音轨无影响 */
    val fourk: Int = 0,
    /** 平台: pc (默认, 需 Referer) , html5 (无 Referer 校验, 仅 MP4) */
    val platform: String = "pc",
    /** platform=html5 时为 1 可拉 1080p (high_quality=1) */
    val highQuality: Int? = null,
    /** 未登录试拉较高画质 (64/80) , 1 开启 */
    val tryLook: Int? = null,
    /** session 透传 */
    val session: String? = null,
    /** 可选: gaia_source, 无 Cookie 时有时需要 (view-card / pre-load) */
    val gaiaSource: String? = null,
    /** 可选: isGaiaAvoided */
    val isGaiaAvoided: Boolean? = null,
)

data class Durl(
    val order: Int,
    val lengthMs: Long,
    val sizeBytes: Long,
    val url: String,
    val backupUrls: List<String>
)

data class DashStream(
    val id: Int,
    val baseUrl: String,
    val backupUrls: List<String>,
    val bandwidth: Long,
    val mimeType: String,
    val codecs: String,
    val width: Int,
    val height: Int,
    val frameRate: String,
    val codecid: Int
)

data class DolbyAudio(
    val type: Int,
    val audios: List<DashStream>
)

data class FlacAudio(
    val display: Boolean,
    val audio: DashStream?
)

/**
 * 统一的播放信息封装
 * MP4 看 durl; DASH 看 dashVideo/dashAudio
 */
data class PlayInfo(
    val code: Int,
    val message: String,
    val qnSelected: Int?,
    val format: String?,
    val timeLengthMs: Long?,
    val acceptDescription: List<String>,
    val acceptQuality: List<Int>,
    // MP4
    val durl: List<Durl>,
    // DASH
    val dashVideo: List<DashStream>,
    val dashAudio: List<DashStream>,
    val dolby: DolbyAudio?,
    val flac: FlacAudio?,
    val raw: JSONObject
)
