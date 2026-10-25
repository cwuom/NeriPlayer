package moe.ouom.neriplayer.data.model.bilibili.subtitle

/** CC 字幕轨道的自动选择取值，具体轨道由视频提供的字幕列表决定 */
const val BILI_SUBTITLE_LANGUAGE_AUTO = ""

/**
 * B 站 CC 字幕轨道。
 *
 * [url] 已补全协议头，可以直接请求；[isAiGenerated] 标记自动生成的字幕。
 */
data class BiliSubtitleTrack(
    val id: Long,
    val lan: String,
    val lanDoc: String,
    val url: String,
    val isAiGenerated: Boolean
)

/** CC 字幕条目，时间单位为毫秒 */
data class BiliSubtitleCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val content: String
)
